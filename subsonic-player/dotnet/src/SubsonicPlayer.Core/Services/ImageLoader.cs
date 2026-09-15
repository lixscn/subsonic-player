using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>封面异步加载 + 内存/磁盘缓存（返回原始字节，与 UI 框架解耦；展示/SMTC 由各端转成可用位图）。</summary>
public static class ImageLoader
{
    private const int MaxCacheSize = 300;

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };
    private static readonly ConcurrentDictionary<string, byte[]> Cache = new();

    private static string CacheDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player", "cover-cache");

    public static async Task<byte[]?> LoadAsync(string? url)
    {
        if (string.IsNullOrEmpty(url))
            return null;
        if (Cache.TryGetValue(url, out var cached))
            return cached;

        // 磁盘缓存：命中直接读盘，避免慢网反复下载封面
        var file = CacheFile(url);
        if (File.Exists(file))
        {
            try
            {
                var bytes = File.ReadAllBytes(file);
                AddToMemory(url, bytes);
                return bytes;
            }
            catch { }
        }

        try
        {
            var bytes = await Retry.DoAsync<byte[]>(() => Http.GetByteArrayAsync(url), 2, 400);
            if (bytes is null)
                return null;

            AddToMemory(url, bytes);

            // 写入磁盘缓存（尽力而为）
            try
            {
                if (!Directory.Exists(CacheDir))
                    Directory.CreateDirectory(CacheDir);
                File.WriteAllBytes(file, bytes);
            }
            catch { }

            return bytes;
        }
        catch
        {
            return null;
        }
    }

    private static void AddToMemory(string url, byte[] bytes)
    {
        // 缓存超限时清空，防止长时间播放大量歌曲导致内存无限增长
        if (Cache.Count >= MaxCacheSize)
            Cache.Clear();
        Cache[url] = bytes;
    }

    private static string CacheFile(string url)
    {
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(url)));
        return Path.Combine(CacheDir, hash + ".img");
    }
}
