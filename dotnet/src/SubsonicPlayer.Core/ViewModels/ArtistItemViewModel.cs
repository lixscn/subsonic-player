using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class ArtistItemViewModel : ViewModelBase
{
    public Artist Artist { get; }

    public string Name => Artist.Name;

    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpper();

    public ArtistItemViewModel(Artist artist) => Artist = artist;

    [RelayCommand]
    private void OpenDetail() => NavigationService.Navigate(new ArtistDetailViewModel(Artist));
}
