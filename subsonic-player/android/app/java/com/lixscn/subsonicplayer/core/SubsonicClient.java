package com.lixscn.subsonicplayer.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Subsonic / OpenSubsonic 协议客户端（纯 Java，org.json 解析）。
 *
 * 针对本机服务端（musicTag / Gonic 系的「道理鱼音乐」）实测得到的要点：
 * 1. nginx 会把 /rest/xxx 301 到 /rest/xxx/，构造 URL 前需探测尾斜杠（流媒体交给 MediaPlayer 时尤其重要）。
 * 2. 认证用 p=enc:&lt;密码字节的十六进制&gt;（比明文 p= 更不容易出现在日志里，服务端两种都接受）。
 * 3. 封面 id 形如 al-&lt;albumId&gt;，部分响应里 coverArt 为空，需要按此约定兜底。
 * 4. 歌曲 JSON 同时有 bitrate 与 bitRate 两个重复键，解析时取其一即可。
 * 5. getBookmarks 可能返回空体（本服务端如此），必须容错。
 * 6. stream / download 返回**原始文件**（不转码），且支持 Range（可拖动进度）。
 */
public class SubsonicClient {

    private static final String TAG = "SubsonicClient";
    private static final String API_VERSION = "1.16.1";
    private static final String CLIENT_NAME = "SubsonicPlayer-Android";

    /** 服务端返回的业务错误 */
    public static class ApiException extends Exception {
        public final int code;

        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 搜索结果 */
    public static class SearchResult {
        public final List<Item> artists = Item.list();
        public final List<Item> albums = Item.list();
        public final List<Item> songs = Item.list();

        public boolean isEmpty() {
            return artists.isEmpty() && albums.isEmpty() && songs.isEmpty();
        }

        public int total() {
            return artists.size() + albums.size() + songs.size();
        }
    }

    private final String baseUrl;
    private final String username;
    private final String password;

    private boolean prefixKnown;
    private boolean trailingSlash;
    private String activeUrl;

    /**
     * 是否使用 token+salt 认证（Subsonic 标准）。
     *
     * 为什么优先用它：`p=<明文>` 与 `p=enc:<hex>` 都会把**可还原的密码**写进 URL，
     * 而反代（nginx）的 access log 默认会记录完整请求行 —— 密码因此落进公网服务器的日志里。
     * `t=MD5(密码+salt)&s=salt` 只在单次请求内有效，日志泄露也拿不到密码。
     * 服务端若不支持（老版本/非标准实现），遇到鉴权失败会自动退回 p=enc: 并记住。
     */
    private boolean useToken = true;
    private boolean tokenFallbackDone;

    public SubsonicClient(Settings.Service svc) {
        String lan = svc.lanUrl == null ? "" : svc.lanUrl.trim();
        String wan = svc.wanUrl == null ? "" : svc.wanUrl.trim();
        while (lan.endsWith("/")) lan = lan.substring(0, lan.length() - 1);
        while (wan.endsWith("/")) wan = wan.substring(0, wan.length() - 1);
        // 内网优先，失败时回退外网
        this.baseUrl = lan.length() > 0 ? lan : wan;
        this.username = svc.username == null ? "" : svc.username;
        this.password = svc.password == null ? "" : svc.password;
        this.activeUrl = this.baseUrl;
    }

    /** 每次请求生成新的 salt（token 认证） */
    private String authQuery() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("u=").append(Http.enc(username));
        if (useToken) {
            String salt = newSalt();
            sb.append("&t=").append(md5Hex(password + salt)).append("&s=").append(salt);
        } else {
            sb.append("&p=enc:").append(hex(password));
        }
        sb.append("&v=").append(API_VERSION).append("&c=").append(Http.enc(CLIENT_NAME));
        return sb.toString();
    }

    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static String newSalt() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append(SALT_CHARS.charAt(r.nextInt(SALT_CHARS.length())));
        return sb.toString();
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public String activeUrl() {
        return activeUrl;
    }

