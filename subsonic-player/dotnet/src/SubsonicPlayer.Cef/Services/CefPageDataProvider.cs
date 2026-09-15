using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.Services;

/// <summary>
/// 页面数据提供者：把 Core 服务层的数据转成 JSON 友好的 DTO，供 CEF 侧 JS 渲染。
/// 封面一律返回 GetCoverArtUrl 的完整 URL（带认证参数），JS 直接用 &lt;img&gt;。
/// </summary>
public sealed class CefPageDataProvider
{
    private IMusicService? Music => AppServices.Music;

    // 会话级连接缓存：按「Music 实例」记录已连接的服务。首次连接成功后在本次运行内复用，
    // 避免每次翻页都做 3s 超时探测。
    // 注意：不能用服务 id 作缓存键——保存/切换服务会重建 Music 实例（id 可能不变），
    // 若仅按 id 判断会误以为「已连接」，导致新实例（尚未 ConnectAsync、_server 未定）被直接使用。
    private IMusicService? _connectedMusic;

    // 列表数据短时缓存。
    // ★ TTL 从 60s 提到 5 分钟：服务端串行处理、每个请求 ~80~180ms，而艺术家索引一次就是 148KB。
    //   60s 的 TTL 意味着只要在页面上多待一会儿就会反复重拉整份索引（每次 ≈2 个请求的代价）。
    //   曲库不会每分钟变，5 分钟足够新鲜；换服务器/改配置走的是 ResetServiceCaches（按服务 id 分键）。
    private readonly TtlCache<string, List<ArtistIndex>> _artistsCache = new(TimeSpan.FromMinutes(5));
    private readonly TtlCache<string, List<Album>> _albumsCache = new(TimeSpan.FromMinutes(5));
    private readonly TtlCache<string, List<Playlist>> _playlistsCache = new(TimeSpan.FromMinutes(5));
    private readonly TtlCache<string, List<Genre>> _genresCache = new(TimeSpan.FromMinutes(10));

    // 发现页结果缓存：随机推荐 = 1+10 个请求、智能推荐 = 20+ 个请求，都是"重算一次好几秒"的量级。
    // 切走再切回发现页不该重算（服务端串行，重算就是干等）。
    private readonly TtlCache<string, object?> _discoverQuickCache = new(TimeSpan.FromMinutes(3));
    private readonly TtlCache<string, object?> _discoverMoreCache = new(TimeSpan.FromMinutes(5));

    // ============ 歌曲分页：先「按专辑展开」，再「按歌切片」 ============

    /// <summary>歌曲列表每页多少首。前端分页器与这里必须一致。</summary>
    public const int SongPageSize = 10;

    /// <summary>专辑列表每页多少张。分页器画总页数时也用它。</summary>
    public const int AlbumPageSize = 20;

    /// <summary>
    /// 每次多展开多少张专辑（**仅回退路径用**：服务端没有"全库歌曲"端点时）。
    ///
    /// <para>★ 本服务端已被 search3 快路径覆盖（见 GetSongsPage），这里是兜底。
    /// 注意本库 newest 专辑大多只有 1 首歌，所以"一次抓多少张"直接决定要循环几轮；
    /// 抓多一点（25）能让专辑列表请求只发一次。</para>
    /// </summary>
    private const int AlbumBatchSize = 25;

    /// <summary>
    /// 「扁平歌曲列表」累加器。
    ///
    /// <para>Gonic / Music Tag 都没有「全部歌曲」端点，只能翻专辑列表再逐张展开曲目。以前是
    /// 「按专辑翻页」——一页 20 张专辑展开出来多少首全看运气（实测 21 首），于是分页器的
    /// 「一页」根本不是一页歌。这里改成**按需增长**的一份扁平列表：要第 N 页就长到第 N 页
    /// 所需的首数，然后按歌切片 —— 每页就是实打实的 <see cref="SongPageSize"/> 首。</para>
    /// </summary>
    private sealed class SongFlattener
    {
        public readonly List<Song> Songs = new();

        /// <summary>已收录的曲目 id（合辑里同一首歌会出现在多张专辑 ⇒ 只留第一次）。</summary>
        public readonly HashSet<string> SeenIds = new(StringComparer.Ordinal);

        /// <summary>已经从专辑列表里展开过的专辑张数（下次从这里继续）。</summary>
        public int AlbumsExpanded;

        /// <summary>专辑列表已经见底（再往后没有专辑了）。</summary>
        public bool AlbumsExhausted;

        /// <summary>歌手页用：该歌手的专辑列表，只取一次。</summary>
        public List<Album>? ArtistAlbums;

        /// <summary>去重后追加（id 为空的照收 —— 不能把所有空 id 当成同一首）。</summary>
        public void Add(IEnumerable<Song> songs)
        {
            foreach (var s in songs)
            {
                if (!string.IsNullOrEmpty(s.Id) && !SeenIds.Add(s.Id)) continue;
                Songs.Add(s);
            }
        }
    }

    private readonly TtlCache<string, SongFlattener> _flatSongsCache = new(TimeSpan.FromMinutes(10));

    /// <summary>累加器是可变对象，翻页可能并发（预加载 + 用户点击）⇒ 增长过程串行化。</summary>
    private readonly SemaphoreSlim _flatSongsLock = new(1, 1);

