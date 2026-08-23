using System;
using System.Threading.Tasks;
using Avalonia.Media;

namespace SubsonicPlayer.Services;

/// <summary>
/// 系统媒体集成抽象（桌面任务栏 / 手机通知栏的媒体控制）。
/// Windows 由 SmtcService 实现，移动端由平台通知服务实现；无实现时静默降级。
/// </summary>
public interface IMediaIntegration
{
    /// <summary>是否已成功绑定平台媒体控件。</summary>
    bool IsAvailable { get; }

    /// <summary>绑定到主窗口/主 Activity（各平台实现自行处理句柄获取）。</summary>
    void Initialize(object? window);

    /// <summary>更新曲目信息。</summary>
    void UpdateTrack(string title, string artist);

    /// <summary>更新播放状态。</summary>
    void UpdatePlaybackStatus(bool playing);

    /// <summary>更新封面缩略图。</summary>
    void UpdateCover(IImage? image);

    /// <summary>释放平台资源。</summary>
    void Dispose();
}

/// <summary>无平台媒体集成（非 Windows / 未启用时静默降级）。</summary>
public sealed class NoopMediaIntegration : IMediaIntegration
{
    public bool IsAvailable => false;
    public void Initialize(object? window) { }
    public void UpdateTrack(string title, string artist) { }
    public void UpdatePlaybackStatus(bool playing) { }
    public void UpdateCover(IImage? image) { }
    public void Dispose() { }
}

/// <summary>
/// 密码/密钥安全存储抽象（跨平台）。
/// Windows 用 DPAPI；macOS/Linux 用 AES-GCM 兜底；移动端可用 Keystore/Keychain。
/// </summary>
public interface ISecretProtector
{
    string Protect(string plain);
    string Unprotect(string stored);
}

/// <summary>剪贴板抽象（桌面/移动端实现差异大，接口化）。</summary>
public interface IClipboardService
{
    Task SetTextAsync(string text);
}

/// <summary>无剪贴板（移动端无系统剪贴板时的静默降级）。</summary>
public sealed class NoopClipboard : IClipboardService
{
    public Task SetTextAsync(string text) => Task.CompletedTask;
}