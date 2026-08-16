using System;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class PlaylistItemViewModel : ViewModelBase
{
    public Playlist Playlist { get; }

    public string Name => Playlist.Name;
    public string SongCountText => $"{Playlist.SongCount} 首";

    [ObservableProperty]
    private IImage? _cover;

    public event Action<PlaylistItemViewModel>? Deleted;

    public PlaylistItemViewModel(Playlist playlist) => Playlist = playlist;

    public void LoadCover(IMusicService music) => _ = LoadCoverAsync(music);

    [RelayCommand]
    private void OpenDetail() => NavigationService.Navigate(new PlaylistDetailViewModel(Playlist));

    [RelayCommand]
    private async Task DeleteAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        if (await music.DeletePlaylistAsync(Playlist.Id))
            Deleted?.Invoke(this);
    }

    private async Task LoadCoverAsync(IMusicService music)
    {
        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(Playlist.CoverArtId, 200));
    }
}