    private string ServiceKey => AppServices.GetCurrentService()?.Id ?? "";

    /// <summary>
    /// 把扁平列表长到至少 <paramref name="need"/> 首，返回累加器本身（调用方还要看 total/是否见底）。
    /// </summary>
    private async Task<SongFlattener> GrowFlatSongsAsync(
        string key, int need, Func<SongFlattener, Task<List<Album>>> nextAlbumBatch)
    {
        await _flatSongsLock.WaitAsync();
        try
        {
            if (!_flatSongsCache.TryGet(key, out var acc))
            {
                acc = new SongFlattener();
                _flatSongsCache.Set(key, acc);
            }

            while (acc.Songs.Count < need && !acc.AlbumsExhausted)
            {
                var batch = await nextAlbumBatch(acc);
                if (batch.Count == 0) { acc.AlbumsExhausted = true; break; }

                var details = await WhenAllLimitedAsync(batch, a => Music!.GetAlbumAsync(a.Id), 6);
                foreach (var d in details)
                    if (d is not null && d.Songs.Count > 0)
                        acc.Add(d.Songs);

                acc.AlbumsExpanded += batch.Count;
                // 这一把没取满 ⇒ 专辑列表见底了。
                if (batch.Count < AlbumBatchSize) acc.AlbumsExhausted = true;
            }

            _flatSongsCache.Set(key, acc);   // 同一个对象，重新 Set 只是把 TTL 续上
            return acc;
        }
        finally
        {
            _flatSongsLock.Release();
        }
    }

    /// <summary>曲库「全部歌曲」的扁平列表（专辑列表按 newest 分批取）。</summary>
    private Task<SongFlattener> GetLibrarySongsFlatAsync(int need) =>
        GrowFlatSongsAsync($"lib|{ServiceKey}", need,
            acc => GetAlbumListCachedAsync("newest", AlbumBatchSize, acc.AlbumsExpanded));

    /// <summary>某个歌手的扁平歌曲列表（专辑列表只取一次，之后分批展开）。</summary>
    private Task<SongFlattener> GetArtistSongsFlatAsync(string artistId, int need) =>
        GrowFlatSongsAsync($"art|{ServiceKey}|{artistId}", need, async acc =>
        {
            acc.ArtistAlbums ??= await Music!.GetArtistAlbumsAsync(artistId);
            return acc.ArtistAlbums.Skip(acc.AlbumsExpanded).Take(AlbumBatchSize).ToList();
        });

    private async Task<List<ArtistIndex>> GetArtistsCachedAsync()
    {
        var key = AppServices.GetCurrentService()?.Id ?? "";
        if (_artistsCache.TryGet(key, out var v)) return v;
        var list = await Music!.GetArtistsAsync();
        _artistsCache.Set(key, list);
        return list;
    }

    private async Task<List<Album>> GetAlbumListCachedAsync(string type, int size, int offset)
    {
        var key = $"{AppServices.GetCurrentService()?.Id}|{type}|{size}|{offset}";
        if (_albumsCache.TryGet(key, out var v)) return v;
        var list = await Music!.GetAlbumListAsync(type, size, offset);
        _albumsCache.Set(key, list);
        return list;
    }

    private async Task<List<Playlist>> GetPlaylistsCachedAsync()
    {
        var key = AppServices.GetCurrentService()?.Id ?? "";
        if (_playlistsCache.TryGet(key, out var v)) return v;
        var list = await Music!.GetPlaylistsAsync();
        _playlistsCache.Set(key, list);
        return list;
    }

    /// <summary>限制并发地展开多个项目（避免 N+1 请求在慢网下一起打出去拖垮网络）。</summary>
    private static async Task<T[]> WhenAllLimitedAsync<TItem, T>(IReadOnlyList<TItem> items, Func<TItem, Task<T>> fetch, int maxConcurrency = 4)
    {
        var results = new T[items.Count];
        using var sem = new SemaphoreSlim(maxConcurrency);
        var runners = Enumerable.Range(0, items.Count).Select(async i =>
        {
            await sem.WaitAsync();
            try { results[i] = await fetch(items[i]); }
            finally { sem.Release(); }
        });
        await Task.WhenAll(runners);
        return results;
    }

    private async Task<bool> EnsureConnectedAsync()
    {
        var music = Music;
        if (music is null)
            return false;

        // 用「实例引用」判断是否已连接：保存/切换服务会重建 Music，若换成了新实例则必须重新连接。
        if (ReferenceEquals(_connectedMusic, music))
            return true;

        // ★ 单飞（single-flight）：服务端是单线程串行处理，启动时 6 个页面请求同时进来，
        //   若每个都各自发一次连接探测，就是让服务端排 6 次队（每请求 ~80ms，串行叠加 ≈0.5s，
        //   而且这 0.5s 会平摊到每个请求的耗时上）。共用一个在飞的探测任务，只探一次。
        if (_connectTask is null || !ReferenceEquals(_connectTaskFor, music))
        {
            _connectTaskFor = music;
            _connectTask = ConnectOnceAsync(music);
        }
        return await _connectTask;
    }

    private Task<bool>? _connectTask;
    private IMusicService? _connectTaskFor;

