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
/// 群晖 AudioStation（DSM）协议实现，按 StreamMusic 抓包文档 audioStation 接口对齐：
/// 认证走 entry.cgi（SYNO.API.Auth，POST body），曲库走 AudioStation/song.cgi、artist.cgi、album.cgi、cover.cgi、stream.cgi、search.cgi。
/// 关键差异（相对 Subsonic）：艺术家/专辑无独立 id，按 song_tag 的 album/album_artist 名称识别；
/// Song.list 为 POST body；部分响应字段是「字符串化的 JSON」需二次解析。
/// 解析失败静默降级（返回空/部分），不崩溃。
/// </summary>
public class AudioStationMusicService : MusicServiceBase
{
    private static readonly string PathEntry = "entry.cgi";            // SYNO.API.Auth
    private static readonly string PathSong = "AudioStation/song.cgi"; // SYNO.AudioStation.Song
    private static readonly string PathCover = "AudioStation/cover.cgi";
    private static readonly string PathStream = "AudioStation/stream.cgi";

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

    private string AuthSuffix => string.IsNullOrEmpty(_sid) ? "" : $"&_sid={_sid}";

    // ============ 连接与认证（entry.cgi POST body） ============

    public override async Task<bool> ConnectAsync()
    {
        var ct = CancellationToken.None;
        try
        {
            _baseUrl = ResolveBaseUrl();
            if (string.IsNullOrEmpty(_baseUrl))
                return false;

            var form = new Dictionary<string, string>
            {
                ["version"] = "6",
                ["api"] = "SYNO.API.Auth",
                ["method"] = "login",
                ["session"] = "audiostation",
                ["account"] = _config.Username,
                ["passwd"] = _config.Password,
            };

            var login = await PostJsonAsync(PathEntry, form, ct);
            if (login is { } j && j.TryGetProperty("data", out var data) && data.TryGetProperty("sid", out var sidEl))
                _sid = sidEl.GetString();
            return !string.IsNullOrEmpty(_sid);
        }
        catch
        {
            return false;
        }
    }

    private async Task<JsonElement?> PostJsonAsync(string path, Dictionary<string, string> form, CancellationToken ct)
    {
        try
        {
            var url = $"{_baseUrl}/webapi/{path}";
            var content = new FormUrlEncodedContent(form);
            var resp = await _http.PostAsync(url, content, ct);
            var body = await resp.Content.ReadAsStringAsync(ct);
            return ParseJson(body);
        }
        catch
        {
            return null;
        }
    }

    private async Task<JsonElement?> GetJsonAsync(string url, CancellationToken ct = default)
    {
        try
        {
            var body = await _http.GetStringAsync(url, ct);
            return ParseJson(body);
        }
        catch
        {
            return null;
        }
    }

