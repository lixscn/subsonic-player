using System.Collections.Generic;
using System.Text.Json.Serialization;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.Models;

/// <summary>
/// 音乐服务类型。当前四者均走 Subsonic 兼容协议（SubsonicClient），类型仅作标签/显示用途；
/// 后续接入 Emby / Plex / AudioStation 等不同协议服务时再扩展并按 Type 分支实现。
/// </summary>
public enum MusicServiceType
{
    Subsonic,
    Navidrome,
    Jellyfin,
    Gonic,
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

    /// <summary>类型的中文/友好显示名（供列表展示）。</summary>
    public string TypeLabel => Type switch
    {
        MusicServiceType.Navidrome => "Navidrome",
        MusicServiceType.Jellyfin => "Jellyfin",
        MusicServiceType.Gonic => "Gonic",
        _ => "Subsonic",
    };

    /// <summary>是否为当前选中使用的服务（由 UI 刷新时设置）。</summary>
    public bool IsCurrent { get; set; }
}

public class AppSettings
{
    public List<MusicServiceConfig> Services { get; set; } = new();
    public string CurrentServiceId { get; set; } = "";
}