    private async Task<bool> ConnectOnceAsync(IMusicService music)
    {
        try
        {
            var ok = await music.ConnectAsync();
            if (ok)
                _connectedMusic = music;
            return ok;
        }
        catch
        {
            return false;
        }
        finally
        {
            _connectTask = null;
            _connectTaskFor = null;
        }
    }

    private string? CoverFor(string? coverArtId, int size = 300)
        => !string.IsNullOrEmpty(coverArtId) && Music is not null
            ? Music.GetCoverArtUrl(coverArtId, size)
            : null;

    // ============ 发现页 ============

    /// <summary>快速版：只返回随机歌曲（最快），用于首屏秒出。</summary>
    public async Task<object?> GetDiscoverQuick()
    {
        var music = Music;
        if (music is null)
            return new { status = "未配置音乐服务" };

        // 命中缓存直接返回：这一项本身要 1+10 个请求（服务端串行 ≈2s），切回发现页不该重算
        if (_discoverQuickCache.TryGet(ServiceKey, out var cachedQuick) && cachedQuick is not null)
            return cachedQuick;
        try
        {
            // 先确保连接：若服务不可达则 3s 内快速失败，避免默认 HttpClient 100s 超时把首屏拖死
            //（切换/保存服务重建了 Music 实例后，这里负责把新实例接到正确的服务器上）。
            if (!await EnsureConnectedAsync())
                return new { status = "连接失败" };

            var songs = await music.GetRandomSongsAsync(10);
            if (songs.Count == 0)
                return new { status = "暂无歌曲" };
            var result = new
            {
                status = "",
                randomSongs = songs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
            };
            // 只缓存真正拿到歌的结果（失败/空结果不缓存，否则一次抖动会粘 3 分钟）
            _discoverQuickCache.Set(ServiceKey, result);
            return result;
        }
        catch (Exception ex)
        {
            Log($"GetDiscoverQuick 异常: {ex.Message}");
            return new { status = "连接失败" };
        }
    }

    /// <summary>发现页补充区块（智能推荐 + 专辑，异步填充不阻塞首屏）。</summary>
    public async Task<object?> GetDiscoverMore()
    {
        var music = Music;
        if (music is null) return new { recommendations = Array.Empty<object>(), newestAlbums = Array.Empty<object>() };

        // 命中缓存直接返回：智能推荐要 20+ 个请求（服务端串行 ≈3.4s），切回发现页不该重算
        if (_discoverMoreCache.TryGet(ServiceKey, out var cachedMore) && cachedMore is not null)
            return cachedMore;
        try
        {
            if (!await EnsureConnectedAsync())
                return new { recommendations = Array.Empty<object>(), newestAlbums = Array.Empty<object>() };

            var recommendTask = Task.Run(() => RecommendationService.GetRecommendationsAsync(music, 10));
            var newestTask = music.GetAlbumListAsync("newest", 10);
            var frequentTask = music.GetAlbumListAsync("frequent", 10);
            var highestTask = music.GetAlbumListAsync("highest", 10);
            await Task.WhenAll(recommendTask, newestTask, frequentTask, highestTask);
            var recs = recommendTask.Result;
            var result = new
            {
                recommendations = recs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
                hasRecommendations = recs.Count > 0,
                newestAlbums = newestTask.Result.Select(a => AlbumDto(a)).ToArray(),
                frequentAlbums = frequentTask.Result.Select(a => AlbumDto(a)).ToArray(),
                highestAlbums = highestTask.Result.Select(a => AlbumDto(a)).ToArray(),
            };
            // 只在真的拿到内容时缓存，避免一次失败粘住 5 分钟
            if (result.newestAlbums.Length > 0 || result.recommendations.Length > 0)
                _discoverMoreCache.Set(ServiceKey, result);
            return result;
        }
        catch
        {
            return new { recommendations = Array.Empty<object>(), newestAlbums = Array.Empty<object>() };
        }
    }

    public async Task<object?> GetDiscoverPage()
    {
        var music = Music;
        if (music is null)
            return new { status = "未配置音乐服务" };

        try
        {
            Log($"ConnectAsync 开始...");
            var ok = await EnsureConnectedAsync();
            Log($"ConnectAsync 结果: {ok}");
            if (!ok)
                return new { status = "连接失败" };

            var randomSongsTask = music.GetRandomSongsAsync(10);
            var newestTask = music.GetAlbumListAsync("newest", 10);
            var frequentTask = music.GetAlbumListAsync("frequent", 10);
            var highestTask = music.GetAlbumListAsync("highest", 10);
            var recommendTask = Task.Run(() => RecommendationService.GetRecommendationsAsync(music, 10));

            await Task.WhenAll(randomSongsTask, newestTask, frequentTask, highestTask, recommendTask);

            Log($"数据: random={randomSongsTask.Result.Count} newest={newestTask.Result.Count} frequent={frequentTask.Result.Count} highest={highestTask.Result.Count} rec={recommendTask.Result.Count}");

            return new
            {
                status = "",
                randomSongs = randomSongsTask.Result.Select((s, i) => SongDto(s, i + 1)).ToArray(),
                recommendations = recommendTask.Result.Select((s, i) => SongDto(s, i + 1)).ToArray(),
                newestAlbums = newestTask.Result.Select(a => AlbumDto(a)).ToArray(),
                frequentAlbums = frequentTask.Result.Select(a => AlbumDto(a)).ToArray(),
                highestAlbums = highestTask.Result.Select(a => AlbumDto(a)).ToArray(),
                hasRecommendations = recommendTask.Result.Count > 0,
            };
        }
        catch (Exception ex)
        {
            Log($"GetDiscoverPage 异常: {ex}");
            return new { status = "加载失败" };
        }
    }

