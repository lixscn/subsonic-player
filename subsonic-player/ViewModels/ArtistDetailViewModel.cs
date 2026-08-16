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
        foreach (var album in albums)
        {
            var item = new AlbumItemViewModel(album);
            Albums.Add(item);
            item.LoadCover(music);
        }
    }

    [RelayCommand]
    private void Back() => NavigationService.GoBack();
}
