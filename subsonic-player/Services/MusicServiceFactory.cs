using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

public static class MusicServiceFactory
{
    /// <summary>
    /// 根据配置创建音乐服务。Navidrome/Jellyfin 等兼容 Subsonic API；
    /// 后期接入完全不同协议的服务时在此按 Type 分支。
    /// </summary>
    public static IMusicService Create(MusicServiceConfig config)
    {
        return config.Type switch
        {
            _ => new SubsonicMusicService(config),
        };
    }
}
