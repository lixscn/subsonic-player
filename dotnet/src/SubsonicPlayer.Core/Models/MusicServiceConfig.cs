using System.Collections.Generic;
using System.Text.Json.Serialization;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.Models;

/// <summary>
/// 音乐服务类型。Subsonic / Navidrome / Jellyfin / Gonic 走 Subsonic 兼容协议（SubsonicClient）；
/// Emby / Plex 各走其原生协议（对应 *MusicService 实现）。
/// </summary>
public enum MusicServiceType
{
    Subsonic,
    Navidrome,
    Jellyfin,
    Gonic,
    Emby,
    Plex,
    AudioStation,
}

public class MusicServiceConfig
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public MusicServiceType Type { get; set; } = MusicServiceType.Subsonic;
    public string LanUrl { get; set; } = "";
    public string WanUrl { get; set; } = "";
    public string Username { get; set; } = "";

    /// <summary>密码：内存中为明文，序列化到 settings.json 时经 DPAPI 加密（见 EncryptedPasswordConverter）。</summary>
    [JsonConverter(typeof(EncryptedPasswordConverter))]
    public string Password { get; set; } = "";

    /// <summary>API Key / Token（Emby API Key、Plex Token 等），经 DPAPI 加密存储。</summary>
    [JsonConverter(typeof(EncryptedPasswordConverter))]
    public string ApiKey { get; set; } = "";

    /// <summary>类型的中文/友好显示名（供列表展示）。</summary>
    public string TypeLabel => Type switch
    {
        MusicServiceType.Navidrome => "Navidrome",
        MusicServiceType.Jellyfin => "Jellyfin",
        MusicServiceType.Gonic => "Gonic",
        MusicServiceType.Emby => "Emby",
        MusicServiceType.Plex => "Plex",
        MusicServiceType.AudioStation => "AudioStation",
        _ => "Subsonic",
    };

    /// <summary>是否为当前选中使用的服务（由 UI 刷新时设置）。</summary>
    public bool IsCurrent { get; set; }
}

/// <summary>流式网络质量（映射到 stream 端点的 maxBitRate / format）。</summary>
public enum NetworkQuality
{
    Original,
    High,
    Medium,
    Low,
}

public class AppSettings
{
    public List<MusicServiceConfig> Services { get; set; } = new();
    public string CurrentServiceId { get; set; } = "";

    /// <summary>流式网络质量。</summary>
    public NetworkQuality NetworkQuality { get; set; } = NetworkQuality.Original;

    /// <summary>下载目录（为空时回退 %USERPROFILE%\Music）。</summary>
    public string DownloadDir { get; set; } = "";

    /// <summary>SMTC（任务栏媒体控制）开关。</summary>
    public bool SmtcEnabled { get; set; } = true;
}
