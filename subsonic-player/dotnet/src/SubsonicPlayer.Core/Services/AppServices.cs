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
    public static LibraryDatabase Library { get; private set; } = null!;

    /// <summary>系统媒体集成（Windows SMTC / 移动端通知栏）。由各端 App 启动时注入。</summary>
    public static IMediaIntegration MediaIntegration { get; set; } = new NoopMediaIntegration();

    /// <summary>剪贴板（各端实现注入）。</summary>
    public static IClipboardService Clipboard { get; set; } = new NoopClipboard();

    /// <summary>密码/密钥加密提供者（Windows 用 DPAPI，其他平台 AES-GCM）。由各端 App 启动时注入。</summary>
    public static ISecretProtector SecretProtector { get; set; } = new AesSecretProtector();

    /// <summary>主线程调度器（由各端 App 注入；未注入时内联执行）。用于 Core 的 UiTimer 等跨平台调度。</summary>
    public static IActionDispatcher UiDispatcher { get; set; } = new InlineActionDispatcher();

    public static string DataDirectory { get; private set; } = "";

    /// <summary>服务列表发生增删改时触发。</summary>
    public static event Action? ServicesChanged;

    /// <summary>当前服务切换后触发。</summary>
    public static event Action? CurrentServiceChanged;

    public static void Initialize()
    {
        DataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "subsonic-player");
        Directory.CreateDirectory(DataDirectory);

        Settings = new SettingsService(DataDirectory);
        Playback = new PlaybackService();

        // 初始化 SQLite 曲库缓存（建表，幂等）
        Library = new LibraryDatabase(DataDirectory);
        Library.Initialize();

        // 首次运行：若尚无服务器配置，则创建一个空模板，由用户在设置页填写连接信息。
        // 服务器地址 / 用户名 / 密码一律不硬编码，也不写入本仓库。
        if (Settings.Settings.Services.Count == 0)
        {
            Settings.Settings.Services.Add(new MusicServiceConfig
            {
                Id = "default",
                Name = "默认服务器",
                Type = MusicServiceType.Subsonic,
                LanUrl = "",
                WanUrl = "",
                Username = "",
                Password = "",
            });
            Settings.Settings.CurrentServiceId = "default";
            Settings.SaveAsync().GetAwaiter().GetResult();
        }

        var current = GetCurrentService();
        if (current is not null)
            Music = MusicServiceFactory.Create(current);

        _ = Favorites.LoadAsync();

        // 恢复上次播放位置（队列 + 当前歌曲 + 进度，不自动播放）
        Playback.RestoreLastSession();
    }

    /// <summary>当前选中的服务配置（按 CurrentServiceId 匹配，失败回退第一个）。</summary>
    public static MusicServiceConfig? GetCurrentService()
        => Settings.Settings.Services
               .FirstOrDefault(s => s.Id == Settings.Settings.CurrentServiceId)
           ?? Settings.Settings.Services.FirstOrDefault();

    /// <summary>重新加载当前音乐服务（连接配置变更后调用）。</summary>
    public static void Reconnect()
    {
        var current = GetCurrentService();
        if (current is not null)
            Music = MusicServiceFactory.Create(current);
    }

    /// <summary>新增服务并保存。</summary>
    public static void AddService(MusicServiceConfig config)
    {
        if (string.IsNullOrEmpty(config.Id))
            config.Id = Guid.NewGuid().ToString("N");

        Settings.Settings.Services.Add(config);
        if (string.IsNullOrEmpty(Settings.Settings.CurrentServiceId))
            Settings.Settings.CurrentServiceId = config.Id;

        _ = Settings.SaveAsync();
        ServicesChanged?.Invoke();
    }

    /// <summary>按 Id 更新服务并保存。</summary>
    public static void UpdateService(MusicServiceConfig config)
    {
        var existing = Settings.Settings.Services.FirstOrDefault(s => s.Id == config.Id);
        if (existing is null)
            return;

        existing.Name = config.Name;
        existing.Type = config.Type;
        existing.LanUrl = config.LanUrl;
        existing.WanUrl = config.WanUrl;
        existing.Username = config.Username;
        existing.Password = config.Password;
        existing.ApiKey = config.ApiKey;

        _ = Settings.SaveAsync();
        ServicesChanged?.Invoke();
    }

    /// <summary>删除服务（最后一个禁止删除；删除当前服务时自动切到剩余第一个并重建）。</summary>
    public static void RemoveService(string id)
    {
        var list = Settings.Settings.Services;
        if (list.Count <= 1)
            return;

        var removingCurrent = Settings.Settings.CurrentServiceId == id;
        list.RemoveAll(s => s.Id == id);

        if (removingCurrent)
        {
            Settings.Settings.CurrentServiceId = list[0].Id;
            _ = Settings.SaveAsync();
            RebuildForCurrent();
            CurrentServiceChanged?.Invoke();
        }
        else
        {
            _ = Settings.SaveAsync();
        }

        ServicesChanged?.Invoke();
    }

    /// <summary>切换当前服务：重建客户端、停止播放、重载收藏，并通知 UI 刷新。</summary>
    public static void SwitchTo(string id)
    {
        var target = Settings.Settings.Services.FirstOrDefault(s => s.Id == id);
        if (target is null || Settings.Settings.CurrentServiceId == id)
            return;

        Settings.Settings.CurrentServiceId = id;
        _ = Settings.SaveAsync();
        RebuildForCurrent();
        CurrentServiceChanged?.Invoke();
    }

    /// <summary>按当前服务重建客户端并重置播放/收藏状态。</summary>
    private static void RebuildForCurrent()
    {
        var current = GetCurrentService();
        if (current is not null)
            Music = MusicServiceFactory.Create(current);

        Playback.StopAndClear();
        Favorites.Reset();
        _ = Favorites.LoadAsync();
    }

    /// <summary>当前服务配置变更后重建客户端并通知 UI 刷新（保存服务器配置后调用）。</summary>
    public static void ReloadCurrent()
    {
        RebuildForCurrent();
        CurrentServiceChanged?.Invoke();
    }
}
