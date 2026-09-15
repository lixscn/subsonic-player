using System;
using System.IO;
using System.Runtime.InteropServices;
using Avalonia.Controls;
using SubsonicPlayer.Services;

#if WINDOWS
using Windows.Media;
using Windows.Storage.Streams;
namespace SubsonicPlayer;

/// <summary>
/// Windows SMTC（System Media Transport Controls）集成：
/// 任务栏媒体控制、缩略图按钮、系统媒体键（播放/暂停/上一首/下一首）。
/// 通过 SystemMediaTransportControlsInterop COM 接口绑定到主窗口句柄（Win32 桌面应用标准做法）。
/// 初始化失败时静默降级，不影响其他功能。
/// 实现 <see cref="IMediaIntegration"/>，仅 Windows 目标框架编译。
/// </summary>
public sealed class SmtcService : IMediaIntegration
{
    private const string ControlsTypeName = "Windows.Media.SystemMediaTransportControls";

    private static readonly Guid SmtcInteropIid = new("ddb0472d-c911-4a1e-86d9-dc3d71a95f5a");
    private static readonly Guid SmtcIid = new("99faf280-5c47-42f0-b1fa-3e1b0eaf6d74");

    private SystemMediaTransportControls? _controls;
    private bool _attempted;

    [ComImport]
    [Guid("ddb0472d-c911-4a1e-86d9-dc3d71a95f5a")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISystemMediaTransportControlsInterop
    {
        void GetForWindow(IntPtr appWindow, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object instance);
    }

    [DllImport("combase.dll", ExactSpelling = true)]
    private static extern int RoGetActivationFactory(
        [MarshalAs(UnmanagedType.HString)] string activatableClassId,
        ref Guid iid,
        [MarshalAs(UnmanagedType.IUnknown)] out object factory);

    public bool IsAvailable => _controls is not null;

    /// <summary>绑定到主窗口（仅尝试一次，失败静默）。</summary>
    public void Initialize(object? window)
    {
        if (_attempted)
            return;
        _attempted = true;

        try
        {
            var win = window as Window;
            var hwnd = win?.TryGetPlatformHandle()?.Handle ?? IntPtr.Zero;
            if (hwnd == IntPtr.Zero)
                return;

            var interopIid = SmtcInteropIid;
            var hr = RoGetActivationFactory(ControlsTypeName, ref interopIid, out var factoryObj);
            if (hr != 0 || factoryObj is not ISystemMediaTransportControlsInterop interop)
                return;

            var iid = SmtcIid;
            interop.GetForWindow(hwnd, ref iid, out var obj);

            _controls = (SystemMediaTransportControls)obj;
            _controls.IsEnabled = true;
            _controls.IsPlayEnabled = true;
            _controls.IsPauseEnabled = true;
            _controls.IsNextEnabled = true;
            _controls.IsPreviousEnabled = true;
            _controls.PlaybackStatus = MediaPlaybackStatus.Closed;
            _controls.ButtonPressed += OnButtonPressed;
        }
        catch
        {
            _controls = null;
        }
    }

    private void OnButtonPressed(SystemMediaTransportControls sender, SystemMediaTransportControlsButtonPressedEventArgs args)
    {
        var playback = AppServices.Playback;
        switch (args.Button)
        {
            case SystemMediaTransportControlsButton.Play:
            case SystemMediaTransportControlsButton.Pause:
                playback.PlayPauseCommand.Execute(null);
                break;
            case SystemMediaTransportControlsButton.Next:
                playback.NextCommand.Execute(null);
                break;
            case SystemMediaTransportControlsButton.Previous:
                playback.PreviousCommand.Execute(null);
                break;
        }
    }

    /// <summary>更新曲目信息。</summary>
    public void UpdateTrack(string title, string artist)
    {
        if (_controls is null)
            return;

        try
        {
            var updater = _controls.DisplayUpdater;
            updater.Type = MediaPlaybackType.Music;
            updater.MusicProperties.Title = title;
            updater.MusicProperties.Artist = artist;
            updater.Update();
        }
        catch
        {
            // 更新失败忽略
        }
    }

    /// <summary>更新播放状态。</summary>
    public void UpdatePlaybackStatus(bool playing)
    {
        if (_controls is null)
            return;

        try
        {
            _controls.PlaybackStatus = playing ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;
        }
        catch
        {
            // 更新失败忽略
        }
    }

    /// <summary>更新封面缩略图（任务栏媒体控件显示的封面，原始字节）。</summary>
    public void UpdateCover(byte[]? imageBytes)
    {
        if (_controls is null || imageBytes is null || imageBytes.Length == 0)
            return;

        try
        {
            // 用 InMemoryRandomAccessStream 承载数据：SMTC 是异步读缩略图，
            // 若用 using 的 MemoryStream 转流，方法结束即被释放，缩略图可能读不到。
            var stream = new InMemoryRandomAccessStream();
            using (var outStream = stream.AsStreamForWrite())
            {
                outStream.Write(imageBytes, 0, imageBytes.Length);
                outStream.Flush();
            }
            stream.Seek(0);

            var updater = _controls.DisplayUpdater;
            updater.Thumbnail = RandomAccessStreamReference.CreateFromStream(stream);
            updater.Update();
        }
        catch
        {
            // 更新失败忽略
        }
    }

    public void Dispose()
    {
        if (_controls is not null)
        {
            try
            {
                _controls.ButtonPressed -= OnButtonPressed;
                _controls.IsEnabled = false;
            }
            catch
            {
                // 释放失败忽略
            }
            _controls = null;
        }
    }
}
#endif
