using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class SettingsViewModel : ViewModelBase
{
    /// <summary>服务列表（供 ListBox 展示）。</summary>
    public ObservableCollection<MusicServiceConfig> Services { get; } = new();

    [ObservableProperty]
    private MusicServiceConfig? _selectedService;

    // ---- 编辑字段（绑定到右侧表单，保存时写回选中项）----

    [ObservableProperty]
    private string _name = "";

    [ObservableProperty]
    private MusicServiceType _type = MusicServiceType.Subsonic;

    [ObservableProperty]
    private string _lanUrl = "";

    [ObservableProperty]
    private string _wanUrl = "";

    [ObservableProperty]
    private string _username = "";

    [ObservableProperty]
    private string _password = "";

    [ObservableProperty]
    private string _apiKey = "";

    /// <summary>密码框掩码字符（null 表示显示明文）。</summary>
    [ObservableProperty]
    private char? _passwordChar = '*';

    [ObservableProperty]
    private double _crossfadeSeconds = 3.0;

    [ObservableProperty]
    private string _saveStatus = "";

    /// <summary>网络质量下拉选项。</summary>
    public record QualityOption(NetworkQuality Value, string Label);

    [ObservableProperty]
    private QualityOption _selectedQuality;

    [ObservableProperty]
    private string _downloadDir = "";

    [ObservableProperty]
    private bool _smtcEnabled = true;

    public IReadOnlyList<QualityOption> QualityOptions { get; } = new[]
    {
        new QualityOption(NetworkQuality.Original, "原始"),
        new QualityOption(NetworkQuality.High, "高 (320kbps MP3)"),
        new QualityOption(NetworkQuality.Medium, "中 (192kbps MP3)"),
        new QualityOption(NetworkQuality.Low, "低 (96kbps MP3)"),
    };

    /// <summary>类型下拉选项（枚举名即产品名）。</summary>
    public IReadOnlyList<MusicServiceType> TypeOptions { get; } = new[]
    {
        MusicServiceType.Subsonic,
        MusicServiceType.Navidrome,
        MusicServiceType.Jellyfin,
        MusicServiceType.Gonic,
        MusicServiceType.Emby,
        MusicServiceType.Plex,
    };

    public bool CanRemove => Services.Count > 1;

    /// <summary>密码显示切换按钮文字。</summary>
    public string PasswordVisibilityText => PasswordChar is null ? "隐藏" : "显示";

    public SettingsViewModel()
    {
        CrossfadeSeconds = AppServices.Playback.CrossfadeSeconds;
        SelectedQuality = QualityOptions.First(q => q.Value == AppServices.Settings.Settings.NetworkQuality);
        DownloadDir = AppServices.Settings.Settings.DownloadDir;
        SmtcEnabled = AppServices.Settings.Settings.SmtcEnabled;
        ReloadServices();

        var current = AppServices.GetCurrentService();
        SelectedService = Services.FirstOrDefault(s => s.Id == current?.Id) ?? Services.FirstOrDefault();
    }

    private void ReloadServices()
    {
        Services.Clear();
        foreach (var s in AppServices.Settings.Settings.Services)
        {
            s.IsCurrent = s.Id == AppServices.Settings.Settings.CurrentServiceId;
            Services.Add(s);
        }
        OnPropertyChanged(nameof(CanRemove));
    }

    partial void OnSelectedServiceChanged(MusicServiceConfig? value)
    {
        if (value is null)
            return;

        Name = value.Name;
        Type = value.Type;
        LanUrl = value.LanUrl;
        WanUrl = value.WanUrl;
        Username = value.Username;
        Password = value.Password;
        ApiKey = value.ApiKey;
    }

    [RelayCommand]
    private void AddService()
    {
        var config = new MusicServiceConfig
        {
            Id = Guid.NewGuid().ToString("N"),
            Name = "新服务器",
            Type = MusicServiceType.Subsonic,
        };
        AppServices.AddService(config);
        ReloadServices();
        SelectedService = Services.FirstOrDefault(s => s.Id == config.Id);
        SaveStatus = "已添加，请填写连接信息后保存";
    }

    [RelayCommand]
    private void RemoveService()
    {
        if (SelectedService is null || Services.Count <= 1)
            return;

        var removedId = SelectedService.Id;
        AppServices.RemoveService(removedId);
        ReloadServices();
        SelectedService = Services.FirstOrDefault();
        SaveStatus = "已删除";
    }

    [RelayCommand]
    private void SetCurrent()
    {
        if (SelectedService is null)
            return;

        var name = SelectedService.Name;
        AppServices.SwitchTo(SelectedService.Id);
        ReloadServices();
        SaveStatus = $"已切换到「{name}」";
    }

    [RelayCommand]
    private void TogglePasswordVisibility()
    {
        PasswordChar = PasswordChar is null ? '*' : null;
        OnPropertyChanged(nameof(PasswordVisibilityText));
    }

    [RelayCommand]
    private void Save()
    {
        if (SelectedService is null)
            return;

        // 先保存 id，避免 ReloadServices 触发 SelectedItem 清空把 SelectedService 置 null 后空引用
        var serviceId = SelectedService.Id;

        SelectedService.Name = Name.Trim();
        SelectedService.Type = Type;
        SelectedService.LanUrl = LanUrl.Trim();
        SelectedService.WanUrl = WanUrl.Trim();
        SelectedService.Username = Username.Trim();
        SelectedService.Password = Password;
        SelectedService.ApiKey = ApiKey.Trim();

        AppServices.UpdateService(SelectedService);
        AppServices.Playback.CrossfadeSeconds = CrossfadeSeconds;
        AppServices.Settings.Settings.NetworkQuality = SelectedQuality.Value;
        AppServices.Settings.Settings.DownloadDir = DownloadDir.Trim();
        AppServices.Settings.Settings.SmtcEnabled = SmtcEnabled;

        // 若编辑的是当前服务，重建客户端
        if (serviceId == AppServices.Settings.Settings.CurrentServiceId)
            AppServices.Reconnect();

        ReloadServices();
        SelectedService = Services.FirstOrDefault(s => s.Id == serviceId);
        SaveStatus = "已保存";
    }
}
