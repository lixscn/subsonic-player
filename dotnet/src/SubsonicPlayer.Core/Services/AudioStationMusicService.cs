using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 群晖 AudioStation（DSM）协议实现：SYNO.AudioStation.* 系列 webapi（JSON）。
/// 已实现：API 发现 + 登录（SYNO.API.Auth → sid）、封面/播放/下载 URL 构造，以及
/// 按 Song.list 聚合的曲库浏览（艺术家/专辑/歌曲/搜索）。字段路径按已知 Synology API 形状映射，
/// 解析失败静默降级为空（不崩溃），真正字段需在真实 DSM 上确认。
/// </summary>
public class AudioStationMusicService : MusicServiceBase
{
    private static readonly string[] ApiNames =
        { "SYNO.API.Auth", "SYNO.AudioStation.Folder", "SYNO.AudioStation.Song", "SYNO.AudioStation.CoverArt" };

    private readonly MusicServiceConfig _config;
    private readonly HttpClient _http;
    private string? _baseUrl;
    private string? _sid;
    private List<Song>? _songCache;

    public AudioStationMusicService(MusicServiceConfig config)
    {
        _config = config;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
    }

    public override string ServiceName => _config.Name;

    private string ResolveBaseUrl()
    {
        var lan = _config.LanUrl?.Trim() ?? "";
        var wan = _config.WanUrl?.Trim() ?? "";
        return (lan.Length > 0 ? lan : wan).TrimEnd('/');
    }

    // ============ 连接与认证 ============

    public override async Task<bool> ConnectAsync()
    {
        var ct = CancellationToken.None;
        try
        {
            _baseUrl = ResolveBaseUrl();
            if (string.IsNullOrEmpty(_baseUrl))
                return false;

            var query = string.Join(",", ApiNames);
            _ = await GetJsonAsync($"webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query={Uri.EscapeDataString(query)}", ct);

            var login = await GetJsonAsync(
                $"webapi/auth.cgi?api=SYNO.API.Auth&version=6&method=login&account={Uri.EscapeDataString(_config.Username)}" +
                $"&passwd={Uri.EscapeDataString(_config.Password)}&session=AudioStation&format=sid", ct);
            if (login is { } j && j.TryGetProperty("data", out var data) && data.TryGetProperty("sid", out var sidEl))
                _sid = sidEl.GetString();
            return !string.IsNullOrEmpty(_sid);
        }
        catch
        {
            return false;
        }
    }

    private async Task<JsonElement?> GetJsonAsync(string path, CancellationToken ct = default)
    {
        try
        {
            var url = $"{_baseUrl}/{path}";
            var resp = await _http.GetStringAsync(url, ct);
            using var doc = JsonDocument.Parse(resp);
            return doc.RootElement.Clone();
        }
        catch
        {
            return null;
        }
    }

    private string AuthSuffix => string.IsNullOrEmpty(_sid) ? "" : $"&_sid={_sid}";

