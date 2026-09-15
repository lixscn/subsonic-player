using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 智能推荐：基于「收藏偏好 + 播放历史权重 + 流派相似度」推荐尚未收藏的歌曲。
/// 算法：收藏歌曲与最近播放合并成一个艺术家评分（收藏权重 2、历史权重 1）→ 取 Top 艺术家专辑收集
/// 未收藏曲目（每艺术家配额上限，保多样性）→ 混入随机歌曲补充 → 按流派亲和（收藏热门流派）重排 +
/// 随机取样。
/// 说明：getSimilarSongs2 / getSongsByGenre 需跨协议扩展 IMusicService（Gonic 的 OpenSubsonic 支持
/// 不确定，Emby/Plex 各不同），该项暂用客户端侧「流派亲和」实现，未引入协议调用。
/// </summary>
public static class RecommendationService
{
    public static async Task<List<Song>> GetRecommendationsAsync(IMusicService music, int count = 10)
    {
        try
        {
            // 1. 读取收藏歌曲：歌曲级星标 getStarred（Music Tap 等没有「喜欢的音乐」歌单的服务）
            //    ∪ 「喜欢的音乐」歌单（Gonic 等）。不在这里提前返回空——即使某服务取不到收藏，
            //    下面仍会用随机歌曲兜底，避免「智能推荐」整块空白。
            //    ★ 这两个请求互不依赖，并行发（原来是串行，白白多等一轮）。
            var favoriteSongs = new List<Song>();
            var starredTask = SafeStarredAsync();
            var playlistsTask = SafePlaylistsAsync();
            await Task.WhenAll(starredTask, playlistsTask);
            favoriteSongs.AddRange(starredTask.Result);
            try
            {
                var favorite = playlistsTask.Result.FirstOrDefault(p => p.Name.Contains("喜欢的音乐"));
                if (favorite is not null)
                {
                    var detail = await music.GetPlaylistAsync(favorite.Id);
                    if (detail?.Songs is { } playlistSongs)
                        favoriteSongs.AddRange(playlistSongs);
                }
            }
            catch { }
            favoriteSongs = favoriteSongs.DistinctBy(s => s.Id).ToList();

            async Task<List<Song>> SafeStarredAsync()
            {
                try { return await music.GetStarredSongsAsync(); } catch { return new List<Song>(); }
            }
            async Task<List<Playlist>> SafePlaylistsAsync()
            {
                try { return await music.GetPlaylistsAsync(); } catch { return new List<Playlist>(); }
            }

            var favoriteIds = new HashSet<string>(favoriteSongs.Select(s => s.Id), StringComparer.Ordinal);

            // 2. 艺术家评分：收藏权重 2、播放历史权重 1（弥补纯收藏样本不足）
            var artistScore = new Dictionary<string, double>(StringComparer.Ordinal);
            void AddArtist(Song s, double w)
            {
                if (string.IsNullOrEmpty(s.ArtistId))
                    return;
                artistScore[s.ArtistId] = artistScore.GetValueOrDefault(s.ArtistId) + w;
            }
            foreach (var s in favoriteSongs) AddArtist(s, 2.0);
            foreach (var s in AppServices.Library.GetRecentSongs(100)) AddArtist(s, 1.0);

            var targetArtists = artistScore
                .OrderByDescending(kv => kv.Value)
                .Take(6)
                .Select(kv => kv.Key)
                .ToList();

            // 3. 收藏热门流派（流派相似度亲和集合）
            var favGenres = favoriteSongs
                .Where(s => !string.IsNullOrEmpty(s.Genre))
                .GroupBy(s => s.Genre, StringComparer.OrdinalIgnoreCase)
                .OrderByDescending(g => g.Count())
                .Take(4)
                .Select(g => g.Key)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);

            // 5'. 随机歌曲与第 4 步**并行**发起（两者互不依赖）：原来等第 4 步跑完才发，
            //     白搭一轮网络（随机歌曲本身还是 1 列表 + N 详情）。
            var randomTask = SafeRandomAsync(count);
            async Task<List<Song>> SafeRandomAsync(int n)
            {
                try { return await music.GetRandomSongsAsync(n); }
                catch (Exception ex)
                {
                    Log($"Step5 random failed: {ex.GetType().Name}: {ex.Message}");
                    return new List<Song>();
                }
            }

            // 4. 候选来源一（**便宜**）：标准 getSimilarSongs2 —— 每首种子 1 个请求。
            //    种子取收藏里的前几首（收藏通常按加入顺序），没有就用最近播放。
            //    本服务端实测可用（10 首 / 207ms）。原来的"取艺术家专辑再逐张展开曲目"要
            //    6 + ≤12 = 最多 18 个请求，而服务端是单线程串行处理（总耗时只由请求条数决定），
            //    所以这条路能省掉十几秒里的一大半。
            const int perArtistCap = 8;
            var perArtist = new Dictionary<string, int>(StringComparer.Ordinal);
            var candidates = new List<Song>();
            var favoriteIdsLocal = favoriteIds;

            var seeds = favoriteSongs.Select(s => s.Id)
                .Concat(AppServices.Library.GetRecentSongs(5).Select(s => s.Id))
                .Where(id => !string.IsNullOrEmpty(id))
                .Distinct()
                .Take(3)
                .ToList();

            foreach (var seed in seeds)
            {
                try
                {
                    var similar = await music.GetSimilarSongsAsync(seed, 20);
                    foreach (var s in similar)
                    {
                        if (!favoriteIdsLocal.Contains(s.Id))
                            candidates.Add(s);
                    }
                }
                catch (Exception ex)
                {
                    Log($"Step4 similar '{seed}' failed: {ex.GetType().Name}: {ex.Message}");
                }
            }
            candidates = candidates.GroupBy(s => s.Id).Select(g => g.First()).ToList();

            // 4b. 候选不够（端点不支持 / 结果太少）才回退到"艺术家专辑展开"（请求多，但保证有内容）。
            //     阈值取 count（而不是 2×count）：之前定得太高，导致 similar 已经够用还要再跑十几张专辑。
            if (candidates.Count < count)
            {
                var artistAlbumLists = await LimitedParallel.SelectAsync(targetArtists, async artistId =>
                {
                    try { return await music.GetArtistAlbumsAsync(artistId); }
                    catch (Exception ex)
                    {
                        Log($"Step4 artist '{artistId}' failed: {ex.GetType().Name}: {ex.Message}");
                        return new List<Album>();
                    }
                });

                // 每位艺术家随机挑 1 张：perArtistCap=8 而一张专辑通常就有 8~12 首，
                // 挑 2 张纯属重复请求（服务端串行，每多一张就是多排一次队）。
                var picks = new List<(string ArtistId, Album Album)>();
                for (var i = 0; i < targetArtists.Count; i++)
                    foreach (var album in artistAlbumLists[i].OrderBy(_ => Guid.NewGuid()).Take(1))
                        picks.Add((targetArtists[i], album));

                var albumDetails = await LimitedParallel.SelectAsync(picks, async pick =>
                {
                    try { return await music.GetAlbumAsync(pick.Album.Id); }
                    catch (Exception ex)
                    {
                        Log($"Step4 album '{pick.Album.Id}' failed: {ex.GetType().Name}: {ex.Message}");
                        return null;
                    }
                });

                var existing = new HashSet<string>(candidates.Select(s => s.Id), StringComparer.Ordinal);
                for (var i = 0; i < picks.Count; i++)
                {
                    var detail = albumDetails[i];
                    if (detail is null)
                        continue;

                    var artistId = picks[i].ArtistId;
                    if (perArtist.GetValueOrDefault(artistId) >= perArtistCap)
                        continue;

                    foreach (var s in detail.Songs)
                    {
                        if (favoriteIdsLocal.Contains(s.Id) || !existing.Add(s.Id))
                            continue;
                        candidates.Add(s);
                        perArtist[artistId] = perArtist.GetValueOrDefault(artistId) + 1;
                        if (perArtist[artistId] >= perArtistCap)
                            break;
                    }
                }
            }

            // 5. 混入（已在第 4 步并行取到的）随机歌曲，补充偏好外多样性
            //    （保证即便无收藏/无艺术家候选也有内容）
            var random = await randomTask;
            candidates.AddRange(random.Where(s => !favoriteIds.Contains(s.Id)));

            Log($"Recommend: favorites={favoriteSongs.Count} artists={targetArtists.Count} favGenres={favGenres.Count} candidates={candidates.Count}");

            // 6. 去重 + 流派亲和加分排序（偏好流派靠前）+ 层内随机 + 取 count
            return candidates
                .GroupBy(s => s.Id)
                .Select(g => g.First())
                .Select(s => new { Song = s, Affinity = s.Genre != null && favGenres.Contains(s.Genre) ? 1 : 0 })
                .OrderByDescending(x => x.Affinity)   // 流派亲和者优先
                .ThenBy(_ => Guid.NewGuid())           // 同层内随机
                .Take(count)
                .Select(x => x.Song)
                .ToList();
        }
        catch (Exception ex)
        {
            Log($"Recommend failed overall: {ex.GetType().Name}: {ex.Message}");
            return new List<Song>();
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
                System.IO.Path.Combine(dir, "rec.log"),
                $"[{DateTime.Now:HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { }
    }
}
