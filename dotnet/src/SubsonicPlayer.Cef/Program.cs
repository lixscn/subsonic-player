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
                // 不显式传 GPU 参数：此前加 disable-gpu / use-angle=swiftshader 等会禁用 CEF 渲染管线，
                // 导致黑屏/闪退。改回默认（硬件 GPU），与工作正常的老版本一致。
                Array.Empty<System.Collections.Generic.KeyValuePair<string, string>>(),
                new[] { AppScheme.Build() }));
}
