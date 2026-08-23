using CommunityToolkit.Mvvm.ComponentModel;

namespace SubsonicPlayer.ViewModels;

public partial class RadioViewModel : ViewModelBase
{
    [ObservableProperty]
    private string _status = "当前服务（Gonic）不支持互联网电台";

    [ObservableProperty]
    private bool _hasStatus = true;
}
