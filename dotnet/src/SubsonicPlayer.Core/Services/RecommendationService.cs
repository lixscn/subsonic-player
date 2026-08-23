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
            // 1. 读取收藏歌曲（Gonic 用「喜欢的音乐」歌单作为可靠来源）
            var playlists = await music.GetPlaylistsAsync();
            var favorite = playlists.FirstOrDefault(p => p.Name.Contains("喜欢的音乐"));
            if (favorite is null)
                return new List<Song>();

            var detail = await music.GetPlaylistAsync(favorite.Id);
            var favoriteSongs = detail?.Songs ?? new List<Song>();
            if (favoriteSongs.Count == 0)
                return new List<Song>();

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

            // 4. 搜集候选：目标艺术家专辑的未收藏曲目（每艺术家配额上限，保多样性）
            var candidates = new List<Song>();
            var perArtist = new Dictionary<string, int>(StringComparer.Ordinal);
            const int perArtistCap = 8;
            foreach (var artistId in targetArtists)
            {
                if (perArtist.GetValueOrDefault(artistId) >= perArtistCap)
                    continue;
                var albums = await music.GetArtistAlbumsAsync(artistId);
                foreach (var album in albums.OrderBy(_ => Guid.NewGuid()).Take(2))
                {
                    var albumDetail = await music.GetAlbumAsync(album.Id);
                    if (albumDetail is null)
                        continue;
                    foreach (var s in albumDetail.Songs)
                    {
                        if (favoriteIds.Contains(s.Id))
                            continue;
                        candidates.Add(s);
                        perArtist[artistId] = perArtist.GetValueOrDefault(artistId) + 1;
                        if (perArtist[artistId] >= perArtistCap)
                            break;
                    }
                }
            }

            // 5. 混入随机歌曲，补充偏好外多样性
            candidates.AddRange((await music.GetRandomSongsAsync(count)).Where(s => !favoriteIds.Contains(s.Id)));

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
        catch
        {
            return new List<Song>();
        }
    }
}
