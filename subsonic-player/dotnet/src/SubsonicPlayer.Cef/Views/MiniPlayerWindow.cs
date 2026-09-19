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
        // 底色由 ApplyTheme 按当前主题设置；先给深色兜底。
        Background = new SolidColorBrush(Color.Parse("#0A0A0C"));
        Opened += (_, _) => InitCef();
    }

    /// <summary>无边框窗的底色要跟主题一致，否则浅色主题下四角会露出深色。</summary>
    public void ApplyTheme(string? themeId)
    {
        var light = string.Equals(themeId, "light", StringComparison.OrdinalIgnoreCase);
        Background = new SolidColorBrush(Color.Parse(light ? "#ECEFF4" : "#0A0A0C"));
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
