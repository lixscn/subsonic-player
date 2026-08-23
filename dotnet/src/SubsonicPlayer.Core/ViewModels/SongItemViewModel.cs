using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class SongItemViewModel : ViewModelBase
{
    public Song Song { get; }

    [ObservableProperty]
    private int _index;

    public string IndexText => Index > 0 ? Index.ToString() : "";

    partial void OnIndexChanged(int value) => OnPropertyChanged(nameof(IndexText));

    public string Title => CleanTitle();

    public string Artist => Song.Artist;
    public string Album => Song.Album;
    public string DurationText => FormatDuration(Song.Duration);

    /// <summary>从歌单移除的回调（由 PlaylistDetailViewModel 注入，非歌单场景为 null）。</summary>
    public Action<SongItemViewModel>? RemoveFromPlaylist { get; set; }

    /// <summary>清理 Gonic 不规范的 title（去重「- 艺术家」前后缀，artist 单独显示）。</summary>
    private string CleanTitle()
    {
        var t = Song.Title ?? "";
        var a = Song.Artist ?? "";
        if (a.Length == 0)
            return t;

        var suffix = " - " + a;
        if (t.EndsWith(suffix, StringComparison.Ordinal))
            return t[..^suffix.Length].Trim();

        var prefix = a + " - ";
        if (t.StartsWith(prefix, StringComparison.Ordinal))
            return t[prefix.Length..].Trim();

        return t;
    }

    [ObservableProperty]
    private byte[]? _cover;

    [ObservableProperty]
    private bool _isFavorite;

    /// <summary>是否为当前播放项（播放队列中用，用于高亮）。</summary>
    [ObservableProperty]
    private bool _isCurrent;

    /// <summary>是否为拖拽插入位置指示（播放队列拖拽时显示顶部横线）。</summary>
    [ObservableProperty]
    private bool _isDropIndicator;

    /// <summary>从队列指定索引播放的回调（由 PlaybackService 注入队列项；列表页为 null 走单曲播放）。</summary>
    public Action<int>? PlayFromQueue { get; set; }

    public SongItemViewModel(Song song)
    {
        Song = song;
        _ = InitFavoriteAsync();
    }

    public void LoadCover(IMusicService music) => _ = LoadCoverAsync(music);

    [RelayCommand]
    private void Play()
    {
        if (PlayFromQueue is not null)
        {
            PlayFromQueue(Index - 1);
            return;
        }
        AppServices.Playback.PlayQueue(new[] { Song }, 0);
    }

    [RelayCommand]
    private void PlayNext() => AppServices.Playback.PlayNext(Song);

    [RelayCommand]
    private void AddToQueue() => AppServices.Playback.AddToQueue(Song);

    [RelayCommand]
    private Task RemoveFromPlaylistAsync()
    {
        RemoveFromPlaylist?.Invoke(this);
        return Task.CompletedTask;
    }

    [RelayCommand]
    private async Task ToggleFavoriteAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        IsFavorite = !IsFavorite;
        AppServices.Favorites.Set(Song.Id, IsFavorite);
        try
        {
            await music.SetFavoriteAsync(Song.Id, IsFavorite);
        }
        catch
        {
            IsFavorite = !IsFavorite;
            AppServices.Favorites.Set(Song.Id, IsFavorite);
        }
    }

    [RelayCommand]
    private async Task DownloadAsync()
    {
        var path = await DownloadService.DownloadAsync(Song);
        DownloadStatus = path is null ? "下载失败" : "已下载";
    }

    /// <summary>最近一次下载/评分的结果提示。</summary>
    [ObservableProperty]
    private string _downloadStatus = "";

    [RelayCommand]
    private async Task RateAsync(string? ratingText)
    {
        if (!int.TryParse(ratingText, out var rating))
            return;

        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            await music.SetRatingAsync(Song.Id, rating);
            DownloadStatus = $"已评分 {rating} 星";
        }
        catch
        {
            DownloadStatus = "评分失败";
        }
    }

    [RelayCommand]
    private async Task ShareAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            var shares = await music.CreateShareAsync(Song.Id);
            var url = shares.FirstOrDefault()?.Url;
            if (url is null)
            {
                DownloadStatus = "分享失败";
                return;
            }

            await AppServices.Clipboard.SetTextAsync(url);

            DownloadStatus = "分享链接已复制";
        }
        catch
        {
            DownloadStatus = "分享失败";
        }
    }

    // ---- 添加到歌单 ----

    /// <summary>「添加到歌单」子菜单的歌单列表（所有歌曲项共享）。</summary>
    public ObservableCollection<Playlist> Playlists => SharedPlaylists;

    public static ObservableCollection<Playlist> SharedPlaylists { get; } = new();
    private static bool _playlistsLoaded;

    /// <summary>懒加载歌单列表（供「添加到歌单」子菜单）。</summary>
    public static async Task EnsurePlaylistsLoadedAsync()
    {
        if (_playlistsLoaded)
            return;
        _playlistsLoaded = true;

        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            var list = await music.GetPlaylistsAsync();
            SharedPlaylists.Clear();
            foreach (var p in list.Where(p => !p.Name.Contains("喜欢的音乐")))
                SharedPlaylists.Add(p);
        }
        catch
        {
            // 加载失败保持空列表
        }
    }

    [RelayCommand]
    private async Task AddToPlaylistAsync(string? playlistId)
    {
        if (string.IsNullOrEmpty(playlistId))
            return;

        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            await music.AddSongsToPlaylistAsync(playlistId, new[] { Song.Id });
            DownloadStatus = "已添加到歌单";
        }
        catch
        {
            DownloadStatus = "添加失败";
        }
    }

    private async Task InitFavoriteAsync()
    {
        await AppServices.Favorites.LoadAsync();
        IsFavorite = AppServices.Favorites.IsFavorite(Song.Id);
    }

    private async Task LoadCoverAsync(IMusicService music)
    {
        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(Song.CoverArtId, 200));
    }

    private static string FormatDuration(int seconds)
    {
        var t = TimeSpan.FromSeconds(seconds);
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }
}
