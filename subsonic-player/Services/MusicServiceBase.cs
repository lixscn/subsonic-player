using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 非 Subsonic 协议服务的公共基类：核心浏览/播放方法抽象，其余（歌单/收藏/评分/歌词/书签/分享等）
/// 提供默认「不支持」实现，各协议按能力覆盖。
/// </summary>
public abstract class MusicServiceBase : IMusicService
{
    public abstract string ServiceName { get; }

    public abstract Task<bool> ConnectAsync();

    public abstract Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default);

    public abstract Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default);

    public abstract Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default);

    public abstract Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default);

    public abstract Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default);

    public abstract Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default);

    public abstract string GetCoverArtUrl(string coverArtId, int size = 300);

    public abstract string GetStreamUrl(string songId);

    public abstract string GetDownloadUrl(string songId);

    // ---- 以下为可选能力，默认不支持 ----

    public virtual Task<List<Playlist>> GetPlaylistsAsync(CancellationToken ct = default)
        => Task.FromResult(new List<Playlist>());

    public virtual Task<Playlist?> GetPlaylistAsync(string id, CancellationToken ct = default)
        => Task.FromResult<Playlist?>(null);

    public virtual Task<Playlist?> CreatePlaylistAsync(string name, CancellationToken ct = default)
        => Task.FromResult<Playlist?>(null);

    public virtual Task<bool> DeletePlaylistAsync(string id, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> UpdatePlaylistAsync(string id, string name, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> AddSongsToPlaylistAsync(string playlistId, IReadOnlyList<string> songIds, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> RemoveFromPlaylistAsync(string playlistId, IReadOnlyList<int> indexes, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> SetFavoriteAsync(string id, bool favorite, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> SetRatingAsync(string id, int rating, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<Lyrics?> GetLyricsAsync(string artist, string title, string? songId = null, CancellationToken ct = default)
        => Task.FromResult<Lyrics?>(null);

    public virtual Task<List<Share>> GetSharesAsync(CancellationToken ct = default)
        => Task.FromResult(new List<Share>());

    public virtual Task<List<Share>> CreateShareAsync(string id, string? description = null, CancellationToken ct = default)
        => Task.FromResult(new List<Share>());

    public virtual Task<bool> DeleteShareAsync(string id, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<List<Bookmark>> GetBookmarksAsync(CancellationToken ct = default)
        => Task.FromResult(new List<Bookmark>());

    public virtual Task<bool> CreateBookmarkAsync(string id, long position, string? comment = null, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> DeleteBookmarkAsync(string id, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<bool> SavePlayQueueAsync(IReadOnlyList<string> songIds, string? current, long positionMs, CancellationToken ct = default)
        => Task.FromResult(false);

    public virtual Task<List<Song>?> GetPlayQueueAsync(CancellationToken ct = default)
        => Task.FromResult<List<Song>?>(null);

    public virtual bool SupportsRadio => false;

    public virtual bool SupportsAudiobooks => false;
}
