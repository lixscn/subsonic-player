using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
using SubsonicPlayer.Models;
using SubsonicPlayer.ViewModels;
using SubsonicPlayer.Views;
using Xilium.CefGlue.Avalonia;

namespace SubsonicPlayer.Services;

/// <summary>
/// 暴露给 JS（window.bridge）的 C# 桥接对象。
/// 通过 RegisterJavascriptObject 注入；JS 端方法调用返回 Promise（异步方法返回 Task 自动包装）。
/// </summary>
public sealed class CefUiBridge : IDisposable
{
    private AvaloniaCefBrowser? _browser;
    private Window? _window;
    private MiniPlayerWindow? _miniWindow;
    private readonly List<Action> _subscriptions = new();

    // 断线重连监听：周期性 ping 服务器，状态变化时向 JS 下发 connection 事件（JS 显示“重连中”并在恢复后刷新页面）。
    private Timer? _connMon;
    private volatile bool _connConnected = true; // 初始假定已连接：预热成功则不下发事件，失败才下发离线
    private int _connFailStreak;
    private bool _disposed;

    /// <summary>页面数据提供者（暴露为 bridge.data，JS 直接调用取数据）。</summary>
    public CefPageDataProvider Data { get; } = new();

    /// <summary>
    /// JS 统一数据入口：所有页面数据请求走这里，在 UI 线程执行，避免 CEF 回调线程问题。
    /// </summary>
    public object? InvokeData(string method, string argsJson)
    {
        try
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            var args = string.IsNullOrEmpty(argsJson)
                ? Array.Empty<object>()
                : System.Text.Json.JsonSerializer.Deserialize<object[]>(argsJson) ?? Array.Empty<object>();

            // 由 RegisterJavascriptObject 的 MethodCallHandler 在线程池调用，直接执行即可
            var mi = Data.GetType().GetMethod(method,
                System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.IgnoreCase);
            if (mi is null)
                throw new ArgumentException($"数据方法不存在: {method}");

            var invokeArgs = BuildArgs(mi, args);
            var task = mi.Invoke(Data, invokeArgs) as System.Threading.Tasks.Task;
            object? result;
            if (task is not null)
            {
                task.GetAwaiter().GetResult();
                var resultProp = task.GetType().GetProperty("Result");
                result = resultProp?.GetValue(task);
            }
            else
            {
                result = mi.Invoke(Data, invokeArgs);
            }

            sw.Stop();
            if (sw.ElapsedMilliseconds > 500)
                LogBridgeInfo($"{method} 耗时 {sw.ElapsedMilliseconds}ms, args={argsJson}");

