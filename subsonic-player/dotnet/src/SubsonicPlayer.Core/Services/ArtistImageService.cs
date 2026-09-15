using System;
using System.Collections.Concurrent;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>
/// 艺术家头像兜底：大部分 Subsonic 服务端（Gonic/Music Tag 等）不提供艺术家照片
/// （getArtistInfo 无图/404），故按艺术家名从网易云搜索拿一张头像（picUrl），内存缓存。
/// 仅作列表头像展示，不走封面/下载链路。
/// </summary>
public static class ArtistImageService
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(6) };
    private static readonly ConcurrentDictionary<string, string> Cache = new(StringComparer.OrdinalIgnoreCase);

    static ArtistImageService()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("SubsonicPlayer/1.0");
    }

    /// <summary>按艺术家名返回一张网易云头像 URL；找不到或出错返回空串。结果内存缓存。</summary>
    public static async Task<string> GetPhotoAsync(string artistName)
    {
        var name = artistName?.Trim() ?? "";
        if (string.IsNullOrEmpty(name))
            return "";

        if (Cache.TryGetValue(name, out var cached))
            return cached;

        var url = "";
        try { url = await SearchPhotoAsync(name); }
        catch { /* 网络失败按无头像处理 */ }

        Cache[name] = url;
        return url;
    }

    /// <summary>网易云艺术家搜索（type=100 返回 artists[].picUrl）。</summary>
    private static async Task<string> SearchPhotoAsync(string name)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get,
            $"https://music.163.com/api/search/get?s={Uri.EscapeDataString(name)}&type=100&limit=1");
        req.Headers.TryAddWithoutValidation("Referer", "https://music.163.com");

        using var resp = await Http.SendAsync(req);
        if (!resp.IsSuccessStatusCode)
            return "";

        using var doc = await JsonDocument.ParseAsync(await resp.Content.ReadAsStreamAsync());
        if (!doc.RootElement.TryGetProperty("result", out var result)
            || !result.TryGetProperty("artists", out var artists)
            || artists.ValueKind != JsonValueKind.Array)
            return "";

        foreach (var a in artists.EnumerateArray())
        {
            if (a.TryGetProperty("picUrl", out var pic) && pic.ValueKind == JsonValueKind.String)
            {
                var url = pic.GetString()?.Trim();
                if (!string.IsNullOrEmpty(url))
                    return url;
            }
        }

        return "";
    }
}
