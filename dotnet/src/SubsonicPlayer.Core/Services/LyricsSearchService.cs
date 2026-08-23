using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 网络歌词兜底：服务端无歌词时，从开放的 LRCLIB（lrclib.net）按 艺术家 + 标题 搜索，
/// 支持同步歌词（LRC）与纯文本。命中结果写入 SQLite 缓存（lyrics_cache），二次离线秒开。
/// </summary>
public static class LyricsSearchService
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };
    /// <summary>LRCLIB 在中国大陆不可达（连接挂起），用 4s 短超时，避免拖慢歌词搜索。</summary>
    private static readonly HttpClient HttpFast = new() { Timeout = TimeSpan.FromSeconds(4) };

    private static readonly ConcurrentDictionary<string, Lyrics?> Cache = new(StringComparer.Ordinal);

    static LyricsSearchService()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("SubsonicPlayer/1.0 (https://github.com/anomalyco)");
    }

    public static async Task<Lyrics?> SearchAsync(string artist, string title, int? duration = null)
    {
        var artistName = CleanArtist(artist);
        var trackName = CleanTitle(title, artist);
        if (string.IsNullOrEmpty(trackName))
            return null;

        var key = BuildKey(artistName, trackName);

        // 1) 内存缓存
        if (Cache.TryGetValue(key, out var cached))
            return cached;

        // 2) SQLite 缓存（离线秒开，后台读避免阻塞 UI）
        var dbHit = await Task.Run(() => TryLoadFromDb(key));
        if (dbHit is not null)
        {
            Cache[key] = dbHit;
            return dbHit;
        }

        // 3) 网络搜索
        var web = await SearchWebAsync(artistName, trackName);
        if (web.Lyrics is not null)
            _ = Task.Run(() => TrySaveToDb(key, web.SyncedLrc, web.PlainText));

        Cache[key] = web.Lyrics;
        return web.Lyrics;
    }

    // ---- 缓存读写 ----

    private static Lyrics? TryLoadFromDb(string key)
    {
        try
        {
            var hit = AppServices.Library.GetLyrics(key);
            if (hit is null)
                return null;
            return BuildFromText(hit.Value.SyncedLrc, hit.Value.PlainText);
        }
        catch
        {
            return null;
        }
    }

    private static void TrySaveToDb(string key, string syncedLrc, string plainText)
    {
        try
        {
            AppServices.Library.SaveLyrics(key, syncedLrc, plainText);
        }
        catch
        {
            // 缓存失败不影响歌词显示
        }
    }

    private static Lyrics? BuildFromText(string syncedLrc, string plainText)
    {
        if (!string.IsNullOrEmpty(syncedLrc))
        {
            var lines = ParseLrc(syncedLrc);
            if (lines.Count > 0)
                return new Lyrics { Lines = lines };
        }

        if (!string.IsNullOrEmpty(plainText))
            return new Lyrics { Text = plainText };

        return null;
    }

    // ---- 网络搜索 ----

    private static async Task<(Lyrics? Lyrics, string SyncedLrc, string PlainText)> SearchWebAsync(string artistName, string trackName)
    {
        // LRCLIB 与网易云并行：谁先返回歌词用谁。LRCLIB 被墙时 4s 超时放弃，不阻塞中文歌。
        var lrclibTask = TryLrclibAsync(artistName, trackName);
        var neteaseTask = TryNetEaseAsync(artistName, trackName);
        var first = await Task.WhenAny(lrclibTask, neteaseTask);
        var result = await first;
        if (result.Lyrics is not null)
            return result;

        var secondTask = ReferenceEquals(first, lrclibTask) ? neteaseTask : lrclibTask;
        return await secondTask;
    }

    /// <summary>LRCLIB：优先精确匹配，其次搜索结果（使用短超时客户端）。</summary>
    private static async Task<(Lyrics? Lyrics, string SyncedLrc, string PlainText)> TryLrclibAsync(string artistName, string trackName)
    {
        try
        {
            var exact = await TryGetRawAsync($"https://lrclib.net/api/get?artist_name={E(artistName)}&track_name={E(trackName)}");
            if (exact.Lyrics is not null)
                return exact;
        }
        catch { }

        return await TrySearchRawAsync($"https://lrclib.net/api/search?artist_name={E(artistName)}&track_name={E(trackName)}");
    }

    /// <summary>网易云音乐歌词：搜索歌曲 → 取第一条结果的 LRC 歌词（无需登录）。</summary>
    private static async Task<(Lyrics? Lyrics, string SyncedLrc, string PlainText)> TryNetEaseAsync(string artistName, string trackName)
    {
        try
        {
            var query = $"{artistName} {trackName}".Trim();
            using (var req = new HttpRequestMessage(HttpMethod.Get, $"https://music.163.com/api/search/get?s={E(query)}&type=1&limit=5"))
            {
                req.Headers.TryAddWithoutValidation("Referer", "https://music.163.com");
                using var searchResp = await Http.SendAsync(req);
                if (!searchResp.IsSuccessStatusCode)
                    return default;

                using var searchDoc = await JsonDocument.ParseAsync(await searchResp.Content.ReadAsStreamAsync());
                if (!searchDoc.RootElement.TryGetProperty("result", out var result)
                    || !result.TryGetProperty("songs", out var songs)
                    || songs.ValueKind != JsonValueKind.Array)
                    return default;

                foreach (var song in songs.EnumerateArray())
                {
                    if (!song.TryGetProperty("id", out var idProp) || idProp.ValueKind != JsonValueKind.Number)
                        continue;

                    var songId = idProp.GetInt64();
                    using (var req2 = new HttpRequestMessage(HttpMethod.Get, $"https://music.163.com/api/song/lyric?id={songId}&lv=1&kv=1&tv=-1"))
                    {
                        req2.Headers.TryAddWithoutValidation("Referer", "https://music.163.com");
                        using var lyricResp = await Http.SendAsync(req2);
                        if (!lyricResp.IsSuccessStatusCode)
                            continue;

                        using var lyricDoc = await JsonDocument.ParseAsync(await lyricResp.Content.ReadAsStreamAsync());
                        if (!lyricDoc.RootElement.TryGetProperty("lrc", out var lrc)
                            || !lrc.TryGetProperty("lyric", out var lyricProp)
                            || lyricProp.ValueKind != JsonValueKind.String)
                            continue;

                        var lrcText = lyricProp.GetString() ?? "";
                        if (string.IsNullOrWhiteSpace(lrcText))
                            continue;

                        var lines = ParseLrc(lrcText);
                        if (lines.Count > 0)
                            return (new Lyrics { Lines = lines }, lrcText, "");
                    }
                }
            }

            return default;
        }
        catch
        {
            return default;
        }
    }

    private static async Task<(Lyrics? Lyrics, string SyncedLrc, string PlainText)> TryGetRawAsync(string url)
    {
        try
        {
            using var resp = await HttpFast.GetAsync(url);
            if (!resp.IsSuccessStatusCode)
                return default;

            using var doc = await JsonDocument.ParseAsync(await resp.Content.ReadAsStreamAsync());
            return ParseLyricsJson(doc.RootElement);
        }
        catch
        {
            return default;
        }
    }

    private static async Task<(Lyrics? Lyrics, string SyncedLrc, string PlainText)> TrySearchRawAsync(string url)
    {
        try
        {
            using var resp = await HttpFast.GetAsync(url);
            if (!resp.IsSuccessStatusCode)
                return default;

            using var doc = await JsonDocument.ParseAsync(await resp.Content.ReadAsStreamAsync());
            if (doc.RootElement.ValueKind != JsonValueKind.Array)
                return default;

            foreach (var item in doc.RootElement.EnumerateArray())
            {
                var parsed = ParseLyricsJson(item);
                if (parsed.Lyrics is not null)
                    return parsed;
            }
            return default;
        }
        catch
        {
            return default;
        }
    }

    private static (Lyrics? Lyrics, string SyncedLrc, string PlainText) ParseLyricsJson(JsonElement e)
    {
        var synced = GetString(e, "syncedLyrics");
        var plain = GetString(e, "plainLyrics");

        if (!string.IsNullOrEmpty(synced))
        {
            var lines = ParseLrc(synced);
            if (lines.Count > 0)
                return (new Lyrics { Lines = lines }, synced, plain);
        }

        if (!string.IsNullOrEmpty(plain))
            return (new Lyrics { Text = plain }, synced, plain);

        return (null, synced, plain);
    }

    /// <summary>解析 LRC 文本为带时间戳的歌词行。</summary>
    private static List<LyricsLine> ParseLrc(string lrc)
    {
        var lines = new List<LyricsLine>();
        var tagRegex = new Regex(@"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]", RegexOptions.Compiled);

        foreach (var raw in lrc.Split('\n'))
        {
            var line = raw.TrimEnd('\r');
            var matches = tagRegex.Matches(line);
            if (matches.Count == 0)
                continue;

            var text = tagRegex.Replace(line, "").Trim();
            if (text.Length == 0)
                continue;

            foreach (Match m in matches)
            {
                var min = int.Parse(m.Groups[1].Value);
                var sec = int.Parse(m.Groups[2].Value);
                var frac = m.Groups[3].Success
                    ? int.Parse(m.Groups[3].Value.PadRight(3, '0'))
                    : 0;
                var seconds = min * 60 + sec + frac / 1000.0;
                lines.Add(new LyricsLine { StartSeconds = seconds, Text = text });
            }
        }

        lines.Sort((a, b) => a.StartSeconds.CompareTo(b.StartSeconds));
        return lines;
    }

    private static string BuildKey(string artist, string title)
        => $"{artist}\u0001{title}";

    /// <summary>清理 Gonic 等不规范的 title（去「- 艺术家」前后缀）。</summary>
    private static string CleanTitle(string title, string artist)
    {
        var t = title?.Trim() ?? "";
        var a = artist?.Trim() ?? "";
        if (a.Length == 0)
            return t;

        var suffix = " - " + a;
        if (t.EndsWith(suffix, StringComparison.OrdinalIgnoreCase))
            return t[..^suffix.Length].Trim();

        var prefix = a + " - ";
        if (t.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            return t[prefix.Length..].Trim();

        return t;
    }

    private static string CleanArtist(string artist)
        => artist?.Trim() ?? "";

    private static string E(string s) => Uri.EscapeDataString(s);

    private static string GetString(JsonElement e, string name)
        => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
}
