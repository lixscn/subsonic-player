using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Data.Core;
using Avalonia.Data.Core.Plugins;
using System;
using System.IO;
using System.Linq;
using System.Threading;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;
using SubsonicPlayer.Views;

namespace SubsonicPlayer;

public partial class App : Application
{
    private TrayIcon? _trayIcon;
    private IClassicDesktopStyleApplicationLifetime? _desktop;
    private bool _isExiting;
    private bool _audioReleased;
    private bool _trayReleased;

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
        ("OverlayBrush", "#7A0E0E11", "#7AF5F5F7"),
    };

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
            InjectPlatformServices();
            AppServices.Initialize();

            var window = new MainWindow();
            desktop.MainWindow = window;

            SetupTray(window);

            // 关闭窗口 → 隐藏到托盘（不退出）
            window.Closing += (_, e) =>
            {
                if (!_isExiting)
                {
                    // 拦截关闭、只隐藏：托盘图标（SetupTray）继续留着，双击/菜单可恢复窗口。
                    e.Cancel = true;
                    try { window.Hide(); } catch (Exception ex) { LogException(ex); }
                }
                // _isExiting == true 说明已经走过 App.Exit()（HTML ✕ 或托盘"退出"），
                // 这里放行让窗口真正关闭，随后 desktop.Exit → ReleaseResources() 移除托盘图标。
            };

            // 应用真正退出时释放托盘图标/音频引擎/SMTC，避免进程残留
            desktop.Exit += (_, _) => ReleaseResources();

            // SMTC 任务栏媒体控制（需在窗口打开后拿到句柄）
            if (AppServices.Settings.Settings.SmtcEnabled)
                window.Opened += (_, _) => AppServices.MediaIntegration.Initialize(window);
        }

        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>注入平台实现（SMTC/剪贴板/密码加密）。Windows 用 DPAPI 与 SMTC，其他平台走 Core 兜底。</summary>
    private static void InjectPlatformServices()
    {
        AppServices.UiDispatcher = new AvaloniaUiDispatcher();
        AppServices.Clipboard = new DesktopClipboard();
        // DPAPI 按运行时系统注入（纯 crypt32 P/Invoke，net10.0 与 net10.0-windows 均可用），
        // 保证同一 settings.json 在两种 TFM 下都能解密已保存密码。
        if (OperatingSystem.IsWindows())
            AppServices.SecretProtector = new DpapiSecretProtector();
#if WINDOWS
        AppServices.MediaIntegration = new SmtcService();
#endif
    }

    /// <summary>停音频引擎与系统媒体集成（幂等、逐步兜底）。退出时先做这步：点关闭后声音要立刻停。</summary>
    private void ReleaseAudio()
    {
        // ★ 退出路径必须「无论如何都能走完」：这里任何一步抛异常都会打断 Avalonia 的关机流程
        //   （历史上 PlaybackService.Shutdown() 的 NullReferenceException 就导致进程关不掉），
        //   所以逐步 try/catch + 幂等。
        if (_audioReleased)
            return;
        _audioReleased = true;

        try { AppServices.Playback.Shutdown(); } catch (Exception ex) { LogException(ex); }
        try { AppServices.MediaIntegration.Dispose(); } catch (Exception ex) { LogException(ex); }
    }

    /// <summary>移除托盘图标（幂等）。</summary>
    private void ReleaseTray()
    {
        if (_trayReleased)
            return;
        _trayReleased = true;

        try { _trayIcon?.Dispose(); } catch { }
        _trayIcon = null;
    }

    /// <summary>释放全部资源（desktop.Exit 与 App.Exit 两条退出路径都会走到，必须幂等且不抛）。</summary>
    private void ReleaseResources()
    {
        ReleaseTray();
        ReleaseAudio();
    }

    /// <summary>退出应用：停音频并显式触发 Shutdown（不依赖「最后窗口关闭」自动退出）。</summary>
    public void Exit()
    {
        if (_isExiting)
            return;
        _isExiting = true;

        // 先停声音（用户点关闭时不该还在放）。
        ReleaseAudio();

        // ★ 托盘图标**不在这里**移除，交给 desktop.Exit → ReleaseResources()。
        //   旧顺序是 _trayIcon.Dispose() 紧挨着 ReleaseResources()：一旦后者抛异常，
        //   _desktop.Shutdown() 就被跳过 —— 结果窗口还在、**托盘图标却已经没了**，
        //   用户既看不到窗口也无法从托盘菜单恢复或退出（19:14:43 那次的现场日志正是如此）。
        //   现在即使关机流程出问题，托盘仍在，「显示主窗口 / 退出」菜单依旧可用。
        try
        {
            _desktop?.Shutdown();
        }
        catch (Exception ex)
        {
            LogException(ex);
        }

        // ★ 兜底：走到这里说明关机请求已发出。若因为 UI 线程被卡住 / Avalonia 关机流程异常
        //   而没有真正退出，进程会残留（窗口没了、BASS 还在放歌，任务管理器里也关不干净）。
        //   给正常关机 4 秒窗口，超时后强制结束进程。
        var forceExit = new Thread(() =>
        {
            Thread.Sleep(4000);
            Environment.Exit(0);
        })
        { IsBackground = true, Name = "force-exit" };
        forceExit.Start();
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
            // Windows 用 ico，macOS/Linux 用 png（非 Windows 平台 .ico 无法解码为托盘图标）
            Icon = new WindowIcon(AssetLoader.Open(new Uri(
                $"avares://SubsonicPlayer/Assets/avalonia-logo.{(OperatingSystem.IsWindows() ? "ico" : "png")}"))),
            ToolTipText = "Subsonic 音乐播放器",
            // 单击/双击托盘图标 → 显示主窗口
            Command = new RelayCommand(() =>
            {
                window.Show();
                window.WindowState = WindowState.Normal;
                window.Activate();
            }),
            Menu = new NativeMenu
            {
                Items =
                {
                    showItem,
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
}
