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
            .AfterSetup(_ =>
            {
                // CefGlue 120 在 .NET 10 下依赖目录探测（GetSubProcessPaths / locale 定位）取不到
                // AppContext.BaseDirectory，导致子进程路径为 null、locales 找不到 → 黑屏/崩溃。
                // 显式指定资源目录、locales 目录、子进程可执行，绕过自动探测。
                // 注意：Windows 发布时 CEF 的 locales 可能在 runtimes\win-x64\native\locales\，而非根目录。
                var baseDir = AppContext.BaseDirectory;
                var localesDir = ResolveLocalesDir(baseDir);
                CefRuntimeLoader.Initialize(
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
                        ResourcesDirPath = baseDir,
                        LocalesDirPath = localesDir,
                        BrowserSubprocessPath = Path.Combine(
                            baseDir, "CefGlueBrowserProcess", "Xilium.CefGlue.BrowserProcess.exe"),
                    },
                    // 无 GPU / 远程 / 虚拟机环境下 CEF 的 GPU 进程会崩溃（GPU process isn't usable），
                    // 导致黑屏 + 自退。禁用 GPU，改用软件渲染（OSR 离屏下可靠）。
                    new System.Collections.Generic.KeyValuePair<string, string>[]
                    {
                        new("disable-gpu", ""),
                        new("disable-gpu-compositing", ""),
                        new("disable-gpu-vsync", ""),
                        new("use-gl", "swiftshader"),
                    },
                    new[] { AppScheme.Build(ResolveWebRoot()) });
            });

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

    /// <summary>
    /// 解析 CEF locales 目录：优先 &lt;base&gt;\locales；否则遍历 &lt;base&gt;\runtimes\*\native\locales。
    /// 不同打包形态（根目录 in-place / RID 子目录）locales 位置不同，需探测。
    /// </summary>
    private static string ResolveLocalesDir(string baseDir)
    {
        var direct = Path.Combine(baseDir, "locales");
        if (Directory.Exists(direct))
            return direct;

        // 遍历 runtimes\*\native\locales
        var runtimes = Path.Combine(baseDir, "runtimes");
        if (Directory.Exists(runtimes))
        {
            foreach (var rid in Directory.GetDirectories(runtimes))
            {
                var native = Path.Combine(rid, "native", "locales");
                if (Directory.Exists(native))
                    return native;
            }
        }
        return direct; // 找不到就退回根目录 locales（至少路径非空，CEF 会自行提示）
    }
}
