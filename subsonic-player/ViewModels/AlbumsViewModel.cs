using System.Collections.ObjectModel;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class AlbumsViewModel : ViewModelBase
{
    private const int PageSize = 50;

    [ObservableProperty]
    private ObservableCollection<AlbumItemViewModel> _albums = new();

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

    public AlbumsViewModel()
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
            var albums = await music.GetAlbumListAsync("newest", PageSize, offset);

            Albums.Clear();
            foreach (var album in albums)
            {
                var item = new AlbumItemViewModel(album);
                Albums.Add(item);
                item.LoadCover(music);
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
