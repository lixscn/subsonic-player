using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>
/// 收藏状态服务（本地「真相来源」）。Gonic 的 getStarred 有 bug，故用「喜欢的音乐」歌单
/// 作为收藏歌曲的可靠读取来源。维护收藏歌曲 id 集合，供所有列表判断红心状态。
///
/// 注意：服务端写回仍走 SubsonicClient 的 star/unstar（见 SongItemViewModel.ToggleFavoriteAsync），
/// 本集合只负责 UI 状态展示与回滚。若 Gonic 的 star/unstar 与歌单不一致，需在此改为
/// 「通过 updatePlaylist 增删歌单歌曲」以彻底统一读写来源 —— 待服务端联调确认后再实施。
/// </summary>
public class FavoritesService
{
    private readonly HashSet<string> _songIds = new(StringComparer.Ordinal);
    private Task? _loadTask;

    public bool IsFavorite(string songId) => _songIds.Contains(songId);

    public void Set(string songId, bool favorite)
    {
        if (favorite)
            _songIds.Add(songId);
        else
            _songIds.Remove(songId);
    }

    public Task LoadAsync() => _loadTask ??= LoadInternalAsync();

    private async Task LoadInternalAsync()
    {
        var music = AppServices.Music;
        if (music is null)
            return;

        try
        {
            var playlists = await music.GetPlaylistsAsync();
            var favorite = playlists.FirstOrDefault(p => p.Name.Contains("喜欢的音乐"));
            if (favorite is null)
                return;

            var detail = await music.GetPlaylistAsync(favorite.Id);
            foreach (var song in detail?.Songs ?? new())
                _songIds.Add(song.Id);
        }
        catch
        {
            // 加载失败忽略，收藏状态后续更新
        }
    }
}
