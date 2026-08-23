using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

public static class MusicServiceFactory
{
    /// <summary>
    /// 根据配置创建音乐服务。Subsonic/Navidrome/Jellyfin/Gonic 走 Subsonic 兼容协议；
    /// Emby / Plex 各走其原生协议客户端。
    /// </summary>
    public static IMusicService Create(MusicServiceConfig config)
    {
        return config.Type switch
        {
            MusicServiceType.Emby => new EmbyMusicService(config),
            MusicServiceType.Plex => new PlexMusicService(config),
            MusicServiceType.AudioStation => new AudioStationMusicService(config),
            _ => new SubsonicMusicService(config),
        };
    }
}
