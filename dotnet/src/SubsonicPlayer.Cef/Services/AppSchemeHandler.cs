using System;
using System.IO;
using Xilium.CefGlue;
using Xilium.CefGlue.Common;
using Xilium.CefGlue.Common.Shared;

namespace SubsonicPlayer.Services;

/// <summary>为 app://ui/ 提供本地 WebAssets 文件。</summary>
public sealed class AppSchemeHandlerFactory : CefSchemeHandlerFactory
{
    private readonly string _root;

    public AppSchemeHandlerFactory(string root) => _root = root;

    protected override CefResourceHandler Create(CefBrowser browser, CefFrame frame, string schemeName, CefRequest request)
    {
        var url = request.Url;
        Log($"Create: {url}");
        var path = ResolvePath(url);
        if (path is null)
            return new NotFoundHandler();

        return new FileResourceHandler(path);
    }

    private static void Log(string msg)
    {
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "subsonic-player");
            System.IO.Directory.CreateDirectory(dir);
            System.IO.File.AppendAllText(
                System.IO.Path.Combine(dir, "scheme.log"),
                $"[{DateTime.Now:HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { }
    }

    /// <summary>把 app://ui/xxx 解析为 WebAssets 下的绝对路径；非法返回 null。</summary>
    private string? ResolvePath(string url)
    {
        var path = url;
        var schemeEnd = path.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd >= 0)
        {
            path = path[(schemeEnd + 3)..];
            var slash = path.IndexOf('/');
            if (slash >= 0) path = path[(slash + 1)..];
        }

        if (string.IsNullOrEmpty(path))
            path = "index.html";

        // 防目录穿越
        var full = Path.GetFullPath(Path.Combine(_root, path));
        if (!full.StartsWith(Path.GetFullPath(_root), StringComparison.OrdinalIgnoreCase))
            return null;

        return File.Exists(full) ? full : null;
    }
}

/// <summary>把文件流返回给 CEF（新式 Open/Read 异步 API）。</summary>
internal sealed class FileResourceHandler : CefResourceHandler
{
    private readonly string _path;
    private Stream? _stream;

    public FileResourceHandler(string path) => _path = path;

    protected override bool Open(CefRequest request, out bool handleRequest, CefCallback callback)
    {
        _stream = File.OpenRead(_path);
        handleRequest = true;
        callback.Continue();
        return true;
    }

    protected override void GetResponseHeaders(CefResponse response, out long responseLength, out string redirectUrl)
    {
        response.Status = 200;
        response.StatusText = "OK";
        response.MimeType = MimeFor(_path);
        responseLength = _stream?.Length ?? 0;
        redirectUrl = null!;
    }

    protected override bool Read(Stream response, int bytesToRead, out int bytesRead, CefResourceReadCallback callback)
    {
        if (_stream is null)
        {
            bytesRead = 0;
            return false;
        }

        var buffer = new byte[bytesToRead];
        var read = _stream.Read(buffer, 0, bytesToRead);
        if (read > 0)
            response.Write(buffer, 0, read);

        bytesRead = read;
        if (read < bytesToRead)
        {
            _stream.Dispose();
            _stream = null;
        }

        return read > 0;
    }

    protected override bool Skip(long bytesToSkip, out long bytesSkipped, CefResourceSkipCallback callback)
    {
        bytesSkipped = 0;
        callback.Continue(bytesSkipped);
        return true;
    }

    protected override void Cancel()
    {
        _stream?.Dispose();
        _stream = null;
    }

    private static string MimeFor(string path)
    {
        var ext = Path.GetExtension(path).ToLowerInvariant();
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

    public static CustomScheme Build(string webRoot) => new()
    {
        SchemeName = Name,
        SchemeHandlerFactory = new AppSchemeHandlerFactory(webRoot),
    };
}
