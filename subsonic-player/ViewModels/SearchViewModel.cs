using System.Collections.ObjectModel;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class SearchViewModel : ViewModelBase
{
    [ObservableProperty]
    private string _query = "";

    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _songs = new();

    [ObservableProperty]
    private ObservableCollection<AlbumItemViewModel> _albums = new();

    [ObservableProperty]
    private ObservableCollection<ArtistItemViewModel> _artists = new();

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    [ObservableProperty]
    private bool _hasResult;

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    public SearchViewModel()
    {
    }

    /// <summary>带初始关键词构造（顶栏搜索回车跳转时使用），自动执行搜索。</summary>
    public SearchViewModel(string query)
    {
        Query = query;
        _ = SearchAsync();
    }

    [RelayCommand]
    private async Task SearchAsync()
    {
        var music = AppServices.Music;
        if (music is null)
        {
            Status = "未配置音乐服务";
            return;
        }

        if (string.IsNullOrWhiteSpace(Query))
            return;

        Status = "搜索中...";
        try
        {
            if (!await music.ConnectAsync())
            {
                Status = "连接失败";
                return;
            }

            var result = await music.SearchAsync(Query, 20);

            Songs.Clear();
            Albums.Clear();
            Artists.Clear();
            foreach (var song in result.Songs)
            {
                var item = new SongItemViewModel(song) { Index = Songs.Count + 1 };
                Songs.Add(item);
                item.LoadCover(music);
            }
            foreach (var album in result.Albums)
            {
                var item = new AlbumItemViewModel(album);
                Albums.Add(item);
                item.LoadCover(music);
            }
            foreach (var artist in result.Artists)
                Artists.Add(new ArtistItemViewModel(artist));

            Status = "";
            HasResult = true;
        }
        catch
        {
            Status = "搜索失败";
        }
    }
}
