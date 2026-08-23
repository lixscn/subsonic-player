using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
using SubsonicPlayer.Models;
using SubsonicPlayer.ViewModels;
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
    private readonly List<Action> _subscriptions = new();

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
        // 非 Windows 无边框拖动需要 PointerEventArgs（macOS/Linux）；OSR 下缓存最近一次按下
        _browser.PointerPressed += (_, e) => _lastPointer = e;
        SubscribeState();
        PrewarmConnection();
    }

    /// <summary>启动预热：后台连接服务器，让首屏数据请求秒回。</summary>
    private void PrewarmConnection()
    {
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

        AppServices.ServicesChanged += OnServicesChanged;
        AppServices.CurrentServiceChanged += OnServicesChanged;
        _subscriptions.Add(() => AppServices.ServicesChanged -= OnServicesChanged);
        _subscriptions.Add(() => AppServices.CurrentServiceChanged -= OnServicesChanged);
    }

    private DateTime _lastProgressPush = DateTime.MinValue;

    private void OnPlaybackChanged(object? sender, PropertyChangedEventArgs e)
    {
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

    private void PushPlayback()
    {
        var pb = AppServices.Playback;
        var song = pb.CurrentSong;
        var coverUrl = song?.CoverArtId != null && AppServices.Music is not null
            ? AppServices.Music.GetCoverArtUrl(song.CoverArtId, 150)
            : null;
        if (coverUrl is not null)
            LogBridgeInfo($"PushPlayback cover: song='{song?.Title}' coverArtId='{song?.CoverArtId}' url={coverUrl}");

        Push("playback", new
        {
            currentSongId = song?.Id,
            currentTitle = pb.CurrentTitle,
            currentArtist = song?.Artist,
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
            return;
        var json = System.Text.Json.JsonSerializer.Serialize(payload);
        // JSON.parse 方式注入，避免引号/换行破坏 JS 语法
        var js = $"window.dispatchEvent(new CustomEvent('bridgeEvent', {{ detail: {{ event: '{eventName}', payload: JSON.parse({ToJsString(json)}) }} }}));";
        Dispatcher.UIThread.Post(() =>
        {
            try { _browser.ExecuteJavaScript(js); } catch { /* 页面未就绪忽略 */ }
        });
    }

    private static string ToJsString(string s)
        => "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n") + "\"";

    public void Dispose()
    {
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
        theme = "dark",
    };

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
            coverUrl,
            isPlaying = pb.IsPlaying,
            positionSeconds = pb.PositionSeconds,
            durationSeconds = pb.DurationSeconds,
            volume = pb.Volume,
            playMode = pb.PlayMode.ToString(),
            isFavorite = song != null && AppServices.Favorites.IsFavorite(song.Id),
        };
    }

    public void TogglePlay() => AppServices.Playback.PlayPauseCommand.Execute(null);
    public void Previous() => AppServices.Playback.PreviousCommand.Execute(null);
    public void Next() => AppServices.Playback.NextCommand.Execute(null);
    public void TogglePlayMode() => AppServices.Playback.TogglePlayModeCommand.Execute(null);
    public void ToggleFavorite() => ToggleFavoriteAsync();

    public void PlaySongs(string[] songIds, int startIndex)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var songs = ResolveSongs(songIds);
            if (songs.Count == 0) return;
            AppServices.Playback.PlayQueue(songs, Math.Clamp(startIndex, 0, songs.Count - 1));
        });
    }

    /// <summary>JS 传完整歌曲 JSON 直接播放（不依赖本地曲库缓存，因为页面数据来自网络）。</summary>
    public void PlaySongsJson(string songsJson, int startIndex)
    {
        try
        {
            var opts = new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            var songs = System.Text.Json.JsonSerializer.Deserialize<List<Song>>(songsJson, opts);
            if (songs is null || songs.Count == 0) return;
            Dispatcher.UIThread.Post(() =>
                AppServices.Playback.PlayQueue(songs, Math.Clamp(startIndex, 0, songs.Count - 1)));
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
            Dispatcher.UIThread.Post(() => AppServices.Playback.PlayQueue(album.Songs, 0));
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
            PushPlayback();
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
        // HTML 关闭按钮 = 真正退出应用（不走「隐藏到托盘」）
        Dispatcher.UIThread.Post(() =>
        {
            if (Application.Current is App app)
                app.Exit();
        });
    }

    /// <summary>OSR 模式下点击输入框时强制浏览器控件获得键盘焦点，否则键盘事件不进入 CEF。</summary>
    public void FocusBrowser()
    {
        Dispatcher.UIThread.Post(() =>
        {
            try { _browser?.Focus(); }
            catch { /* 忽略 */ }
        });
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

    public void ToggleTheme()
    {
        // HTML UI 主题切换：推送当前要切换到的主题，JS 端切换 html.light class
        var isLight = _isLightTheme;
        _isLightTheme = !isLight;
        Push("theme", new { theme = _isLightTheme ? "light" : "dark" });
        // 同步窗口背景 + 浏览器容器背景，避免浅色下 CEF 边缘露深色/残留
        Dispatcher.UIThread.Post(() =>
        {
            var bg = new Avalonia.Media.SolidColorBrush(
                _isLightTheme ? Avalonia.Media.Color.Parse("#F5F5F7") : Avalonia.Media.Color.Parse("#0A0A0C"));
            if (_window is not null)
            {
                _window.Background = bg;
                if (_window.FindControl<Avalonia.Controls.Border>("BrowserHost") is { } host)
                    host.Background = bg;
            }
        });
    }

    private bool _isLightTheme = false;

    public void SwitchService(string id)
    {
        Dispatcher.UIThread.Post(() => AppServices.SwitchTo(id));
    }

    /// <summary>从队列指定位置播放。</summary>
    public void PlayFromQueue(int index)
    {
        Dispatcher.UIThread.Post(() => AppServices.Playback.PlayFromIndex(index));
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
                ["lanUrl"] = s.LanUrl,
                ["wanUrl"] = s.WanUrl,
                ["username"] = s.Username,
                ["hasPassword"] = !string.IsNullOrEmpty(s.Password),
            }).ToArray(),
            currentServiceId = AppServices.GetCurrentService()?.Id,
        };
    }

    public void SaveService(string id, string name, string lanUrl, string wanUrl, string username, string password)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var config = AppServices.Settings.Settings.Services.FirstOrDefault(s => s.Id == id);
            if (config is null)
            {
                // 新增
                AppServices.AddService(new Models.MusicServiceConfig
                {
                    Id = id,
                    Name = name,
                    LanUrl = lanUrl,
                    WanUrl = wanUrl,
                    Username = username,
                    Password = password,
                });
            }
            else
            {
                config.Name = name;
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

    public void DeleteService(string id)
    {
        Dispatcher.UIThread.Post(() => AppServices.RemoveService(id));
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
