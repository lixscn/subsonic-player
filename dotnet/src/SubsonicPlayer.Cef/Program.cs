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
                try
                {
                    // CEF(Chromium) 原生永远不打包进 exe，全部外置为目录里的文件：
                    //   - resources.pak / icudtl.dat / snapshot_blob.bin / chrome_*.pak / libcef.dll 在 exe 旁（ResourcesDirPath）
                    //   - locales\*.pak 在 exe 旁目录（LocalesDirPath，可能是根 locales 或 runtimes\*\native\locales）
                    //   - CefGlueBrowserProcess 子进程
                    // 单文件(托管)模式下自动探测不可靠，这里显式告诉 CEF 去外置目录找。
                    var baseDir = AppContext.BaseDirectory;
                    var localesDir = ResolveLocalesDir(baseDir);
                    LogInit($"--- CEF init start --- baseDir={baseDir} localesDir={localesDir}");
                    LogInit($"libcef exists={File.Exists(Path.Combine(baseDir, "libcef.dll"))} resources={File.Exists(Path.Combine(baseDir, "resources.pak"))} subprocess={File.Exists(Path.Combine(baseDir, "CefGlueBrowserProcess", "Xilium.CefGlue.BrowserProcess.exe"))}");

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
                        // 不传任何 GPU 参数（之前加 swiftshader 被确认方向不对；非 GPU 问题）。
                        Array.Empty<System.Collections.Generic.KeyValuePair<string, string>>(),
                        new[] { AppScheme.Build() });

                    LogInit("--- CEF init OK ---");
                }
                catch (Exception ex)
                {
                    LogInit("--- CEF init FAILED --- " + ex);
                    throw;
                }
            });

    /// <summary>解析 CEF locales 目录：优先 &lt;base&gt;\locales；否则遍历 &lt;base&gt;\runtimes\*\native\locales。</summary>
    private static string ResolveLocalesDir(string baseDir)
    {
        var direct = Path.Combine(baseDir, "locales");
        if (Directory.Exists(direct))
            return direct;

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
        return direct; // 找不到就退回根目录 locales（路径非空，CEF 会自行提示）
    }

    private static void LogInit(string msg)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            Directory.CreateDirectory(dir);
            File.AppendAllText(
                Path.Combine(dir, "cef-init.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { }
    }
}
