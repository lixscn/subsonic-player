package com.lixscn.subsonicplayer.core;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌词获取：服务端歌词优先，缺失时走网络兜底。
 *
 * <p>兜底顺序（照搬桌面版 C# LyricsSearchService 的策略）：
 * <ol>
 *   <li>LRCLIB 精确匹配 {@code /api/get?artist_name=&track_name=&duration=}（字段 syncedLyrics / plainLyrics）</li>
 *   <li>LRCLIB 搜索 {@code /api/search?q=}（数组，逐条找同步歌词，退化取第一条纯文本）</li>
 *   <li>网易云 {@code /api/search/get} 取歌曲 id → {@code /api/song/lyric?id=&lv=1&kv=1&tv=-1} 取 {@code lrc.lyric}</li>
 * </ol>
 *
 * <p>优先同步歌词（LRC → {@link LrcParser}），没有同步歌词则填 {@link Lyrics#plainText}。
 * 网络失败 / 超时 / 解析异常一律静默降级，最终无结果时回调 {@code null}，绝不向调用方抛异常。
 * 请求在后台线程执行，回调通过主线程 Handler 派发。
 *
 * <p>只依赖 Android Framework（HttpURLConnection / org.json / Handler）+ Java 8 API。
 */
public class LyricsProvider {

    /** 结果回调，必定在主线程调用。 */
    public interface Callback {
        /** @param lyrics 命中的歌词；失败时为 null */
        void onLyrics(Lyrics lyrics);
    }

    /** 通用请求超时（网易云）。 */
    private static final int TIMEOUT_MS = 10000;
    /** LRCLIB 短超时，对齐桌面版的 HttpFast：LRCLIB 在部分网络下连接挂起，避免拖慢中文歌兜底。 */
    private static final int TIMEOUT_LRCLIB_MS = 4000;
    /** 一次 load 的网络总预算，防止多级兜底串行叠加导致长时间占用线程。 */
    private static final long TOTAL_BUDGET_MS = 40000L;

    /** LRCLIB 要求带可识别的 UA。 */
    private static final String UA_LRCLIB = "SubsonicPlayerAndroid/1.0 (https://github.com/anomalyco)";
    /** 网易云接口需要浏览器式 UA，否则可能被拒。 */
    private static final String UA_BROWSER =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    /** 网易云接口必需的 Referer。 */
    private static final String NETEASE_REFERER = "https://music.163.com/";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** LRC 元信息标签行（[ar:..] / [ti:..] / [offset:..] 等），纯文本展示时剔除。 */
    private static final java.util.regex.Pattern META_TAG =
            java.util.regex.Pattern.compile("^\\[(ar|ti|al|by|offset|length|kana|re|ve|au|encoding):.*\\]$");

    private final Handler mainHandler;
    /** 进行中的请求，用于 cancelAll。 */
    private final Set<Request> active =
            Collections.newSetFromMap(new ConcurrentHashMap<Request, Boolean>());

    public LyricsProvider() {
        // 显式绑定主线程 Looper，构造发生在任意线程都安全
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 主入口：先同步返回服务端歌词（有效时不再发网络请求），否则后台查网络后异步回调。
     *
     * @param serverLyrics 服务端已拿到的歌词（可为 null）
     * @param artist       艺术家（可为 null/空）
     * @param title        标题
     * @param durationSec  时长秒（可为 0，仅用于 LRCLIB 精确匹配）
     * @param cb           结果回调（必定在主线程调用；失败传 null）
     */
    public void load(final Lyrics serverLyrics, final String artist, final String title,
                     final int durationSec, final Callback cb) {
        if (cb == null) {
            return;
        }

        // 1) 服务端歌词优先：非空且内容有效 → 直接用，不发网络请求
        try {
            if (serverLyrics != null && !serverLyrics.isEmpty()) {
                normalize(serverLyrics, artist, title);
                deliverSync(cb, serverLyrics);
                return;
            }
        } catch (Throwable ignored) {
            // 服务端歌词异常不应影响网络兜底
        }

        // 2) 网络兜底
        final String cleanArtist = cleanArtist(artist);
        final String cleanTitle = cleanTitle(title, artist);
        if (cleanTitle.isEmpty()) {
            // 没有可用标题，无从搜索，直接回调 null（仍保证主线程）
            deliverSync(cb, null);
            return;
        }

        final Request request = new Request();
        active.add(request);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                Lyrics result = null;
                try {
                    long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
                    result = tryLrclib(request, cleanArtist, cleanTitle, durationSec, deadline);
                    if (result == null && alive(request) && System.currentTimeMillis() <= deadline) {
                        result = tryNetEase(request, cleanArtist, cleanTitle, deadline);
                    }
                } catch (Throwable ignored) {
                    result = null;
                } finally {
                    active.remove(request);
                }
                postResult(request, cb, result);
            }
        }, "lyrics-fetch");
        worker.setDaemon(true);
        request.thread = worker;
        worker.start();
    }

    /** 取消未完成的请求（页面销毁时调用），取消后不得再回调。 */
    public void cancelAll() {
        List<Request> snapshot = new ArrayList<Request>(active);
        active.clear();
        for (int i = 0; i < snapshot.size(); i++) {
            Request request = snapshot.get(i);
            if (request == null) {
                continue;
            }
            request.cancelled = true;
            HttpURLConnection conn = request.connection;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // 断开失败无所谓，cancelled 标记已保证不再回调
                }
            }
            Thread thread = request.thread;
            if (thread != null) {
                try {
                    thread.interrupt();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    // ---------------------------------------------------------------- 网络兜底

    /** LRCLIB：先精确匹配 /api/get，失败再 /api/search。 */
    private Lyrics tryLrclib(Request request, String artist, String title, int durationSec, long deadline) {
        // 1) 精确匹配
        try {
            StringBuilder url = new StringBuilder("https://lrclib.net/api/get?artist_name=");
            url.append(enc(artist)).append("&track_name=").append(enc(title));
            if (durationSec > 0) {
                url.append("&duration=").append(durationSec);
            }
            String body = httpGet(request, url.toString(), null, TIMEOUT_LRCLIB_MS);
            if (body != null) {
                Lyrics hit = fromLrclib(new JSONObject(body), artist, title);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable ignored) {
            // 落空 → 搜索
        }
        if (!alive(request) || System.currentTimeMillis() > deadline) {
            return null;
        }

        // 2) 搜索（数组）：优先取同步歌词，退化取第一条纯文本
        try {
            String query = artist.isEmpty() ? title : artist + " " + title;
            String body = httpGet(request, "https://lrclib.net/api/search?q=" + enc(query),
                    null, TIMEOUT_LRCLIB_MS);
            if (body == null) {
                return null;
            }
            JSONArray array = new JSONArray(body);
            Lyrics plainCandidate = null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Lyrics hit = fromLrclib(item, artist, title);
                if (hit == null) {
                    continue;
                }
                if (hit.isSynced()) {
                    return hit;
                }
                if (plainCandidate == null) {
                    plainCandidate = hit;
                }
            }
            return plainCandidate;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 网易云：搜索歌曲 id → 取 LRC 歌词。 */
    private Lyrics tryNetEase(Request request, String artist, String title, long deadline) {
        if (!alive(request) || System.currentTimeMillis() > deadline) {
            return null;
        }
        try {
            String query = (artist + " " + title).trim();
            String searchBody = httpGet(request,
                    "https://music.163.com/api/search/get?s=" + enc(query) + "&type=1&limit=5",
                    NETEASE_REFERER, TIMEOUT_MS);
            if (searchBody == null) {
                return null;
            }
            JSONObject root = new JSONObject(searchBody);
            JSONObject result = root.optJSONObject("result");
            if (result == null) {
                return null;
            }
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null) {
                return null;
            }

            Lyrics plainCandidate = null;
            int limit = Math.min(songs.length(), 5);
            for (int i = 0; i < limit; i++) {
                if (!alive(request) || System.currentTimeMillis() > deadline) {
                    break;
                }
                JSONObject song = songs.optJSONObject(i);
                if (song == null) {
                    continue;
                }
                long songId = asLong(song.opt("id"));
                if (songId <= 0) {
                    continue;
                }
                String lyricBody = httpGet(request,
                        "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1",
                        NETEASE_REFERER, TIMEOUT_MS);
                if (lyricBody == null) {
                    continue;
                }
                JSONObject lyricRoot = new JSONObject(lyricBody);
                JSONObject lrc = lyricRoot.optJSONObject("lrc");
                if (lrc == null) {
                    continue;
                }
                String lrcText = optString(lrc, "lyric");
                if (isBlank(lrcText)) {
                    continue;
                }
                Lyrics hit = build(lrcText, stripJunkLines(lrcText));
                if (hit == null) {
                    continue;
                }
                fillDisplay(hit, artist, title);
                if (hit.isSynced()) {
                    return hit;
                }
                if (plainCandidate == null) {
                    plainCandidate = hit;
                }
            }
            return plainCandidate;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** LRCLIB JSON（/api/get 为对象，/api/search 的每个元素同样结构）→ Lyrics。 */
    private static Lyrics fromLrclib(JSONObject item, String artist, String title) {
        if (item == null) {
            return null;
        }
        String synced = optString(item, "syncedLyrics");
        String plain = optString(item, "plainLyrics");
        Lyrics lyrics = build(synced, plain);
        if (lyrics == null) {
            return null;
        }
        String displayArtist = optString(item, "artistName");
        String displayTitle = optString(item, "trackName");
        lyrics.displayArtist = isBlank(displayArtist) ? trimToNull(artist) : displayArtist;
        lyrics.displayTitle = isBlank(displayTitle) ? trimToNull(title) : displayTitle;
        return lyrics;
    }

    /**
     * 同步歌词优先：syncedLyrics 能解析出时间轴就用 lines；
     * 否则用 plainLyrics（缺失时退而用 synced 原文）填 plainText。
     */
    private static Lyrics build(String synced, String plain) {
        if (!isBlank(synced)) {
            List<Lyrics.Line> lines = LrcParser.parse(synced);
            if (!lines.isEmpty()) {
                Lyrics lyrics = new Lyrics();
                lyrics.lines = lines;
                return lyrics;
            }
        }
        if (!isBlank(plain)) {
            Lyrics lyrics = new Lyrics();
            lyrics.plainText = plain;
            return lyrics;
        }
        if (!isBlank(synced)) {
            Lyrics lyrics = new Lyrics();
            lyrics.plainText = synced;
            return lyrics;
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * GET 一个 URL，按 UTF-8 解码后返回文本；非 2xx / 出错 / 已取消返回 null。
     * 不抛异常给上层（IOException 由调用处 catch）。
     */
    private String httpGet(Request request, String url, String referer, int timeoutMs) throws IOException {
        if (!alive(request)) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", referer == null ? UA_LRCLIB : UA_BROWSER);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (referer != null) {
                // 网易云接口必需
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            }
            request.connection = conn;

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                byte[] bytes = readAll(in);
                String text = new String(bytes, "UTF-8"); // 显式 UTF-8 解码
                if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
                    text = text.substring(1); // 去 BOM
                }
                return text;
            } finally {
                closeQuietly(in);
            }
        } finally {
            if (request.connection == conn) {
                request.connection = null;
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    // ---------------------------------------------------------------- 回调

    /** 当前线程是主线程就直接调用（服务端歌词走同步返回），否则 post 到主线程。 */
    private void deliverSync(Callback cb, Lyrics lyrics) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                cb.onLyrics(lyrics);
            } catch (Throwable ignored) {
                // 回调异常不向外抛
            }
            return;
        }
        postResult(null, cb, lyrics);
    }

    /** 后台线程结果 → 主线程回调；已取消则丢弃。 */
    private void postResult(final Request request, final Callback cb, final Lyrics lyrics) {
        if (request != null && !alive(request)) {
            return;
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (request != null && !alive(request)) {
                    return; // 取消后不得再回调
                }
                try {
                    cb.onLyrics(lyrics);
                } catch (Throwable ignored) {
                    // 回调异常不向外抛
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    /** 请求是否仍在有效期内（未被 cancelAll 取消）。active 只用于登记/清理，不参与判定。 */
    private boolean alive(Request request) {
        return request != null && !request.cancelled;
    }

    // ---------------------------------------------------------------- 工具

    /** 进行中的一次请求。 */
    private static final class Request {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Thread thread;
    }

    /** 服务端歌词补全展示字段，并在只给纯文本但内容其实是 LRC 时补出同步行。 */
    private static void normalize(Lyrics lyrics, String artist, String title) {
        try {
            if (isBlank(lyrics.displayArtist)) {
                lyrics.displayArtist = trimToNull(artist);
            }
            if (isBlank(lyrics.displayTitle)) {
                lyrics.displayTitle = trimToNull(title);
            }
            if ((lyrics.lines == null || lyrics.lines.isEmpty()) && !isBlank(lyrics.plainText)) {
                List<Lyrics.Line> parsed = LrcParser.parse(lyrics.plainText);
                if (!parsed.isEmpty()) {
                    if (lyrics.lines == null) {
                        lyrics.lines = new ArrayList<Lyrics.Line>();
                    } else {
                        lyrics.lines.clear();
                    }
                    lyrics.lines.addAll(parsed);
                }
            }
        } catch (Throwable ignored) {
            // 归一化失败不影响使用
        }
    }

    /** 去掉网易云 lrc.lyric 里可能混入的非歌词元信息行。 */
    private static String stripJunkLines(String lrc) {
        if (lrc == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(lrc.length());
        String[] rawLines = lrc.split("\n");
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line.indexOf('\r') >= 0) {
                line = line.replace("\r", "");
            }
            String lower = line.trim().toLowerCase();
            if (lower.startsWith("{\"t\":") || lower.startsWith("{ \"t\":")) {
                continue;
            }
            if (META_TAG.matcher(lower).matches()) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static void fillDisplay(Lyrics lyrics, String artist, String title) {
        if (lyrics == null) {
            return;
        }
        if (isBlank(lyrics.displayArtist)) {
            lyrics.displayArtist = trimToNull(artist);
        }
        if (isBlank(lyrics.displayTitle)) {
            lyrics.displayTitle = trimToNull(title);
        }
    }

    /** 清理 Gonic 等不规范的 title（去「- 艺术家」前后缀），与桌面版一致。 */
    private static String cleanTitle(String title, String artist) {
        String t = title == null ? "" : title.trim();
        String a = artist == null ? "" : artist.trim();
        if (a.isEmpty()) {
            return t;
        }
        String suffix = " - " + a;
        if (t.length() > suffix.length() && t.regionMatches(true, t.length() - suffix.length(), suffix, 0, suffix.length())) {
            return t.substring(0, t.length() - suffix.length()).trim();
        }
        String prefix = a + " - ";
        if (t.length() > prefix.length() && t.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return t.substring(prefix.length()).trim();
        }
        return t;
    }

    private static String cleanArtist(String artist) {
        return artist == null ? "" : artist.trim();
    }

    /** RFC 3986 百分号编码（对齐 C# Uri.EscapeDataString：空格为 %20）。 */
    private static String enc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        byte[] bytes;
        try {
            bytes = s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = s.getBytes();
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(HEX[(c >> 4) & 0xF]);
                sb.append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 安全读取字符串字段：字段缺失或为 JSON null 时返回 null（org.json 的 optString 会返回 "null"）。 */
    private static String optString(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return null;
        }
        String value = o.optString(key, null);
        return value == null ? null : value;
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
