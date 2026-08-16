using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class PlaylistsViewModel : ViewModelBase
{
    [ObservableProperty]
    private ObservableCollection<PlaylistItemViewModel> _playlists = new();

    [ObservableProperty]
    private string _newPlaylistName = "";

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    public PlaylistsViewModel()
    {
        _ = LoadAsync();
    }

    [RelayCommand]
    private async Task CreatePlaylistAsync()
    {
        var music = AppServices.Music;
        if (music is null || string.IsNullOrWhiteSpace(NewPlaylistName))
            return;

        var playlist = await music.CreatePlaylistAsync(NewPlaylistName.Trim());
        if (playlist is null)
            return;

        AddItem(music, playlist);
        NewPlaylistName = "";
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

            Status = "";
            var playlists = await music.GetPlaylistsAsync();
            // 过滤掉系统自动管理的「喜欢的音乐」歌单（收藏页单独展示）
            foreach (var playlist in playlists.Where(p => !p.Name.Contains("喜欢的音乐")))
                AddItem(music, playlist);
        }
        catch
        {
            Status = "加载失败";
        }
    }

    private void AddItem(IMusicService music, Models.Playlist playlist)
    {
        var item = new PlaylistItemViewModel(playlist);
        item.Deleted += i => Playlists.Remove(i);
        Playlists.Add(item);
        item.LoadCover(music);
    }
}
