using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class ArtistDetailViewModel : ViewModelBase
{
    public Artist Artist { get; }
    public string Name => Artist.Name;

    [ObservableProperty]
    private ObservableCollection<AlbumItemViewModel> _albums = new();

    [ObservableProperty]
    private IImage? _cover;

    /// <summary>横幅卡片统计（N 张专辑 · N 首 · 总时长）。</summary>
    [ObservableProperty]
    private string _statsText = "";

    public ArtistDetailViewModel(Artist artist)
    {
        Artist = artist;
        _ = LoadAsync();
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl($"artist:{Artist.Id}", 300));

        var albums = await music.GetArtistAlbumsAsync(Artist.Id);
        var songCount = 0;
        var duration = 0;
        foreach (var album in albums)
        {
            var item = new AlbumItemViewModel(album);
            Albums.Add(item);
            item.LoadCover(music);
            songCount += album.SongCount;
            duration += album.Duration;
        }

        StatsText = $"{albums.Count} 张专辑 · {songCount} 首 · {FormatDuration(duration)}";
    }

    /// <summary>播放该艺术家的全部歌曲（逐专辑拉取后拼接播放）。</summary>
    [RelayCommand]
    private async Task PlayAllAsync()
    {
        var music = AppServices.Music;
        if (music is null || Albums.Count == 0)
            return;

        var songs = new List<Song>();
        foreach (var item in Albums)
        {
            try
            {
                var detail = await music.GetAlbumAsync(item.Album.Id);
                if (detail is not null)
                    songs.AddRange(detail.Songs);
            }
            catch
            {
                // 单张专辑失败不影响其余
            }
        }

        if (songs.Count > 0)
            AppServices.Playback.PlayQueue(songs, 0);
    }

    private static string FormatDuration(int seconds)
    {
        var t = TimeSpan.FromSeconds(seconds);
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }

    [RelayCommand]
    private void Back() => NavigationService.GoBack();
}
