using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// Emby 原生 API 客户端（兼容 Jellyfin 大部分端点）。
/// 认证：优先 ApiKey（X-Emby-Token / api_key），否则用户名+密码 AuthenticateByName。
/// </summary>
public class EmbyMusicService : MusicServiceBase
{
    private const string ClientName = "SubsonicPlayer";
    private const string DeviceId = "subsonic-player-pc";

    private readonly string _lan;
    private readonly string _wan;
    private readonly string _username;
    private readonly string _password;
    private readonly string _apiKey;
    private readonly string _name;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(20) };

    private string _server = "";
    private string _token = "";
    private string _userId = "";

    public EmbyMusicService(MusicServiceConfig config)
    {
        _name = config.Name;
        _lan = (config.LanUrl ?? "").TrimEnd('/');
        _wan = (config.WanUrl ?? "").TrimEnd('/');
        _username = config.Username;
        _password = config.Password;
        _apiKey = config.ApiKey ?? "";
        _server = _lan;
    }

    public override string ServiceName => _name;

    public override async Task<bool> ConnectAsync()
    {
        foreach (var srv in new[] { _lan, _wan })
        {
            if (string.IsNullOrEmpty(srv))
                continue;

            _server = srv;
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));

                if (!string.IsNullOrEmpty(_apiKey))
                {
                    _token = _apiKey;
                    _userId = await ResolveUserIdAsync(cts.Token);
                }
                else if (!string.IsNullOrEmpty(_username))
                {
                    if (!await AuthenticateAsync(cts.Token))
                        continue;
                }
                else
                {
                    continue;
                }

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

    private async Task<bool> AuthenticateAsync(CancellationToken ct)
    {
        var url = $"{_server}/Users/AuthenticateByName";
        using var req = new HttpRequestMessage(HttpMethod.Post, url);
        req.Headers.TryAddWithoutValidation("X-Emby-Authorization",
            $"MediaBrowser Client=\"{ClientName}\", Device=\"PC\", DeviceId=\"{DeviceId}\", Version=\"1.0\"");
        req.Content = new StringContent(
            JsonSerializer.Serialize(new { Username = _username, Pw = _password }),
            Encoding.UTF8, "application/json");

        using var resp = await _http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        using var doc = await JsonDocument.ParseAsync(await resp.Content.ReadAsStreamAsync(ct), cancellationToken: ct);

        var root = doc.RootElement;
        _token = Js(root, "AccessToken");
        if (root.TryGetProperty("User", out var user))
            _userId = Js(user, "Id");
        return !string.IsNullOrEmpty(_token);
    }

    private async Task<string> ResolveUserIdAsync(CancellationToken ct)
    {
        try
        {
            using var doc = await GetJsonAsync("/Users/Me", null, ct);
            return Js(doc.RootElement, "Id");
        }
        catch
        {
            return "";
        }
    }

    private async Task<bool> PingAsync(CancellationToken ct)
    {
        using var resp = await _http.GetAsync($"{_server}/System/Info/Public", ct);
        return resp.IsSuccessStatusCode;
    }

    // ---- 曲库浏览 ----

    public override async Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var doc = await GetJsonAsync("/Artists",
            new Dictionary<string, string> { ["UserId"] = _userId, ["Recursive"] = "true", ["Limit"] = "2000" }, ct);

        var artists = new List<Artist>();
        if (doc.RootElement.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
            foreach (var item in items.EnumerateArray())
                if (Js(item, "Type") == "MusicArtist")
                    artists.Add(ParseArtist(item));

        return GroupArtists(artists);
    }

    public override async Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);

        var sort = type switch
        {
            "newest" or "recent" => ("DateCreated", "Descending"),
            "frequent" => ("PlayCount", "Descending"),
            "highest" => ("CommunityRating", "Descending"),
            "random" => ("Random", "Ascending"),
            _ => ("SortName", "Ascending"),
        };

        var query = new Dictionary<string, string>
        {
            ["IncludeItemTypes"] = "MusicAlbum",
            ["Recursive"] = "true",
            ["SortBy"] = sort.Item1,
            ["SortOrder"] = sort.Item2,
            ["StartIndex"] = offset.ToString(),
            ["Limit"] = size.ToString(),
        };
        if (!string.IsNullOrEmpty(_userId))
            query["UserId"] = _userId;

        using var doc = await GetJsonAsync("/Items", query, ct);
        return ParseAlbums(doc.RootElement);
    }

    public override async Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);

        Album album;
        using (var doc = await GetJsonAsync($"/Users/{_userId}/Items/{id}", null, ct))
        {
            var root = doc.RootElement;
            album = new Album
            {
                Id = Js(root, "Id"),
                Name = Js(root, "Name"),
                Artist = Js(root, "AlbumArtist"),
                CoverArtId = Js(root, "Id"),
                Year = Ji(root, "ProductionYear"),
                Genre = FirstGenre(root),
            };
        }

        using (var doc = await GetJsonAsync("/Items",
            new Dictionary<string, string>
            {
                ["ParentId"] = id,
                ["IncludeItemTypes"] = "Audio",
                ["Recursive"] = "true",
                ["SortBy"] = "IndexNumber,ParentIndexNumber,Name",
                ["Limit"] = "2000",
            }, ct))
        {
            album.Songs = ParseSongs(doc.RootElement);
        }
        album.SongCount = album.Songs.Count;
        return album;
    }

    public override async Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var doc = await GetJsonAsync("/Items",
            new Dictionary<string, string>
            {
                ["ParentId"] = artistId,
                ["IncludeItemTypes"] = "MusicAlbum",
                ["Recursive"] = "true",
                ["SortBy"] = "ProductionYear,SortName",
            }, ct);
        return ParseAlbums(doc.RootElement);
    }

    public override async Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var doc = await GetJsonAsync("/Items",
            new Dictionary<string, string>
            {
                ["IncludeItemTypes"] = "Audio",
                ["Recursive"] = "true",
                ["SortBy"] = "Random",
                ["Limit"] = size.ToString(),
            }, ct);
        return ParseSongs(doc.RootElement);
    }

    public override async Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var doc = await GetJsonAsync("/Items",
            new Dictionary<string, string>
            {
                ["SearchTerm"] = query,
                ["IncludeItemTypes"] = "Audio,MusicAlbum,MusicArtist",
                ["Recursive"] = "true",
                ["Limit"] = (count * 3).ToString(),
            }, ct);

        var result = new SearchResult();
        if (doc.RootElement.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in items.EnumerateArray())
            {
                switch (Js(item, "Type"))
                {
                    case "MusicArtist":
                        result.Artists.Add(ParseArtist(item));
                        break;
                    case "MusicAlbum":
                        result.Albums.Add(ParseAlbum(item));
                        break;
                    case "Audio":
                        result.Songs.Add(ParseSong(item));
                        break;
                }
            }
        }
        return result;
    }

    // ---- 媒体 URL ----

    public override string GetCoverArtUrl(string coverArtId, int size = 300)
        => $"{_server}/Items/{Uri.EscapeDataString(coverArtId)}/Images/Primary?maxWidth={size}&api_key={Uri.EscapeDataString(_token)}";

    public override string GetStreamUrl(string songId)
        => $"{_server}/Audio/{Uri.EscapeDataString(songId)}/stream?static=true&api_key={Uri.EscapeDataString(_token)}";

    public override string GetDownloadUrl(string songId)
        => $"{_server}/Items/{Uri.EscapeDataString(songId)}/Download?api_key={Uri.EscapeDataString(_token)}";

    // ---- 收藏 / 评分 ----

    public override async Task<bool> SetFavoriteAsync(string id, bool favorite, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        var url = $"{_server}/Users/{_userId}/FavoriteItems/{Uri.EscapeDataString(id)}";
        using var resp = await SendAsync(favorite ? HttpMethod.Post : HttpMethod.Delete, url, ct);
        return resp.IsSuccessStatusCode;
    }

    public override async Task<bool> SetRatingAsync(string id, int rating, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        // Emby 用户评分用 Likes（喜欢/不喜欢），映射 4-5 星为喜欢
        var likes = rating >= 4;
        var url = $"{_server}/Users/{_userId}/Items/{Uri.EscapeDataString(id)}/Rating?Likes={likes}";
        using var resp = await SendAsync(HttpMethod.Post, url, ct);
        return resp.IsSuccessStatusCode;
    }

    public override async Task<Lyrics?> GetLyricsAsync(string artist, string title, string? songId = null, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(songId))
            return null;

        await EnsureConnectedAsync(ct);
        try
        {
            using var doc = await GetJsonAsync($"/Items/{Uri.EscapeDataString(songId!)}/Lyrics", null, ct);
            var lyrics = new Lyrics();

            if (doc.RootElement.TryGetProperty("Lyrics", out var arr) && arr.ValueKind == JsonValueKind.Array)
            {
                foreach (var l in arr.EnumerateArray())
                    lyrics.Lines.Add(new LyricsLine
                    {
                        Text = Js(l, "Text"),
                        StartSeconds = Jl(l, "Start") / 10_000_000.0,
                    });
                return lyrics;
            }

            if (doc.RootElement.TryGetProperty("Lyrics", out var txt) && txt.ValueKind == JsonValueKind.String)
            {
                lyrics.Text = txt.GetString() ?? "";
                return lyrics;
            }

            return null;
        }
        catch
        {
            return null;
        }
    }

    // ---- 歌单 ----

    public override async Task<List<Playlist>> GetPlaylistsAsync(CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var doc = await GetJsonAsync($"/Users/{_userId}/Playlists", null, ct);

        var list = new List<Playlist>();
        if (doc.RootElement.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
            foreach (var item in items.EnumerateArray())
                list.Add(new Playlist
                {
                    Id = Js(item, "Id"),
                    Name = Js(item, "Name"),
                    SongCount = Ji(item, "ChildCount"),
                });
        return list;
    }

    public override async Task<Playlist?> GetPlaylistAsync(string id, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var doc = await GetJsonAsync($"/Playlists/{id}/Items",
            new Dictionary<string, string> { ["UserId"] = _userId }, ct);

        var playlist = new Playlist { Id = id };
        playlist.Songs = ParseSongs(doc.RootElement);
        playlist.SongCount = playlist.Songs.Count;
        return playlist;
    }

    public override async Task<Playlist?> CreatePlaylistAsync(string name, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        var url = $"{_server}/Playlists?Name={Uri.EscapeDataString(name)}&UserId={_userId}";
        using var resp = await SendAsync(HttpMethod.Post, url, ct);
        if (!resp.IsSuccessStatusCode)
            return null;

        using var doc = await JsonDocument.ParseAsync(await resp.Content.ReadAsStreamAsync(ct), cancellationToken: ct);
        var id = Js(doc.RootElement, "Id");
        return string.IsNullOrEmpty(id) ? null : new Playlist { Id = id, Name = name };
    }

    public override async Task<bool> DeletePlaylistAsync(string id, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        using var resp = await SendAsync(HttpMethod.Delete, $"{_server}/Items/{Uri.EscapeDataString(id)}", ct);
        return resp.IsSuccessStatusCode;
    }

    public override async Task<bool> AddSongsToPlaylistAsync(string playlistId, IReadOnlyList<string> songIds, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);
        var ids = string.Join(",", songIds);
        var url = $"{_server}/Playlists/{Uri.EscapeDataString(playlistId)}/Items?Ids={Uri.EscapeDataString(ids)}&UserId={_userId}";
        using var resp = await SendAsync(HttpMethod.Post, url, ct);
        return resp.IsSuccessStatusCode;
    }

    public override async Task<bool> RemoveFromPlaylistAsync(string playlistId, IReadOnlyList<int> indexes, CancellationToken ct = default)
    {
        await EnsureConnectedAsync(ct);

        using var doc = await GetJsonAsync($"/Playlists/{playlistId}/Items",
            new Dictionary<string, string> { ["UserId"] = _userId }, ct);

        var entryIds = new List<string>();
        if (doc.RootElement.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
        {
            var all = items.EnumerateArray().ToList();
            foreach (var idx in indexes)
            {
                if (idx < 0 || idx >= all.Count)
                    continue;
                var entryId = Js(all[idx], "PlaylistItemId");
                if (!string.IsNullOrEmpty(entryId))
                    entryIds.Add(entryId);
            }
        }

        if (entryIds.Count == 0)
            return false;

        var ids = string.Join(",", entryIds);
        var url = $"{_server}/Playlists/{Uri.EscapeDataString(playlistId)}/Items?EntryIds={Uri.EscapeDataString(ids)}";
        using var resp = await SendAsync(HttpMethod.Delete, url, ct);
        return resp.IsSuccessStatusCode;
    }

    // ---- 解析辅助 ----

    private async Task EnsureConnectedAsync(CancellationToken ct)
    {
        if (string.IsNullOrEmpty(_token))
            await ConnectAsync();
        ct.ThrowIfCancellationRequested();
    }

    private async Task<JsonDocument> GetJsonAsync(string path, IReadOnlyDictionary<string, string>? query, CancellationToken ct)
    {
        var url = _server + path;
        if (query is not null && query.Count > 0)
            url += "?" + string.Join("&", query.Select(kv => $"{kv.Key}={Uri.EscapeDataString(kv.Value)}"));

        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        if (!string.IsNullOrEmpty(_token))
            req.Headers.TryAddWithoutValidation("X-Emby-Token", _token);

        using var resp = await _http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        return await JsonDocument.ParseAsync(await resp.Content.ReadAsStreamAsync(ct), cancellationToken: ct);
    }

    private async Task<HttpResponseMessage> SendAsync(HttpMethod method, string url, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(method, url);
        if (!string.IsNullOrEmpty(_token))
            req.Headers.TryAddWithoutValidation("X-Emby-Token", _token);
        return await _http.SendAsync(req, ct);
    }

    private static List<Album> ParseAlbums(JsonElement root)
    {
        var list = new List<Album>();
        if (root.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
            foreach (var item in items.EnumerateArray())
                list.Add(ParseAlbum(item));
        return list;
    }

    private static List<Song> ParseSongs(JsonElement root)
    {
        var list = new List<Song>();
        if (root.TryGetProperty("Items", out var items) && items.ValueKind == JsonValueKind.Array)
            foreach (var item in items.EnumerateArray())
                list.Add(ParseSong(item));
        return list;
    }

    private static Album ParseAlbum(JsonElement e)
    {
        var id = Js(e, "Id");
        return new Album
        {
            Id = id,
            Name = Js(e, "Name"),
            Artist = Js(e, "AlbumArtist"),
            CoverArtId = id,
            SongCount = Ji(e, "ChildCount"),
            Year = Ji(e, "ProductionYear"),
            Genre = FirstGenre(e),
        };
    }

    private static Artist ParseArtist(JsonElement e)
    {
        var name = Js(e, "Name");
        return new Artist
        {
            Id = Js(e, "Id"),
            Name = name,
            AlbumCount = Ji(e, "ChildCount"),
        };
    }

    private static Song ParseSong(JsonElement e)
    {
        var id = Js(e, "Id");
        var albumId = Js(e, "AlbumId");
        return new Song
        {
            Id = id,
            Title = Js(e, "Name"),
            Artist = Js(e, "AlbumArtist"),
            Album = Js(e, "Album"),
            AlbumId = albumId,
            CoverArtId = string.IsNullOrEmpty(albumId) ? id : albumId,
            Duration = (int)(Jl(e, "RunTimeTicks") / 10_000_000L),
            Track = Ji(e, "IndexNumber"),
            Year = Ji(e, "ProductionYear"),
            Suffix = "mp3",
        };
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

    private static string FirstGenre(JsonElement e)
    {
        if (e.TryGetProperty("Genres", out var genres) && genres.ValueKind == JsonValueKind.Array)
            foreach (var g in genres.EnumerateArray())
                if (g.ValueKind == JsonValueKind.String)
                    return g.GetString() ?? "";
        return "";
    }

    private static string Js(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static int Ji(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i) ? i : 0;

    private static long Jl(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var i) ? i : 0;
}
