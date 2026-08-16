using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class DiscoverViewModel : ViewModelBase
{
    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _randomSongs = new();

    [ObservableProperty]
    private ObservableCollection<AlbumItemViewModel> _newestAlbums = new();

    [ObservableProperty]
    private ObservableCollection<AlbumItemViewModel> _frequentAlbums = new();

    [ObservableProperty]
    private ObservableCollection<AlbumItemViewModel> _highestAlbums = new();

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    public DiscoverViewModel()
    {
        _ = LoadAsync();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            var songs = await music.GetRandomSongsAsync(10);
            var newItems = new System.Collections.Generic.List<SongItemViewModel>();
            var index = 1;
            foreach (var song in songs)
            {
                var item = new SongItemViewModel(song) { Index = index++ };
                item.LoadCover(music);
                newItems.Add(item);
            }

            RandomSongs.Clear();
            foreach (var item in newItems)
                RandomSongs.Add(item);
        }
        catch
        {
            // 换一批失败保持原样
        }
    }

    [RelayCommand]
    private void PlayAll()
    {
        if (RandomSongs.Count > 0)
            AppServices.Playback.PlayQueue(RandomSongs.Select(s => s.Song), 0);
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
        {
            Status = "未配置音乐服务";
            return;
        }

        IsLoading = true;
        Status = "连接中...";
        try
        {
            if (!await music.ConnectAsync())
            {
                Status = "连接失败";
                return;
            }

            Status = "";
            await Task.WhenAll(
                LoadAlbumsAsync(music, "newest", NewestAlbums),
                LoadAlbumsAsync(music, "frequent", FrequentAlbums),
                LoadAlbumsAsync(music, "highest", HighestAlbums),
                LoadRandomSongsAsync(music));
        }
        finally
        {
            IsLoading = false;
        }
    }

    private static async Task LoadAlbumsAsync(IMusicService music, string type, ObservableCollection<AlbumItemViewModel> target)
    {
        var albums = await music.GetAlbumListAsync(type, 10);
        foreach (var album in albums)
        {
            var item = new AlbumItemViewModel(album);
            target.Add(item);
            item.LoadCover(music);
        }
    }

    private async Task LoadRandomSongsAsync(IMusicService music)
    {
        var songs = await music.GetRandomSongsAsync(10);
        foreach (var song in songs)
        {
            var item = new SongItemViewModel(song) { Index = RandomSongs.Count + 1 };
            RandomSongs.Add(item);
            item.LoadCover(music);
        }
    }
}
