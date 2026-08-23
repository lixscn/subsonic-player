using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class ArtistsViewModel : ViewModelBase
{
    private const int PageSize = 100;

    [ObservableProperty]
    private ObservableCollection<ArtistItemViewModel> _artists = new();

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    [ObservableProperty]
    private int _currentPage = 1;

    [ObservableProperty]
    private int _totalPages = 1;

    [ObservableProperty]
    private string _pageInfo = "";

    [ObservableProperty]
    private bool _hasPagination;

    private readonly List<ArtistItemViewModel> _allArtists = new();

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    partial void OnCurrentPageChanged(int value) => ApplyPage();

    public ArtistsViewModel()
    {
        _ = LoadAsync();
    }

    [RelayCommand]
    private void NextPage()
    {
        if (CurrentPage < TotalPages)
            CurrentPage++;
    }

    [RelayCommand]
    private void PreviousPage()
    {
        if (CurrentPage > 1)
            CurrentPage--;
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
        {
            Status = "未配置音乐服务";
            return;
        }

        Status = "连接中...";
        try
        {
            if (!await music.ConnectAsync())
            {
                Status = "连接失败";
                return;
            }

            Status = "";
            var indexes = await music.GetArtistsAsync();

            _allArtists.Clear();
            foreach (var index in indexes)
                foreach (var artist in index.Artists)
                    _allArtists.Add(new ArtistItemViewModel(artist));

            TotalPages = Math.Max(1, (int)Math.Ceiling(_allArtists.Count / (double)PageSize));
            HasPagination = TotalPages > 1;
            CurrentPage = 1;
            ApplyPage();
        }
        catch
        {
            Status = "加载失败";
        }
    }

    private void ApplyPage()
    {
        Artists.Clear();
        var start = (CurrentPage - 1) * PageSize;
        foreach (var item in _allArtists.Skip(start).Take(PageSize))
            Artists.Add(item);

        PageInfo = $"第 {CurrentPage} / {TotalPages} 页（共 {_allArtists.Count} 位）";
    }
}
