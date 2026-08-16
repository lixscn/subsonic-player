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

    /// <summary>向歌单添加歌曲。</summary>
    Task<bool> AddSongsToPlaylistAsync(string playlistId, IReadOnlyList<string> songIds, CancellationToken ct = default);

    /// <summary>从歌单移除歌曲（按索引）。</summary>
    Task<bool> RemoveFromPlaylistAsync(string playlistId, IReadOnlyList<int> indexes, CancellationToken ct = default);

    Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default);

    /// <summary>收藏/取消收藏（星标）。id 可为歌曲/专辑/艺术家 id。</summary>
    Task<bool> SetFavoriteAsync(string id, bool favorite, CancellationToken ct = default);

    /// <summary>评分（1–5）。</summary>
    Task<bool> SetRatingAsync(string id, int rating, CancellationToken ct = default);

    /// <summary>获取歌词（返回 null 表示服务端不支持或无歌词）。</summary>
    Task<Lyrics?> GetLyricsAsync(string artist, string title, string? songId = null, CancellationToken ct = default);

    /// <summary>获取所有分享链接。</summary>
    Task<List<Share>> GetSharesAsync(CancellationToken ct = default);

    /// <summary>创建分享链接（id 可为歌曲/专辑/歌单 id）。</summary>
    Task<List<Share>> CreateShareAsync(string id, string? description = null, CancellationToken ct = default);

    /// <summary>删除分享链接。</summary>
    Task<bool> DeleteShareAsync(string id, CancellationToken ct = default);

    /// <summary>获取播放书签。</summary>
    Task<List<Bookmark>> GetBookmarksAsync(CancellationToken ct = default);

    /// <summary>创建播放书签（position 毫秒）。</summary>
    Task<bool> CreateBookmarkAsync(string id, long position, string? comment = null, CancellationToken ct = default);

    /// <summary>删除播放书签。</summary>
    Task<bool> DeleteBookmarkAsync(string id, CancellationToken ct = default);

    /// <summary>保存播放队列到云端。</summary>
    Task<bool> SavePlayQueueAsync(IReadOnlyList<string> songIds, string? current, long positionMs, CancellationToken ct = default);

    /// <summary>获取云端播放队列（不支持时返回 null）。</summary>
    Task<List<Song>?> GetPlayQueueAsync(CancellationToken ct = default);

    string GetCoverArtUrl(string coverArtId, int size = 300);

    string GetStreamUrl(string songId);

    /// <summary>下载原文件 URL。</summary>
    string GetDownloadUrl(string songId);

    /// <summary>是否支持互联网电台（Gonic 不支持；Navidrome 等支持时 override 为 true）。</summary>
    bool SupportsRadio => false;

    /// <summary>是否支持有声书（OpenSubsonic 扩展，Gonic 不支持）。</summary>
    bool SupportsAudiobooks => false;
}
