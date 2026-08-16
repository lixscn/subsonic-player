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

    public Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default)
        => _client.ScrobbleAsync(songId, submission, ct);

    public Task<bool> SetFavoriteAsync(string id, bool favorite, CancellationToken ct = default)
        => favorite ? _client.StarAsync(id, ct) : _client.UnstarAsync(id, ct);

    public string GetCoverArtUrl(string coverArtId, int size = 300)
        => _client.GetCoverArtUrl(coverArtId, size);

    public string GetStreamUrl(string songId)
        => _client.GetStreamUrl(songId);
}
