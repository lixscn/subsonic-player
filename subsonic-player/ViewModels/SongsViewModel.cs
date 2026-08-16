using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class SongsViewModel : ViewModelBase
{
    private const int PageSize = 20;

    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _songs = new();

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    [ObservableProperty]
    private int _currentPage = 1;

    [ObservableProperty]
    private string _pageInfo = "";

    [ObservableProperty]
    private bool _hasPreviousPage;

    [ObservableProperty]
    private bool _hasNextPage;

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private string _pageInput = "";

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    public SongsViewModel()
    {
        _ = LoadPageAsync(1);
    }

    [RelayCommand]
    private async Task NextPageAsync()
    {
        if (HasNextPage && !IsLoading)
            await LoadPageAsync(CurrentPage + 1);
    }

    [RelayCommand]
    private async Task PreviousPageAsync()
    {
        if (HasPreviousPage && !IsLoading)
            await LoadPageAsync(CurrentPage - 1);
    }

    [RelayCommand]
    private async Task JumpPageAsync()
    {
        if (int.TryParse(PageInput, out var page) && page >= 1 && !IsLoading)
        {
            PageInput = "";
            await LoadPageAsync(page);
        }
    }

    [RelayCommand]
    private void PlayAll()
    {
        if (Songs.Count > 0)
            AppServices.Playback.PlayQueue(Songs.Select(s => s.Song), 0);
    }

    private async Task LoadPageAsync(int page)
    {
        var music = AppServices.Music;
        if (music is null)
        {
            Status = "未配置音乐服务";
            return;
        }

        IsLoading = true;
        try
        {
            if (!await music.ConnectAsync())
            {
                Status = "连接失败";
                return;
            }

            Status = "";
            var offset = (page - 1) * PageSize;
            // Gonic 无「全部歌曲」端点，分页拉最新专辑并展开其歌曲
            var albums = await music.GetAlbumListAsync("newest", PageSize, offset);

            Songs.Clear();
            foreach (var album in albums)
            {
                var detail = await music.GetAlbumAsync(album.Id);
                if (detail is null)
                    continue;

                foreach (var song in detail.Songs)
                {
                    var item = new SongItemViewModel(song) { Index = Songs.Count + 1 };
                    Songs.Add(item);
                    item.LoadCover(music);
                }
            }

            CurrentPage = page;
            HasNextPage = albums.Count >= PageSize;
            HasPreviousPage = page > 1;
            PageInfo = $"第 {page} 页";
        }
        catch
        {
            Status = "加载失败";
        }
        finally
        {
            IsLoading = false;
        }
    }
}
