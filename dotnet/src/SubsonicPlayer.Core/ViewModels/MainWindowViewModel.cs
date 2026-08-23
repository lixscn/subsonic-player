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

    /// <summary>当前曲目封面（作为模糊底图）。</summary>
    [ObservableProperty]
    private IImage? _coverBackground;

    /// <summary>是否有封面可作底图（无封面时回退到主色渐变）。</summary>
    public bool HasCoverBackground => CoverBackground is not null;

    partial void OnCoverBackgroundChanged(IImage? value) => OnPropertyChanged(nameof(HasCoverBackground));

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

    /// <summary>右侧信息面板是否展开。</summary>
    [ObservableProperty]
    private bool _isSidePanelOpen;

    [RelayCommand]
    private void ToggleSidePanel() => IsSidePanelOpen = !IsSidePanelOpen;

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
            ThemeManager.ApplyTheme(false);
            ThemeName = "浅色";
        }
        else
        {
            ThemeManager.ApplyTheme(true);
            ThemeName = "深色";
        }
    }

    public MainWindowViewModel()
    {
        // 根据当前服务能力动态构建导航（不支持电台则隐藏，后续服务支持时自动显示）
        var items = new List<NavItem>
        {
            new("Discover", "发现", "M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z"),
            new("NowPlaying", "正在播放", "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"),
            new("Albums", "专辑", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z"),
            new("Artists", "艺术家", "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"),
            new("Songs", "歌曲", "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z"),
            new("Playlists", "歌单", "M4 10h12v2H4zm0-4h12v2H4zm0 8h8v2H4zm10 0v6l5-3z"),
            new("Favorites", "收藏", "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"),
            new("History", "最近播放", "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"),
            new("Bookmarks", "书签", "M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z"),
        };

        if (AppServices.Music?.SupportsRadio == true)
            items.Add(new("Radio", "电台", "M3.24 6.15C2.51 6.43 2 7.17 2 8v12c0 1.1.89 2 2 2h16c1.11 0 2-.9 2-2V8c0-1.11-.89-2-2-2H8.3l8.26-3.34L15.88 1 3.24 6.15zM7 20c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm13-8h-2v-2h-2v2H4V8h16v4z"));

        items.Add(new("Search", "搜索", "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"));

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
            else if (e.PropertyName == nameof(PlaybackService.CurrentCover))
                CoverBackground = Playback.CurrentCover;
        };
        CoverBackground = Playback.CurrentCover;
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

    [RelayCommand]
    private void GoToNowPlaying()
    {
        SelectedNav = NavItems.First(n => n.Key == "NowPlaying");
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

public record NavItem(string Key, string Label, string Icon);
