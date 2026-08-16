using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class FavoritesViewModel : ViewModelBase
{
    private const int PageSize = 50;

    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _favoriteSongs = new();

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

    private readonly List<SongItemViewModel> _allItems = new();

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    partial void OnCurrentPageChanged(int value) => ApplyPage();

    public FavoritesViewModel()
    {
        _ = LoadAsync();
    }

    [RelayCommand]
    private void PlayAll()
    {
        if (FavoriteSongs.Count > 0)
            AppServices.Playback.PlayQueue(FavoriteSongs.Select(s => s.Song), 0);
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

            // Gonic 的 getStarred 有 bug，「喜欢的音乐」歌单是收藏歌曲的可靠来源
            var playlists = await music.GetPlaylistsAsync();
            var favorite = playlists.FirstOrDefault(p => p.Name.Contains("喜欢的音乐"));
            if (favorite is null)
            {
                Status = "未找到「喜欢的音乐」歌单";
                return;
            }

            var detail = await music.GetPlaylistAsync(favorite.Id);
            var index = 1;
            foreach (var song in detail?.Songs ?? new())
            {
                var item = new SongItemViewModel(song) { Index = index++, IsFavorite = true };
                _allItems.Add(item);
                item.LoadCover(music);
            }

            Status = "";
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
        TotalPages = Math.Max(1, (int)Math.Ceiling(_allItems.Count / (double)PageSize));
        HasPagination = TotalPages > 1;

        FavoriteSongs.Clear();
        var start = (CurrentPage - 1) * PageSize;
        foreach (var item in _allItems.Skip(start).Take(PageSize))
            FavoriteSongs.Add(item);

        PageInfo = $"第 {CurrentPage} / {TotalPages} 页（共 {_allItems.Count} 首）";
    }
}
