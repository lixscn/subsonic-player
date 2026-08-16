using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class SettingsViewModel : ViewModelBase
{
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

    public SettingsViewModel()
    {
        var config = AppServices.Settings.Settings.Services.FirstOrDefault();
        if (config is not null)
        {
            LanUrl = config.LanUrl;
            WanUrl = config.WanUrl;
            Username = config.Username;
            Password = config.Password;
        }

        CrossfadeSeconds = AppServices.Playback.CrossfadeSeconds;
    }

    [RelayCommand]
    private void Save()
    {
        var config = AppServices.Settings.Settings.Services.FirstOrDefault();
        if (config is not null)
        {
            config.LanUrl = LanUrl.Trim();
            config.WanUrl = WanUrl.Trim();
            config.Username = Username.Trim();
            config.Password = Password;
        }

        AppServices.Playback.CrossfadeSeconds = CrossfadeSeconds;
        _ = AppServices.Settings.SaveAsync();
        AppServices.Reconnect();
        SaveStatus = "已保存，连接配置已更新";
    }
}
