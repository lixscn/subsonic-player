using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class FavoritesViewModel : ViewModelBase
{
    [ObservableProperty]
    private ObservableCollection<SongItemViewModel> _favoriteSongs = new();

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    public FavoritesViewModel()
    {
        _ = LoadAsync();
    }

    [RelayCommand]
    private void PlayAll()
    {
        if (FavoriteSongs.Count > 0)
            AppServices.Playback.PlayQueue(FavoriteSongs.Select(s => s.Song), 0);
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
        {
            Status = "未配置音乐服务";
            return;
        }

        Status = "连接中...";
        try
        {
            if (!await music.ConnectAsync())
            {
                Status = "连接失败";
                return;
            }

            // Gonic 的 getStarred 有 bug，「喜欢的音乐」歌单是收藏歌曲的可靠来源
            var playlists = await music.GetPlaylistsAsync();
            var favorite = playlists.FirstOrDefault(p => p.Name.Contains("喜欢的音乐"));
            if (favorite is null)
            {
                Status = "未找到「喜欢的音乐」歌单";
                return;
            }

            var detail = await music.GetPlaylistAsync(favorite.Id);
            foreach (var song in detail?.Songs ?? new())
            {
                var item = new SongItemViewModel(song) { Index = FavoriteSongs.Count + 1, IsFavorite = true };
                FavoriteSongs.Add(item);
                item.LoadCover(music);
            }

            Status = "";
        }
        catch
        {
            Status = "加载失败";
        }
    }
}
