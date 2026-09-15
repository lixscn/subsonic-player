using System;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>歌曲原文件下载。</summary>
public static class DownloadService
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(5) };

    /// <summary>解析下载目录（配置为空时回退「我的音乐」）。</summary>
    public static string ResolveDownloadDir()
    {
        var dir = AppServices.Settings.Settings.DownloadDir?.Trim();
        if (string.IsNullOrEmpty(dir))
            dir = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);

        Directory.CreateDirectory(dir);
        return dir;
    }

    /// <summary>下载歌曲原文件，返回保存路径（失败返回 null）。</summary>
    public static async Task<string?> DownloadAsync(Song song)
    {
        var music = AppServices.Music;
        if (music is null || string.IsNullOrEmpty(song.Id))
            return null;

        var url = music.GetDownloadUrl(song.Id);
        var dir = ResolveDownloadDir();

        try
        {
            using var resp = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();

            var fileName = ExtractFileName(resp, song);
            var path = UniquePath(dir, fileName);

            await using var src = await resp.Content.ReadAsStreamAsync();
            await using var dst = File.Create(path);
            await src.CopyToAsync(dst);
            return path;
        }
        catch
        {
            return null;
        }
    }

    private static string ExtractFileName(HttpResponseMessage resp, Song song)
    {
        var disposition = resp.Content.Headers.ContentDisposition?.FileNameStar
                          ?? resp.Content.Headers.ContentDisposition?.FileName;
        if (!string.IsNullOrEmpty(disposition))
        {
            disposition = disposition.Trim('"');
            if (!string.IsNullOrEmpty(disposition))
                return Sanitize(disposition);
        }

        var suffix = string.IsNullOrEmpty(song.Suffix) ? "mp3" : song.Suffix;
        return Sanitize($"{song.Title} - {song.Artist}") + "." + suffix;
    }

    private static string Sanitize(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars())
            name = name.Replace(c, '_');
        return string.IsNullOrWhiteSpace(name) ? "song" : name.Trim();
    }

    private static string UniquePath(string dir, string fileName)
    {
        var full = Path.Combine(dir, fileName);
        if (!File.Exists(full))
            return full;

        var name = Path.GetFileNameWithoutExtension(fileName);
        var ext = Path.GetExtension(fileName);
        for (var i = 2; ; i++)
        {
            var candidate = Path.Combine(dir, $"{name} ({i}){ext}");
            if (!File.Exists(candidate))
                return candidate;
        }
    }
}