    private static string Str(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static int IntOf(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetInt32() : 0;

    private static Song MapSong(JsonElement e)
    {
        var s = new Song { Id = Str(e, "id"), Title = Str(e, "title") };

        if (e.TryGetProperty("artist_artist", out var aa) && aa.ValueKind == JsonValueKind.Array && aa.GetArrayLength() > 0)
        {
            var a0 = aa[0];
            s.Artist = Str(a0, "name");
            s.ArtistId = Str(a0, "id");
        }
        if (e.TryGetProperty("album_album", out var ab) && ab.ValueKind == JsonValueKind.Array && ab.GetArrayLength() > 0)
        {
            var a0 = ab[0];
            s.Album = Str(a0, "title");
            s.AlbumId = Str(a0, "id");
        }
        if (e.TryGetProperty("additional", out var add) && add.ValueKind == JsonValueKind.Object)
        {
            s.Duration = IntOf(add, "duration");
            if (add.TryGetProperty("cover", out var cov) && cov.ValueKind == JsonValueKind.Object)
                s.CoverArtId = Str(cov, "id");
            if (add.TryGetProperty("song_tag", out var tag) && tag.ValueKind == JsonValueKind.Object)
            {
                s.Genre = Str(tag, "genre");
                s.Year = IntOf(tag, "year");
                s.Track = IntOf(tag, "track");
                if (s.Album == "") s.Album = Str(tag, "album");
                if (s.Artist == "") s.Artist = Str(tag, "album_artist");
                if (s.Artist == "") s.Artist = Str(tag, "artist");
            }
        }
        return s;
    }

    /// <summary>分页拉取共享曲库全部歌曲（结果缓存，避免重复请求）。</summary>
    private async Task<List<Song>> FetchSongsAsync(CancellationToken ct)
    {
        if (_songCache is not null)
            return _songCache;

        var result = new List<Song>();
        try
        {
            int offset = 0;
            const int limit = 500;
            while (true)
            {
                var json = await GetJsonAsync(
                    $"webapi/entry.cgi?api=SYNO.AudioStation.Song&version=1&method=list&library=shared" +
                    $"&sort_key=title&sort_direction=ASC&limit={limit}&offset={offset}" +
                    $"&additional={Uri.EscapeDataString("[\"song_tag\",\"duration\",\"cover\"]")}{AuthSuffix}", ct);
                if (json is not { } j || !j.TryGetProperty("data", out var data))
                    break;
                if (!data.TryGetProperty("songs", out var arr) || arr.ValueKind != JsonValueKind.Array)
                    break;

                foreach (var e in arr.EnumerateArray())
                    result.Add(MapSong(e));

                var total = data.TryGetProperty("total", out var t) ? t.GetInt32() : 0;
                offset += limit;
                if (offset >= total)
                    break;
            }
        }
        catch
        {
            // 失败返回已收集部分
        }
        _songCache = result;
        return result;
    }

    private static Album BuildAlbum(string id, string title, string artist, List<Song> songs) => new()
    {
        Id = id,
        Name = title,
        Title = title,
        Artist = artist,
        CoverArtId = songs.FirstOrDefault()?.CoverArtId ?? "",
        SongCount = songs.Count,
        Songs = songs,
    };

    // ============ 曲库浏览 ============

    public override async Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)
    {
        var songs = await FetchSongsAsync(ct);
        return songs
            .Where(s => s.Artist != "")
            .GroupBy(s => s.ArtistId != "" ? s.ArtistId : s.Artist, StringComparer.Ordinal)
            .Select(g => new Artist { Id = g.First().ArtistId, Name = g.First().Artist, AlbumCount = g.Select(x => x.AlbumId).Where(x => x != "").Distinct().Count() })
            .GroupBy(a => char.IsLetter(a.Name[0]) ? char.ToUpper(a.Name[0]).ToString() : "#")
            .OrderBy(gi => gi.Key)
            .Select(gi => new ArtistIndex { Name = gi.Key, Artists = gi.ToList() })
            .ToList();
    }

    public override async Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default)
    {
        var songs = await FetchSongsAsync(ct);
        var albums = songs
            .Where(s => s.AlbumId != "" || s.Album != "")
            .GroupBy(s => s.AlbumId != "" ? s.AlbumId : s.Album, StringComparer.Ordinal)
            .Select(g => BuildAlbum(g.Key, g.First().Album, g.First().Artist, g.ToList()))
            .ToList();
        return albums.Skip(offset).Take(size).ToList();
    }

    public override async Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
    {
        var songs = await FetchSongsAsync(ct).ConfigureAwait(false);
        var list = songs.Where(s => s.AlbumId == id || s.Album == id).ToList();
        if (list.Count == 0)
            return null;
        return BuildAlbum(id, list[0].Album, list[0].Artist, list);
    }

    public override async Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
    {
        var songs = await FetchSongsAsync(ct);
        return songs
            .Where(s => s.ArtistId == artistId || s.Artist == artistId)
            .GroupBy(s => s.AlbumId != "" ? s.AlbumId : s.Album, StringComparer.Ordinal)
            .Select(g => BuildAlbum(g.Key, g.First().Album, g.First().Artist, g.ToList()))
            .ToList();
    }

    public override async Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default)
    {
        var songs = await FetchSongsAsync(ct);
        return songs.OrderBy(_ => Guid.NewGuid()).Take(size).ToList();
    }

    public override async Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default)
    {
        var songs = await FetchSongsAsync(ct);
        var q = query.Trim();
        var hits = songs
            .Where(s => s.Title.Contains(q, StringComparison.OrdinalIgnoreCase)
                     || s.Artist.Contains(q, StringComparison.OrdinalIgnoreCase)
                     || s.Album.Contains(q, StringComparison.OrdinalIgnoreCase))
            .Take(count)
            .ToList();

        var result = new SearchResult { Songs = hits };
        result.Albums = hits
            .Where(s => s.AlbumId != "" || s.Album != "")
            .GroupBy(s => s.AlbumId != "" ? s.AlbumId : s.Album, StringComparer.Ordinal)
            .Select(g => BuildAlbum(g.Key, g.First().Album, g.First().Artist, g.ToList()))
            .ToList();
        result.Artists = hits
            .Where(s => s.Artist != "")
            .GroupBy(s => s.ArtistId != "" ? s.ArtistId : s.Artist, StringComparer.Ordinal)
            .Select(g => new Artist { Id = g.First().ArtistId, Name = g.First().Artist })
            .ToList();
        return result;
    }

    // ============ URL ============

    public override string GetStreamUrl(string songId)
        => $"{_baseUrl}/webapi/entry.cgi?api=SYNO.AudioStation.Song&version=1&method=download&library=shared&id={Uri.EscapeDataString(songId)}{AuthSuffix}";

    public override string GetDownloadUrl(string songId) => GetStreamUrl(songId);

    public override string GetCoverArtUrl(string coverArtId, int size = 300)
        => $"{_baseUrl}/webapi/entry.cgi?api=SYNO.AudioStation.CoverArt&version=1&method=getcover&library=shared&id={Uri.EscapeDataString(coverArtId)}{AuthSuffix}";
}
