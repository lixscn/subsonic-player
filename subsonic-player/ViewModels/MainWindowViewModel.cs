using System.Collections.Generic;
using Avalonia;
using Avalonia.Media;
using Avalonia.Styling;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class MainWindowViewModel : ViewModelBase
{
    [ObservableProperty]
    private ViewModelBase? _currentPage;

    [ObservableProperty]
    private NavItem? _selectedNav;

    [ObservableProperty]
    private IBrush? _backgroundBrush = BuildBackground("#1F3A5F");

    public IReadOnlyList<NavItem> NavItems { get; }

    public PlaybackService Playback => AppServices.Playback;

    public EqPanelViewModel EqPanel { get; } = new();

    [ObservableProperty]
    private string _themeName = "深色";

    [RelayCommand]
    private void ToggleTheme()
    {
        if (Application.Current is not { } app)
            return;

        if (app.RequestedThemeVariant == ThemeVariant.Dark)
        {
            App.ApplyTheme(false);
            ThemeName = "浅色";
        }
        else
        {
            App.ApplyTheme(true);
            ThemeName = "深色";
        }
    }

    public MainWindowViewModel()
    {
        // 根据当前服务能力动态构建导航（不支持电台则隐藏，后续服务支持时自动显示）
        var items = new List<NavItem>
        {
            new("Discover", "发现"),
            new("NowPlaying", "正在播放"),
            new("Albums", "专辑"),
            new("Artists", "艺术家"),
            new("Songs", "歌曲"),
            new("Playlists", "歌单"),
            new("Favorites", "收藏"),
        };

        if (AppServices.Music?.SupportsRadio == true)
            items.Add(new("Radio", "电台"));

        items.Add(new("Search", "搜索"));

        NavItems = items;
        _selectedNav = NavItems[0];
        _currentPage = new DiscoverViewModel();

        // 订阅详情页导航
        NavigationService.Navigated += vm => CurrentPage = vm;
    }

    [RelayCommand]
    private void OpenSettings()
    {
        CurrentPage = new SettingsViewModel();
    }

    partial void OnSelectedNavChanged(NavItem? value)
    {
        if (value is null)
            return;

        CurrentPage = value.Key switch
        {
            "NowPlaying" => new NowPlayingViewModel(),
            "Albums" => new AlbumsViewModel(),
            "Artists" => new ArtistsViewModel(),
            "Songs" => new SongsViewModel(),
            "Playlists" => new PlaylistsViewModel(),
            "Favorites" => new FavoritesViewModel(),
            "Radio" => new RadioViewModel(),
            "Search" => new SearchViewModel(),
            _ => new DiscoverViewModel(),
        };
    }

    /// <summary>
    /// 切换当前曲目时，由 PlaybackService 传入封面主色，重建背景渐变。
    /// 主色提取（后续 P1 接入）：用 ImageSharp 解码封面 → 缩到 1x1 求平均色，或采样 5x5 网格取主色。
    /// </summary>
    public void SetCoverBackground(string coverColorHex)
    {
        BackgroundBrush = BuildBackground(coverColorHex);
    }

    private static IBrush BuildBackground(string topColorHex)
    {
        return new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
            EndPoint = new RelativePoint(1, 1, RelativeUnit.Relative),
            GradientStops =
            {
                new GradientStop(Color.Parse(topColorHex), 0),
                new GradientStop(Color.Parse("#0E0E11"), 1),
            },
        };
    }
}

public record NavItem(string Key, string Label);