    private static void Log(string msg)
    {
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            System.IO.Directory.CreateDirectory(dir);
            System.IO.File.AppendAllText(
                System.IO.Path.Combine(dir, "provider.log"),
                $"[{DateTime.Now:HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { }
    }

    public async Task<object?> RefreshRandomSongs()
    {
        var music = Music;
        if (music is null) return null;
        var songs = await music.GetRandomSongsAsync(10);
        return songs.Select((s, i) => SongDto(s, i + 1)).ToArray();
    }

    // ============ 专辑 ============

    public async Task<object?> GetAlbumsPage(int page, int pageSize = AlbumPageSize)
    {
        var music = Music;
        if (music is null)
            return new { status = "未配置音乐服务" };
        if (!await EnsureConnectedAsync())
            return new { status = "连接失败" };

        var albums = await GetAlbumListCachedAsync("alphabetical", pageSize, (page - 1) * pageSize);
        var total = await GetAlbumTotalAsync();
        return new
        {
            albums = albums.Select(a => AlbumDto(a)).ToArray(),
            currentPage = page,
            pageSize,
            // 服务端的 getAlbumList2 不返回总数，但**专辑数可以从艺术家索引算出来**
            // （每个艺术家带 albumCount，求和 == 逐块走 getAlbumList2 的专辑总数，实测 3502 = 3502），
            // 而艺术家索引本来就整份取回并缓存 ⇒ 总页数是确切的，分页器一进来就能把页码画全。
            total,
            totalPages = total == 0 ? 1 : (int)Math.Ceiling(total / (double)pageSize),
            hasMore = albums.Count >= pageSize,
        };
    }

    /// <summary>
    /// 曲库规模（分页器要"一进来就把页码画全"，就必须知道总数）。
    /// 实测本服务：艺术家索引 sum(albumCount) = 3502，与逐块走 getAlbumList2 得到的专辑数**完全一致**，
    /// 所以专辑数用索引算（零额外请求）。歌曲数没有这样的捷径（艺术家不带 songCount），
    /// 只能逐块走专辑列表累加 songCount（500/块，约 8 次请求、1MB 上下）⇒ 缓存 30 分钟。
    /// </summary>
    public async Task<object?> GetPagerTotals()
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { albums = 0, songs = 0, albumPages = 0, songPages = 0 };

        var albums = await GetAlbumTotalAsync();
        var songs = await GetSongTotalAsync();
        return new
        {
            albums,
            songs,
            albumPages = albums == 0 ? 1 : (int)Math.Ceiling(albums / (double)AlbumPageSize),
            songPages = songs == 0 ? 1 : (int)Math.Ceiling(songs / (double)SongPageSize),
        };
    }

    private readonly TtlCache<string, int> _albumTotalCache = new(TimeSpan.FromMinutes(30));
    private readonly TtlCache<string, int> _songTotalCache = new(TimeSpan.FromMinutes(30));

    /// <summary>专辑总数：艺术家索引里 albumCount 求和（索引本来就整份取回并缓存）。</summary>
    private async Task<int> GetAlbumTotalAsync()
    {
        var key = ServiceKey;
        if (_albumTotalCache.TryGet(key, out var cached)) return cached;
        try
        {
            var indexes = await GetArtistsCachedAsync();
            var total = indexes.Sum(i => i.Artists.Sum(a => a.AlbumCount));
            if (total > 0) _albumTotalCache.Set(key, total);
            return total;
        }
        catch { return 0; }
    }

    /// <summary>歌曲总数：逐块走专辑列表，把每张专辑的 SongCount 加起来。</summary>
    private async Task<int> GetSongTotalAsync()
    {
        var key = ServiceKey;
        if (_songTotalCache.TryGet(key, out var cached)) return cached;
        try
        {
            var total = 0;
            const int block = 500;              // 服务端单次上限就是 500（要更多也只给 500）
            for (var offset = 0; offset <= 100_000; offset += block)
            {
                var list = await GetAlbumListCachedAsync("alphabetical", block, offset);
                foreach (var a in list) total += a.SongCount;
                if (list.Count < block) break;
            }
            if (total > 0) _songTotalCache.Set(key, total);
            return total;
        }
        catch { return 0; }
    }

    public async Task<object?> GetAlbumDetail(string id)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return null;

        var album = await music.GetAlbumAsync(id);
        if (album is null) return null;

