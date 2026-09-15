using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using SubsonicPlayer.Services;
using Xilium.CefGlue.Avalonia;

namespace SubsonicPlayer.Views;

public partial class MainWindow : Window
{
#if WINDOWS
    private readonly GlobalHotkeyManager _hotkeys = new();
#endif
    private AvaloniaCefBrowser? _browser;
    private CefUiBridge? _bridge;

    public MainWindow()
    {
        InitializeComponent();
        // 窗口图标：Windows 用 ico，macOS/Linux 用 png
        Icon = new WindowIcon(Avalonia.Platform.AssetLoader.Open(new Uri(
            $"avares://SubsonicPlayer/Assets/avalonia-logo.{(OperatingSystem.IsWindows() ? "ico" : "png")}")));
#if WINDOWS
        Opened += (_, _) => _hotkeys.Register(this);
#endif
        Opened += (_, _) => InitCef();
    }

    private void InitCef()
    {
        if (_browser is not null)
            return;

        _browser = new ImeAvaloniaCefBrowser(); // IME 支持版：让中文输入法在搜索框可用
        _bridge = new CefUiBridge();
        // 自定义 MethodCallHandler：返回 Task，CefGlue 会转成 JS Promise。
        // 方法体在线程池执行，CEF renderer 线程不被阻塞 → 页面切换不卡顿。
        _browser.RegisterJavascriptObject(_bridge, "bridge", originalFunction =>
        {
            return System.Threading.Tasks.Task<object?>.Run(() => originalFunction());
        });
        _browser.Address = "app://ui/index.html";
        _bridge.AttachBrowser(_browser, this);
        // 按持久化的主题同步窗口/浏览器底色（浅色下避免 CEF 边缘残留深色）
        _bridge.ApplyWindowTheme();

        if (BrowserHost is not null)
            BrowserHost.Child = _browser;
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.MediaPlayPause:
                AppServices.Playback.PlayPauseCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaNextTrack:
                AppServices.Playback.NextCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaPreviousTrack:
                AppServices.Playback.PreviousCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaStop:
                AppServices.Playback.PlayPauseCommand.Execute(null);
                e.Handled = true;
                break;
        }
    }
}
