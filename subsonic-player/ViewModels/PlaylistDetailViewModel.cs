using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class PlaylistDetailViewModel : ViewModelBase
{
    public Playlist Playlist { get; }
    public string Name => Playlist.Name;

    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _songs = new();

    [ObservableProperty]
    private IImage? _cover;

    [ObservableProperty]
    private string _songCountText = "";

    public PlaylistDetailViewModel(Playlist playlist)
    {
        Playlist = playlist;
        _ = LoadAsync();
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(Playlist.CoverArtId, 300));

        var detail = await music.GetPlaylistAsync(Playlist.Id);
        if (detail is null)
            return;

        SongCountText = $"{detail.Songs.Count} 首";
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
    private void Back() => NavigationService.GoBack();
}
