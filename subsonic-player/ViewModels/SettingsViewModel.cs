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
    private double _crossfadeSeconds = 3.0;

    [ObservableProperty]
    private string _saveStatus = "";

    /// <summary>类型下拉选项（枚举名即产品名）。</summary>
    public IReadOnlyList<MusicServiceType> TypeOptions { get; } = new[]
    {
        MusicServiceType.Subsonic,
        MusicServiceType.Navidrome,
        MusicServiceType.Jellyfin,
        MusicServiceType.Gonic,
    };

    public bool CanRemove => Services.Count > 1;

    public SettingsViewModel()
    {
        CrossfadeSeconds = AppServices.Playback.CrossfadeSeconds;
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

        AppServices.SwitchTo(SelectedService.Id);
        ReloadServices();
        SaveStatus = $"已切换到「{SelectedService.Name}」";
    }

    [RelayCommand]
    private void Save()
    {
        if (SelectedService is null)
            return;

        SelectedService.Name = Name.Trim();
        SelectedService.Type = Type;
        SelectedService.LanUrl = LanUrl.Trim();
        SelectedService.WanUrl = WanUrl.Trim();
        SelectedService.Username = Username.Trim();
        SelectedService.Password = Password;

        AppServices.UpdateService(SelectedService);
        AppServices.Playback.CrossfadeSeconds = CrossfadeSeconds;

        // 若编辑的是当前服务，重建客户端
        if (SelectedService.Id == AppServices.Settings.Settings.CurrentServiceId)
            AppServices.Reconnect();

        ReloadServices();
        SelectedService = Services.FirstOrDefault(s => s.Id == SelectedService.Id);
        SaveStatus = "已保存";
    }
}
