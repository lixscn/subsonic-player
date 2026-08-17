using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Data.Core;
using Avalonia.Data.Core.Plugins;
using System;
using System.IO;
using System.Linq;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Threading;
using SubsonicPlayer.Services;
using SubsonicPlayer.ViewModels;
using SubsonicPlayer.Views;

namespace SubsonicPlayer;

public partial class App : Application
{
    private TrayIcon? _trayIcon;
    private MiniPlayerView? _miniPlayer;
    private IClassicDesktopStyleApplicationLifetime? _desktop;
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
        // 全局异常处理：记录到日志文件，便于定位崩溃
        AppDomain.CurrentDomain.UnhandledException += (_, e) => LogException(e.ExceptionObject as Exception);
        Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            LogException(e.Exception);
            e.Handled = true; // 避免 UI 线程异常直接崩溃
        };

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            _desktop = desktop;
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

            // 应用真正退出时释放托盘图标/音频引擎/SMTC，避免进程残留
            desktop.Exit += (_, _) => ReleaseResources();

            // SMTC 任务栏媒体控制（需在窗口打开后拿到句柄）
            if (AppServices.Settings.Settings.SmtcEnabled)
                window.Opened += (_, _) => AppServices.Smtc.Initialize(window);
        }

        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>释放托盘图标、音频引擎、SMTC 等资源，确保进程可完整退出。</summary>
    private void ReleaseResources()
    {
        if (_trayIcon is not null)
        {
            _trayIcon.Dispose();
            _trayIcon = null;
        }
        AppServices.Playback.Shutdown();
        AppServices.Smtc.Dispose();
    }

    /// <summary>退出应用：关闭迷你播放器、释放托盘图标，并显式触发 Shutdown（不依赖「最后窗口关闭」自动退出）。</summary>
    private void Exit()
    {
        if (_isExiting)
            return;
        _isExiting = true;

        _miniPlayer?.Close();
        _miniPlayer = null;

        _trayIcon?.Dispose();
        _trayIcon = null;

        ReleaseResources();
        _desktop?.Shutdown();
    }

    /// <summary>记录未处理异常到数据目录的 crash.log，便于定位崩溃。</summary>
    private static void LogException(Exception? ex)
    {
        if (ex is null)
            return;

        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            Directory.CreateDirectory(dir);
            File.AppendAllText(
                Path.Combine(dir, "crash.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex.GetType().Name}: {ex.Message}{Environment.NewLine}{ex.StackTrace}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
            // 日志失败忽略
        }
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

        var miniPlayerItem = new NativeMenuItem("迷你播放器");
        miniPlayerItem.Click += (_, _) => ShowMiniPlayer();

        var playPauseItem = new NativeMenuItem("播放 / 暂停");
        playPauseItem.Click += (_, _) => AppServices.Playback.PlayPauseCommand.Execute(null);

        var nextItem = new NativeMenuItem("下一首");
        nextItem.Click += (_, _) => AppServices.Playback.NextCommand.Execute(null);

        var bookmarkItem = new NativeMenuItem("记住播放位置");
        bookmarkItem.Click += (_, _) => _ = AppServices.Playback.BookmarkCurrentAsync();

        var restoreItem = new NativeMenuItem("恢复播放队列");
        restoreItem.Click += (_, _) => _ = AppServices.Playback.RestoreQueueFromCloudAsync();

        var exitItem = new NativeMenuItem("退出");
        exitItem.Click += (_, _) => Exit();

        _trayIcon = new TrayIcon
        {
            Icon = new WindowIcon(AssetLoader.Open(new Uri("avares://SubsonicPlayer/Assets/avalonia-logo.ico"))),
            ToolTipText = "Subsonic 音乐播放器",
            Menu = new NativeMenu
            {
                Items =
                {
                    showItem,
                    miniPlayerItem,
                    new NativeMenuItemSeparator(),
                    playPauseItem,
                    nextItem,
                    bookmarkItem,
                    restoreItem,
                    new NativeMenuItemSeparator(),
                    exitItem,
                },
            },
            IsVisible = true,
        };
    }

    /// <summary>打开/聚焦迷你播放器窗口（单例）。</summary>
    public void ShowMiniPlayer()
    {
        if (_miniPlayer is null)
        {
            _miniPlayer = new MiniPlayerView { DataContext = new MiniPlayerViewModel() };
            _miniPlayer.Closed += (_, _) => _miniPlayer = null;
        }

        _miniPlayer.Show();
        _miniPlayer.Activate();
    }
}
