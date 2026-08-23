using Avalonia;
using System;
using System.IO;
using Xilium.CefGlue;
using Xilium.CefGlue.Common;
using Xilium.CefGlue.Common.Shared;
using SubsonicPlayer.Services;

namespace SubsonicPlayer;

sealed class Program
{
    // Initialization code. Don't use any Avalonia, third-party APIs or any
    // SynchronizationContext-reliant code before AppMain is called: things aren't initialized
    // yet and stuff might break.
    [STAThread]
    public static void Main(string[] args) => BuildAvaloniaApp()
        .StartWithClassicDesktopLifetime(args);

    // Avalonia configuration, don't remove; also used by visual designer.
    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
#if DEBUG
            .WithDeveloperTools()
#endif
            .WithInterFont()
            .LogToTrace()
            .AfterSetup(_ => CefRuntimeLoader.Initialize(
                new CefSettings
                {
                    RootCachePath = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                        "subsonic-player", "cef-cache"),
                    LogFile = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                        "subsonic-player", "cef.log"),
                    LogSeverity = CefLogSeverity.Info,
                    WindowlessRenderingEnabled = true,
                },
                Array.Empty<System.Collections.Generic.KeyValuePair<string, string>>(),
                new[] { AppScheme.Build(ResolveWebRoot()) }));

    /// <summary>定位 WebAssets 目录（开发/发布两种形态）。</summary>
    private static string ResolveWebRoot()
    {
        var probe = Path.Combine(AppContext.BaseDirectory, "WebAssets");
        if (Directory.Exists(probe))
            return probe;

        // 开发时 WebAssets 在项目目录；发布时作为 Content 拷到输出目录
        var dev = Path.Combine(Environment.CurrentDirectory, "src", "SubsonicPlayer.Cef", "WebAssets");
        return Directory.Exists(dev) ? dev : AppContext.BaseDirectory;
    }
}
