using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Input.Platform;
using SubsonicPlayer.Services;

namespace SubsonicPlayer;

/// <summary>桌面剪贴板实现（Avalonia 桌面应用，经主窗口 TopLevel 获取）。</summary>
public sealed class DesktopClipboard : IClipboardService
{
    public async Task SetTextAsync(string text)
    {
        var clipboard = GetClipboard();
        if (clipboard is not null)
            await clipboard.SetTextAsync(text);
    }

    private static IClipboard? GetClipboard()
    {
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop
            && desktop.MainWindow is { } window)
        {
            return TopLevel.GetTopLevel(window)?.Clipboard;
        }
        return null;
    }
}