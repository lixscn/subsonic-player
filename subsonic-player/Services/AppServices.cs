using System;
using System.IO;
using System.Linq;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>轻量服务定位器，App 启动时初始化。</summary>
public static class AppServices
{
    public static SettingsService Settings { get; private set; } = null!;
    public static IMusicService? Music { get; private set; }
    public static PlaybackService Playback { get; private set; } = null!;
    public static FavoritesService Favorites { get; } = new();
    public static string DataDirectory { get; private set; } = "";

    public static void Initialize()
    {
        DataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "subsonic-player");
        Directory.CreateDirectory(DataDirectory);

        Settings = new SettingsService(DataDirectory);
        Playback = new PlaybackService();

        // 首次运行：若尚无服务器配置，则创建一个空模板，由用户在设置页填写连接信息。
        // 服务器地址 / 用户名 / 密码一律不硬编码，也不写入本仓库。
        if (Settings.Settings.Services.Count == 0)
        {
            Settings.Settings.Services.Add(new MusicServiceConfig
            {
                Id = "default",
                Name = "我的服务器",
                Type = MusicServiceType.Subsonic,
                LanUrl = "",
                WanUrl = "",
                Username = "",
                Password = "",
            });
            Settings.Settings.CurrentServiceId = "default";
            Settings.SaveAsync().GetAwaiter().GetResult();
        }

        var current = Settings.Settings.Services
                          .FirstOrDefault(s => s.Id == Settings.Settings.CurrentServiceId)
                      ?? Settings.Settings.Services.FirstOrDefault();

        if (current is not null)
            Music = MusicServiceFactory.Create(current);

        _ = Favorites.LoadAsync();
    }

    /// <summary>重新加载当前音乐服务（连接配置变更后调用）。</summary>
    public static void Reconnect()
    {
        var current = Settings.Settings.Services
                          .FirstOrDefault(s => s.Id == Settings.Settings.CurrentServiceId)
                      ?? Settings.Settings.Services.FirstOrDefault();

        if (current is not null)
            Music = MusicServiceFactory.Create(current);
    }
}
