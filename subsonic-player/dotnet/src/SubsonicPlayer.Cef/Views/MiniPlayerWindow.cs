using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using SubsonicPlayer.Services;
using Xilium.CefGlue.Avalonia;

namespace SubsonicPlayer.Views;

/// <summary>
/// 迷你播放器浮窗：无边框小窗 + 第二个 CefGlue OSR 浏览器实例（app://ui/mini.html）。
/// 由主窗口 CefUiBridge.OpenMiniPlayer 创建；关闭时恢复主窗口。
/// </summary>
public sealed class MiniPlayerWindow : Window
{
    private AvaloniaCefBrowser? _browser;
    private MiniPlayerBridge? _bridge;

    public MiniPlayerWindow()
    {
        Width = 300;
        Height = 84;
        CanResize = false;
        ShowInTaskbar = true;
        Topmost = true;
        SystemDecorations = SystemDecorations.None;
        Background = new SolidColorBrush(Color.Parse("#17171B"));
        Opened += (_, _) => InitCef();
    }

    private void InitCef()
    {
        if (_browser is not null)
            return;

        _browser = new AvaloniaCefBrowser();
        _bridge = new MiniPlayerBridge();
        // 与主窗口一致：方法在线程池执行，CefGlue 自动转 Promise
        _browser.RegisterJavascriptObject(_bridge, "bridge", originalFunction =>
            System.Threading.Tasks.Task<object?>.Run(originalFunction));
        _browser.Address = "app://ui/mini.html";
        _bridge.AttachBrowser(_browser, this);

        Content = _browser;
    }

    protected override void OnClosed(EventArgs e)
    {
        _bridge?.Dispose();
        base.OnClosed(e);
    }
}