    /** 换用备用地址（内网不通时用外网重试） */
    public void useUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.length() > 0) {
            activeUrl = u;
            prefixKnown = false;
        }
    }

    public String lanUrl() {
        return baseUrl;
    }

    private static String hex(String s) {
        try {
            byte[] b = s.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- URL 构造 ----------------

    private String restPath(String endpoint) {
        return "/rest/" + endpoint + (trailingSlash ? "/" : "");
    }

    /** 探测服务端是否强制尾斜杠（只做一次） */
    public void learnPrefix() {
        if (prefixKnown) return;
        prefixKnown = true;
        try {
            String probe = activeUrl + "/rest/ping?" + authQuery();
            int code = Http.headStatus(probe, 6000, false);
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                trailingSlash = true;
            }
        } catch (Exception e) {
            // 探测失败就按无尾斜杠处理，后续请求会自动跟随重定向
        }
    }

    private String dataUrl(String endpoint, String extra) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(activeUrl).append(restPath(endpoint)).append('?').append(authQuery()).append("&f=json");
        if (extra != null && extra.length() > 0) sb.append(extra);
        return sb.toString();
    }

    private String binaryUrl(String endpoint, String extra) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(activeUrl).append(restPath(endpoint)).append('?').append(authQuery());
        if (extra != null && extra.length() > 0) sb.append(extra);
        return sb.toString();
    }

    // ---------------- 请求 ----------------

    private JSONObject call(String endpoint, String extra) throws Exception {
        learnPrefix();
        try {
            return parseRoot(Http.getText(dataUrl(endpoint, extra), 20000));
        } catch (ApiException e) {
            // 服务端不支持 token 认证（鉴权失败）→ 自动退回 p=enc: 并重试一次
            if (e.code == 40 && useToken && !tokenFallbackDone) {
                tokenFallbackDone = true;
                useToken = false;
                Log.i(TAG, "服务端不支持 token 认证，改用 p=enc: 兼容模式");
                return parseRoot(Http.getText(dataUrl(endpoint, extra), 20000));
            }
            throw e;
        }
    }

    private JSONObject parseRoot(String body) throws ApiException {
        if (body == null || body.trim().length() == 0) {
            throw new ApiException(-1, "服务端返回空响应");
        }
        try {
            JSONObject all = new JSONObject(body);
            JSONObject resp = all.optJSONObject("subsonic-response");
            if (resp == null) {
                throw new ApiException(-1, "响应缺少 subsonic-response 节点");
            }
            String status = resp.optString("status", "");
            if (!"ok".equals(status)) {
                JSONObject err = resp.optJSONObject("error");
                int code = err == null ? -1 : err.optInt("code", -1);
                String msg = err == null ? "未知错误" : err.optString("message", "未知错误");
                throw new ApiException(code, msg);
            }
            return resp;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "响应解析失败: " + e.getMessage());
        }
    }

    public boolean ping() {
        return ping(20000);
    }

    /**
     * 带超时的连通性探测。
     *
     * 用于「内网 → 外网」快速切换：内网地址不在家时不可达，必须用很短的时间判定失败，
     * 否则每次冷启动都要白等一个完整的请求超时。探测不做尾斜杠预判（重定向会自动跟随），
     * 以节省一次往返。
     */
    public boolean ping(int timeoutMs) {
        try {
            return parseRoot(Http.getText(dataUrl("ping", null), timeoutMs)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 解析辅助 ----------------

    private static JSONArray arr(JSONObject o, String key) {
        if (o == null) return new JSONArray();
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return new JSONArray();
        if (v instanceof JSONArray) return (JSONArray) v;
        JSONArray a = new JSONArray();
        a.put(v);
        return a;
    }

    private static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    private static String str(JSONObject o, String key) {
        if (o == null) return "";
        String v = o.optString(key, "");
        return v == null ? "" : v;
    }

    private static int num(JSONObject o, String key) {
        if (o == null) return 0;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long lnum(JSONObject o, String key) {
        if (o == null) return 0;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 清理服务端脏数据：Go 内存地址、&lt;nil&gt; 等 */
    private static String clean(String s) {
        if (s == null) return "";
        if (s.startsWith("0x")) return "";
        if (s.equalsIgnoreCase("nil") || s.equalsIgnoreCase("<nil>")) return "";
        return s;
    }

    private static boolean isStarred(JSONObject o) {
        if (o == null) return false;
        Object v = o.opt("starred");
        if (v == null || v == JSONObject.NULL) return false;
        String s = String.valueOf(v);
        return s.length() > 0 && !"false".equals(s);
    }

    // ---------------- 实体解析 ----------------

    public static Item parseSong(JSONObject o) {
        Item it = Item.song();
        it.id = str(o, "id");
        it.title = clean(str(o, "title"));
        if (it.title.length() == 0) it.title = "(未知曲目)";
        it.artist = clean(str(o, "artist"));
        it.artistId = str(o, "artistId");
        it.album = clean(str(o, "album"));
        it.albumId = str(o, "albumId");
        it.durationSec = num(o, "duration");
        it.track = num(o, "track");
        it.discNumber = Math.max(1, num(o, "discNumber"));
        it.year = num(o, "year");
        it.coverArt = clean(str(o, "coverArt"));
        it.suffix = str(o, "suffix");
        it.contentType = str(o, "contentType");
        int br = num(o, "bitRate");
        it.bitrate = br > 0 ? br : num(o, "bitrate");
        it.size = lnum(o, "size");
        it.genre = clean(str(o, "genre"));
        it.playCount = lnum(o, "playCount");
        it.rating = num(o, "userRating");
        it.path = str(o, "path");
        it.starred = isStarred(o);
        it.starredAt = isStarred(o) ? str(o, "starred") : "";
        if (it.coverArt.length() == 0 && it.albumId.length() > 0) {
            it.coverArt = "al-" + it.albumId;   // 本服务端封面 id 约定
        }
        it.subtitle = songSubtitle(it);
        return it;
    }

    private static String songSubtitle(Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.artist.length() > 0 && !"未知艺术家".equals(it.artist)) sb.append(it.artist);
        if (it.album.length() > 0 && !"未知".equals(it.album) && !"未知专辑".equals(it.album)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(it.album);
        }
        if (sb.length() == 0 && it.album.length() > 0) sb.append(it.album);
        return sb.toString();
    }

    public static Item parseAlbum(JSONObject o) {
        Item it = Item.album();
        it.id = str(o, "id");
        it.title = clean(str(o, "name"));
        if (it.title.length() == 0) it.title = clean(str(o, "title"));
        if (it.title.length() == 0) it.title = "(未知专辑)";
        it.artist = clean(str(o, "artist"));
        it.artistId = str(o, "artistId");
        it.coverArt = clean(str(o, "coverArt"));
        it.songCount = num(o, "songCount");
        it.durationSec = num(o, "duration");
        it.year = num(o, "year");
        it.genre = clean(str(o, "genre"));
        it.starred = isStarred(o);
        it.starredAt = it.starred ? str(o, "starred") : "";
        it.playCount = lnum(o, "playCount");
        if (it.coverArt.length() == 0 && it.id.length() > 0) it.coverArt = "al-" + it.id;
        String sub = it.artist;
        if (it.year > 0) sub = sub.length() > 0 ? sub + " · " + it.year : String.valueOf(it.year);
        it.subtitle = sub;
        return it;
    }

    public static Item parseArtist(JSONObject o) {
        Item it = Item.artist();
        it.id = str(o, "id");
        it.title = clean(str(o, "name"));
        if (it.title.length() == 0) it.title = "(未知艺术家)";
        it.coverArt = clean(str(o, "coverArt"));
        int ac = albumCountOf(o);
        it.songCount = ac;
        it.subtitle = ac > 0 ? ac + " 张专辑" : "";
        return it;
    }

    private static int albumCountOf(JSONObject o) {
        return num(o, "albumCount");
    }

    public static Item parsePlaylist(JSONObject o) {
        Item it = Item.playlist();
        it.id = str(o, "id");
        it.title = clean(str(o, "name"));
        if (it.title.length() == 0) it.title = "(未命名歌单)";
        it.coverArt = clean(str(o, "coverArt"));
        it.songCount = num(o, "songCount");
        it.durationSec = num(o, "duration");
        it.subtitle = clean(str(o, "owner"));
        return it;
    }

    // ---------------- 业务端点 ----------------

    /** 艺术家全集（扁平列表，按服务端 index 顺序） */
    public List<Item> getArtists() throws Exception {
        JSONObject r = call("getArtists", null);
        List<Item> out = Item.list();
        JSONArray indexes = arr(obj(r, "artists"), "index");
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject idx = indexes.optJSONObject(i);
            JSONArray artists = arr(idx, "artist");
            for (int j = 0; j < artists.length(); j++) {
                JSONObject a = artists.optJSONObject(j);
                if (a != null) out.add(parseArtist(a));
            }
        }
        return out;
    }

    /** 专辑分页（type: alphabeticalByArtist / alphabeticalByName / random / newest / recent / frequent / starred / byYear / byGenre） */
    public List<Item> getAlbums(String type, int size, int offset) throws Exception {
        StringBuilder q = new StringBuilder();
        q.append("&type=").append(Http.enc(type)).append("&size=").append(size);
        if (offset > 0) q.append("&offset=").append(offset);
        JSONObject r = call("getAlbumList2", q.toString());
        List<Item> out = Item.list();
        JSONArray albums = arr(obj(r, "albumList2"), "album");
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.optJSONObject(i);
            if (a != null) out.add(parseAlbum(a));
        }
        return out;
    }

    /** 专辑详情（含曲目） */
    public Item getAlbum(String id) throws Exception {
        JSONObject r = call("getAlbum", "&id=" + Http.enc(id));
        JSONObject a = obj(r, "album");
        if (a == null) return null;
        Item it = parseAlbum(a);
        it.songs = parseSongs(arr(a, "song"));
        return it;
    }

    private static List<Item> parseSongs(JSONArray arr) {
        List<Item> out = Item.list();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            Item song = parseSong(s);
            song.listIndex = i;
            out.add(song);
        }
        return out;
    }

    /** 艺术家的专辑列表 */
    public List<Item> getArtistAlbums(String artistId) throws Exception {
        JSONObject r = call("getArtist", "&id=" + Http.enc(artistId));
        List<Item> out = Item.list();
        JSONArray albums = arr(obj(r, "artist"), "album");
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.optJSONObject(i);
            if (a != null) out.add(parseAlbum(a));
        }
        return out;
    }

    /** 艺术家信息（简介 + 相似艺术家），失败返回 null */
    public JSONObject getArtistInfo(String artistId) {
        try {
            JSONObject r = call("getArtistInfo2", "&id=" + Http.enc(artistId));
            return obj(r, "artistInfo2");
        } catch (Exception e) {
            return null;
        }
    }

    public SearchResult search(String query, int count) throws Exception {
        String q = "&query=" + Http.enc(query) + "&artistCount=" + count
                + "&albumCount=" + count + "&songCount=" + count;
        JSONObject r = call("search3", q);
        JSONObject res = obj(r, "searchResult3");
        SearchResult out = new SearchResult();
        if (res == null) return out;
        JSONArray artists = arr(res, "artist");
        for (int i = 0; i < artists.length(); i++) {
            JSONObject a = artists.optJSONObject(i);
            if (a != null) out.artists.add(parseArtist(a));
        }
        JSONArray albums = arr(res, "album");
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.optJSONObject(i);
            if (a != null) out.albums.add(parseAlbum(a));
        }
        JSONArray songs = arr(res, "song");
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s != null) out.songs.add(parseSong(s));
        }
        return out;
    }

    /** 收藏的歌曲 */
    public List<Item> getStarredSongs() throws Exception {
        JSONObject r = call("getStarred2", null);
        return parseSongs(arr(obj(r, "starred2"), "song"));
    }

    /** 收藏的专辑 */
    public List<Item> getStarredAlbums() throws Exception {
        JSONObject r = call("getStarred2", null);
        List<Item> out = Item.list();
        JSONArray albums = arr(obj(r, "starred2"), "album");
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.optJSONObject(i);
            if (a != null) out.add(parseAlbum(a));
        }
        return out;
    }

    /** 收藏的艺术家 */
    public List<Item> getStarredArtists() throws Exception {
        JSONObject r = call("getStarred2", null);
        List<Item> out = Item.list();
        JSONArray artists = arr(obj(r, "starred2"), "artist");
        for (int i = 0; i < artists.length(); i++) {
            JSONObject a = artists.optJSONObject(i);
            if (a != null) out.add(parseArtist(a));
        }
        return out;
    }

    public List<Item> getGenres() throws Exception {
        JSONObject r = call("getGenres", null);
        List<Item> out = Item.list();
        JSONArray genres = arr(obj(r, "genres"), "genre");
        for (int i = 0; i < genres.length(); i++) {
            JSONObject g = genres.optJSONObject(i);
            if (g == null) continue;
            String name = clean(str(g, "value"));
            if (name.length() == 0) name = clean(g.optString("name", ""));
            if (name.length() == 0) continue;
            Item it = Item.genre();
            it.title = name;
            it.id = name;
            it.songCount = num(g, "songCount");
            int ac = num(g, "albumCount");
            StringBuilder sb = new StringBuilder();
            if (it.songCount > 0) sb.append(it.songCount).append(" 首");
            if (ac > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(ac).append(" 张专辑");
            }
            it.subtitle = sb.toString();
            out.add(it);
        }
        return out;
    }

    public List<Item> getSongsByGenre(String genre, int count, int offset) throws Exception {
        StringBuilder q = new StringBuilder();
        q.append("&genre=").append(Http.enc(genre)).append("&count=").append(count);
        if (offset > 0) q.append("&offset=").append(offset);
        JSONObject r = call("getSongsByGenre", q.toString());
        return parseSongs(arr(obj(r, "songsByGenre"), "song"));
    }

    /** 随机歌曲（本服务端不支持 getRandomSongs，用随机专辑各取一首兜底） */
    public List<Item> getRandomSongs(int size) throws Exception {
        List<Item> out = Item.list();
        List<Item> albums = getAlbums("random", size, 0);
        for (Item al : albums) {
            try {
                Item detail = getAlbum(al.id);
                if (detail != null && detail.songs != null && !detail.songs.isEmpty()) {
                    out.add(detail.songs.get(0));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<Item> getPlaylists() throws Exception {
        JSONObject r = call("getPlaylists", null);
        List<Item> out = Item.list();
        JSONArray arr = arr(obj(r, "playlists"), "playlist");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null) out.add(parsePlaylist(p));
        }
        return out;
    }

    public Item getPlaylist(String id) throws Exception {
        JSONObject r = call("getPlaylist", "&id=" + Http.enc(id));
        JSONObject p = obj(r, "playlist");
        if (p == null) return null;
        Item it = parsePlaylist(p);
        it.songs = parseSongs(arr(p, "entry"));
        return it;
    }

    public Item createPlaylist(String name, List<String> songIds) throws Exception {
        StringBuilder q = new StringBuilder("&name=").append(Http.enc(name));
        if (songIds != null && !songIds.isEmpty()) {
            q.append("&songId=");
            for (int i = 0; i < songIds.size(); i++) {
                if (i > 0) q.append(',');
                q.append(Http.enc(songIds.get(i)));
            }
        }
        JSONObject r = call("createPlaylist", q.toString());
        JSONObject p = obj(r, "playlist");
        return p == null ? null : parsePlaylist(p);
    }

    public boolean deletePlaylist(String id) throws Exception {
        call("deletePlaylist", "&id=" + Http.enc(id));
        return true;
    }

    public boolean renamePlaylist(String id, String name) throws Exception {
        call("updatePlaylist", "&playlistId=" + Http.enc(id) + "&name=" + Http.enc(name));
        return true;
    }

    public boolean addToPlaylist(String playlistId, List<String> songIds) throws Exception {
        if (songIds == null || songIds.isEmpty()) return true;
        StringBuilder q = new StringBuilder("&playlistId=").append(Http.enc(playlistId));
        for (String sid : songIds) {
            q.append("&songIdToAdd=").append(Http.enc(sid));
        }
        // songIdToAdd 可重复出现，服务端按参数列表逐个追加
        call("updatePlaylist", q.toString());
        return true;
    }

    public boolean removeFromPlaylist(String playlistId, int[] indexes) throws Exception {
        if (indexes == null || indexes.length == 0) return true;
        StringBuilder q = new StringBuilder("&playlistId=").append(Http.enc(playlistId));
        for (int idx : indexes) {
            q.append("&songIndexToRemove=").append(idx);
        }
        call("updatePlaylist", q.toString());
        return true;
    }

    public boolean star(String id) throws Exception {
        call("star", "&id=" + Http.enc(id));
        return true;
    }

    public boolean unstar(String id) throws Exception {
        call("unstar", "&id=" + Http.enc(id));
        return true;
    }

    public boolean setRating(String id, int rating) throws Exception {
        int r = Math.max(0, Math.min(5, rating));
        call("setRating", "&id=" + Http.enc(id) + "&rating=" + r);
        return true;
    }

    public boolean scrobble(String songId, boolean submissionMs) throws Exception {
        call("scrobble", "&id=" + Http.enc(songId) + "&submission=" + (submissionMs ? "true" : "false"));
        return true;
    }

    public Item getSong(String id) throws Exception {
        JSONObject r = call("getSong", "&id=" + Http.enc(id));
        JSONObject s = obj(r, "song");
        return s == null ? null : parseSong(s);
    }

    /** 播放书签（本服务端返回空体，做容错） */
    public List<Item> getBookmarks() {
        List<Item> out = Item.list();
        try {
            JSONObject r = call("getBookmarks", null);
            JSONArray arr = arr(r, "bookmarks");
            // 标准结构是 bookmarks.bookmark[]，部分实现直接给 bookmark[]
            JSONArray list = arr;
            if (arr.length() == 0) {
                JSONArray bm = arr(r, "bookmark");
                if (bm.length() > 0) list = bm;
            } else {
                JSONObject first = arr.optJSONObject(0);
                JSONArray inner = arr(first, "bookmark");
                if (inner.length() > 0) list = inner;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject b = list.optJSONObject(i);
                if (b == null) continue;
                long pos = lnum(b, "position");
                JSONArray entries = arr(b, "entry");
                if (entries.length() > 0) {
                    Item song = parseSong(entries.optJSONObject(0));
                    song.positionMs = pos;
                    song.subtitle = "上次听到 " + SubsonicClient.fmtPos(pos / 1000);
                    out.add(song);
                } else {
                    Item it = Item.song();
                    it.id = str(b, "id");
                    it.title = str(b, "comment");
                    it.positionMs = pos;
                    out.add(it);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "getBookmarks 不可用：" + e.getMessage());
        }
        return out;
    }

    public boolean createBookmark(String songId, long positionMs, String comment) throws Exception {
        String q = "&id=" + Http.enc(songId) + "&position=" + positionMs;
        if (comment != null && comment.length() > 0) q += "&comment=" + Http.enc(comment);
        call("createBookmark", q);
        return true;
    }

    public boolean deleteBookmark(String songId) throws Exception {
        call("deleteBookmark", "&id=" + Http.enc(songId));
        return true;
    }

    /** 云端播放队列（不支持时返回 null） */
    public List<Item> getPlayQueue() {
        try {
            JSONObject r = call("getPlayQueue", null);
            JSONObject q = obj(r, "playQueue");
            if (q == null) return null;
            List<Item> songs = parseSongs(arr(q, "entry"));
            return songs.isEmpty() ? null : songs;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean savePlayQueue(List<String> songIds, String currentId, long positionMs) {
        try {
            if (songIds == null || songIds.isEmpty()) return false;
            StringBuilder q = new StringBuilder("&id=");
            for (int i = 0; i < songIds.size(); i++) {
                if (i > 0) q.append(',');
                q.append(songIds.get(i));
            }
            q.append("&position=").append(positionMs);
            if (currentId != null && currentId.length() > 0) q.append("&current=").append(Http.enc(currentId));
            call("savePlayQueue", q.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 扫描状态（歌曲/专辑/艺术家总量） */
    public JSONObject getScanStatus() {
        try {
            return obj(call("getScanStatus", null), "scanStatus");
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 歌词 ----------------

    /** 按歌曲 id 取歌词（OpenSubsonic，优先同步歌词）。失败返回 null。 */
    public Lyrics getLyricsBySongId(String songId) {
        try {
            JSONObject r = call("getLyricsBySongId", "&id=" + Http.enc(songId));
            JSONArray list = arr(obj(r, "lyricsList"), "structuredLyrics");
            if (list.length() == 0) return null;
            JSONObject sl = list.optJSONObject(0);
            if (sl == null) return null;
            Lyrics out = new Lyrics();
            out.displayArtist = clean(str(sl, "displayArtist"));
            out.displayTitle = clean(str(sl, "displayTitle"));
            double offsetSec = num(sl, "offset") / 1000.0;
            JSONArray lines = arr(sl, "line");
            for (int i = 0; i < lines.length(); i++) {
                JSONObject ln = lines.optJSONObject(i);
                if (ln == null) continue;
                double startMs = 0;
                Object sv = ln.opt("start");
                if (sv instanceof Number) startMs = ((Number) sv).doubleValue();
                else {
                    try {
                        startMs = Double.parseDouble(String.valueOf(sv));
                    } catch (Exception ignored) {
                    }
                }
                String text = ln.optString("value", "");
                out.lines.add(new Lyrics.Line(startMs / 1000.0 + offsetSec, text));
            }
            if (out.lines.isEmpty()) return null;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 歌名/艺术家 取歌词（旧接口，返回 LRC 文本或纯文本） */
    public Lyrics getLyrics(String artist, String title) {
        try {
            JSONObject r = call("getLyrics", "&artist=" + Http.enc(artist == null ? "" : artist)
                    + "&title=" + Http.enc(title == null ? "" : title));
            JSONObject l = obj(r, "lyrics");
            if (l == null) return null;
            String value = l.optString("value", "");
            if (value == null || value.trim().length() == 0) return null;
            Lyrics out = new Lyrics();
            out.displayArtist = clean(str(l, "artist"));
            out.displayTitle = clean(str(l, "title"));
            List<Lyrics.Line> parsed = LrcParser.parse(value);
            if (!parsed.isEmpty()) {
                out.lines.addAll(parsed);
            } else {
                out.plainText = value;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 直链 ----------------

    /** 播放地址（MediaPlayer 直接用；不带 f 参数，服务端返回原始文件） */
    public String streamUrl(String songId, int networkQuality) {
        learnPrefix();
        StringBuilder extra = new StringBuilder("&id=").append(Http.enc(songId));
        if (networkQuality >= 1) {
            int br = networkQuality == 1 ? 320 : (networkQuality == 2 ? 192 : 96);
            extra.append("&maxBitRate=").append(br).append("&format=mp3");
        }
        return binaryUrl("stream", extra.toString());
    }

    public String coverUrl(String coverArtId, int size) {
        if (coverArtId == null || coverArtId.length() == 0) return "";
        learnPrefix();
        return binaryUrl("getCoverArt", "&id=" + Http.enc(coverArtId) + "&size=" + size);
    }

    public String downloadUrl(String songId) {
        learnPrefix();
        return binaryUrl("download", "&id=" + Http.enc(songId));
    }

    public String shareUrl(String id) {
        learnPrefix();
        return binaryUrl("createShare", "&id=" + Http.enc(id));
    }

    // ---------------- 小工具 ----------------

    public static String fmtPos(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + ":" + (m < 10 ? "0" + m : String.valueOf(m)) + ":" + (s < 10 ? "0" + s : String.valueOf(s));
        }
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
