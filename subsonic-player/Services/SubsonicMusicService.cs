using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>Subsonic/OpenSubsonic 协议的 IMusicService 实现（兼容 Gonic/Navidrome/Jellyfin）。</summary>
public class SubsonicMusicService : IMusicService
{
    private readonly SubsonicClient _client;
    private readonly MusicServiceConfig _config;

    public SubsonicMusicService(MusicServiceConfig config)
    {
        _config = config;
        _client = new SubsonicClient(config.LanUrl, config.WanUrl, config.Username, config.Password);
    }

    public string ServiceName => _config.Name;

    public Task<bool> ConnectAsync() => _client.ConnectAsync();

    public Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)
        => _client.GetArtistsAsync(ct);

    public Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default)
        => _client.GetAlbumList2Async(type, size, offset, ct);

    public Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
        => _client.GetAlbumAsync(id, ct);

    public Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
        => _client.GetArtistAlbumsAsync(artistId, ct);

    /// <summary>Gonic 不支持 getRandomSongs，变通：随机专辑 + 各取一首。</summary>
    public async Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default)
    {
        var albums = await _client.GetAlbumList2Async("random", size, 0, ct);
        var songs = new List<Song>();

        foreach (var album in albums)
        {
            var detail = await _client.GetAlbumAsync(album.Id, ct);
            if (detail is { Songs.Count: > 0 })
                songs.Add(detail.Songs[0]);
        }

        return songs;
    }

    public Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default)
        => _client.Search3Async(query, count, ct);

    public Task<List<Playlist>> GetPlaylistsAsync(CancellationToken ct = default)
        => _client.GetPlaylistsAsync(ct);

    public Task<Playlist?> GetPlaylistAsync(string id, CancellationToken ct = default)
        => _client.GetPlaylistAsync(id, ct);

    public Task<Playlist?> CreatePlaylistAsync(string name, CancellationToken ct = default)
        => _client.CreatePlaylistAsync(name, ct);

    public Task<bool> DeletePlaylistAsync(string id, CancellationToken ct = default)
        => _client.DeletePlaylistAsync(id, ct);

    public Task<bool> UpdatePlaylistAsync(string id, string name, CancellationToken ct = default)
        => _client.UpdatePlaylistAsync(id, name, ct);

    public Task<bool> AddSongsToPlaylistAsync(string playlistId, IReadOnlyList<string> songIds, CancellationToken ct = default)
        => _client.AddSongsToPlaylistAsync(playlistId, songIds, ct);

    public Task<bool> RemoveFromPlaylistAsync(string playlistId, IReadOnlyList<int> indexes, CancellationToken ct = default)
        => _client.RemoveFromPlaylistAsync(playlistId, indexes, ct);

    public Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default)
        => _client.ScrobbleAsync(songId, submission, ct);

    public Task<bool> SetFavoriteAsync(string id, bool favorite, CancellationToken ct = default)
        => favorite ? _client.StarAsync(id, ct) : _client.UnstarAsync(id, ct);

    public Task<bool> SetRatingAsync(string id, int rating, CancellationToken ct = default)
        => _client.SetRatingAsync(id, rating, ct);

    public Task<Lyrics?> GetLyricsAsync(string artist, string title, string? songId = null, CancellationToken ct = default)
        => _client.GetLyricsAsync(artist, title, songId, ct);

    public Task<List<Share>> GetSharesAsync(CancellationToken ct = default)
        => _client.GetSharesAsync(ct);

    public Task<List<Share>> CreateShareAsync(string id, string? description = null, CancellationToken ct = default)
        => _client.CreateShareAsync(id, description, ct);

    public Task<bool> DeleteShareAsync(string id, CancellationToken ct = default)
        => _client.DeleteShareAsync(id, ct);

    public Task<List<Bookmark>> GetBookmarksAsync(CancellationToken ct = default)
        => _client.GetBookmarksAsync(ct);

    public Task<bool> CreateBookmarkAsync(string id, long position, string? comment = null, CancellationToken ct = default)
        => _client.CreateBookmarkAsync(id, position, comment, ct);

    public Task<bool> DeleteBookmarkAsync(string id, CancellationToken ct = default)
        => _client.DeleteBookmarkAsync(id, ct);

    public Task<bool> SavePlayQueueAsync(IReadOnlyList<string> songIds, string? current, long positionMs, CancellationToken ct = default)
        => _client.SavePlayQueueAsync(songIds, current, positionMs, ct);

    public Task<List<Song>?> GetPlayQueueAsync(CancellationToken ct = default)
        => _client.GetPlayQueueAsync(ct);

    public string GetCoverArtUrl(string coverArtId, int size = 300)
        => _client.GetCoverArtUrl(coverArtId, size);

    public string GetStreamUrl(string songId)
    {
        // 网络质量 → maxBitRate/format 映射（原始时不传参）
        var quality = AppServices.Settings.Settings.NetworkQuality;
        return quality switch
        {
            NetworkQuality.High => _client.GetStreamUrl(songId, maxBitRate: 320, format: "mp3"),
            NetworkQuality.Medium => _client.GetStreamUrl(songId, maxBitRate: 192, format: "mp3"),
            NetworkQuality.Low => _client.GetStreamUrl(songId, maxBitRate: 96, format: "mp3"),
            _ => _client.GetStreamUrl(songId),
        };
    }

    public string GetDownloadUrl(string songId)
        => _client.GetDownloadUrl(songId);
}
