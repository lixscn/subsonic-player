using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using System.Xml.Linq;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// Subsonic API 客户端。
/// 认证：本服务器（Gonic）要求 p 参数（明文密码），不使用 token。
/// 地址：内网优先，不可达时回退外网。
/// </summary>
public class SubsonicClient
{
    private const string ApiVersion = "1.16.1";
    private const string ClientName = "SubsonicPlayer";

    private readonly string _lanServer;
    private readonly string _wanServer;
    private readonly string _username;
    private readonly string _password;
    private readonly HttpClient _http;
    private string _server;

    public string ActiveServer => _server;

    public SubsonicClient(string lanServer, string wanServer, string username, string password)
    {
        _lanServer = (lanServer ?? "").TrimEnd('/');
        _wanServer = (wanServer ?? "").TrimEnd('/');
        _username = username;
        _password = password;
        _server = _lanServer;
        _http = new HttpClient();
    }

    /// <summary>优先连内网，失败回退外网。</summary>
    public async Task<bool> ConnectAsync()
    {
        foreach (var srv in new[] { _lanServer, _wanServer })
        {
            if (string.IsNullOrEmpty(srv))
                continue;

            _server = srv;
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                if (await PingAsync(cts.Token))
                    return true;
            }
            catch
            {
                // 尝试下一个地址
            }
        }

