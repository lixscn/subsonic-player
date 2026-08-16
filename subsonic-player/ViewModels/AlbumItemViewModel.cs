using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class AlbumItemViewModel : ViewModelBase
{
    public Album Album { get; }

    public string Name => Album.Name;
    public string Artist => Album.Artist;

    [ObservableProperty]
    private IImage? _cover;

    public AlbumItemViewModel(Album album) => Album = album;

    public void LoadCover(IMusicService music) => _ = LoadCoverAsync(music);

    [RelayCommand]
    private void OpenDetail() => NavigationService.Navigate(new AlbumDetailViewModel(Album));

    private async Task LoadCoverAsync(IMusicService music)
    {
        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(Album.CoverArtId, 200));
    }
}
