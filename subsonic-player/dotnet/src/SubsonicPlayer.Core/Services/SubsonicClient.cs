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
        // ★ 必须给超时：HttpClient 默认超时是 **100 秒**，而 JS→C# 的 bridge.data 是**同步**调用
        //   （渲染进程主线程会一直等 C# 返回），所以服务器一挂（例如 music-tag-web 的
        //   SQLAlchemy 连接池耗尽时请求全部悬挂），界面就会被冻住好几分钟，
        //   而音频线程不受影响 ⇒ "一直显示正在重连、窗口卡住、但歌还能放"。
        //   15 秒足够慢 NAS 上的大列表查询（实测 getAlbumList2 size=500 约 0.75s），
        //   挂了也能很快失败并让界面恢复可用。
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
    }

    /// <summary>优先连内网，失败回退外网。</summary>
    public async Task<bool> ConnectAsync()
    {
        // ★ 关键：只有探测**成功**才把 _server 切过去。
        //
        // 老实现是「先把 _server 赋成正在试的地址，再探测」，于是内网探测一失败（哪怕是慢，
        // 不是真的断），_server 就永久停在最后试过的外网（Tailscale 100.x）地址上：
        //   · 之后每一次翻页/搜索都打向这个多半不可达的地址 → HttpClient 15s 超时
        //     → GetXmlAsync 再重连 + 重试一次 → 单个请求可以卡 ~35s；
        //   · 连接监听同时把 connected=false 推给前端 → 顶部横幅「正在重新连接服务器…」常驻；
        //   · 已建好的 BASS 流不受影响 → 「一直显示正在重连、窗口像卡住、歌还在放」。
        //
        // 现在失败时保留上一次可用的地址（_server 只在成功时更新），探测失败不再污染路由。
        //
        // 另外加一层「失败退避」：探测失败后的退避窗口内直接返回 false，不再逐个地址干等超时。
        // 否则曲库页一次预加载会并发发出 6 个请求，每个都各自 ConnectAsync 一次
        // （LAN 6s + WAN 6s），线程池瞬间被这些纯等待占满 → 整个界面连着「关闭」按钮一起失去响应。
        // 退避随连续失败次数递增（2→4→…→10s），首次失败只退避 2s，避免刚启动时误杀首屏。
        if (_lastProbeFailedAt is { } failedAt
            && (DateTime.UtcNow - failedAt).TotalSeconds < CurrentBackoffSeconds)
            return false;

        foreach (var srv in new[] { _lanServer, _wanServer })
        {
            if (string.IsNullOrEmpty(srv))
                continue;

            try
            {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(ProbeTimeoutSeconds));
                if (await PingOnAsync(srv, cts.Token))
                {
                    sw.Stop();
                    // 只有真正切换时才记一条：这就是"到底走内网还是走 Tailscale"的答案。
                    if (!string.Equals(_server, srv, StringComparison.Ordinal))
                        AppLog.Network($"线路切换 -> {Describe(srv)}（探测 {sw.ElapsedMilliseconds}ms）");
                    else
                        AppLog.Network($"线路保持 {Describe(srv)}（探测 {sw.ElapsedMilliseconds}ms）");

                    _server = srv;          // ← 只在成功时提交
                    _lastProbeFailedAt = null;
                    _probeFailures = 0;
                    return true;
                }

                AppLog.Network($"探测失败 {Describe(srv)}（{sw.ElapsedMilliseconds}ms，超时上限 {ProbeTimeoutSeconds}s）");
            }
            catch (Exception ex)
            {
                AppLog.Network($"探测异常 {Describe(srv)}: {ex.GetType().Name}: {ex.Message}");
                // 尝试下一个地址
            }
        }

        _probeFailures++;
        _lastProbeFailedAt = DateTime.UtcNow;
        AppLog.Network($"两条线路都不可达（连续失败 {_probeFailures} 次，退避 {CurrentBackoffSeconds}s）");
        return false;
    }

    /// <summary>线路名（内网 / 外网），日志里一眼看出走的是哪条。</summary>
    private string Describe(string server)
        => string.Equals(server, _lanServer, StringComparison.Ordinal) ? $"内网 {server}"
         : string.Equals(server, _wanServer, StringComparison.Ordinal) ? $"外网(WAN/Tailscale) {server}"
         : server;

    /// <summary>探测超时（秒）。取 3s 会误判慢 NAS：实测 getDiscoverMore 单请求可到 4.8s，
    /// ping 也慢，于是「只是慢」被当成「断了」并触发地址漂移。</summary>
    private const int ProbeTimeoutSeconds = 6;

    /// <summary>退避窗口上限（秒）。</summary>
    private const int ProbeBackoffMaxSeconds = 10;

    /// <summary>连续探测失败次数（成功后归零），用于自适应退避。</summary>
    private int _probeFailures;

    private DateTime? _lastProbeFailedAt;

    /// <summary>
    /// 当前退避秒数：2、4、6、8、10、10…（失败越多退避越久，避免每个页面请求都干等一轮超时）。
    /// 首次失败只退避 2s，保证「服务器只是刚启动/刚醒」时能很快恢复，不会把首屏数据卡死。
    /// </summary>
    private int CurrentBackoffSeconds => Math.Min(2 * Math.Max(1, _probeFailures), ProbeBackoffMaxSeconds);

    /// <summary>对指定地址发一次 ping：**不碰 _server**，也不走重连/重试（避免 ConnectAsync 递归）。</summary>
    private async Task<bool> PingOnAsync(string server, CancellationToken ct)
    {
        var doc = await GetXmlOnAsync(server, "ping", null, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    private string BuildUrl(string server, string endpoint, IReadOnlyDictionary<string, string>? extra = null)
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
        return $"{server}/rest/{endpoint}?{qs}";
    }

    private readonly AsyncLocal<int> _retryDepth = new();

    private async Task<XDocument> GetXmlAsync(string endpoint, IReadOnlyDictionary<string, string>? extra = null, CancellationToken ct = default)
    {
        try
        {
            return await GetXmlOnceAsync(endpoint, extra, ct);
        }
        catch (Exception ex) when (IsTransient(ex) && !ct.IsCancellationRequested && _retryDepth.Value < 1)
        {
            // 断线/网络错误（非服务器返回错误码）：先重新连接（重选可达地址），再重试一次。
            // _retryDepth 用 AsyncLocal 防止 ConnectAsync→Ping→GetXmlAsync 递归。
            _retryDepth.Value++;
            try
            {
                if (await ConnectAsync())
                    return await GetXmlOnceAsync(endpoint, extra, ct);
            }
            finally { _retryDepth.Value--; }
            throw;
        }
    }

    /// <summary>网络层瞬时错误才触发重连；带 StatusCode 的 HttpRequestException 是服务器返回的错误，不重连。</summary>
    private static bool IsTransient(Exception ex)
        => ex switch
        {
            HttpRequestException hre => hre.StatusCode is null,
            System.Net.Sockets.SocketException => true,
            System.IO.IOException => true,
            TaskCanceledException => true,
            OperationCanceledException => true,
            _ => false,
        };

    private async Task<XDocument> GetXmlOnceAsync(string endpoint, IReadOnlyDictionary<string, string>? extra = null, CancellationToken ct = default)
        // 每次请求开始时快照一次地址：中途即使连接监听成功切换了地址，本次请求也自始至终打同一台，
        // 不会出现「URL 用内网建、重试用外网」的错配。
        => await GetXmlOnAsync(_server, endpoint, extra, ct);

    /// <summary>对指定服务器地址发一次请求并解析 XML（不含重连/重试）。</summary>
    private async Task<XDocument> GetXmlOnAsync(string server, string endpoint, IReadOnlyDictionary<string, string>? extra, CancellationToken ct)
    {
        var url = BuildUrl(server, endpoint, extra);
        using var resp = await _http.GetAsync(url, ct);
        resp.EnsureSuccessStatusCode();

        // 强制按 UTF-8 读字节解码：部分服务器（如 Music Tag）返回 Content-Type: text/xml 且不带 charset，
        // .NET 的 ReadAsStringAsync 对无 charset 的 text/* 会回退 ISO-8859-1(Latin-1)，把 UTF-8 中文解成乱码。
        // 而响应体（子sonic-response）声明 encoding="utf-8"，故一律按 UTF-8 解码最稳。
        var bytes = await resp.Content.ReadAsByteArrayAsync(ct);
        var xml = System.Text.Encoding.UTF8.GetString(bytes);

        // Gonic 可能返回非法控制字符，清理后再解析（保留 \t \n \r）
        xml = System.Text.RegularExpressions.Regex.Replace(xml, @"[\x00-\x08\x0B\x0C\x0E-\x1F]", "");
        return XDocument.Parse(xml);
    }

    public async Task<bool> PingAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("ping", ct: ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>
    /// 随机歌曲（标准端点 <c>getRandomSongs</c>，**1 个请求**）。
    /// 服务端不支持时返回空列表，由调用方决定是否回退到"随机专辑 + 逐张取详情"的变通实现。
    /// </summary>
    public async Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default)
    {
        try
        {
            var doc = await GetXmlAsync("getRandomSongs",
                new Dictionary<string, string> { ["size"] = size.ToString() }, ct);
            var root = doc.Root;
            if (root is null || root.Attribute("status")?.Value != "ok")
                return new List<Song>();

            return doc.Descendants(root.Name.Namespace + "song").Select(ParseSong).ToList();
        }
        catch
        {
            // 端点不存在 / 解析失败：交给调用方回退
            return new List<Song>();
        }
    }

    /// <summary>
    /// 全库歌曲分页（<c>search3</c> 空查询）：**1 个请求**直接拿到第 offset 首起的 size 首。
    ///
    /// <para>引擎不提供"全部歌曲"端点时，客户端只能"翻专辑列表 + 逐张展开曲目"——
    /// 实测本库 newest 专辑大多只有 1 首歌，凑 10 首要展开二十多张专辑 = 二十多个串行请求
    /// （服务端单线程，≈4s）。而 <c>search3</c> 传空 query 就能当"全库歌曲"用（实测 20 首 / 361ms，
    /// 且支持 songOffset 分页），一下从 26 个请求降到 1 个。</para>
    /// <para>不支持的服务器返回空列表，调用方回退到"按专辑展开"。</para>
    /// </summary>
    public async Task<List<Song>> GetSongsPageAsync(int size, int offset, CancellationToken ct = default)
    {
        try
        {
            var doc = await GetXmlAsync("search3", new Dictionary<string, string>
            {
                ["query"] = "",
                ["songCount"] = size.ToString(),
                ["songOffset"] = offset.ToString(),
                ["albumCount"] = "0",
                ["artistCount"] = "0",
            }, ct);
            var root = doc.Root;
            if (root is null || root.Attribute("status")?.Value != "ok")
                return new List<Song>();

            return doc.Descendants(root.Name.Namespace + "song").Select(ParseSong).ToList();
        }
        catch
        {
            return new List<Song>();
        }
    }

    /// <summary>
    /// 相似歌曲（<c>getSimilarSongs2</c>，**1 个请求**换最多 count 首"和这首像的歌"）。
    /// 这是 Subsonic 官方的推荐端点 —— 比"取艺术家专辑再逐张展开曲目"（十几个请求）便宜一个数量级。
    /// 不支持时返回空列表。
    /// </summary>
    public async Task<List<Song>> GetSimilarSongsAsync(string songId, int count = 10, CancellationToken ct = default)
    {
        try
        {
            var doc = await GetXmlAsync("getSimilarSongs2", new Dictionary<string, string>
            {
                ["id"] = songId,
                ["count"] = count.ToString(),
            }, ct);
            var root = doc.Root;
            if (root is null || root.Attribute("status")?.Value != "ok")
                return new List<Song>();

            return doc.Descendants(root.Name.Namespace + "song").Select(ParseSong).ToList();
        }
        catch
        {
            return new List<Song>();
        }
    }

    public async Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)    {
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

    /// <summary>获取全部流派/风格（OpenSubsonic getGenres；服务端不支持时返回空列表）。</summary>
    public async Task<List<Genre>> GetGenresAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getGenres", ct: ct);
        var ns = doc.Root!.Name.Namespace;
        return doc.Descendants(ns + "genre")
            .Select(el => new Genre
            {
                // 流派名可能在 value 属性，也可能直接是元素文本（Music Tag Web/Music Tap 用文本）
                Name = (el.Attribute("value")?.Value ?? el.Value ?? "").Trim(),
                SongCount = Ai(el, "songCount"),
                AlbumCount = Ai(el, "albumCount"),
            })
            .Where(g => !string.IsNullOrWhiteSpace(g.Name))
            .ToList();
    }

    /// <summary>按流派/风格获取歌曲（getSongsByGenre）。</summary>
    public async Task<List<Song>> GetSongsByGenreAsync(string genre, int count = 100, int offset = 0, CancellationToken ct = default)
    {
        var extra = new Dictionary<string, string>
        {
            ["genre"] = genre,
            ["count"] = count.ToString(),
        };
        if (offset > 0)
            extra["offset"] = offset.ToString();

        var doc = await GetXmlAsync("getSongsByGenre", extra, ct);
        var ns = doc.Root!.Name.Namespace;
        return doc.Descendants(ns + "song").Select(ParseSong).ToList();
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

    /// <summary>向歌单添加歌曲。</summary>
    public async Task<bool> AddSongsToPlaylistAsync(string playlistId, IEnumerable<string> songIds, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("updatePlaylist", new Dictionary<string, string>
        {
            ["playlistId"] = playlistId,
            ["songIdToAdd"] = string.Join(",", songIds),
        }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>从歌单移除歌曲（按索引）。</summary>
    public async Task<bool> RemoveFromPlaylistAsync(string playlistId, IEnumerable<int> indexes, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("updatePlaylist", new Dictionary<string, string>
        {
            ["playlistId"] = playlistId,
            ["songIndexToRemove"] = string.Join(",", indexes),
        }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    public async Task<bool> ScrobbleAsync(string songId, bool submission = false, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("scrobble", new Dictionary<string, string> { ["id"] = songId, ["submission"] = submission ? "true" : "false" }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    public string GetCoverArtUrl(string coverArtId, int size = 300)
    {
        // getCoverArt 返回二进制图片，不能带 f 参数（部分服务器会因 f 返回错误内容）
        var q = new Dictionary<string, string>
        {
            ["u"] = _username,
            ["p"] = _password,
            ["v"] = ApiVersion,
            ["c"] = ClientName,
            ["id"] = coverArtId,
            ["size"] = size.ToString(),
        };
        var qs = string.Join("&", q.Select(kv => $"{kv.Key}={Uri.EscapeDataString(kv.Value)}"));
        return $"{_server}/rest/getCoverArt?{qs}";
    }

    public string GetStreamUrl(string songId, int? maxBitRate = null, string? format = null)
    {
        var extra = new Dictionary<string, string> { ["id"] = songId };
        if (maxBitRate is int br)
            extra["maxBitRate"] = br.ToString();
        if (!string.IsNullOrEmpty(format))
            extra["format"] = format;
        return BuildUrl(_server, "stream", extra);
    }

    /// <summary>收藏（星标）。id 可为歌曲/专辑/艺术家 id。</summary>
    public async Task<bool> StarAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("star", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>获取全部收藏歌曲（OpenSubsonic getStarred 的 song 部分）。解析失败或为空返回空列表。</summary>
    public async Task<List<Song>> GetStarredSongsAsync(CancellationToken ct = default)
    {
        try
        {
            var doc = await GetXmlAsync("getStarred", null, ct);
            var ns = doc.Root!.Name.Namespace;
            var starred = doc.Root.Element(ns + "starred");
            if (starred is null)
                return new List<Song>();
            return starred.Elements(ns + "song").Select(ParseSong).ToList();
        }
        catch
        {
            return new List<Song>();
        }
    }

    /// <summary>取消收藏。</summary>
    public async Task<bool> UnstarAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("unstar", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>评分（1–5）。</summary>
    public async Task<bool> SetRatingAsync(string id, int rating, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("setRating", new Dictionary<string, string>
        {
            ["id"] = id,
            ["rating"] = Math.Clamp(rating, 1, 5).ToString(),
        }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>按歌曲 id 获取歌词（OpenSubsonic getLyricsBySongId，失败时回退 getLyrics）。</summary>
    public async Task<Lyrics?> GetLyricsAsync(string artist, string title, string? songId = null, CancellationToken ct = default)
    {
        XDocument? doc = null;

        if (!string.IsNullOrEmpty(songId))
        {
            try
            {
                doc = await GetXmlAsync("getLyricsBySongId", new Dictionary<string, string> { ["id"] = songId! }, ct);
            }
            catch
            {
                doc = null;
            }
        }

        if (doc is null && !string.IsNullOrEmpty(artist) && !string.IsNullOrEmpty(title))
        {
            doc = await GetXmlAsync("getLyrics", new Dictionary<string, string>
            {
                ["artist"] = artist,
                ["title"] = title,
            }, ct);
        }

        return doc is null ? null : ParseLyrics(doc);
    }

    private static Lyrics? ParseLyrics(XDocument doc)
    {
        var ns = doc.Root!.Name.Namespace;
        var result = new Lyrics();

        // 结构化歌词（OpenSubsonic structuredLyrics）
        var structured = doc.Descendants(ns + "structuredLyrics").FirstOrDefault();
        if (structured is not null)
        {
            result.DisplayArtist = A(structured, "displayArtist");
            result.DisplayTitle = A(structured, "displayTitle");
            var offset = 0.0;
            var offsetRaw = A(structured, "offset");
            if (double.TryParse(offsetRaw, out var off))
                offset = off;

            foreach (var line in structured.Elements(ns + "line"))
            {
                var startRaw = A(line, "start");
                if (!double.TryParse(startRaw, out var startMs))
                    startMs = 0;
                result.Lines.Add(new LyricsLine
                {
                    StartSeconds = (startMs + offset) / 1000.0,
                    Text = line.Value ?? "",
                });
            }
            return result;
        }

        // 非结构化歌词
        var lyricsEl = doc.Descendants(ns + "lyrics").FirstOrDefault();
        if (lyricsEl is not null)
        {
            result.DisplayArtist = A(lyricsEl, "artist");
            result.DisplayTitle = A(lyricsEl, "title");
            result.Text = lyricsEl.Value ?? "";
            return result;
        }

        return null;
    }

    /// <summary>获取所有分享链接。</summary>
    public async Task<List<Share>> GetSharesAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getShares", ct: ct);
        var ns = doc.Root!.Name.Namespace;
        return doc.Descendants(ns + "share").Select(ParseShare).ToList();
    }

    /// <summary>创建分享链接（id 可为歌曲/专辑/歌单 id）。</summary>
    public async Task<List<Share>> CreateShareAsync(string id, string? description = null, CancellationToken ct = default)
    {
        var extra = new Dictionary<string, string> { ["id"] = id };
        if (!string.IsNullOrEmpty(description))
            extra["description"] = description;

        var doc = await GetXmlAsync("createShare", extra, ct);
        var ns = doc.Root!.Name.Namespace;
        return doc.Descendants(ns + "share").Select(ParseShare).ToList();
    }

    /// <summary>删除分享链接。</summary>
    public async Task<bool> DeleteShareAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("deleteShare", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    private static Share ParseShare(XElement el)
    {
        var ns = el.Name.Namespace;
        return new Share
        {
            Id = A(el, "id"),
            Url = A(el, "url"),
            Description = A(el, "description"),
            Username = A(el, "username"),
            VisitCount = Ai(el, "visitCount"),
            Songs = el.Elements(ns + "entry").Select(ParseSong).ToList(),
        };
    }

    /// <summary>获取播放书签。</summary>
    public async Task<List<Bookmark>> GetBookmarksAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getBookmarks", ct: ct);
        var ns = doc.Root!.Name.Namespace;
        return doc.Descendants(ns + "bookmark").Select(ParseBookmark).ToList();
    }

    /// <summary>创建播放书签（position 毫秒）。</summary>
    public async Task<bool> CreateBookmarkAsync(string id, long position, string? comment = null, CancellationToken ct = default)
    {
        var extra = new Dictionary<string, string>
        {
            ["id"] = id,
            ["position"] = position.ToString(),
        };
        if (!string.IsNullOrEmpty(comment))
            extra["comment"] = comment;

        var doc = await GetXmlAsync("createBookmark", extra, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>删除播放书签。</summary>
    public async Task<bool> DeleteBookmarkAsync(string id, CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("deleteBookmark", new Dictionary<string, string> { ["id"] = id }, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    private static Bookmark ParseBookmark(XElement el)
    {
        var ns = el.Name.Namespace;
        return new Bookmark
        {
            Position = long.TryParse(A(el, "position"), out var pos) ? pos : 0,
            Username = A(el, "username"),
            Comment = A(el, "comment"),
            Created = ParseDate(A(el, "created")),
            Changed = ParseDate(A(el, "changed")),
            Songs = el.Elements(ns + "entry").Select(ParseSong).ToList(),
        };
    }

    private static DateTime? ParseDate(string? s)
        => long.TryParse(s, out var ms) ? DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime : null;

    /// <summary>保存播放队列到云端（OpenSubsonic）。</summary>
    public async Task<bool> SavePlayQueueAsync(IEnumerable<string> songIds, string? current, long positionMs, CancellationToken ct = default)
    {
        var extra = new Dictionary<string, string>
        {
            ["id"] = string.Join(",", songIds),
            ["position"] = positionMs.ToString(),
        };
        if (!string.IsNullOrEmpty(current))
            extra["current"] = current!;

        var doc = await GetXmlAsync("savePlayQueue", extra, ct);
        return doc.Root?.Attribute("status")?.Value == "ok";
    }

    /// <summary>获取云端播放队列（OpenSubsonic）。返回 null 表示不支持或无队列。</summary>
    public async Task<List<Song>?> GetPlayQueueAsync(CancellationToken ct = default)
    {
        var doc = await GetXmlAsync("getPlayQueue", ct: ct);
        var ns = doc.Root!.Name.Namespace;
        var queue = doc.Descendants(ns + "playQueue").FirstOrDefault();
        if (queue is null)
            return null;
        return queue.Elements(ns + "entry").Select(ParseSong).ToList();
    }

    /// <summary>下载原文件 URL（download 端点）。</summary>
    public string GetDownloadUrl(string songId)
        => BuildUrl(_server, "download", new Dictionary<string, string> { ["id"] = songId });

    // ---- 解析辅助 ----

    private static string A(XElement el, string name) => Clean(el.Attribute(name)?.Value);

    private static int Ai(XElement el, string name)
        => int.TryParse(el.Attribute(name)?.Value, out var v) ? v : 0;

    /// <summary>解析 ReplayGain 数值属性（dB/峰值）；缺失或非法返回 double.NaN。</summary>
    private static double Ad(XElement el, string name)
        => double.TryParse(el.Attribute(name)?.Value, System.Globalization.NumberStyles.Float,
               System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : double.NaN;

    /// <summary>清理 Gonic 的脏数据：Go 内存地址（0x…）与 &lt;nil&gt;。</summary>
    private static string Clean(string? s)
    {
        if (string.IsNullOrEmpty(s))
            return "";
        if (s.StartsWith("0x", StringComparison.Ordinal))
            return "";
        if (s.Equals("nil", StringComparison.OrdinalIgnoreCase) ||
            s.Equals("<nil>", StringComparison.OrdinalIgnoreCase))
            return "";
        return s;
    }

    private static Album ParseAlbum(XElement el) => new()
    {
        Id = A(el, "id"),
        Name = A(el, "name"),
        Title = A(el, "title"),
        Artist = A(el, "artist"),
        // 专辑响应里其实带着 artistId（子元素也有 <artist id="...">），只是以前没读。
        // 读它，专辑页/专辑卡上的歌手名才能点进那个艺术家。
        ArtistId = A(el, "artistId"),
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
        Genre = A(el, "genre"),
        // ReplayGain（Navidrome/Gonic 等 OpenSubsonic 服务器在 <song> 上提供；无则 NaN）
        ReplayGainTrackGain = Ad(el, "replayGainTrackGain"),
        ReplayGainAlbumGain = Ad(el, "replayGainAlbumGain"),
        ReplayGainTrackPeak = Ad(el, "replayGainTrackPeak"),
    };
}
