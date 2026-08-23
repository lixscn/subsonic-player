using System;
using System.Collections.Concurrent;
using System.Net.Http;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>封面异步加载 + 内存缓存（返回原始字节，与 UI 框架解耦；展示/SMTC 由各端转成可用位图）。</summary>
public static class ImageLoader
{
    private const int MaxCacheSize = 300;

    private static readonly HttpClient Http = new();
    private static readonly ConcurrentDictionary<string, byte[]> Cache = new();

    public static async Task<byte[]?> LoadAsync(string? url)
    {
        if (string.IsNullOrEmpty(url))
            return null;
        if (Cache.TryGetValue(url, out var cached))
            return cached;

        try
        {
            var bytes = await Http.GetByteArrayAsync(url);

            // 缓存超限时清空，防止长时间播放多首歌曲导致内存无限增长
            if (Cache.Count >= MaxCacheSize)
                Cache.Clear();
            Cache[url] = bytes;
            return bytes;
        }
        catch
        {
            return null;
        }
    }
}
