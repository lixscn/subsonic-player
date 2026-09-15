using System;
using System.IO;

namespace SubsonicPlayer.Services;

/// <summary>
/// 轻量文件日志：写 <c>%APPDATA%\subsonic-player\&lt;name&gt;.log</c>。
///
/// <para>只为诊断「慢 / 不生效 / 到底走哪条线」这类问题存在：每次 Write 都独立开关文件，
/// 失败静默（日志绝不能影响播放）。</para>
/// </summary>
public static class AppLog
{
    /// <summary>
    /// 最近一次建流失败的原因（供 UI 显示可见提示）。
    /// 由 <see cref="AudioEngine"/> 在失败时写入；成功时清空。
    /// </summary>
    public static string LastCreateStreamError { get; set; } = "";

    /// <summary>播放链路日志（playback.log）：建流耗时、降级下载、归一化。</summary>
    public static void Playback(string message) => Write("playback.log", message);

    /// <summary>网络/线路日志（network.log）：每个服务器地址的探测结果与耗时、最终选中的线路。</summary>
    public static void Network(string message) => Write("network.log", message);

    public static void Write(string fileName, string message)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            Directory.CreateDirectory(dir);
            File.AppendAllText(
                Path.Combine(dir, fileName),
                $"[{DateTime.Now:HH:mm:ss.fff}] {message}{Environment.NewLine}");
        }
        catch
        {
            // 日志失败忽略：绝不影响播放
        }
    }
}
