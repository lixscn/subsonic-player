using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Data.Core;
using Avalonia.Data.Core.Plugins;
using System;
using System.Linq;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Styling;
using SubsonicPlayer.Services;
using SubsonicPlayer.ViewModels;
using SubsonicPlayer.Views;

namespace SubsonicPlayer;

public partial class App : Application
{
    private TrayIcon? _trayIcon;
    private bool _isExiting;

    private static readonly (string Key, string Dark, string Light)[] ThemeColors =
    {
        ("BgAppBrush", "#0E0E11", "#F5F5F7"),
        ("BgSurfaceBrush", "#17171B", "#FFFFFF"),
        ("BgCardBrush", "#1E1E24", "#ECECF0"),
        ("BgHoverBrush", "#26262E", "#E0E0E6"),
        ("BorderBrush", "#2E2E38", "#D5D5DC"),
        ("TextPrimaryBrush", "#F5F5F7", "#1A1A1F"),
        ("TextSecondaryBrush", "#A1A1AA", "#6B6B76"),
        ("TextMutedBrush", "#6B6B76", "#A1A1AA"),
        ("OverlayBrush", "#C00E0E11", "#CCF5F5F7"),
    };

    /// <summary>切换深浅色主题。</summary>
    public static void ApplyTheme(bool dark)
    {
        if (Current is not { } app)
            return;

        foreach (var (key, darkColor, lightColor) in ThemeColors)
        {
            var color = Color.Parse(dark ? darkColor : lightColor);
            if (app.Resources.TryGetResource(key, null, out var existing) && existing is SolidColorBrush brush)
                brush.Color = color;
            else
                app.Resources[key] = new SolidColorBrush(color);
        }

        app.RequestedThemeVariant = dark ? ThemeVariant.Dark : ThemeVariant.Light;
    }

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            AppServices.Initialize();

            var window = new MainWindow
            {
                DataContext = new MainWindowViewModel(),
            };
            desktop.MainWindow = window;

            SetupTray(window);

            // 关闭窗口 → 隐藏到托盘（不退出）
            window.Closing += (_, e) =>
            {
                if (!_isExiting)
                {
                    e.Cancel = true;
                    window.Hide();
                }
            };
        }

        base.OnFrameworkInitializationCompleted();
    }

    private void SetupTray(MainWindow window)
    {
        var showItem = new NativeMenuItem("显示主窗口");
        showItem.Click += (_, _) =>
        {
            window.Show();
            window.WindowState = WindowState.Normal;
            window.Activate();
        };

        var playPauseItem = new NativeMenuItem("播放 / 暂停");
        playPauseItem.Click += (_, _) => AppServices.Playback.PlayPauseCommand.Execute(null);

        var nextItem = new NativeMenuItem("下一首");
        nextItem.Click += (_, _) => AppServices.Playback.NextCommand.Execute(null);

        var exitItem = new NativeMenuItem("退出");
        exitItem.Click += (_, _) =>
        {
            _isExiting = true;
            window.Close();
        };

        _trayIcon = new TrayIcon
        {
            Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://SubsonicPlayer/Assets/avalonia-logo.ico"))),
            ToolTipText = "Subsonic 音乐播放器",
            Menu = new NativeMenu
            {
                Items =
                {
                    showItem,
                    playPauseItem,
                    nextItem,
                    new NativeMenuItemSeparator(),
                    exitItem,
                },
            },
            IsVisible = true,
        };
    }
}
