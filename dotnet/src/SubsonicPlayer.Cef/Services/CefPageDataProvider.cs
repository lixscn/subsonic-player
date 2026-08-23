using System;
using System.Collections.Generic;
using System.Linq;
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

    // 会话级连接缓存：首次连接成功后在本次运行内复用，避免每次翻页都做 3s 超时探测
    private string? _connectedServiceId;

    private async Task<bool> EnsureConnectedAsync()
    {
        var music = Music;
        if (music is null)
            return false;

        var serviceId = AppServices.GetCurrentService()?.Id;
        if (_connectedServiceId == serviceId)
            return true;

        var ok = await music.ConnectAsync();
        if (ok)
            _connectedServiceId = serviceId;
        return ok;
    }

    private string? CoverFor(string? coverArtId, int size = 300)
        => !string.IsNullOrEmpty(coverArtId) && Music is not null
            ? Music.GetCoverArtUrl(coverArtId, size)
            : null;

    // ============ 发现页 ============

    /// <summary>快速版：只返回随机歌曲（最快），用于首屏秒出。跳过连接探测（直接请求，失败即返回状态）。</summary>
    public async Task<object?> GetDiscoverQuick()
    {
        var music = Music;
        if (music is null)
            return new { status = "未配置音乐服务" };
        try
        {
            // 直接请求随机歌曲：若已连接秒回；未连接则触发首次请求（比 3+3s 探测快）
            var songs = await music.GetRandomSongsAsync(10);
            if (songs.Count == 0)
                return new { status = "暂无歌曲" };
            return new
            {
                status = "",
                randomSongs = songs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
            };
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
        try
        {
            var recommendTask = Task.Run(() => RecommendationService.GetRecommendationsAsync(music, 10));
            var newestTask = music.GetAlbumListAsync("newest", 10);
            var frequentTask = music.GetAlbumListAsync("frequent", 10);
            var highestTask = music.GetAlbumListAsync("highest", 10);
            await Task.WhenAll(recommendTask, newestTask, frequentTask, highestTask);
            var recs = recommendTask.Result;
            return new
            {
                recommendations = recs.Select((s, i) => SongDto(s, i + 1)).ToArray(),
                hasRecommendations = recs.Count > 0,
                newestAlbums = newestTask.Result.Select(a => AlbumDto(a)).ToArray(),
                frequentAlbums = frequentTask.Result.Select(a => AlbumDto(a)).ToArray(),
                highestAlbums = highestTask.Result.Select(a => AlbumDto(a)).ToArray(),
            };
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

    public async Task<object?> GetAlbumsPage(int page, int pageSize = 20)
    {
        var music = Music;
        if (music is null)
            return new { status = "未配置音乐服务" };
        if (!await EnsureConnectedAsync())
            return new { status = "连接失败" };

        var albums = await music.GetAlbumListAsync("alphabetical", pageSize, (page - 1) * pageSize);
        return new
        {
            albums = albums.Select(a => AlbumDto(a)).ToArray(),
            currentPage = page,
            pageSize,
        };
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

        var indexes = await music.GetArtistsAsync();

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

        return new
        {
            artists,
            groups = groups.ToArray(),
            currentPage = page,
            total = offset,
        };
    }

    /// <summary>按起始偏移加载艺术家（A-Z 快速跳转用）。</summary>
    public async Task<object?> GetArtistsAt(int offset, int count = 100)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { artists = Array.Empty<object>() };

        var indexes = await music.GetArtistsAsync();
        var artists = indexes
            .SelectMany(idx => idx.Artists)
            .Skip(offset)
            .Take(count)
            .Select(a => new Dictionary<string, object?>
            {
                ["id"] = a.Id,
                ["name"] = a.Name,
                ["albumCount"] = a.AlbumCount,
            })
            .ToArray();

        return new { artists };
    }

    public async Task<object?> GetArtistDetail(string id)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return null;

        var all = await music.GetArtistsAsync();
        var artist = all.SelectMany(i => i.Artists).FirstOrDefault(a => a.Id == id);
        if (artist is null) return null;

        var albums = await music.GetArtistAlbumsAsync(id);
        return new
        {
            id = artist.Id,
            name = artist.Name,
            albumCount = albums.Count,
            albums = albums.Select(a => AlbumDto(a)).ToArray(),
        };
    }

    /// <summary>获取艺术家的代表专辑封面（作为列表头像）。</summary>
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

    // ============ 歌曲 ============

    public async Task<object?> GetSongsPage(int page, int pageSize = 20, int startIndex = 0)
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { songs = Array.Empty<object>(), status = "未配置/连接失败" };

        // Gonic 无「全部歌曲」端点：分页拉最新专辑，并行获取详情展开歌曲
        var albums = await music.GetAlbumListAsync("newest", pageSize, (page - 1) * pageSize);
        var details = await Task.WhenAll(albums.Select(a => music.GetAlbumAsync(a.Id)));
        var flattened = new List<Song>();
        foreach (var d in details)
        {
            if (d is not null)
                flattened.AddRange(d.Songs);
        }

        return new
        {
            songs = flattened.Select((s, i) => SongDto(s, startIndex + i + 1)).ToArray(),
            currentPage = page,
        };
    }

    // ============ 歌单 ============

    public async Task<object?> GetPlaylists()
    {
        var music = Music;
        if (music is null || !await EnsureConnectedAsync())
            return new { playlists = Array.Empty<object>() };

        var playlists = await music.GetPlaylistsAsync();
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

    // ============ 收藏 / 历史 / 书签 ============

    public async Task<object?> GetFavorites()
    {
        var music = Music;
        if (music is null) return new { songs = Array.Empty<object>() };

        var albumList = await music.GetAlbumListAsync("starred", 50, 0);
        var songs = new List<Song>();
        foreach (var album in albumList)
            songs.AddRange(album.Songs);

        // 若 starred 为空，尝试随机兜底（部分服务不支持）
        if (songs.Count == 0)
        {
            var starredPlaylist = (await music.GetPlaylistsAsync()).FirstOrDefault(p =>
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
