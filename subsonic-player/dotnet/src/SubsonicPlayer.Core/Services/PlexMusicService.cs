using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Xml.Linq;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// Plex 原生 API 客户端。认证用 X-Plex-Token（配置在 ApiKey）。
/// 播放流为 Media/Part 的 key，解析时缓存 songId → partKey 映射。
/// </summary>
public class PlexMusicService : MusicServiceBase
{
    private readonly string _lan;
    private readonly string _wan;
    private readonly string _token;
    private readonly string _name;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(20) };

    private string _server = "";
    private string _musicSectionId = "";

    private readonly Dictionary<string, string> _partKeys = new(StringComparer.Ordinal);

    public PlexMusicService(MusicServiceConfig config)
    {
        _name = config.Name;
        _lan = (config.LanUrl ?? "").TrimEnd('/');
        _wan = (config.WanUrl ?? "").TrimEnd('/');
        _token = config.ApiKey ?? "";
        _server = _lan;
    }

    public override string ServiceName => _name;

    public override async Task<bool> ConnectAsync()
    {
        foreach (var srv in new[] { _lan, _wan })
        {
            if (string.IsNullOrEmpty(srv) || string.IsNullOrEmpty(_token))
                continue;

            _server = srv;
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                var sections = await GetXmlAsync("/library/sections", null, cts.Token);
                _musicSectionId = sections.Descendants("Directory")
                    .FirstOrDefault(d => (string?)d.Attribute("type") == "artist")
                    ?.Attribute("key")?.Value ?? "";
                return true;
            }
            catch
            {
                // 尝试下一个地址
            }
        }

        return false;
    }

    public override async Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync($"/library/sections/{_musicSectionId}/all",
            new Dictionary<string, string> { ["type"] = "8" }, ct);

        var artists = doc.Descendants("Directory")
            .Where(d => (string?)d.Attribute("type") == "artist")
            .Select(d => new Artist
            {
                Id = A(d, "ratingKey"),
                Name = A(d, "title"),
            }).ToList();

        return GroupArtists(artists);
    }

    public override async Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default)
    {
        var sort = type switch
        {
            "newest" or "recent" => "addedAt:desc",
            "highest" => "rating:desc",
            "random" => "random",
            _ => "titleSort:asc",
        };

        var query = new Dictionary<string, string>
        {
            ["type"] = "9",
            ["sort"] = sort,
            ["X-Plex-Container-Start"] = offset.ToString(),
            ["X-Plex-Container-Size"] = size.ToString(),
        };

        var doc = await GetXmlAsync($"/library/sections/{_musicSectionId}/all", query, ct);
        return doc.Descendants("Directory")
            .Where(d => (string?)d.Attribute("type") == "album")
            .Select(ParseAlbum)
            .ToList();
    }

    public override async Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync($"/library/metadata/{id}/children", null, ct);

        var albumEl = doc.Descendants("Directory").FirstOrDefault(d => (string?)d.Attribute("type") == "album");
        if (albumEl is null && doc.Root is { } root)
            albumEl = root;

        var album = ParseAlbum(albumEl ?? new XElement("Directory"));

        var songs = doc.Descendants("Track")
            .Select(t => ParseTrack(t, album.CoverArtId))
            .ToList();
        album.Songs = songs;
        album.SongCount = songs.Count;
        return album;
    }

    public override async Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync($"/library/metadata/{artistId}/children", null, ct);
        return doc.Descendants("Directory")
            .Where(d => (string?)d.Attribute("type") == "album")
            .Select(ParseAlbum)
            .ToList();
    }

    public override async Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync($"/library/sections/{_musicSectionId}/all",
            new Dictionary<string, string>
            {
                ["type"] = "10",
                ["sort"] = "random",
                ["X-Plex-Container-Size"] = size.ToString(),
            }, ct);
        return doc.Descendants("Track").Select(t => ParseTrack(t, null)).ToList();
    }

    public override async Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("/library/search",
            new Dictionary<string, string> { ["query"] = query, ["limit"] = (count * 3).ToString() }, ct);

        var result = new SearchResult();
        foreach (var el in doc.Descendants())
        {
            var type = (string?)el.Attribute("type");
            switch (type)
            {
                case "artist":
                    result.Artists.Add(new Artist { Id = A(el, "ratingKey"), Name = A(el, "title") });
                    break;
                case "album":
                    result.Albums.Add(ParseAlbum(el));
                    break;
                case "track":
                    result.Songs.Add(ParseTrack(el, null));
                    break;
            }
        }
        return result;
    }

    public override string GetCoverArtUrl(string coverArtId, int size = 300)
    {
        var thumb = coverArtId;
        if (string.IsNullOrEmpty(thumb))
            return "";
        var url = thumb.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? thumb : _server + thumb;
        return AppendToken(url);
    }

    public override string GetStreamUrl(string songId)
        => _partKeys.TryGetValue(songId, out var part)
            ? AppendToken(_server + part)
            : "";

    public override string GetDownloadUrl(string songId)
        => GetStreamUrl(songId);

    // ---- 评分 ----

    public override async Task<bool> SetRatingAsync(string id, int rating, CancellationToken ct = default)
    {
        // Plex 评分 1–10，映射 1–5 星
        var value = Math.Clamp(rating, 1, 5) * 2;
        var url = $"{_server}/:/rate?key={Uri.EscapeDataString(id)}&identifier=com.plexapp.plugins.library&rating={value}";
        using var req = new HttpRequestMessage(HttpMethod.Put, url);
        req.Headers.TryAddWithoutValidation("X-Plex-Token", _token);
        using var resp = await _http.SendAsync(req, ct);
        return resp.IsSuccessStatusCode;
    }

    // ---- 歌单 ----

    public override async Task<List<Playlist>> GetPlaylistsAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("/playlists", null, ct);
        return doc.Descendants("Playlist")
            .Select(p => new Playlist
            {
                Id = A(p, "ratingKey"),
                Name = A(p, "title"),
                SongCount = Ai(p, "leafCount"),
            })
            .ToList();
    }

    public override async Task<Playlist?> GetPlaylistAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync($"/playlists/{id}/items", null, ct);
        var playlist = new Playlist { Id = id };
        playlist.Songs = doc.Descendants("Track").Select(t => ParseTrack(t, null)).ToList();
        playlist.SongCount = playlist.Songs.Count;
        return playlist;
    }

    public override async Task<Playlist?> CreatePlaylistAsync(string name, CancellationToken ct = default)
    {
        var url = $"{_server}/playlists?title={Uri.EscapeDataString(name)}&type=audio";
        using var req = new HttpRequestMessage(HttpMethod.Post, url);
        req.Headers.TryAddWithoutValidation("X-Plex-Token", _token);
        using var resp = await _http.SendAsync(req, ct);
        if (!resp.IsSuccessStatusCode)
            return null;

        var doc = XDocument.Parse(await resp.Content.ReadAsStringAsync(ct));
        var pl = doc.Descendants("Playlist").FirstOrDefault();
        return pl is null ? null : new Playlist { Id = A(pl, "ratingKey"), Name = A(pl, "title") };
    }

    public override async Task<bool> DeletePlaylistAsync(string id, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Delete, $"{_server}/playlists/{id}");
        req.Headers.TryAddWithoutValidation("X-Plex-Token", _token);
        using var resp = await _http.SendAsync(req, ct);
        return resp.IsSuccessStatusCode;
    }

    public override async Task<bool> AddSongsToPlaylistAsync(string playlistId, IReadOnlyList<string> songIds, CancellationToken ct = default)
    {
        var ok = true;
        foreach (var songId in songIds)
        {
            var uri = $"library://{_musicSectionId}/item/{Uri.EscapeDataString(songId)}";
            var url = $"{_server}/playlists/{playlistId}/items?uri={Uri.EscapeDataString(uri)}";
            using var req = new HttpRequestMessage(HttpMethod.Put, url);
            req.Headers.TryAddWithoutValidation("X-Plex-Token", _token);
            using var resp = await _http.SendAsync(req, ct);
            if (!resp.IsSuccessStatusCode)
                ok = false;
        }
        return ok;
    }

    public override async Task<bool> RemoveFromPlaylistAsync(string playlistId, IReadOnlyList<int> indexes, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync($"/playlists/{playlistId}/items", null, ct);
        var tracks = doc.Descendants("Track").ToList();

        var ok = true;
        foreach (var idx in indexes)
        {
            if (idx < 0 || idx >= tracks.Count)
                continue;

            var itemId = A(tracks[idx], "playlistItemID");
            if (string.IsNullOrEmpty(itemId))
                continue;

            using var req = new HttpRequestMessage(HttpMethod.Delete, $"{_server}/playlists/{playlistId}/items/{itemId}");
            req.Headers.TryAddWithoutValidation("X-Plex-Token", _token);
            using var resp = await _http.SendAsync(req, ct);
            if (!resp.IsSuccessStatusCode)
                ok = false;
        }
        return ok;
    }

    // ---- 解析辅助 ----

    private async Task<XDocument> GetXmlAsync(string path, IReadOnlyDictionary<string, string>? query, CancellationToken ct)
    {
        var url = _server + path;
        var sep = url.Contains('?') ? '&' : '?';

        if (query is not null)
            foreach (var (k, v) in query)
            {
                url += $"{sep}{Uri.EscapeDataString(k)}={Uri.EscapeDataString(v)}";
                sep = '&';
            }

        url += $"{sep}X-Plex-Token={Uri.EscapeDataString(_token)}";

        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("Accept", "application/xml");
        using var resp = await _http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        return XDocument.Parse(await resp.Content.ReadAsStringAsync(ct));
    }

    private Album ParseAlbum(XElement el)
    {
        var thumb = A(el, "thumb");
        return new Album
        {
            Id = A(el, "ratingKey"),
            Name = A(el, "title"),
            Artist = A(el, "parentTitle"),
            CoverArtId = thumb,
            Year = Ai(el, "year"),
            Genre = A(el, "genre"),
        };
    }

    private Song ParseTrack(XElement el, string? albumThumb)
    {
        var ratingKey = A(el, "ratingKey");
        var partKey = el.Descendants("Part").Select(p => A(p, "key")).FirstOrDefault(k => !string.IsNullOrEmpty(k)) ?? "";
        if (!string.IsNullOrEmpty(ratingKey) && !string.IsNullOrEmpty(partKey))
            _partKeys[ratingKey] = partKey;

        var thumb = A(el, "thumb");
        if (string.IsNullOrEmpty(thumb))
            thumb = albumThumb ?? "";

        return new Song
        {
            Id = ratingKey,
            Title = A(el, "title"),
            Artist = A(el, "originalTitle").Length > 0 ? A(el, "originalTitle") : A(el, "grandparentTitle"),
            Album = A(el, "parentTitle"),
            AlbumId = A(el, "parentRatingKey"),
            CoverArtId = thumb,
            Duration = Ai(el, "duration") / 1000,
            Track = Ai(el, "index"),
            Year = Ai(el, "parentYear"),
            Suffix = "mp3",
        };
    }

    private string AppendToken(string url)
    {
        var sep = url.Contains('?') ? '&' : '?';
        return $"{url}{sep}X-Plex-Token={Uri.EscapeDataString(_token)}";
    }

    private static List<ArtistIndex> GroupArtists(List<Artist> artists)
    {
        var groups = new Dictionary<string, List<Artist>>();
        foreach (var artist in artists)
        {
            var key = FirstLetterKey(artist.Name);
            if (!groups.TryGetValue(key, out var list))
                groups[key] = list = new List<Artist>();
            list.Add(artist);
        }
        return groups
            .OrderBy(g => g.Key, StringComparer.OrdinalIgnoreCase)
            .Select(g => new ArtistIndex { Name = g.Key, Artists = g.Value })
            .ToList();
    }

    private static string FirstLetterKey(string name)
    {
        if (string.IsNullOrEmpty(name))
            return "#";
        var c = char.ToUpperInvariant(name[0]);
        return c is >= 'A' and <= 'Z' ? c.ToString() : "#";
    }

    private static string A(XElement el, string name) => (string?)el.Attribute(name) ?? "";

    private static int Ai(XElement el, string name)
        => int.TryParse((string?)el.Attribute(name), out var v) ? v : 0;
}
