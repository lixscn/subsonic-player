using System;
using System.Runtime.InteropServices;
using Avalonia.Controls;

namespace SubsonicPlayer.Services;

/// <summary>全局快捷键（Win32 RegisterHotKey），应用在后台时也能响应。</summary>
public class GlobalHotkeyManager
{
    private const int WM_HOTKEY = 0x0312;
    private const uint MOD_CONTROL = 0x0002;
    private const uint MOD_ALT = 0x0001;

    private const uint VK_P = 0x50;
    private const uint VK_LEFT = 0x25;
    private const uint VK_RIGHT = 0x27;
    private const uint VK_SPACE = 0x20;

    private const int HotkeyPlayPause = 1;
    private const int HotkeyNext = 2;
    private const int HotkeyPrevious = 3;

    [DllImport("user32.dll")]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    private TopLevel? _topLevel;
    private Win32Properties.CustomWndProcHookCallback? _hook;

    public void Register(TopLevel topLevel)
    {
        var handle = topLevel.TryGetPlatformHandle();
        if (handle is null)
            return;

        _topLevel = topLevel;
        var hwnd = handle.Handle;

        RegisterHotKey(hwnd, HotkeyPlayPause, MOD_CONTROL | MOD_ALT, VK_P);
        RegisterHotKey(hwnd, HotkeyNext, MOD_CONTROL | MOD_ALT, VK_RIGHT);
        RegisterHotKey(hwnd, HotkeyPrevious, MOD_CONTROL | MOD_ALT, VK_LEFT);

        _hook = WndProc;
        Win32Properties.AddWndProcHookCallback(topLevel, _hook);
    }

    public void Unregister()
    {
        if (_topLevel is null)
            return;

        var handle = _topLevel.TryGetPlatformHandle();
        if (handle is not null)
        {
            UnregisterHotKey(handle.Handle, HotkeyPlayPause);
            UnregisterHotKey(handle.Handle, HotkeyNext);
            UnregisterHotKey(handle.Handle, HotkeyPrevious);
        }

        if (_hook is not null)
        {
            Win32Properties.RemoveWndProcHookCallback(_topLevel, _hook);
            _hook = null;
        }

        _topLevel = null;
    }

    private IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_HOTKEY)
        {
            switch (wParam.ToInt32())
            {
                case HotkeyPlayPause:
                    AppServices.Playback.PlayPauseCommand.Execute(null);
                    handled = true;
                    break;
                case HotkeyNext:
                    AppServices.Playback.NextCommand.Execute(null);
                    handled = true;
                    break;
                case HotkeyPrevious:
                    AppServices.Playback.PreviousCommand.Execute(null);
                    handled = true;
                    break;
            }
        }

        return IntPtr.Zero;
    }
}
