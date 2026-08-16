using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class HistoryViewModel : ViewModelBase
{
    private const int PageSize = 50;
    private const int MaxHistory = 500;

    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _recentSongs = new();

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

    public HistoryViewModel()
    {
        Load();
    }

    private void Load()
    {
        var music = AppServices.Music;
        var songs = AppServices.Library.GetRecentSongs(MaxHistory);

        var index = 1;
        foreach (var song in songs)
        {
            var item = new SongItemViewModel(song) { Index = index++ };
            _allItems.Add(item);
            if (music is not null)
                item.LoadCover(music);
        }

        if (_allItems.Count == 0)
            Status = "暂无播放记录";

        CurrentPage = 1;
        ApplyPage();
    }

    [RelayCommand]
    private void PlayAll()
    {
        if (RecentSongs.Count > 0)
            AppServices.Playback.PlayQueue(RecentSongs.Select(s => s.Song), 0);
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

    [RelayCommand]
    private void ClearHistory()
    {
        AppServices.Library.ClearHistory();
        _allItems.Clear();
        RecentSongs.Clear();
        CurrentPage = 1;
        ApplyPage();
        Status = "暂无播放记录";
    }

    private void ApplyPage()
    {
        TotalPages = Math.Max(1, (int)Math.Ceiling(_allItems.Count / (double)PageSize));
        HasPagination = TotalPages > 1;

        RecentSongs.Clear();
        var start = (CurrentPage - 1) * PageSize;
        foreach (var item in _allItems.Skip(start).Take(PageSize))
            RecentSongs.Add(item);

        PageInfo = $"第 {CurrentPage} / {TotalPages} 页（共 {_allItems.Count} 首）";
    }
}
