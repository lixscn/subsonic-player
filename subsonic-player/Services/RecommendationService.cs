using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 智能推荐：基于用户收藏歌曲的艺术家偏好，推荐其「喜欢的艺术家」名下尚未收藏的歌曲。
/// 算法：统计收藏歌曲中 Top 艺术家 → 拉取其专辑 → 收集未收藏曲目 → 去重随机取样。
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

            // 2. 统计最常收藏的艺术家 Top 3
            var topArtistIds = favoriteSongs
                .Where(s => !string.IsNullOrEmpty(s.ArtistId))
                .GroupBy(s => s.ArtistId)
                .OrderByDescending(g => g.Count())
                .Take(3)
                .Select(g => g.Key)
                .ToList();

            // 3. 对每个 Top 艺术家，随机挑专辑收集未收藏歌曲
            var candidates = new List<Song>();
            foreach (var artistId in topArtistIds)
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

            // 4. 去重 + 随机取 count
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
