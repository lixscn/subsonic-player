package com.lixscn.subsonicplayer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 工具（HttpURLConnection，无第三方依赖）。
 *
 * 关键点：
 * - 响应体一律按 UTF-8 解码：本服务端返回 text/xml|application/json 且不带 charset，
 *   若交给默认解码会变 Latin-1 乱码（桌面版 .NET 踩过这个坑）。
 * - 自动跟随重定向（本服务端 nginx 对 /rest/xxx 会 301 到 /rest/xxx/）。
 */
public final class Http {

    private static final String UA = "SubsonicPlayer-Android/0.1";
    /** 连接超时上限：不可达地址（尤其蜂窝网络访问内网 IP）只能靠超时判定，不能等太久 */
    private static final int CONNECT_TIMEOUT_CAP_MS = 8000;

    private Http() {
    }

    /** 网络层异常（区别于服务端返回的业务错误） */
    public static class HttpException extends IOException {
        public final int status;

        public HttpException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    /** GET 文本（UTF-8）。非 2xx 抛 HttpException。 */
    public static String getText(String url, int timeoutMs) throws IOException {
        byte[] bytes = getBytes(url, timeoutMs);
        return new String(bytes, "UTF-8");
    }

    /** GET 字节。用于封面等二进制。 */
    public static byte[] getBytes(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = open(url, timeoutMs, true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readSmall(conn.getErrorStream());
                throw new HttpException(code, "HTTP " + code + " " + body);
            }
            in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            return readAll(in);
        } finally {
            closeQuietly(in);
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 打开连接。followRedirect=false 时用于探测服务端是否强制尾斜杠。
     * 调用方负责 disconnect。
     *
     * 注意：连接超时单独封顶（默认 8 秒）。有些网络（例如手机在蜂窝数据上访问内网地址）不会回 RST，
     * 而是一直丢包，此时只有超时才能判定失败 —— 若沿用 20 秒的读超时，冷启动会白等 20 秒。
     */
    public static HttpURLConnection open(String url, int timeoutMs, boolean followRedirect) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(followRedirect);
        conn.setConnectTimeout(Math.min(timeoutMs, CONNECT_TIMEOUT_CAP_MS));
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        return conn;
    }

    /** 只取响应头，不读 body（探测用，读完立即断开） */
    public static int headStatus(String url, int timeoutMs, boolean followRedirect) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(url, timeoutMs, followRedirect);
            return conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 探测重定向目标（Location 头）；无重定向返回 null */
    public static String redirectLocation(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, timeoutMs, false);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307 || code == 308) {
                return conn.getHeaderField("Location");
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readSmall(InputStream in) {
        if (in == null) return "";
        try {
            byte[] b = readAll(in);
            String s = new String(b, "UTF-8");
            return s.length() > 300 ? s.substring(0, 300) : s;
        } catch (Exception e) {
            return "";
        }
    }

    public static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** URL 参数编码（RFC 3986，空格用 %20 而非 +） */
    public static String enc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        byte[] bytes;
        try {
            bytes = s.getBytes("UTF-8");
        } catch (Exception e) {
            return s;
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
