using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// 群晖 AudioStation（DSM）协议实现：SYNO.AudioStation.* 系列 webapi（JSON）。
/// 已实现：API 发现 + 登录（SYNO.API.Auth account/passwd → sid）、封面/播放/下载 URL 构造。
/// 浏览类（artists/albums/songs/search）按官方文档接入，但封面/曲库字段映射需在真实 DSM 上验证，
/// 未验证前暂返回空（避免误导）。各接口失败静默返回空，不崩溃。
/// </summary>
public class AudioStationMusicService : MusicServiceBase
{
    private static readonly string[] ApiNames =
        { "SYNO.API.Auth", "SYNO.AudioStation.Folder", "SYNO.AudioStation.Song", "SYNO.AudioStation.CoverArt" };

    private readonly MusicServiceConfig _config;
    private readonly HttpClient _http;

    private string? _baseUrl;
    private string? _sid;

    public AudioStationMusicService(MusicServiceConfig config)
    {
        _config = config;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
    }

    public override string ServiceName => _config.Name;

    private string ResolveBaseUrl()
    {
        var lan = _config.LanUrl?.Trim() ?? "";
        var wan = _config.WanUrl?.Trim() ?? "";
        return (lan.Length > 0 ? lan : wan).TrimEnd('/');
    }

    // ============ 连接与认证 ============

    public override async Task<bool> ConnectAsync()
    {
        // 基类抽象签名无 CancellationToken；下传默认取消令牌。
        var ct = CancellationToken.None;
        try
        {
            _baseUrl = ResolveBaseUrl();
            if (string.IsNullOrEmpty(_baseUrl))
                return false;

            // 1. 发现 API（可选校验，失败不阻塞登录）
            var query = string.Join(",", ApiNames);
            _ = await GetJsonAsync($"webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query={Uri.EscapeDataString(query)}", ct);

            // 2. 登录：account/passwd → sid
            var login = await GetJsonAsync(
                $"webapi/auth.cgi?api=SYNO.API.Auth&version=6&method=login&account={Uri.EscapeDataString(_config.Username)}" +
                $"&passwd={Uri.EscapeDataString(_config.Password)}&session=AudioStation&format=sid", ct);
            if (login is { } j && j.TryGetProperty("data", out var data) && data.TryGetProperty("sid", out var sidEl))
                _sid = sidEl.GetString();
            return !string.IsNullOrEmpty(_sid);
        }
        catch
        {
            return false;
        }
    }

    private async Task<JsonElement?> GetJsonAsync(string path, CancellationToken ct = default)
    {
        try
        {
            var url = $"{_baseUrl}/{path}";
            var resp = await _http.GetStringAsync(url, ct);
            using var doc = JsonDocument.Parse(resp);
            return doc.RootElement.Clone();
        }
        catch
        {
            return null;
        }
    }

    private string AuthSuffix => string.IsNullOrEmpty(_sid) ? "" : $"&_sid={_sid}";

    // ============ URL ============

    public override string GetStreamUrl(string songId)
        => $"{_baseUrl}/webapi/entry.cgi?api=SYNO.AudioStation.Song&version=1&method=download&library=shared&id={Uri.EscapeDataString(songId)}{AuthSuffix}";

    public override string GetDownloadUrl(string songId) => GetStreamUrl(songId);

    public override string GetCoverArtUrl(string coverArtId, int size = 300)
        => $"{_baseUrl}/webapi/entry.cgi?api=SYNO.AudioStation.CoverArt&version=1&method=getcover&library=shared&id={Uri.EscapeDataString(coverArtId)}{AuthSuffix}";

    // ============ 浏览（需真机验证字段映射，暂返回空） ============

    public override Task<List<ArtistIndex>> GetArtistsAsync(CancellationToken ct = default)
        => Task.FromResult(new List<ArtistIndex>());

    public override Task<List<Album>> GetAlbumListAsync(string type, int size = 20, int offset = 0, CancellationToken ct = default)
        => Task.FromResult(new List<Album>());

    public override Task<Album?> GetAlbumAsync(string id, CancellationToken ct = default)
        => Task.FromResult<Album?>(null);

    public override Task<List<Album>> GetArtistAlbumsAsync(string artistId, CancellationToken ct = default)
        => Task.FromResult(new List<Album>());

    public override Task<List<Song>> GetRandomSongsAsync(int size = 10, CancellationToken ct = default)
        => Task.FromResult(new List<Song>());

    public override Task<SearchResult> SearchAsync(string query, int count = 20, CancellationToken ct = default)
        => Task.FromResult(new SearchResult());
}
