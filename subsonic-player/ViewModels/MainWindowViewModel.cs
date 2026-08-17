using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Media;
using Avalonia.Styling;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
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

    /// <summary>顶栏服务切换下拉的数据源。</summary>
    public ObservableCollection<MusicServiceConfig> ServiceOptions { get; } = new();

    [ObservableProperty]
    private MusicServiceConfig? _selectedService;

    /// <summary>顶栏搜索框文本。</summary>
    [ObservableProperty]
    private string _searchText = "";

    /// <summary>当前播放歌曲是否已收藏（底部播放栏红心状态）。</summary>
    [ObservableProperty]
    private bool _isCurrentFavorite;

    [RelayCommand]
    private async Task ToggleCurrentFavoriteAsync()
    {
        var song = Playback.CurrentSong;
        var music = AppServices.Music;
        if (song is null || music is null)
            return;

        var favorite = !AppServices.Favorites.IsFavorite(song.Id);
        AppServices.Favorites.Set(song.Id, favorite);
        IsCurrentFavorite = favorite;

        try
        {
            await music.SetFavoriteAsync(song.Id, favorite);
        }
        catch
        {
            AppServices.Favorites.Set(song.Id, !favorite);
            IsCurrentFavorite = !favorite;
        }
    }

    private void RefreshFavoriteState()
        => IsCurrentFavorite = Playback.CurrentSong is { } s && AppServices.Favorites.IsFavorite(s.Id);

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
            new("History", "最近播放"),
            new("Bookmarks", "书签"),
        };

        if (AppServices.Music?.SupportsRadio == true)
            items.Add(new("Radio", "电台"));

        items.Add(new("Search", "搜索"));

        NavItems = items;
        _selectedNav = NavItems[0];
        _currentPage = new DiscoverViewModel();

        // 订阅详情页导航
        NavigationService.Navigated += vm => SetCurrentPage(vm);

        // 订阅服务列表/切换事件，刷新顶栏下拉
        AppServices.ServicesChanged += ReloadServiceOptions;
        AppServices.CurrentServiceChanged += OnCurrentServiceChanged;
        ReloadServiceOptions();

        // 订阅播放事件：切歌/收藏变化时刷新底部红心状态
        Playback.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(PlaybackService.CurrentSong))
                RefreshFavoriteState();
        };
        AppServices.Favorites.Changed += RefreshFavoriteState;

        // 预加载歌单列表（供歌曲「添加到歌单」子菜单）
        _ = SongItemViewModel.EnsurePlaylistsLoadedAsync();
    }

    /// <summary>替换当前页，并在替换前释放旧页面（如 NowPlayingViewModel 的事件订阅）。</summary>
    private void SetCurrentPage(ViewModelBase? vm)
    {
        if (CurrentPage is IDisposable old && !ReferenceEquals(old, vm))
            old.Dispose();
        CurrentPage = vm;
    }

    [RelayCommand]
    private void OpenSettings()
    {
        SetCurrentPage(new SettingsViewModel());
    }

    /// <summary>顶栏搜索：回车跳转到搜索页并自动搜索。</summary>
    [RelayCommand]
    private void Search()
    {
        if (string.IsNullOrWhiteSpace(SearchText))
            return;

        SetCurrentPage(new SearchViewModel(SearchText.Trim()));
    }

    partial void OnSelectedNavChanged(NavItem? value)
    {
        if (value is null)
            return;

        SetCurrentPage(CreatePage(value.Key));
    }

    /// <summary>切换顶栏下拉选中服务时触发。</summary>
    partial void OnSelectedServiceChanged(MusicServiceConfig? value)
    {
        if (value is null)
            return;

        if (value.Id != AppServices.Settings.Settings.CurrentServiceId)
            AppServices.SwitchTo(value.Id);
    }

    /// <summary>刷新顶栏服务下拉并选中当前服务。</summary>
    private void ReloadServiceOptions()
    {
        ServiceOptions.Clear();
        foreach (var s in AppServices.Settings.Settings.Services)
        {
            s.IsCurrent = s.Id == AppServices.Settings.Settings.CurrentServiceId;
            ServiceOptions.Add(s);
        }

        var current = AppServices.GetCurrentService();
        SelectedService = ServiceOptions.FirstOrDefault(s => s.Id == current?.Id);
    }

    /// <summary>切换服务后：刷新下拉并重建当前页（重拉新服务的数据）。</summary>
    private void OnCurrentServiceChanged()
    {
        ReloadServiceOptions();

        // 刷新当前页（若在设置页则保持设置页，避免编辑中被重置）
        if (CurrentPage is SettingsViewModel)
            return;

        var key = SelectedNav?.Key;
        if (key is not null)
            SetCurrentPage(CreatePage(key));
    }

    private static ViewModelBase CreatePage(string key) => key switch
    {
        "NowPlaying" => new NowPlayingViewModel(),
        "Albums" => new AlbumsViewModel(),
        "Artists" => new ArtistsViewModel(),
        "Songs" => new SongsViewModel(),
        "Playlists" => new PlaylistsViewModel(),
        "Favorites" => new FavoritesViewModel(),
        "History" => new HistoryViewModel(),
        "Bookmarks" => new BookmarksViewModel(),
        "Radio" => new RadioViewModel(),
        "Search" => new SearchViewModel(),
        _ => new DiscoverViewModel(),
    };

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
