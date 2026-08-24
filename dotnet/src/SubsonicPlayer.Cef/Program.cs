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
                new[] { AppScheme.Build() }));
}
