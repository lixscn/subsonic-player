using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class AlbumDetailViewModel : ViewModelBase
{
    public Album Album { get; }
    public string Name => Album.Name;
    public string Artist => Album.Artist;

    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _songs = new();

    [ObservableProperty]
    private IImage? _cover;

    [ObservableProperty]
    private string _songCountText = "";

    /// <summary>横幅卡片副信息（年份 · N 首）。</summary>
    [ObservableProperty]
    private string _metaText = "";

    /// <summary>专辑收藏状态（星标）。</summary>
    [ObservableProperty]
    private bool _isFavorite;

    public AlbumDetailViewModel(Album album)
    {
        Album = album;
        IsFavorite = AppServices.Favorites.IsFavorite(album.Id);
        _ = LoadAsync();
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(Album.CoverArtId, 300));

        var detail = await music.GetAlbumAsync(Album.Id);
        if (detail is null)
            return;

        SongCountText = $"{detail.Songs.Count} 首";
        MetaText = (detail.Year > 0 ? $"{detail.Year} · " : "") + SongCountText;
        var index = 1;
        foreach (var song in detail.Songs)
        {
            var item = new SongItemViewModel(song) { Index = index++ };
            Songs.Add(item);
            item.LoadCover(music);
        }
    }

    [RelayCommand]
    private void PlayAll()
    {
        if (Songs.Count > 0)
            AppServices.Playback.PlayQueue(Songs.Select(s => s.Song), 0);
    }

    [RelayCommand]
    private void ShufflePlay()
    {
        if (Songs.Count > 0)
        {
            var shuffled = Songs.Select(s => s.Song).OrderBy(_ => Random.Shared.Next()).ToList();
            AppServices.Playback.PlayQueue(shuffled, 0);
        }
    }

    [RelayCommand]
    private async Task ToggleFavoriteAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        var favorite = !IsFavorite;
        IsFavorite = favorite;
        try
        {
            await music.SetFavoriteAsync(Album.Id, favorite);
            // 同步更新本地歌曲收藏状态
            foreach (var item in Songs)
                AppServices.Favorites.Set(item.Song.Id, favorite);
        }
        catch
        {
            IsFavorite = !favorite;
        }
    }

    [RelayCommand]
    private void Back() => NavigationService.GoBack();
}