        return false;
    }

    private string BuildUrl(string endpoint, IReadOnlyDictionary<string, string>? extra = null)
    {
        var q = new Dictionary<string, string>
        {
            ["u"] = _username,
            ["p"] = _password,
            ["v"] = ApiVersion,
            ["c"] = ClientName,
            ["f"] = "xml",
        };

        if (extra is not null)
            foreach (var (k, v) in extra)
                q[k] = v;

        var qs = string.Join("&", q.Select(kv => $"{kv.Key}={Uri.EscapeDataString(kv.Value)}"));
        return $"{_server}/rest/{endpoint}?{qs}";
    }

    private async Task<XDocument> GetXmlAsync(string endpoint, IReadOnlyDictionary<string, string>? extra = null, CancellationToken ct = default)
    {
        var url = BuildUrl(endpoint, extra);
        using var resp = await _http.GetAsync(url, ct);
        resp.EnsureSuccessStatusCode();
        var xml = await resp.Content.ReadAsStringAsync(ct);

        // Gonic 可能返回非法控制字符，清理后再解析（保留 \t \n \r）
        xml = System.Text.RegularExpressions.Regex.Replace(xml, @"[\x00-\x08\x0B\x0C\x0E-\x1F]", "");
        return XDocument.Parse(xml);
    }

    public async Task<bool> PingAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("ping", ct: ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    public async Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getArtists", ct: ct);
        var ns = doc.Root!.Name.Namespace;

        return doc.Descendants(ns + "index")
            .Select(idx => new ArtistIndex
            {
                Name = A(idx, "name"),
                Artists = idx.Elements(ns + "artist")
                    .Select(a => new Artist
                    {
                        Id = A(a, "id"),
                        Name = A(a, "name"),
                        AlbumCount = Ai(a, "albumCount"),
                    }).ToList(),
            }).ToList();
    }

    public async Task<List<Album>> GetAlbumList2Async(string type, int size = 20, int offset = 0, CancellationToken ct = default)
    {
        var extra = new Dictionary<string, string>
        {
            ["type"] = type,
            ["size"] = size.ToString(),
        };
        if (offset > 0)
            extra["offset"] = offset.ToString();

        var doc = await GetXmlAsync("getAlbumList2", extra, ct);
        var ns = doc.Root!.Name.Namespace;

        return doc.Descendants(ns + "album").Select(ParseAlbum).ToList();
    }

    public async Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getAlbum", new Dictionary<string, string> { ["id"] = id }, ct);
        var ns = doc.Root!.Name.Namespace;

        var el = doc.Descendants(ns + "album").FirstOrDefault();
        if (el is null)
            return null;

        var album = ParseAlbum(el);
        album.Songs = el.Elements(ns + "song").Select(ParseSong).ToList();
        return album;
    }

    /// <summary>获取艺术家的专辑列表。</summary>
    public async Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getArtist", new Dictionary<string, string> { ["id"] = artistId }, ct);
        var ns = doc.Root!.Name.Namespace;
        return doc.Descendants(ns + "album").Select(ParseAlbum).ToList();
    }

    public async Task<SearchResult> Search3Async(string query, int count = 20, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("search3", new Dictionary<string, string>
        {
            ["query"] = query,
            ["artistCount"] = count.ToString(),
            ["albumCount"] = count.ToString(),
            ["songCount"] = count.ToString(),
        }, ct);
        var ns = doc.Root!.Name.Namespace;

        var result = new SearchResult();
        var r = doc.Descendants(ns + "searchResult3").FirstOrDefault();
        if (r is null)
            return result;

        result.Artists = r.Elements(ns + "artist")
            .Select(a => new Artist { Id = A(a, "id"), Name = A(a, "name"), AlbumCount = Ai(a, "albumCount") }).ToList();
        result.Albums = r.Elements(ns + "album").Select(ParseAlbum).ToList();
        result.Songs = r.Elements(ns + "song").Select(ParseSong).ToList();
        return result;
    }

    public async Task<List<Playlist>> GetPlaylistsAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getPlaylists", ct: ct);
        var ns = doc.Root!.Name.Namespace;

        return doc.Descendants(ns + "playlist")
            .Select(el => new Playlist
            {
                Id = A(el, "id"),
                Name = A(el, "name"),
                Owner = A(el, "owner"),
                CoverArtId = A(el, "coverArt"),
                SongCount = Ai(el, "songCount"),
                Duration = Ai(el, "duration"),
            }).ToList();
    }

    public async Task<Playlist?> GetPlaylistAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getPlaylist", new Dictionary<string, string> { ["id"] = id }, ct);
        var ns = doc.Root!.Name.Namespace;

        var el = doc.Descendants(ns + "playlist").FirstOrDefault();
        if (el is null)
            return null;

        var playlist = new Playlist
        {
            Id = A(el, "id"),
            Name = A(el, "name"),
            Owner = A(el, "owner"),
            CoverArtId = A(el, "coverArt"),
            SongCount = Ai(el, "songCount"),
            Duration = Ai(el, "duration"),
        };
        playlist.Songs = el.Elements(ns + "entry").Select(ParseSong).ToList();
        return playlist;
    }

    public async Task<Playlist?> CreatePlaylistAsync(string name, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("createPlaylist", new Dictionary<string, string> { ["name"] = name }, ct);
        var ns = doc.Root!.Name.Namespace;
        var el = doc.Descendants(ns + "playlist").FirstOrDefault();
        if (el is null)
            return null;

        return new Playlist
        {
            Id = A(el, "id"),
            Name = A(el, "name"),
            Owner = A(el, "owner"),
            CoverArtId = A(el, "coverArt"),
            SongCount = Ai(el, "songCount"),
            Duration = Ai(el, "duration"),
        };
    }

    public async Task<bool> DeletePlaylistAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("deletePlaylist", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    public async Task<bool> UpdatePlaylistAsync(string id, string name, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("updatePlaylist", new Dictionary<string, string> { ["playlistId"] = id, ["name"] = name }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    public async Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("scrobble", new Dictionary<string, string> { ["id"] = songId, ["submission"] = submission ? "true" : "false" }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    public string GetCoverArtUrl(string coverArtId, int size = 300)
        => BuildUrl("getCoverArt", new Dictionary<string, string>
        {
            ["id"] = coverArtId,
            ["size"] = size.ToString(),
        });

    public string GetStreamUrl(string songId, int? maxBitRate = null, string? format = null)
    {
        var extra = new Dictionary<string, string> { ["id"] = songId };
        if (maxBitRate is int br)
            extra["maxBitRate"] = br.ToString();
        if (!string.IsNullOrEmpty(format))
            extra["format"] = format;
        return BuildUrl("stream", extra);
    }

    /// <summary>收藏（星标）。id 可为歌曲/专辑/艺术家 id。</summary>
    public async Task<bool> StarAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("star", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>取消收藏。</summary>
    public async Task<bool> UnstarAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("unstar", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    // ---- 解析辅助 ----

    private static string A(XElement el, string name) => Clean(el.Attribute(name)?.Value);

    private static int Ai(XElement el, string name)
        => int.TryParse(el.Attribute(name)?.Value, out var v) ? v : 0;

    /// <summary>清理 Gonic 的脏数据：Go 内存地址（0x…）与 &lt;nil&gt;。</summary>
    private static string Clean(string? s)
    {
        if (string.IsNullOrEmpty(s))
            return "";
        if (s.StartsWith("0x", StringComparison.Ordinal))
            return "";
        if (s.Contains("nil", StringComparison.OrdinalIgnoreCase))
            return "";
        return s;
    }

    private static Album ParseAlbum(XElement el) => new()
    {
        Id = A(el, "id"),
        Name = A(el, "name"),
        Title = A(el, "title"),
        Artist = A(el, "artist"),
        CoverArtId = A(el, "coverArt"),
        SongCount = Ai(el, "songCount"),
        Duration = Ai(el, "duration"),
        Year = Ai(el, "year"),
        Genre = A(el, "genre"),
    };

    private static Song ParseSong(XElement el) => new()
    {
        Id = A(el, "id"),
        Title = A(el, "title"),
        Artist = A(el, "artist"),
        ArtistId = A(el, "artistId"),
        Album = A(el, "album"),
        AlbumId = A(el, "albumId"),
        Duration = Ai(el, "duration"),
        Track = Ai(el, "track"),
        Year = Ai(el, "year"),
        CoverArtId = A(el, "coverArt"),
        Suffix = A(el, "suffix"),
        BitRate = Ai(el, "bitRate"),
        ContentType = A(el, "contentType"),
    };
}
