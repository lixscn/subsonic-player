using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 智能推荐：基于用户「收藏偏好 + 播放历史权重」推荐尚未收藏的歌曲。
/// 算法：收藏歌曲统计 Top 艺术家 + 最近播放历史统计高频艺术家（合并去重）→ 拉取其专辑收集未收藏曲目 →
/// 混入随机歌曲补充多样性 → 去重随机取样。
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

            // 2. 收藏偏好：最常收藏的艺术家 Top 3
            var topArtistIds = favoriteSongs
                .Where(s => !string.IsNullOrEmpty(s.ArtistId))
                .GroupBy(s => s.ArtistId)
                .OrderByDescending(g => g.Count())
                .Take(3)
                .Select(g => g.Key)
                .ToList();

            // 2.1 播放历史权重：最近播放歌曲中的高频艺术家 Top 3（弥补纯收藏样本不足）
            var recentArtistIds = AppServices.Library.GetRecentSongs(100)
                .Where(s => !string.IsNullOrEmpty(s.ArtistId))
                .GroupBy(s => s.ArtistId)
                .OrderByDescending(g => g.Count())
                .Take(3)
                .Select(g => g.Key);

            var targetArtistIds = topArtistIds
                .Concat(recentArtistIds)
                .Distinct(StringComparer.Ordinal)
                .Take(5)
                .ToList();

            // 3. 对每个目标艺术家，随机挑专辑收集未收藏歌曲
            var candidates = new List<Song>();
            foreach (var artistId in targetArtistIds)
            {
                var albums = await music.GetArtistAlbumsAsync(artistId);
                foreach (var album in albums.OrderBy(_ => Guid.NewGuid()).Take(2))
                {
                    var albumDetail = await music.GetAlbumAsync(album.Id);
                    if (albumDetail is null)
                        continue;
                    candidates.AddRange(albumDetail.Songs.Where(s => !favoriteIds.Contains(s.Id)));
                }
            }

            // 4. 混入随机歌曲，补充偏好外的多样性
            var randomSongs = await music.GetRandomSongsAsync(count);
            candidates.AddRange(randomSongs.Where(s => !favoriteIds.Contains(s.Id)));

            // 5. 去重 + 随机取 count
            return candidates
                .GroupBy(s => s.Id)
                .Select(g => g.First())
                .OrderBy(_ => Guid.NewGuid())
                .Take(count)
                .ToList();
        }
        catch
        {
            return new List<Song>();
        }
    }
}