    private static JsonElement? ParseJson(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.Clone();
        }
        catch
        {
            return null;
        }
    }

    // ============ 字段解析 ============

    private static string Str(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static int IntOf(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetInt32() : 0;

    /// <summary>部分节点返回「字符串化的 JSON」，这里对字符串型属性尝试二次解析。</summary>
    private static JsonElement Unwrap(JsonElement e)
    {
        if (e.ValueKind == JsonValueKind.String)
        {
            var s = e.GetString();
            if (!string.IsNullOrEmpty(s) && (s[0] == '{' || s[0] == '['))
            {
                try
                {
                    using var doc = JsonDocument.Parse(s);
                    return doc.RootElement.Clone();
                }
                catch { /* 保持原样 */ }
            }
        }
        return e;
    }

    /// <summary>轻量 JsonElement 访问器（含 unwrap）。</summary>
    private readonly struct JeObj
    {
        private readonly JsonElement _e;
        public JeObj(JsonElement? j) => _e = j ?? default;
        public bool Ok => _e.ValueKind == JsonValueKind.Object;
        public JeObj Get(string name) =>
            _e.ValueKind == JsonValueKind.Object && _e.TryGetProperty(name, out var v)
                ? new JeObj(Unwrap(v))
                : default;
        public string Str(string name) => Get(name).RawStr;
        public int Int(string name) => Get(name).RawInt;
        public string RawStr => _e.ValueKind == JsonValueKind.String ? _e.GetString() ?? "" : "";
        public int RawInt => _e.ValueKind == JsonValueKind.Number ? _e.GetInt32() : 0;
        public IEnumerable<JeObj> Array(string name)
        {
            if (_e.ValueKind != JsonValueKind.Object) yield break;
            if (!_e.TryGetProperty(name, out var v)) yield break;
            v = Unwrap(v);
            if (v.ValueKind == JsonValueKind.Array)
                foreach (var x in v.EnumerateArray())
                    yield return new JeObj(x);
        }
    }

    private static Song MapSong(JsonElement e)
    {
        var s = new Song { Id = Str(e, "id"), Title = Str(e, "title") };
        var add = new JeObj(Unwrap(GetProp(e, "additional")));
        var tag = add.Get("song_tag");
        var audio = add.Get("song_audio");

        s.Album = tag.Str("album");
        s.Artist = tag.Str("album_artist");
        if (s.Artist == "") s.Artist = tag.Str("artist");
        s.AlbumId = s.Album;       // AudioStation 无专辑 id，用名称
        s.ArtistId = s.Artist;     // 无艺术家 id，用名称
        s.Genre = tag.Str("genre");
        s.Year = tag.Int("year");
        s.Track = tag.Int("track");
        s.Duration = audio.Int("duration");
        return s;
    }

    private static JsonElement GetProp(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) ? Unwrap(v) : default;

    /// <summary>分页拉取共享曲库全部歌曲（结果缓存）。</summary>
    private async Task<List<Song>> FetchSongsAsync(CancellationToken ct)
    {
        if (_songCache is not null)
            return _songCache;

        var result = new List<Song>();
        try
        {
            int offset = 0;
            const int limit = 1000;
            while (true)
            {
                var form = new Dictionary<string, string>
                {
                    ["version"] = "3",
                    ["api"] = "SYNO.AudioStation.Song",
                    ["method"] = "list",
                    ["library"] = "all",
                    ["offset"] = offset.ToString(),
                    ["limit"] = limit.ToString(),
                    ["additional"] = "[\"song_tag\",\"song_audio\"]",
                    ["_sid"] = _sid ?? "",
                };
                var json = await PostJsonAsync(PathSong, form, ct);
                if (json is not { } j || !j.TryGetProperty("data", out var data))
                    break;
                var songs = GetProp(data, "songs");
                if (songs.ValueKind != JsonValueKind.Array)
                    break;
                foreach (var e2 in songs.EnumerateArray())
                    result.Add(MapSong(e2));

                var total = data.TryGetProperty("total", out var t) ? t.GetInt32() : 0;
                offset += limit;
                if (offset >= total)
                    break;
            }
        }
        catch { /* 返回已收集部分 */ }
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
        try
        {
            var songs = await FetchSongsAsync(ct);
            return songs
                .Where(s => s.Artist != "")
                .GroupBy(s => s.Artist, StringComparer.Ordinal)
                .Select(g => new Artist { Id = g.Key, Name = g.Key, AlbumCount = g.Select(x => x.Album).Where(x => x != "").Distinct().Count() })
                .GroupBy(a => char.IsLetter(a.Name[0]) ? char.ToUpper(a.Name[0]).ToString() : "#")
                .OrderBy(gi => gi.Key)
                .Select(gi => new ArtistIndex { Name = gi.Key, Artists = gi.ToList() })
                .ToList();
        }
        catch { return new List<ArtistIndex>(); }
    }

    public override async Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default)
    {
        try
        {
            var songs = await FetchSongsAsync(ct);
            return songs
                .Where(s => s.Album != "")
                .GroupBy(s => s.Album, StringComparer.Ordinal)
                .Select(g => BuildAlbum(g.Key, g.Key, g.First().Artist, g.ToList()))
                .Skip(offset).Take(size).ToList();
        }
        catch { return new List<Album>(); }
    }

    public override async Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
    {
        try
        {
            var songs = await FetchSongsAsync(ct).ConfigureAwait(false);
            var list = songs.Where(s => s.Album == id).ToList();
            if (list.Count == 0) return null;
            return BuildAlbum(id, id, list[0].Artist, list);
        }
        catch { return null; }
    }

    public override async Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
    {
        try
        {
            var songs = await FetchSongsAsync(ct);
            return songs
                .Where(s => s.Artist == artistId)
                .GroupBy(s => s.Album, StringComparer.Ordinal)
                .Select(g => BuildAlbum(g.Key, g.Key, artistId, g.ToList()))
                .ToList();
        }
        catch { return new List<Album>(); }
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
        result.Albums = hits.Where(s => s.Album != "")
            .GroupBy(s => s.Album, StringComparer.Ordinal)
            .Select(g => BuildAlbum(g.Key, g.Key, g.First().Artist, g.ToList())).ToList();
        result.Artists = hits.Where(s => s.Artist != "")
            .GroupBy(s => s.Artist, StringComparer.Ordinal)
            .Select(g => new Artist { Id = g.Key, Name = g.Key }).ToList();
        return result;
    }

    // ============ URL ============

    public override string GetStreamUrl(string songId)
        => $"{_baseUrl}/webapi/{PathStream}?version=2&api=SYNO.AudioStation.Stream&method=stream&id={Uri.EscapeDataString(songId)}{AuthSuffix}";

    public override string GetDownloadUrl(string songId) => GetStreamUrl(songId);

    public override string GetCoverArtUrl(string coverArtId, int size = 300)
        => $"{_baseUrl}/webapi/{PathCover}?version=1&api=SYNO.AudioStation.Cover&method=getsongcover&library=all&id={Uri.EscapeDataString(coverArtId)}{AuthSuffix}";
}
