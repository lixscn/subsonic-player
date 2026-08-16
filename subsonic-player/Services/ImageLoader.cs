using System.Collections.Concurrent;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using Avalonia.Media.Imaging;

namespace SubsonicPlayer.Services;

/// <summary>封面异步加载 + 内存缓存。</summary>
public static class ImageLoader
{
    private const int MaxCacheSize = 300;

    private static readonly HttpClient Http = new();
    private static readonly ConcurrentDictionary<string, Bitmap> Cache = new();

    public static async Task<Bitmap?> LoadAsync(string? url)
    {
        if (string.IsNullOrEmpty(url))
            return null;
        if (Cache.TryGetValue(url, out var cached))
            return cached;

        try
        {
            var bytes = await Http.GetByteArrayAsync(url);
            using var ms = new MemoryStream(bytes);
            var bmp = new Bitmap(ms);

            // 缓存超限时清空，防止长时间播放多首歌曲导致内存无限增长
            if (Cache.Count >= MaxCacheSize)
                Cache.Clear();
            Cache[url] = bmp;
            return bmp;
        }
        catch
        {
            return null;
        }
    }
}
