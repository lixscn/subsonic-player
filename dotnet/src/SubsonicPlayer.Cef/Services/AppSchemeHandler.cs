using System;
using System.IO;
using System.Reflection;
using Xilium.CefGlue;
using Xilium.CefGlue.Common;
using Xilium.CefGlue.Common.Shared;

namespace SubsonicPlayer.Services;

/// <summary>为 app://ui/ 提供内嵌的 WebAssets 资源（AppUI.*），无需磁盘 WebAssets 目录。</summary>
public sealed class AppSchemeHandlerFactory : CefSchemeHandlerFactory
{
    private readonly Assembly _asm;
    private readonly System.Collections.Generic.HashSet<string> _resources;

    public AppSchemeHandlerFactory()
    {
        _asm = typeof(AppSchemeHandlerFactory).Assembly;
        _resources = new System.Collections.Generic.HashSet<string>(_asm.GetManifestResourceNames(), StringComparer.Ordinal);
        Log($"AppSchemeHandlerFactory ctor. resource count={_resources.Count}");
        foreach (var r in _resources)
            Log($"  resource: {r}");
    }

    protected override CefResourceHandler Create(CefBrowser browser, CefFrame frame, string schemeName, CefRequest request)
    {
        var url = request.Url;
        var path = ResolvePath(url);
        Log($"Create: scheme={schemeName} url={url} -> path={path ?? "<null>"}");

        if (path is null)
            return new NotFoundHandler();

        var resourceName = "AppUI." + path.Replace('/', '.');
        if (!_resources.Contains(resourceName))
        {
            Log($"  MISS resource: {resourceName}");
            return new NotFoundHandler();
        }

        var stream = _asm.GetManifestResourceStream(resourceName);
        if (stream is null)
        {
            Log($"  NULL stream for: {resourceName}");
            return new NotFoundHandler();
        }

        Log($"  HIT resource: {resourceName} (len={stream.Length})");
        return new ResourceHandler(stream, path);
    }

    /// <summary>把 app://ui/xxx 解析为相对于 WebAssets 的路径（如 index.html）；非法返回 null。</summary>
    private static string? ResolvePath(string url)
    {
        var path = url;
        var schemeEnd = path.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd >= 0)
        {
            path = path[(schemeEnd + 3)..];
            var slash = path.IndexOf('/');
            if (slash >= 0) path = path[(slash + 1)..];
        }

        // 去掉查询串
        var q = path.IndexOf('?');
        if (q >= 0) path = path[..q];
        var h = path.IndexOf('#');
        if (h >= 0) path = path[..h];

        if (string.IsNullOrEmpty(path))
            path = "index.html";

        // 防目录穿越
        if (path.Contains("..", StringComparison.Ordinal))
            return null;

        return path;
    }

    private static void Log(string msg)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            Directory.CreateDirectory(dir);
            File.AppendAllText(
                Path.Combine(dir, "scheme.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { }
    }
}

/// <summary>把内嵌资源流返回给 CEF（新式 Open/Read 异步 API）。</summary>
internal sealed class ResourceHandler : CefResourceHandler
{
    private readonly Stream _stream;
    private readonly string _name;

    public ResourceHandler(Stream stream, string name)
    {
        _stream = stream;
        _name = name;
    }

    protected override bool Open(CefRequest request, out bool handleRequest, CefCallback callback)
    {
        handleRequest = true;
        callback.Continue();
        return true;
    }

    protected override void GetResponseHeaders(CefResponse response, out long responseLength, out string redirectUrl)
    {
        response.Status = 200;
        response.StatusText = "OK";
        response.MimeType = MimeFor(_name);
        responseLength = _stream.Length;
        redirectUrl = null!;
    }

    protected override bool Read(Stream response, int bytesToRead, out int bytesRead, CefResourceReadCallback callback)
    {
        var buffer = new byte[bytesToRead];
        var read = _stream.Read(buffer, 0, bytesToRead);
        if (read > 0)
            response.Write(buffer, 0, read);

        bytesRead = read;
        if (read < bytesToRead)
        {
            _stream.Dispose();
        }

        return read > 0;
    }

    protected override bool Skip(long bytesToSkip, out long bytesSkipped, CefResourceSkipCallback callback)
    {
        bytesSkipped = 0;
        callback.Continue(bytesSkipped);
        return true;
    }

    protected override void Cancel() => _stream.Dispose();

    private static string MimeFor(string name)
    {
        var ext = Path.GetExtension(name).ToLowerInvariant();
        return ext switch
        {
            ".html" => "text/html",
            ".css" => "text/css",
            ".js" => "application/javascript",
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".gif" => "image/gif",
            ".svg" => "image/svg+xml",
            ".json" => "application/json",
            ".woff2" => "font/woff2",
            _ => "application/octet-stream",
        };
    }
}

/// <summary>404 兜底。</summary>
internal sealed class NotFoundHandler : CefResourceHandler
{
    protected override bool Open(CefRequest request, out bool handleRequest, CefCallback callback)
    {
        handleRequest = true;
        callback.Cancel();
        return true;
    }

    protected override void GetResponseHeaders(CefResponse response, out long responseLength, out string redirectUrl)
    {
        response.Status = 404;
        response.StatusText = "Not Found";
        response.MimeType = "text/plain";
        responseLength = 0;
        redirectUrl = null!;
    }

    protected override bool Read(Stream response, int bytesToRead, out int bytesRead, CefResourceReadCallback callback)
    {
        bytesRead = 0;
        return false;
    }

    protected override bool Skip(long bytesToSkip, out long bytesSkipped, CefResourceSkipCallback callback)
    {
        bytesSkipped = 0;
        callback.Continue(bytesSkipped);
        return true;
    }

    protected override void Cancel() { }
}

/// <summary>注册 app://ui/ 自定义 scheme 到 CEF。</summary>
public static class AppScheme
{
    public const string Name = "app";

    public static CustomScheme Build() => new()
    {
        SchemeName = Name,
        SchemeHandlerFactory = new AppSchemeHandlerFactory(),
    };
}
