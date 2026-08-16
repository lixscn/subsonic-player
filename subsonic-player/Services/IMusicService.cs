using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 统一音乐服务抽象。后期接入其他开放音乐服务（Navidrome、Jellyfin 等）时实现此接口即可，
/// 上层 ViewModel 只依赖本接口，不感知具体实现。
/// </summary>
public interface IMusicService
{
    string ServiceName { get; }

    Task<bool> ConnectAsync();

    Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default);

    Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default);

    Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default);

    Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default);

    Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default);

    Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default);

    Task<List<Playlist>> GetPlaylistsAsync(CancellationToken ct = default);

    Task<Playlist?> GetPlaylistAsync(string id, CancellationToken ct = default);

    Task<Playlist?> CreatePlaylistAsync(string name, CancellationToken ct = default);

    Task<bool> DeletePlaylistAsync(string id, CancellationToken ct = default);

    Task<bool> UpdatePlaylistAsync(string id, string name, CancellationToken ct = default);

    Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default);

    /// <summary>收藏/取消收藏（星标）。id 可为歌曲/专辑/艺术家 id。</summary>
    Task<bool> SetFavoriteAsync(string id, bool favorite, CancellationToken ct = default);

    string GetCoverArtUrl(string coverArtId, int size = 300);

    string GetStreamUrl(string songId);

    /// <summary>是否支持互联网电台（Gonic 不支持；Navidrome 等支持时 override 为 true）。</summary>
    bool SupportsRadio => false;

    /// <summary>是否支持有声书（OpenSubsonic 扩展，Gonic 不支持）。</summary>
    bool SupportsAudiobooks => false;
}
