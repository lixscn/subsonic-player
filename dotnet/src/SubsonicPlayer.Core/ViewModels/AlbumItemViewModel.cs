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

    /// <summary>播放整张专辑（网格卡片悬浮播放按钮）。</summary>
    [RelayCommand]
    private async Task PlayAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            var detail = await music.GetAlbumAsync(Album.Id);
            if (detail is not null && detail.Songs.Count > 0)
                AppServices.Playback.PlayQueue(detail.Songs, 0);
        }
        catch
        {
            // 拉取失败忽略
        }
    }

    private async Task LoadCoverAsync(IMusicService music)
    {
        Cover = await ImageLoader.LoadAsync(music.GetCoverArtUrl(Album.CoverArtId, 200));
    }
}
