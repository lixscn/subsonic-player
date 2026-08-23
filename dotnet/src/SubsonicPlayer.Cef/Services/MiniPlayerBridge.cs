using System;
using System.Collections.Generic;
using System.ComponentModel;
using Avalonia.Threading;
using SubsonicPlayer.Views;
using Xilium.CefGlue.Avalonia;

namespace SubsonicPlayer.Services;

/// <summary>
/// 迷你播放器窗口的 JS↔C# 桥接。
/// 订阅 PlaybackService 并把播放状态推给迷你浏览器；JS 通过 window.bridge 发命令。
/// 与主窗口的 CefUiBridge 各自独立，两个浏览器各收各的推送。
/// </summary>
public sealed class MiniPlayerBridge : IDisposable
{
    private AvaloniaCefBrowser? _browser;
    private MiniPlayerWindow? _window;
    private readonly List<Action> _unsub = new();
    private DateTime _lastProgressPush = DateTime.MinValue;
    private Avalonia.Input.PointerPressedEventArgs? _lastPointer;

    /// <summary>绑定浏览器与窗口，建立播放状态订阅并推送一次初始状态。</summary>
    public void AttachBrowser(AvaloniaCefBrowser browser, MiniPlayerWindow window)
    {
        _browser = browser;
        _window = window;
        // OSR 下点击需让浏览器获得焦点，避免键盘/点击失效
        _browser.PointerPressed += (_, e) => { _lastPointer = e; TryFocusBrowser(); };

        var pb = AppServices.Playback;
        pb.PropertyChanged += OnPlaybackChanged;
        _unsub.Add(() => pb.PropertyChanged -= OnPlaybackChanged);

        Dispatcher.UIThread.Post(PushPlayback);
    }

    private void OnPlaybackChanged(object? sender, PropertyChangedEventArgs e)
    {
        // 进度类属性高频触发，节流到 500ms 一次
        if (e.PropertyName is nameof(PlaybackService.PositionSeconds)
            or nameof(PlaybackService.PositionText)
            or nameof(PlaybackService.DurationText))
        {
            var now = DateTime.UtcNow;
            if ((now - _lastProgressPush).TotalMilliseconds < 500) return;
            _lastProgressPush = now;
        }
        PushPlayback();
    }

    private object Snapshot()
    {
        var pb = AppServices.Playback;
        var song = pb.CurrentSong;
        var coverUrl = song?.CoverArtId != null && AppServices.Music is not null
            ? AppServices.Music.GetCoverArtUrl(song.CoverArtId, 150)
            : null;
        return new
        {
            currentTitle = pb.CurrentTitle,
            currentArtist = song?.Artist,
            coverUrl,
            isPlaying = pb.IsPlaying,
            positionSeconds = pb.PositionSeconds,
            durationSeconds = pb.DurationSeconds,
        };
    }

    private void PushPlayback() => Push("playback", Snapshot());

    private void Push(string eventName, object payload)
    {
        if (_browser is null) return;
        var json = System.Text.Json.JsonSerializer.Serialize(payload);
        var js = $"window.dispatchEvent(new CustomEvent('bridgeEvent', {{ detail: {{ event: '{eventName}', payload: JSON.parse({ToJsString(json)}) }} }}));";
        Dispatcher.UIThread.Post(() =>
        {
            try { _browser.ExecuteJavaScript(js); } catch { /* 页面未就绪忽略 */ }
        });
    }

    private static string ToJsString(string s)
        => "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n") + "\"";

    // ============ JS → C# ============

    /// <summary>迷你页加载时拉取一次初始状态（避免推送早于监听注册而丢失）。</summary>
    public object GetState() => Snapshot();

    public void TogglePlay() => AppServices.Playback.PlayPauseCommand.Execute(null);
    public void Previous() => AppServices.Playback.PreviousCommand.Execute(null);
    public void Next() => AppServices.Playback.NextCommand.Execute(null);

    public void Seek(double ratio)
    {
        var pb = AppServices.Playback;
        if (pb.DurationSeconds <= 0) return;
        Dispatcher.UIThread.Post(() => pb.Seek(ratio * pb.DurationSeconds));
    }

    /// <summary>关闭迷你窗（Closed 事件里恢复主窗口）。</summary>
    public void CloseMini() => Dispatcher.UIThread.Post(() => _window?.Close());

    /// <summary>从迷你窗标题栏拖动窗口（Win32 / BeginMoveDrag）。</summary>
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
                Win32.ReleaseCapture();
                Win32.SendMessage(hwnd, 0x00A1 /* WM_NCLBUTTONDOWN */, new IntPtr(2), IntPtr.Zero);
#else
                if (_lastPointer is not null)
                    _window.BeginMoveDrag(_lastPointer);
#endif
            }
            catch { /* 忽略 */ }
        });
    }

    public void FocusBrowser() => Dispatcher.UIThread.Post(() => TryFocusBrowser());

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

    public void Dispose()
    {
        foreach (var unsub in _unsub)
            unsub();
        _unsub.Clear();
    }
}