        return new
        {
            id = album.Id,
            name = album.Name,
            artist = album.Artist,
            coverUrl = CoverFor(album.CoverArtId, 400),
            year = album.Year,
            songCount = album.SongCount,
            durationText = FormatTime(album.Duration),
            isFavorite = AppServices.Favorites.IsFavorite(album.Id),
            songs = album.Songs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
        };
    }

    // ============ 艺术家 ============

    public async Task<object?> GetArtistsPage(int page, int pageSize = 100)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { artists = Array.Empty<object>(), groups = Array.Empty<object>(), status = "未配置/连接失败" };

        var indexes = await GetArtistsCachedAsync();

        // 分组导航：字母 + 起始偏移（供右侧 A-Z 快速跳转）
        var groups = new List<Dictionary<string, object?>>();
        var offset = 0;
        foreach (var idx in indexes)
        {
            groups.Add(new Dictionary<string, object?>
            {
                ["label"] = idx.Name,
                ["offset"] = offset,
            });
            offset += idx.Artists.Count;
        }

        var artists = indexes
            .SelectMany(idx => idx.Artists)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .Select(a => new Dictionary<string, object?>
            {
                ["id"] = a.Id,
                ["name"] = a.Name,
                ["albumCount"] = a.AlbumCount,
            })
            .ToArray();

        var total = offset;
        var totalPages = total == 0 ? 1 : (int)Math.Ceiling(total / (double)pageSize);
        return new
        {
            artists,
            groups = groups.ToArray(),
            currentPage = page,
            pageSize,
            total,
            // 艺术家索引是完整取回的，所以这里能给出**确切**的总页数（专辑/歌曲两页只能给 hasMore，
            // 因为服务端分页不返回总数——分页器对两种情况都用了同一套渲染）。
            totalPages,
            // 有了确切总页数也照样给 hasMore：分页器靠它决定「下一页」是不是灰的，
            // 少了这个字段最后一页的「下一页」会亮着，点进去是空页。
            hasMore = page < totalPages,
        };
    }

    /// <summary>
    /// 按艺术家 id 取详情。
    /// </summary>
    public async Task<object?> GetArtistDetail(string id)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return null;

        var all = await GetArtistsCachedAsync();
        var artist = all.SelectMany(i => i.Artists).FirstOrDefault(a => a.Id == id);
        if (artist is null) return null;

        var albums = await music.GetArtistAlbumsAsync(id);

        // 第一页单曲走**和翻页时同一条路**（同一个扁平累加器），这样"进页面看到的单曲"
        // 与"翻回第 1 页"逐首一致 —— 各算各的迟早会出现两套结果。
        var acc = await GetArtistSongsFlatAsync(id, SongPageSize + 1);
        var songs = acc.Songs.Take(SongPageSize).ToList();

        // 总曲目数用专辑自带的 SongCount 求和：不必把每张专辑都拉下来就知道"一共多少首"。
        var known = albums.Sum(a => a.SongCount);
        var songTotal = acc.AlbumsExhausted ? acc.Songs.Count : Math.Max(known, acc.Songs.Count);
        var songPages = songTotal == 0 ? 1 : (int)Math.Ceiling(songTotal / (double)SongPageSize);

        return new
        {
            id = artist.Id,
            name = artist.Name,
            albumCount = albums.Count,
            songTotal,
            songPages,
            songs = songs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
            albums = albums.Select(a => AlbumDto(a)).ToArray(),
        };
    }

    /// <summary>获取艺术家的代表专辑封面（作为列表头像兜底）。</summary>
    public async Task<object?> GetArtistCover(string id)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { coverUrl = "" };
        try
        {
            var albums = await music.GetArtistAlbumsAsync(id);
            var cover = albums.FirstOrDefault(a => !string.IsNullOrEmpty(a.CoverArtId));
            return new { coverUrl = cover is not null ? CoverFor(cover.CoverArtId, 200) : "" };
        }
        catch
        {
            return new { coverUrl = "" };
        }
    }

    /// <summary>按艺术家名取一张头像（网易云兜底；服务端一般无艺术家照片）。</summary>
    public async Task<object?> GetArtistPhoto(string name)
    {
        var url = await ArtistImageService.GetPhotoAsync(name);
        return new { photoUrl = url };
    }

    // ============ 歌曲 ============

    /// <summary>
    /// 曲库「全部歌曲」，**按歌分页**（每页 <see cref="SongPageSize"/> 首）。
    ///
    /// <para>服务端没有「全部歌曲」端点（Gonic 有 getRandomSongs/getAlbumList2，没有全量歌单），
    /// 所以做法是「按 newest 专辑列表逐批展开成扁平列表，再按歌切片」。展开始终从第 0 张专辑
    /// 开始累积（<see cref="SongFlattener"/>），不是从"第 (page-1)*10 首所在的专辑"猜 —— 猜不出，
    /// 因为只有展开过才知道每张专辑有几首。</para>
    /// </summary>
    public async Task<object?> GetSongsPage(int page, int pageSize = SongPageSize)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { songs = Array.Empty<object>(), status = "未配置/连接失败" };

        var skip = (page - 1) * pageSize;

        // ★ 快路径：服务端支持"全库歌曲分页"的话（Subsonic search3 空查询，实测本服务端可用），
        //   1 个请求就能取回第 skip 首起的 pageSize+1 首。走老路要展开二十多张专辑
        //   （本库 newest 专辑大多只有 1 首，凑 10 首要 26+ 个串行请求 ≈4s）。
        var direct = await music.GetSongsPageAsync(pageSize + 1, skip);
        if (direct.Count > 0)
        {
            var pageSongs = direct.Take(pageSize).ToList();
            return new
            {
                songs = pageSongs.Select((s, i) => SongDto(s, skip + i + 1)).ToArray(),
                currentPage = page,
                pageSize,
                hasMore = direct.Count > pageSize,
                expanded = skip + pageSongs.Count,
                albumsExpanded = 0,
                source = "search3",
            };
        }

        // 回退路径：不支持全库歌曲端点时，按专辑展开累加（老实现）。
        // 多要一首：有第 skip+pageSize+1 首才说明还有下一页。
        var acc = await GetLibrarySongsFlatAsync(skip + pageSize + 1);
        var songs = acc.Songs.Skip(skip).Take(pageSize).ToList();

        return new
        {
            songs = songs.Select((s, i) => SongDto(s, skip + i + 1)).ToArray(),
            currentPage = page,
            pageSize,
            hasMore = acc.Songs.Count > skip + pageSize,
            // 全库总首数要展开完所有专辑才知道，不猜 —— 分页器只靠 hasMore 就能工作。
            expanded = acc.Songs.Count,
            albumsExpanded = acc.AlbumsExpanded,
        };
    }

    /// <summary>
    /// 一个艺术家的单曲，**按歌分页**（每页 <see cref="SongPageSize"/> 首）。
    ///
    /// <para>展开是按专辑走的（服务端只有专辑级端点），但切片按歌 —— 页大小因此是确定的，
    /// 不会出现「第 3 页只有 4 首」。</para>
    /// </summary>
    public async Task<object?> GetArtistSongsPage(string artistId, int page, int pageSize = SongPageSize)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { songs = Array.Empty<object>(), status = "未配置/连接失败" };

        var skip = (page - 1) * pageSize;
        // 多要一首：有第 skip+pageSize+1 首才说明还有下一页。
        var acc = await GetArtistSongsFlatAsync(artistId, skip + pageSize + 1);
        var songs = acc.Songs.Skip(skip).Take(pageSize).ToList();

        // 总数：专辑自带 SongCount 求和（不必把每张专辑都拉下来）。个别服务端 SongCount 为 0，
        // 那就退化成"当前已展开的首数"，宁少不多 —— 分页器对不足的 totalPages 有 count===0 兜底。
        var known = acc.ArtistAlbums?.Sum(a => a.SongCount) ?? 0;
        var total = acc.AlbumsExhausted ? acc.Songs.Count : Math.Max(known, acc.Songs.Count);

        return new
        {
            songs = songs.Select((s, i) => SongDto(s, skip + i + 1)).ToArray(),
            currentPage = page,
            pageSize,
            total,
            totalPages = total == 0 ? 1 : (int)Math.Ceiling(total / (double)pageSize),
            hasMore = acc.Songs.Count > skip + pageSize,
        };
    }

    // ============ 歌单 ============

    public async Task<object?> GetPlaylists()
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { playlists = Array.Empty<object>() };

        var playlists = await GetPlaylistsCachedAsync();
        return new
        {
            playlists = playlists.Select(p => new Dictionary<string, object?>
            {
                ["id"] = p.Id,
                ["name"] = p.Name,
                ["songCount"] = p.SongCount,
                ["coverUrl"] = CoverFor(p.CoverArtId, 200),
            }).ToArray(),
        };
    }

    public async Task<object?> GetPlaylistDetail(string id)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return null;

        var playlist = await music.GetPlaylistAsync(id);
        if (playlist is null) return null;

        return new
        {
            id = playlist.Id,
            name = playlist.Name,
            songCountText = $"{playlist.SongCount} 首",
            coverUrl = CoverFor(playlist.CoverArtId, 400),
            songs = playlist.Songs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
        };
    }

    // ============ 风格 / 流派 ============

    /// <summary>风格（流派）列表，按歌曲数降序。服务端不支持 getGenres 时 supported=false（前端给提示）。</summary>
    public async Task<object?> GetGenres()
    {
        var music = Music;
        if (music is null)
            return new { genres = Array.Empty<object>(), supported = false };

        if (!await EnsureConnectedAsync())
            return new { genres = Array.Empty<object>(), supported = false, status = "连接失败" };

        List<Genre> genres;
        try
        {
            var key = AppServices.GetCurrentService()?.Id ?? "";
            if (_genresCache.TryGet(key, out var cached))
            {
                genres = cached;
            }
            else
            {
                genres = await music.GetGenresAsync();
                _genresCache.Set(key, genres);
            }
        }
        catch
        {
            genres = new List<Genre>();
        }

        // 清洗「流派」：很多文件的 genre 字段被下载站写成了来源水印（wusunk.com收藏 / kuwo /
        // 论坛名 / 纯标点…），不是真流派 → 过滤掉；「未知/Unknown」这类沉到最后。
        var cleaned = genres.Where(g => !IsJunkGenre(g.Name)).ToList();

        return new
        {
            supported = cleaned.Count > 0,
            filtered = genres.Count - cleaned.Count,   // 被过滤掉的水印/垃圾流派数量（供 UI 提示）
            genres = cleaned
                .OrderBy(g => IsUnknownGenre(g.Name) ? 1 : 0)
                .ThenByDescending(g => g.SongCount)
                .ThenBy(g => g.Name, StringComparer.OrdinalIgnoreCase)
                .Select(g => new Dictionary<string, object?>
                {
                    ["name"] = g.Name,
                    ["songCount"] = g.SongCount,
                    ["albumCount"] = g.AlbumCount,
                    ["countText"] = GenreCountText(g),
                }).ToArray(),
        };
    }

    /// <summary>判断 genre 是不是「下载站水印 / 垃圾」而非真实流派。</summary>
    private static bool IsJunkGenre(string name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return true;
        // 纯标点/符号（如 "," "." "---"）
        if (System.Text.RegularExpressions.Regex.IsMatch(name, @"^[\s\p{P}\p{S}]+$"))
            return true;
        // 网址 / 下载站 / 论坛 / 合集水印
        return System.Text.RegularExpressions.Regex.IsMatch(name,
            @"(?i)(https?://|www\.|\.(com|cn|net|org|cc|top|xyz|vip)\b|wusunk|kuwo|kugou|qqmusic|论坛|分享|合购|下载|收藏|资源|美图|无损|hires|dsd\b)");
    }

    /// <summary>「未知/Unknown/其他」这类兜底流派（排序时沉底，不当真实流派看待）。</summary>
    private static bool IsUnknownGenre(string name)
    {
        var n = name.Trim();
        return n is "未知" or "Unknown" or "unknown" or "未知流派" or "其他" or "Other" or "other" or "无";
    }

    /// <summary>某个风格下的歌曲（getSongsByGenre，按歌分页，每页 <see cref="SongPageSize"/> 首）。</summary>
    public async Task<object?> GetSongsByGenre(string genre, int page = 1, int pageSize = SongPageSize)
    {
        var music = Music;
        if (music is null || string.IsNullOrWhiteSpace(genre))
            return new { genre, songs = Array.Empty<object>(), hasMore = false };

        if (!await EnsureConnectedAsync())
            return new { genre, songs = Array.Empty<object>(), hasMore = false, status = "连接失败" };

        var offset = Math.Max(0, (page - 1) * Math.Max(1, pageSize));
        List<Song> songs;
        try { songs = await music.GetSongsByGenreAsync(genre, pageSize + 1, offset); }
        catch { songs = new List<Song>(); }

        var hasMore = songs.Count > pageSize;
        if (hasMore)
            songs = songs.Take(pageSize).ToList();

        // 这个流派一共多少首：getGenres 的每个流派自带 songCount（那份列表已经整份取回并缓存，
        // 这里零额外请求）⇒ 分页器能一次把页码画全，不用"翻到下一页才知道有没有下一页"。
        var total = await GetGenreSongTotalAsync(genre);

        return new
        {
            genre,
            hasMore,
            total,
            totalPages = total > 0 ? (int)Math.Ceiling(total / (double)Math.Max(1, pageSize)) : 0,
            songs = songs.Select((s, i) => SongDto(s, offset + i + 1)).ToArray(),
        };
    }

    /// <summary>某个流派的歌曲总数（取自已缓存的流派列表；查不到返回 0 = 未知）。</summary>
    private async Task<int> GetGenreSongTotalAsync(string genre)
    {
        try
        {
            var key = AppServices.GetCurrentService()?.Id ?? "";
            if (!_genresCache.TryGet(key, out var genres))
            {
                var music = Music;
                if (music is null) return 0;
                genres = await music.GetGenresAsync();
                _genresCache.Set(key, genres);
            }
            var hit = genres.FirstOrDefault(g => string.Equals(g.Name, genre, StringComparison.OrdinalIgnoreCase));
            return hit?.SongCount ?? 0;
        }
        catch { return 0; }
    }

    private static string GenreCountText(Genre g)
    {
        if (g.SongCount > 0 && g.AlbumCount > 0) return $"{g.SongCount} 首 · {g.AlbumCount} 张专辑";
        if (g.SongCount > 0) return $"{g.SongCount} 首";
        if (g.AlbumCount > 0) return $"{g.AlbumCount} 张专辑";
        return "";
    }

    // ============ 收藏 / 历史 / 书签 ============

    public async Task<object?> GetFavorites()
    {
        var music = Music;
        if (music is null) return new { songs = Array.Empty<object>() };

        var songs = new List<Song>();

        // 1) 标准 getStarred（歌曲级收藏）：Music Tap/Music Tag 等按此返回收藏歌曲。
        try { songs.AddRange(await music.GetStarredSongsAsync()); }
        catch { /* 忽略 */ }

        // 2) 兜底：getAlbumList type=starred（部分服务按专辑收藏）。
        if (songs.Count == 0)
        {
            var albumList = await music.GetAlbumListAsync("starred", 50, 0);
            foreach (var album in albumList)
                songs.AddRange(album.Songs);
        }

        // 3) 再兜底：「喜欢的音乐」歌单（Gonic 的 getStarred 有 bug）。
        if (songs.Count == 0)
        {
            var starredPlaylist = (await GetPlaylistsCachedAsync()).FirstOrDefault(p =>
                p.Name.Contains("喜欢", StringComparison.OrdinalIgnoreCase) ||
                p.Name.Contains("Starred", StringComparison.OrdinalIgnoreCase));
            if (starredPlaylist is not null)
                songs.AddRange((await music.GetPlaylistAsync(starredPlaylist.Id))?.Songs ?? new List<Song>());
        }

        return new { songs = songs.Select((s, i) => SongDto(s, i + 1)).ToArray() };
    }

    public async Task<object?> GetHistory(int limit = 50)
    {
        var recent = AppServices.Library.GetRecentSongs(limit);
        return new { songs = recent.Select((s, i) => SongDto(s, i + 1)).ToArray() };
    }

    public async Task<object?> GetBookmarks()
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { bookmarks = Array.Empty<object>() };

        var bookmarks = await music.GetBookmarksAsync();
        return new
        {
            bookmarks = bookmarks.Select(b =>
            {
                var song = b.Songs.FirstOrDefault();
                return new Dictionary<string, object?>
                {
                    ["id"] = song?.Id ?? b.Comment,
                    ["title"] = song?.Title ?? b.Comment,
                    ["artist"] = song?.Artist,
                    ["positionText"] = FormatTime(b.Position / 1000.0),
                    ["comment"] = b.Comment,
                };
            }).ToArray(),
        };
    }

    // ============ 搜索 ============

    public async Task<object?> Search(string query)
    {
        var music = Music;
        if (music is null || string.IsNullOrWhiteSpace(query) || !await EnsureConnectedAsync())
            return new { songs = Array.Empty<object>(), albums = Array.Empty<object>(), artists = Array.Empty<object>() };

        var result = await music.SearchAsync(query, 20);
        return new
        {
            songs = result.Songs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
            albums = result.Albums.Select(a => AlbumDto(a)).ToArray(),
            artists = result.Artists.Select(a => new Dictionary<string, object?>
            {
                ["id"] = a.Id,
                ["name"] = a.Name,
            }).ToArray(),
        };
    }

    // ============ DTO ============

    private Dictionary<string, object?> SongDto(Song s, int index) => new()
    {
        ["id"] = s.Id,
        ["index"] = index,
        ["title"] = s.Title,
        ["artist"] = s.Artist,
        ["artistId"] = s.ArtistId,
        ["album"] = s.Album,
        ["albumId"] = s.AlbumId,
        ["duration"] = s.Duration,
        ["durationText"] = FormatTime(s.Duration),
        ["track"] = s.Track,
        ["year"] = s.Year,
        ["coverArtId"] = s.CoverArtId,
        ["coverUrl"] = CoverFor(s.CoverArtId, 100),
        ["isFavorite"] = AppServices.Favorites.IsFavorite(s.Id),
    };

    private Dictionary<string, object?> AlbumDto(Album a) => new()
    {
        ["id"] = a.Id,
        ["name"] = a.Name,
        ["artist"] = a.Artist,
        // 专辑卡/专辑页上的歌手名要能点进该艺术家（前端在没有这个值时退回纯文本）。
        ["artistId"] = a.ArtistId,
        ["coverUrl"] = CoverFor(a.CoverArtId, 300),
        ["songCount"] = a.SongCount,
        ["year"] = a.Year,
    };

    private static string FormatTime(double seconds)
    {
        if (seconds < 0 || double.IsNaN(seconds))
            return "0:00";
        var total = (int)Math.Floor(seconds);
        var h = total / 3600;
        var m = (total % 3600) / 60;
        var s = total % 60;
        return h > 0 ? $"{h}:{m:D2}:{s:D2}" : $"{m}:{s:D2}";
    }

    /// <summary>当前播放队列（含当前索引，供队列面板）。</summary>
    public object? GetQueue()
    {
        var pb = AppServices.Playback;
        var queue = pb.Queue;
        return new
        {
            songs = queue.Select((s, i) => SongDto(s, i + 1)).ToArray(),
            currentIndex = pb.CurrentIndex,
        };
    }

    /// <summary>当前歌曲歌词（服务端优先，失败走在线搜索）。</summary>
    public async Task<object?> GetCurrentLyrics()
    {
        var song = AppServices.Playback.CurrentSong;
        if (song is null)
            return new { hasLyrics = false };
        Lyrics? lyrics = null;
        try
        {
            var music = AppServices.Music;
            if (music is not null)
            {
                try
                {
                    lyrics = await music.GetLyricsAsync(song.Artist, song.Title, song.Id);
                }
                catch (Exception ex)
                {
                    Log($"GetCurrentLyrics server error: {ex.Message}");
                }
            }
            Log($"GetCurrentLyrics server: {(lyrics is null ? "null" : lyrics.IsSynced ? "synced" : lyrics.Text.Length + "chars")}");

            if (lyrics is null || (!lyrics.IsSynced && string.IsNullOrWhiteSpace(lyrics.Text)))
            {
                try
                {
                    lyrics = await LyricsSearchService.SearchAsync(song.Artist, song.Title, song.Duration);
                }
                catch (Exception ex)
                {
                    Log($"GetCurrentLyrics web error: {ex.Message}");
                }
            }
            Log($"GetCurrentLyrics web: {(lyrics is null ? "null" : lyrics.IsSynced ? "synced" : lyrics.Text.Length + "chars")}");

            if (lyrics is null || (!lyrics.IsSynced && string.IsNullOrWhiteSpace(lyrics.Text)))
                return new { hasLyrics = false };

            return new
            {
                hasLyrics = true,
                isSynced = lyrics.IsSynced,
                title = song.Title,
                artist = song.Artist,
                text = lyrics.Text,
                lines = lyrics.Lines.Select(l => new Dictionary<string, object?>
                {
                    ["start"] = l.StartSeconds,
                    ["text"] = l.Text,
                }).ToArray(),
            };
        }
        catch (Exception ex)
        {
            Log($"GetCurrentLyrics unexpected: {ex}");
            return new { hasLyrics = false };
        }
    }
}
