using System;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class SongItemViewModel : ViewModelBase
{
    public Song Song { get; }

    public int Index { get; set; }

    public string IndexText => Index > 0 ? Index.ToString() : "";

    public string Title => CleanTitle();

    public string Artist => Song.Artist;
    public string DurationText => FormatDuration(Song.Duration);

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
    private IImage? _cover;

    [ObservableProperty]
    private bool _isFavorite;

    public SongItemViewModel(Song song)
    {
        Song = song;
        _ = InitFavoriteAsync();
    }

    public void LoadCover(IMusicService music) => _ = LoadCoverAsync(music);

    [RelayCommand]
    private void Play() => AppServices.Playback.PlayQueue(new[] { Song }, 0);

    [RelayCommand]
    private void PlayNext() => AppServices.Playback.PlayNext(Song);

    [RelayCommand]
    private void AddToQueue() => AppServices.Playback.AddToQueue(Song);

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