            return result;
        }
        catch (Exception ex)
        {
            LogBridgeError($"InvokeData({method})", ex);
            return new Dictionary<string, object?> { ["error"] = ex.Message };
        }
    }

    private static void LogBridgeInfo(string msg)
    {
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            System.IO.Directory.CreateDirectory(dir);
            System.IO.File.AppendAllText(
                System.IO.Path.Combine(dir, "bridge.log"),
                $"[{DateTime.Now:HH:mm:ss}] {msg}{Environment.NewLine}");
        }
        catch { }
    }

    private static void LogBridgeError(string what, Exception ex)
    {
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            System.IO.Directory.CreateDirectory(dir);
            System.IO.File.AppendAllText(
                System.IO.Path.Combine(dir, "bridge.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {what}: {ex}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
            // 忽略
        }
    }

    private static object[] BuildArgs(System.Reflection.MethodInfo mi, object[] raw)
    {
        var pars = mi.GetParameters();
        var result = new object[pars.Length];
        for (var i = 0; i < pars.Length; i++)
        {
            var p = pars[i];
            if (i < raw.Length && raw[i] is not null)
            {
                try
                {
                    result[i] = Convert.ChangeType(raw[i], p.ParameterType) ?? Activator.CreateInstance(p.ParameterType)!;
                }
                catch
                {
                    result[i] = System.Text.Json.JsonSerializer.Deserialize(System.Text.Json.JsonSerializer.Serialize(raw[i]), p.ParameterType) ?? Activator.CreateInstance(p.ParameterType)!;
                }
            }
            else
            {
                result[i] = p.HasDefaultValue ? p.DefaultValue! : (p.ParameterType.IsValueType ? Activator.CreateInstance(p.ParameterType)! : null!);
            }
        }
        return result;
    }

    public void AttachBrowser(AvaloniaCefBrowser browser, Window window)
    {
        _browser = browser;
        _window = window;
        // OSR 键盘输入需要 AvaloniaCefBrowser 持有键盘焦点：每次点击 CEF 区域强制聚焦，物理键盘才能进入 CEF
        _browser.PointerPressed += (_, e) =>
        {
            _lastPointer = e;
            TryFocusBrowser();
        };
        SubscribeState();
        PrewarmConnection();
        StartConnectionMonitor();
    }

    /// <summary>启动连接健康监听：断线后自动重连，并向 JS 推送 connection 事件。</summary>
    private void StartConnectionMonitor()
    {
        // 单次触发（dueTime=interval, period=Infinite），每轮结束后由 CheckConnectionAsync 重新排期。
        // 老实现用 new Timer(cb, null, interval, interval) 并且每轮在回调里 Dispose + new：
        //   · period 会重复触发，而 ConnectAsync 在网络慢时可以超过 interval ⇒ 多轮检查并发叠在一起；
        //   · 多个检查同时调 ConnectAsync，会并发改写服务地址、并发推 connected 事件，横幅反复闪。
        _connMon = new Timer(_ => _ = Task.Run(CheckConnectionAsync), null, ConnCheckStartupDelay, Timeout.InfiniteTimeSpan);
    }

    private static readonly TimeSpan ConnCheckStartupDelay = TimeSpan.FromSeconds(8);
    private static readonly TimeSpan ConnCheckUpInterval = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan ConnCheckDownInterval = TimeSpan.FromSeconds(6);

    /// <summary>为下一轮检查排期（单次触发；只改 dueTime，不重建 Timer）。</summary>
    private void ScheduleConnectionCheck(TimeSpan interval)
    {
        if (_disposed)
            return;
        try { _connMon?.Change(interval, Timeout.InfiniteTimeSpan); } catch { }
    }

    private async Task CheckConnectionAsync()
    {
        try
        {
            var music = AppServices.Music;
            if (music is null)
            {
                ScheduleConnectionCheck(ConnCheckDownInterval);
                return;
            }

            bool ok;
            try { ok = await music.ConnectAsync(); } catch { ok = false; }

            if (_disposed)
                return;

            if (ok)
            {
                _connFailStreak = 0;
                if (!_connConnected)
                {
                    _connConnected = true;
                    Push("connection", new { connected = true });
                }
            }
            else
            {
                _connFailStreak++;
                // 连续 2 次探测失败才判定断开：慢网/瞬时丢包造成的一次探测抖动不应该弹出
                // 「正在重新连接服务器…」横幅（否则横幅会反复闪，看起来像一直连不上）。
                if (_connConnected && _connFailStreak >= 2)
                {
                    _connConnected = false;
                    Push("connection", new { connected = false });
                }
            }

            // 连接中低频复查，断开时高频重试，尽快在恢复后刷新页面
            ScheduleConnectionCheck(ok ? ConnCheckUpInterval : ConnCheckDownInterval);
        }
        catch
        {
            // 检查本身绝不允许把监听链断掉：出错也要继续排下一轮。
            ScheduleConnectionCheck(ConnCheckDownInterval);
        }
    }

    /// <summary>启动预热：后台连接服务器，让首屏数据请求秒回。</summary>
    private void PrewarmConnection()
    {
        // 启动时按持久化设置应用音量平均（引擎在各首歌开始时采样归一）
        try { AppServices.Playback.SetVolumeNormalization(AppServices.Settings.Settings.VolumeNormalization); } catch { }
        System.Threading.Tasks.Task.Run(async () =>
        {
            try
            {
                var music = AppServices.Music;
                if (music is null) return;
                await music.ConnectAsync();
            }
            catch { /* 预热失败不影响 */ }
        });
    }

    private void SubscribeState()
    {
        var pb = AppServices.Playback;
        pb.PropertyChanged += OnPlaybackChanged;
        _subscriptions.Add(() => pb.PropertyChanged -= OnPlaybackChanged);

        pb.PlaybackFailed += OnPlaybackFailed;
        _subscriptions.Add(() => pb.PlaybackFailed -= OnPlaybackFailed);

        AppServices.ServicesChanged += OnServicesChanged;
        AppServices.CurrentServiceChanged += OnServicesChanged;
        _subscriptions.Add(() => AppServices.ServicesChanged -= OnServicesChanged);
        _subscriptions.Add(() => AppServices.CurrentServiceChanged -= OnServicesChanged);
    }

    /// <summary>建流失败 → 前端弹一条可见提示（否则用户只看到"点了歌没反应、歌没变"）。</summary>
    private void OnPlaybackFailed(Song song, string reason)
        => Push("playbackError", new { songId = song.Id, title = song.Title, message = reason });

    private DateTime _lastProgressPush = DateTime.MinValue;
    private string _lastPlaybackSignature = "";

    private void OnPlaybackChanged(object? sender, PropertyChangedEventArgs e)
    {
        // 频谱（Spectrum）每 50ms 变一次，但它**不在 playback payload 里**（前端没有任何地方用它，
        // Spectrum 属性全工程只有写入、没有读取）⇒ 直接忽略，别拿它当"状态变了"。
        if (e.PropertyName is nameof(PlaybackService.Spectrum))
            return;

        // 换歌诊断：确认 C# 侧 CurrentSong 真的变了（排查"点了歌界面没反应"用）
        if (e.PropertyName is nameof(PlaybackService.CurrentSong))
        {
            var cur = AppServices.Playback.CurrentSong;
            LogBridgeInfo($"CurrentSong -> {cur?.Id} / {cur?.Title}");
        }

        // 进度类属性（PositionSeconds/PositionText/DurationText）高频触发：节流到 500ms 一次
        if (e.PropertyName is nameof(PlaybackService.PositionSeconds)
            or nameof(PlaybackService.PositionText)
            or nameof(PlaybackService.DurationText))
        {
            var now = DateTime.UtcNow;
            if ((now - _lastProgressPush).TotalMilliseconds < 500)
                return;
            _lastProgressPush = now;
        }
        PushPlayback();
    }

    private void OnServicesChanged() => Push("services", new
    {
        services = AppServices.Settings.Settings.Services
            .Select(s => new Dictionary<string, object?> { ["id"] = s.Id, ["name"] = s.Name })
            .ToArray(),
        currentServiceId = AppServices.GetCurrentService()?.Id,
    });

    /// <summary>
    /// 把播放状态推给前端。
    ///
    /// <para>★ 只有**前端真正会用到的字段**变了才推：PlaybackService 是个 ObservableObject，
    /// 任何属性变化都会走到这里，而其中 Spectrum 每 50ms 变一次（且不在本 payload 里）。
    /// 原来照单全推，实测每秒 ~20 次"JSON 序列化 + ExecuteJavaScript + 写一条 bridge.log"，
    /// 把 UI 线程和渲染进程一直占着 —— 表现就是**点歌换歌很迟钝**（点了半天界面才反应）。
    /// 现在用签名比对：状态真的变了才推，且 payload 里没有的字段不再触发推送。</para>
    /// </summary>
    private void PushPlayback(bool force = false)
    {
        var pb = AppServices.Playback;
        var song = pb.CurrentSong;
        var coverUrl = song?.CoverArtId != null && AppServices.Music is not null
            ? AppServices.Music.GetCoverArtUrl(song.CoverArtId, 150)
            : null;

        // 签名覆盖 payload 的全部字段（含 isFavorite）——收藏单独走 favoriteChanged，
        // 但"当前这首歌的收藏状态"变了也要刷底栏，所以那条路径用 force: true。
        var signature = string.Join('|',
            song?.Id, pb.CurrentTitle, song?.Artist, song?.ArtistId, song?.CoverArtId,
            pb.IsPlaying, (int)pb.PositionSeconds, (int)pb.DurationSeconds,
            pb.Volume.ToString("F3", System.Globalization.CultureInfo.InvariantCulture),
            pb.PlayMode,
            song is not null && AppServices.Favorites.IsFavorite(song.Id));
        if (!force && signature == _lastPlaybackSignature)
            return;
        _lastPlaybackSignature = signature;

        // 换歌诊断：确认确实往 JS 推了"新曲目"（若这里没有而 CurrentSong 有，说明被签名比对吃掉了）
        if (song?.Id != _lastPushedSongId)
        {
            _lastPushedSongId = song?.Id;
            LogBridgeInfo($"推送 playback -> {song?.Id} / {pb.CurrentTitle} isPlaying={pb.IsPlaying}");
        }

        Push("playback", new
        {
            currentSongId = song?.Id,
            currentTitle = pb.CurrentTitle,
            currentArtist = song?.Artist,
            // 底栏歌手名可点（进该艺术家的单曲列表）。有的服务拿名字当 id（AudioStation），那时它等于
            // artist 本身、点击照样有效；真的没有 id 时前端退回纯文本，不做"点了没反应"的死链接。
            currentArtistId = song?.ArtistId,
            coverUrl,
            isPlaying = pb.IsPlaying,
            positionSeconds = pb.PositionSeconds,
            durationSeconds = pb.DurationSeconds,
            volume = pb.Volume,
            playMode = pb.PlayMode.ToString(),
            isFavorite = pb.CurrentSong != null && AppServices.Favorites.IsFavorite(pb.CurrentSong.Id),
        });
    }

    private void Push(string eventName, object payload)
    {
        if (_browser is null)
        {
            // 以前这里是静默 return：万一 _browser 为空，所有状态推送都会无声消失（界面"不刷新"却毫无线索）
            LogBridgeInfo($"Push({eventName}) 跳过：browser 为空");
            return;
        }
        var json = System.Text.Json.JsonSerializer.Serialize(payload);
        // JSON.parse 方式注入，避免引号/换行破坏 JS 语法
        var js = $"window.dispatchEvent(new CustomEvent('bridgeEvent', {{ detail: {{ event: '{eventName}', payload: JSON.parse({ToJsString(json)}) }} }}));";
        Dispatcher.UIThread.Post(() =>
        {
            try { _browser.ExecuteJavaScript(js); }
            catch (Exception ex)
            {
                // 以前这里静默吞掉：注入失败会让界面"永远停在旧状态"却查不到原因
                LogBridgeInfo($"Push({eventName}) ExecuteJavaScript 失败: {ex.GetType().Name}: {ex.Message}");
            }
        });
    }

    /// <summary>上一次已推送的曲目 id（换歌诊断用）。</summary>
    private string? _lastPushedSongId;

    private static string ToJsString(string s)
        => "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n") + "\"";

    public void Dispose()
    {
        _disposed = true;
        _connMon?.Dispose();
        _connMon = null;
        foreach (var unsub in _subscriptions)
            unsub();
        _subscriptions.Clear();
    }

    // ============ JS → C# 方法 ============

    public object GetInitialState() => new
    {
        playback = PlaybackSnapshot(),
        services = AppServices.Settings.Settings.Services
            .Select(s => new Dictionary<string, object?> { ["id"] = s.Id, ["name"] = s.Name })
            .ToArray(),
        currentServiceId = AppServices.GetCurrentService()?.Id,
        theme = AppServices.Settings.Settings.ThemeId,
        volumeNormalization = AppServices.Settings.Settings.VolumeNormalization,
        version = AppVersion,
    };

    /// <summary>应用版本号（读程序集版本，csproj <Version> 控制）。</summary>
    internal static string AppVersion =>
        System.Reflection.Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.0.0";

    private object PlaybackSnapshot()
    {
        var pb = AppServices.Playback;
        var song = pb.CurrentSong;
        var coverUrl = song?.CoverArtId != null && AppServices.Music is not null
            ? AppServices.Music.GetCoverArtUrl(song.CoverArtId, 150)
            : null;
        return new
        {
            currentSongId = song?.Id,
            currentTitle = pb.CurrentTitle,
            currentArtist = song?.Artist,
            currentArtistId = song?.ArtistId,   // 见 PushPlayback：底栏歌手名可点
            coverUrl,
            isPlaying = pb.IsPlaying,
            positionSeconds = pb.PositionSeconds,
            durationSeconds = pb.DurationSeconds,
            volume = pb.Volume,
            playMode = pb.PlayMode.ToString(),
            isFavorite = song != null && AppServices.Favorites.IsFavorite(song.Id),
        };
    }

    // 传输控制一律回到 UI 线程执行（Send 优先级，保证立刻响应）：
    // PlaybackService 的属性变更多数会走到 SMTC/托盘等 UI 相关对象，
    // 原来直接在 JS 回调线程（线程池）上改状态，既不规范也容易被积压任务拖慢。
    public void TogglePlay() => Dispatcher.UIThread.Post(
        () => AppServices.Playback.PlayPauseCommand.Execute(null), DispatcherPriority.Send);
    public void Previous() => Dispatcher.UIThread.Post(
        () => AppServices.Playback.PreviousCommand.Execute(null), DispatcherPriority.Send);
    public void Next() => Dispatcher.UIThread.Post(
        () => AppServices.Playback.NextCommand.Execute(null), DispatcherPriority.Send);
    public void TogglePlayMode() => Dispatcher.UIThread.Post(
        () => AppServices.Playback.TogglePlayModeCommand.Execute(null), DispatcherPriority.Send);
    public void ToggleFavorite() => ToggleFavoriteAsync();

    public void PlaySongs(string[] songIds, int startIndex)
    {
        // Send 优先级：点歌要立刻插队执行，不能排在（慢网下可能积压的）状态推送后面。
        Dispatcher.UIThread.Post(() =>
        {
            var songs = ResolveSongs(songIds);
            if (songs.Count == 0) return;
            AppServices.Playback.PlayQueue(songs, Math.Clamp(startIndex, 0, songs.Count - 1));
        }, DispatcherPriority.Send);
    }

    /// <summary>JS 侧诊断日志入口（JS 调 Bridge.invoke('logClient', msg) 落到 bridge.log）。</summary>
    public void LogClient(string message) => LogBridgeInfo("JS: " + message);

    /// <summary>JS 传完整歌曲 JSON 直接播放（不依赖本地曲库缓存，因为页面数据来自网络）。</summary>
    public void PlaySongsJson(string songsJson, int startIndex)
    {
        try
        {
            var opts = new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            var songs = System.Text.Json.JsonSerializer.Deserialize<List<Song>>(songsJson, opts);
            if (songs is null || songs.Count == 0) return;
            var start = Math.Clamp(startIndex, 0, songs.Count - 1);
            LogBridgeInfo($"PlaySongsJson count={songs.Count} startIndex={startIndex} -> [{start}] {songs[start].Id} / {songs[start].Title}");
            // Send 优先级：点歌立刻插队（理由同 PlaySongs）。
            Dispatcher.UIThread.Post(() =>
                AppServices.Playback.PlayQueue(songs, start),
                DispatcherPriority.Send);
        }
        catch (Exception ex)
        {
            LogBridgeError("PlaySongsJson", ex);
        }
    }

    /// <summary>JS 传完整歌曲 JSON 添加到队列。</summary>
    public void AddSongToQueueJson(string songJson)
    {
        try
        {
            var opts = new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            var song = System.Text.Json.JsonSerializer.Deserialize<Song>(songJson, opts);
            if (song is null) return;
            Dispatcher.UIThread.Post(() => AppServices.Playback.AddToQueue(song));
        }
        catch (Exception ex)
        {
            LogBridgeError("AddSongToQueueJson", ex);
        }
    }

    public async void PlayAlbum(string albumId)
    {
        var music = AppServices.Music;
        if (music is null) return;
        try
        {
            var album = await music.GetAlbumAsync(albumId);
            if (album is null || album.Songs.Count == 0) return;
            Dispatcher.UIThread.Post(() => AppServices.Playback.PlayQueue(album.Songs, 0), DispatcherPriority.Send);
        }
        catch (Exception ex)
        {
            LogBridgeError($"PlayAlbum({albumId})", ex);
        }
    }

    public void AddSongToQueue(string songId)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var song = ResolveSongs(new[] { songId }).FirstOrDefault();
            if (song is not null)
                AppServices.Playback.AddToQueue(song);
        });
    }

    public void ToggleFavoriteForSong(string songId) => ToggleFavoriteForSongAsync(songId);

    public async void PlayBookmark(string songId, long positionMs)
    {
        var music = AppServices.Music;
        if (music is null) return;
        var bookmarks = await music.GetBookmarksAsync();
        var bookmark = bookmarks.FirstOrDefault(b => b.Songs.FirstOrDefault()?.Id == songId);
        if (bookmark is not null)
            Dispatcher.UIThread.Post(() => AppServices.Playback.PlayBookmark(bookmark));
    }

    private static List<Song> ResolveSongs(IEnumerable<string> ids)
    {
        var list = new List<Song>();
        var db = AppServices.Library;
        foreach (var id in ids)
        {
            if (db.GetSong(id) is { } cached)
                list.Add(cached);
        }
        return list;
    }

    private async void ToggleFavoriteForSongAsync(string songId)
    {
        var music = AppServices.Music;
        if (music is null) return;

        var favorite = !AppServices.Favorites.IsFavorite(songId);
        await ApplyFavoriteAsync(songId, favorite);
    }

    /// <summary>设置收藏并推送状态更新（播放栏红心 + 列表红心）。</summary>
    private async System.Threading.Tasks.Task ApplyFavoriteAsync(string songId, bool favorite)
    {
        AppServices.Favorites.Set(songId, favorite);
        var music = AppServices.Music;
        try
        {
            if (music is not null)
                await music.SetFavoriteAsync(songId, favorite);
        }
        catch
        {
            AppServices.Favorites.Set(songId, !favorite);
        }

        // 推送收藏状态变化，JS 更新播放栏与列表红心
        Push("favoriteChanged", new { songId, isFavorite = AppServices.Favorites.IsFavorite(songId) });
        if (AppServices.Playback.CurrentSong?.Id == songId)
            PushPlayback(force: true);   // 收藏状态在 payload 里，但没改任何 PlaybackService 属性 ⇒ 强制推
    }

    public void Seek(double ratio)
    {
        var pb = AppServices.Playback;
        if (pb.DurationSeconds > 0)
            pb.Seek(ratio * pb.DurationSeconds);
    }

    public void SetVolume(double volume)
    {
        AppServices.Playback.Volume = Math.Clamp(volume, 0, 1);
    }

    private async void ToggleFavoriteAsync()
    {
        var pb = AppServices.Playback;
        var song = pb.CurrentSong;
        if (song is null)
            return;

        var favorite = !AppServices.Favorites.IsFavorite(song.Id);
        await ApplyFavoriteAsync(song.Id, favorite);
    }

    // ============ 窗口控制 ============

    public void WindowMinimize()
    {
        Dispatcher.UIThread.Post(() =>
        {
            if (_window is null) return;
            _window.WindowState = WindowState.Minimized;
        });
    }

    public void WindowMaximize()
    {
        Dispatcher.UIThread.Post(() =>
        {
            if (_window is null) return;
            _window.WindowState = _window.WindowState == WindowState.Maximized
                ? WindowState.Normal
                : WindowState.Maximized;
        });
    }

    public void WindowClose()
    {
        // HTML 关闭按钮 = 真正退出应用（不走「隐藏到托盘」）。
        // 用 DispatcherPriority.Send：UI 队列里积压着大量数据回填任务时，关闭也要立刻插队执行，
        // 否则表现就是「点了关闭半天没反应」。
        Dispatcher.UIThread.Post(() =>
        {
            if (Application.Current is App app)
                app.Exit();
        }, DispatcherPriority.Send);

        // 兜底：若 UI 线程被卡住（服务器挂起时 bridge 调用堆积）导致上面那段迟迟不执行，
        // 6 秒后强制结束进程 —— 保证「关闭」按钮在任何情况下都能把应用关掉。
        var watchdog = new System.Threading.Thread(() =>
        {
            System.Threading.Thread.Sleep(6000);
            Environment.Exit(0);
        })
        { IsBackground = true, Name = "close-watchdog" };
        watchdog.Start();
    }

    /// <summary>打开迷你播放器浮窗：收起主窗口，关闭小窗时恢复主窗口。</summary>
    public void OpenMiniPlayer()
    {
        Dispatcher.UIThread.Post(() =>
        {
            if (_miniWindow is null)
            {
                _miniWindow = new MiniPlayerWindow();
                _miniWindow.Closed += (_, _) =>
                {
                    _miniWindow = null;
                    if (_window is not null)
                    {
                        _window.Show();
                        _window.Activate();
                    }
                };
            }
            _window?.Hide();
            _miniWindow.Show();
        });
    }

    /// <summary>OSR 模式下强制浏览器控件获得键盘焦点，否则键盘事件不进入 CEF。</summary>
    public void FocusBrowser()
    {
        Dispatcher.UIThread.Post(() => TryFocusBrowser());
    }

    /// <summary>强制 AvaloniaCefBrowser 获得 Avalonia 键盘焦点（Focusable + Focus 双保险）。</summary>
    private void TryFocusBrowser()
    {
        if (_browser is null) return;
        try
        {
            _browser.Focusable = true;
            _browser.Focus();
        }
        catch { /* 忽略 */ }
    }

    /// <summary>最近一次鼠标按下（非 Windows 平台 BeginMoveDrag 需要 PointerPressedEventArgs）。</summary>
    private Avalonia.Input.PointerPressedEventArgs? _lastPointer;

    /// <summary>从 HTML 标题栏发起窗口拖动。</summary>
    public void StartWindowDrag()
    {
        if (_window is null) return;
        Dispatcher.UIThread.Post(() =>
        {
            try
            {
#if WINDOWS
                var handle = _window.TryGetPlatformHandle()?.Handle;
                if (handle is null || handle == nint.Zero) return;
                var hwnd = handle.Value;
                // ReleaseCapture + WM_NCLBUTTONDOWN(2=HTCAPTION)：让系统接管拖动
                Win32.ReleaseCapture();
                Win32.SendMessage(hwnd, 0x00A1 /* WM_NCLBUTTONDOWN */, new IntPtr(2), IntPtr.Zero);
#else
                // macOS/Linux：走 Avalonia 原生无边框拖动（OSR 下缓存最近按下事件）
                if (_lastPointer is not null)
                    _window.BeginMoveDrag(_lastPointer);
#endif
            }
            catch { /* 忽略 */ }
        });
    }

    public void SetTheme(string themeId)
    {
        // 主题按 id 持久化（C# AppSettings 是唯一事实，避免依赖 app:// 下可能不可用的 localStorage）。
        // 「深浅」不再是独立开关，而是 dark/light 两套主题之一。
        var valid = new[] { "dark", "light", "forest", "midnight", "sunset", "rose" };
        if (!valid.Contains(themeId)) themeId = "dark";
        AppServices.Settings.Settings.ThemeId = themeId;
        _ = AppServices.Settings.SaveAsync();
        ApplyWindowTheme();
        Push("theme", new { theme = themeId });
    }

    /// <summary>音量平均开关：持久化 + 应用到引擎 + 回推状态。</summary>
    public void SetVolumeNormalization(bool enabled)
    {
        AppServices.Settings.Settings.VolumeNormalization = enabled;
        _ = AppServices.Settings.SaveAsync();
        AppServices.Playback.SetVolumeNormalization(enabled);
        Push("volumeNormalization", new { enabled });
    }

    public bool GetVolumeNormalization() => AppServices.Settings.Settings.VolumeNormalization;

    /// <summary>按当前 ThemeId 同步窗口/浏览器底色（浅色下避免 CEF 边缘残留深色）。</summary>
    public void ApplyWindowTheme()
    {
        var isLight = string.Equals(AppServices.Settings.Settings.ThemeId, "light", StringComparison.OrdinalIgnoreCase);
        Dispatcher.UIThread.Post(() =>
        {
            var bg = new Avalonia.Media.SolidColorBrush(
                isLight ? Avalonia.Media.Color.Parse("#F5F5F7") : Avalonia.Media.Color.Parse("#0A0A0C"));
            if (_window is not null)
            {
                _window.Background = bg;
                if (_window.FindControl<Avalonia.Controls.Border>("BrowserHost") is { } host)
                    host.Background = bg;
            }
            if (_miniWindow is not null)
                _miniWindow.Background = bg;
        });
    }

    public Task SwitchService(string id) => PostOnUI(() => AppServices.SwitchTo(id));

    /// <summary>从队列指定位置播放。</summary>
    public void PlayFromQueue(int index)
    {
        Dispatcher.UIThread.Post(() => AppServices.Playback.PlayFromIndex(index), DispatcherPriority.Send);
    }

    /// <summary>设置均衡器某一频段增益（-15..15）。</summary>
    public void SetEqGain(int band, double gain)
    {
        Dispatcher.UIThread.Post(() =>
        {
            LogBridgeInfo($"SetEqGain band={band} gain={gain}");
            AppServices.Playback.SetEqGain(band, (float)gain);
        });
    }

    /// <summary>读取当前 10 段 EQ 增益（面板重开时回显，保持上次调整）。</summary>
    public double[] GetEqGains()
    {
        return Enumerable.Range(0, 10)
            .Select(i => (double)AppServices.Playback.GetEqGain(i))
            .ToArray();
    }

    /// <summary>设置睡眠定时器（分钟，0 关闭）。</summary>
    public void SetSleepTimer(int minutes)
    {
        Dispatcher.UIThread.Post(() =>
        {
            LogBridgeInfo($"SetSleepTimer minutes={minutes}");
            AppServices.Playback.SetSleepTimerCommand.Execute(minutes.ToString());
        });
    }

    /// <summary>应用 EQ 预设（摇滚/流行/古典/人声/重低音/自定义）。</summary>
    public void ApplyEqPreset(string name)
    {
        Dispatcher.UIThread.Post(() =>
        {
            float[] gains = name switch
            {
                "摇滚" => new float[] { 5, 3, 0, -2, -1, 2, 4, 5, 4, 3 },
                "流行" => new float[] { -1, 1, 3, 4, 3, 0, -1, -1, 0, 1 },
                "古典" => new float[] { 4, 3, 2, 0, -1, -1, 0, 2, 3, 4 },
                "人声" => new float[] { -2, -1, 0, 2, 4, 4, 3, 1, 0, -1 },
                "重低音" => new float[] { 6, 5, 4, 2, 0, 0, 0, 0, 0, 0 },
                _ => new float[10],
            };
            for (var i = 0; i < 10 && i < gains.Length; i++)
                AppServices.Playback.SetEqGain(i, gains[i]);
        });
    }

    /// <summary>重置 EQ 全部归零。</summary>
    public void ResetEq()
    {
        Dispatcher.UIThread.Post(() =>
        {
            for (var i = 0; i < 10; i++)
                AppServices.Playback.SetEqGain(i, 0);
        });
    }

    // ============ 设置：服务管理 ============

    public object? GetServices()
    {
        return new
        {
            services = AppServices.Settings.Settings.Services.Select(s => new Dictionary<string, object?>
            {
                ["id"] = s.Id,
                ["name"] = s.Name,
                ["type"] = s.Type.ToString(),
                ["lanUrl"] = s.LanUrl,
                ["wanUrl"] = s.WanUrl,
                ["username"] = s.Username,
                ["hasPassword"] = !string.IsNullOrEmpty(s.Password),
            }).ToArray(),
            currentServiceId = AppServices.GetCurrentService()?.Id,
        };
    }

    public Task SaveService(string id, string name, string type, string lanUrl, string wanUrl, string username, string password)
    {
        // 解析客户端传入的服务类型字符串（Subsonic/Navidrome/Jellyfin/Gonic/Emby/Plex/AudioStation）
        var serviceType = Enum.TryParse<Models.MusicServiceType>(type, true, out var parsed) ? parsed : Models.MusicServiceType.Subsonic;
        return PostOnUI(() =>
        {
            var config = AppServices.Settings.Settings.Services.FirstOrDefault(s => s.Id == id);
            if (config is null)
            {
                // 新增
                AppServices.AddService(new Models.MusicServiceConfig
                {
                    Id = id,
                    Name = name,
                    Type = serviceType,
                    LanUrl = lanUrl,
                    WanUrl = wanUrl,
                    Username = username,
                    Password = password,
                });
            }
            else
            {
                config.Name = name;
                config.Type = serviceType;
                config.LanUrl = lanUrl;
                config.WanUrl = wanUrl;
                config.Username = username;
                // 密码留空 = 保持原密码（避免每次编辑回填明文密码）
                if (!string.IsNullOrEmpty(password))
                    config.Password = password;
                AppServices.UpdateService(config);
            }
            _ = AppServices.Settings.SaveAsync();

            // 配置变更后重建当前服务客户端并刷新 UI，确保新配置立即生效（新用户首次配置后能加载数据）。
            AppServices.ReloadCurrent();
        });
    }

    public Task DeleteService(string id) => PostOnUI(() => AppServices.RemoveService(id));

    /// <summary>把动作调度到 UI 线程执行，并返回一个会在执行完成后完成的 Task，供 JS 端 await。</summary>
    private static Task PostOnUI(Action action)
    {
        var tcs = new TaskCompletionSource<bool>();
        Dispatcher.UIThread.Post(() =>
        {
            try { action(); tcs.TrySetResult(true); }
            catch (Exception ex) { tcs.TrySetException(ex); }
        });
        return tcs.Task;
    }
}

#if WINDOWS
/// <summary>窗口拖动的 Win32 P/Invoke（仅 Windows）。</summary>
internal static class Win32
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    internal static extern bool ReleaseCapture();

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    internal static extern IntPtr SendMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
}
#endif
