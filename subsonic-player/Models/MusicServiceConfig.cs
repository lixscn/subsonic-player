using System.Collections.Generic;

namespace SubsonicPlayer.Models;

/// <summary>音乐服务类型，后期接入其他开放音乐服务时扩展。</summary>
public enum MusicServiceType
{
    Subsonic,
    Navidrome,
    Custom,
}

public class MusicServiceConfig
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public MusicServiceType Type { get; set; } = MusicServiceType.Subsonic;
    public string LanUrl { get; set; } = "";
    public string WanUrl { get; set; } = "";
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";
}

public class AppSettings
{
    public List<MusicServiceConfig> Services { get; set; } = new();
    public string CurrentServiceId { get; set; } = "";
}
