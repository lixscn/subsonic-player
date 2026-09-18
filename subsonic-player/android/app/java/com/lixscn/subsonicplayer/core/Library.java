package com.lixscn.subsonicplayer.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据门面：统一「后台线程取数 + 主线程回调」，并做内存缓存。
 *
 * 页面只跟这一层打交道，不直接碰 SubsonicClient 的异常与线程问题。
 */
public class Library {

    private static final String TAG = "Library";
    private static Library sInst;

    /** 后台任务：可以抛异常 */
    public interface Work<T> {
        T run() throws Exception;
    }

    /** 主线程回调 */
    public interface Done<T> {
        void ok(T value);

        void fail(String message);
    }

    /** 可取消的任务句柄 */
    public interface Task {
        void cancel();

        boolean isCancelled();
    }

    private final Context appCtx;
    private final Settings settings;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());

    private SubsonicClient client;
    private String connectedUrl = "";
    private String lastError = "";

    private static class Entry {
        Object value;
        long at;
    }

    private final Map<String, Entry> cache = new HashMap<String, Entry>();

    private Library(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.settings = Settings.get(appCtx);
        rebuildClient();
        startNetworkWatch();
    }

    public static synchronized Library get(Context ctx) {
        if (sInst == null) sInst = new Library(ctx);
        return sInst;
    }

    public Settings settings() {
        return settings;
    }

    public SubsonicClient client() {
        return client;
    }

    public boolean isConfigured() {
        return client != null;
    }

    public String connectedUrl() {
        return connectedUrl;
    }

    public String lastError() {
        return lastError;
    }

    /** 服务器配置变更后重建客户端并清缓存 */
    public synchronized void rebuildClient() {
        Settings.Service svc = settings.currentService();
        client = (svc == null || svc.lanUrl.length() == 0 && svc.wanUrl.length() == 0)
                ? null : new SubsonicClient(svc);
        connectedUrl = "";
        lastError = "";
        cache.clear();
    }

    // ---------------- 线程与缓存 ----------------

    public <T> void run(final Work<T> work, final Done<T> done) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T v = work.run();
                    if (done != null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                done.ok(v);
                            }
                        });
                    }
                } catch (final Exception e) {
                    Log.w(TAG, "任务失败: " + e.getMessage());
                    if (done != null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                done.fail(friendly(e));
                            }
                        });
                    }
                }
            }
        });
    }


    public void onMain(Runnable r) {
        main.post(r);
    }

    private static String friendly(Exception e) {
        if (e instanceof SubsonicClient.ApiException) {
            SubsonicClient.ApiException a = (SubsonicClient.ApiException) e;
            if (a.code == 40) return "用户名或密码错误";
            if (a.code == 41) return "服务端不支持该操作";
            if (a.code == 50) return "服务端未开启该功能";
            if (a.code == 70) return "未找到该曲目";
            return a.getMessage() + "（code " + a.code + "）";
        }
        if (e instanceof java.net.SocketTimeoutException) return "连接超时，请检查服务器地址";
        if (e instanceof java.net.UnknownHostException) return "无法解析服务器地址";
        if (e instanceof java.net.ConnectException) return "无法连接服务器";
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }

    @SuppressWarnings("unchecked")
    private <T> T fromCache(String key, long ttlMs) {
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() - e.at > ttlMs) {
                cache.remove(key);
                return null;
            }
            return (T) e.value;
        }
    }

    private <T> void toCache(String key, T value) {
        synchronized (cache) {
            Entry e = new Entry();
            e.value = value;
            e.at = System.currentTimeMillis();
            cache.put(key, e);
        }
    }

    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }



    // ---------------- 连接 ----------------

    /**
     * 连接服务器：内网 → 外网依次尝试。结果在主线程回调。
     * 成功后 connectedUrl 为可用地址。
     */
    public void connect(final Done<Boolean> done) {
        if (client == null) {
            if (done != null) main.post(new Runnable() {
                @Override
                public void run() {
                    done.fail("尚未配置音乐服务器");
                }
            });
            return;
        }
        final Settings.Service svc = settings.currentService();
        run(new Work<Boolean>() {
            @Override
            public Boolean run() {
                List<String> candidates = new ArrayList<String>();
                if (svc != null) {
                    if (svc.lanUrl.length() > 0) candidates.add(svc.lanUrl);
                    if (svc.wanUrl.length() > 0 && !svc.wanUrl.equals(svc.lanUrl)) candidates.add(svc.wanUrl);
                }
                for (int i = 0; i < candidates.size(); i++) {
                    String url = candidates.get(i);
                    client.useUrl(url);
                    // 第一个（内网）用 3 秒快速判定：不在家时不该让用户干等；
                    // 最后一个（外网/域名）给足 8 秒，走公网可能慢一些。
                    int timeout = i == candidates.size() - 1 ? 8000 : 3000;
                    if (client.ping(timeout)) {
                        connectedUrl = url;
                        // 顺便探测尾斜杠，后续 stream URL 才正确
                        client.learnPrefix();
                        lastError = "";
                        return Boolean.TRUE;
                    }
                }
                lastError = "连接失败：内网/外网地址都不可达";
                return Boolean.FALSE;
            }
        }, new Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                if (done != null) {
                    if (value != null && value.booleanValue()) done.ok(Boolean.TRUE);
                    else done.fail(lastError.length() > 0 ? lastError : "连接失败");
                }
            }

            @Override
            public void fail(String message) {
                lastError = message;
                if (done != null) done.fail(message);
            }
        });
    }

    /**
     * 换用另一个地址重连（内网 ⇄ 外网）。
     *
     * 用途：切网后当前地址可能已不可达（例如在家连上内网、出门切蜂窝），
     * 此时取流会失败；播放器捕获失败后调用本方法切到另一地址重试同一首。
     */
    public void reconnectAlternate(final Done<Boolean> done) {
        if (client == null) {
            if (done != null) done.ok(Boolean.FALSE);
            return;
        }
        final Settings.Service svc = settings.currentService();
        final String current = client.activeUrl();
        run(new Work<Boolean>() {
            @Override
            public Boolean run() {
                if (svc == null) return Boolean.FALSE;
                List<String> candidates = new ArrayList<String>();
                if (svc.lanUrl.length() > 0) candidates.add(svc.lanUrl);
                if (svc.wanUrl.length() > 0 && !svc.wanUrl.equals(svc.lanUrl)) candidates.add(svc.wanUrl);
                for (String url : candidates) {
                    if (url.equals(current)) continue;
                    client.useUrl(url);
                    if (client.ping(6000)) {
                        connectedUrl = url;
                        client.learnPrefix();
                        return Boolean.TRUE;
                    }
                }
                client.useUrl(current);   // 都不通就恢复原地址
                return Boolean.FALSE;
            }
        }, done);
    }

    // ---------------- 网络变化 → 自动换地址（方案 A） ----------------

    /** 地址真的换了才回调（播放器据此用新地址断点续播） */
    public interface AddressListener {
        void onAddressChanged(String url, long latencyMs);
    }

    /** 两次探测的最小间隔：系统在网络切换瞬间会连发多个回调，必须防抖 */
    private static final long NET_CHECK_COOLDOWN_MS = 5000;
    /** 收到回调后先等一下再探测：新网络刚建立时立刻探容易假失败 */
    private static final long NET_CHECK_DELAY_MS = 1500;

    private AddressListener addressListener;
    private volatile boolean netWatchStarted;
    private boolean netCheckPending;
    private long lastNetCheckAt;
    private volatile long lastSwitchLatency;
    /** 上一次「网络已验证可上网」的状态：用来过滤掉刷屏的 onCapabilitiesChanged */
    private boolean capsValidated;

    public void setAddressListener(AddressListener l) {
        this.addressListener = l;
    }

    /**
     * 注册系统网络变化监听（进程级只注册一次）。
     *
     * <p>用途：手机在「家里 WiFi ⇄ 蜂窝」之间切来切去时，当前地址可能已经不可达，
     * 但 App 原先**完全不知道网络变了**（grep 全项目 0 处网络监听），于是出门在外还拿着
     * 内网地址猛试。这里只负责「发现变化 → 重新确认地址」，**是否切换**由
     * {@link #onNetworkChanged} 按方案 A 决定。
     */
    public void startNetworkWatch() {
        if (netWatchStarted) return;
        netWatchStarted = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ConnectivityManager cm = (ConnectivityManager)
                            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm == null) return;
                    cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            scheduleNetworkCheck("onAvailable");
                        }

                        @Override
                        public void onLost(Network network) {
                            scheduleNetworkCheck("onLost");
                        }

                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                            // 这个回调在部分 ROM（实测 MIUI）上每十几秒就来一次。要是每次都去 ping
                            // 服务器，既费电又费流量（用户明确在意流量）—— 只有「能不能上网」这个
                            // 状态真的变了才重新确认地址；WiFi ⇄ 蜂窝 的切换由 onAvailable/onLost 覆盖。
                            boolean validated = caps != null
                                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                            synchronized (Library.this) {
                                if (validated == capsValidated) return;
                                capsValidated = validated;
                            }
                            scheduleNetworkCheck("onCapabilitiesChanged");
                        }
                    });
                    PlayLog.init(appCtx);
                    PlayLog.w(TAG, "已注册网络变化监听（切网后自动确认地址）");
                } catch (Throwable t) {
                    PlayLog.w(TAG, "网络监听注册失败（不影响其它功能）", t);
                }
            }
        });
    }

    /** 合并短时间内的多次回调，延迟一小段再探测（网络刚切换时探测会假失败） */
    private void scheduleNetworkCheck(final String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (netCheckPending) return;
                netCheckPending = true;
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        netCheckPending = false;
                        PlayLog.w(TAG, "网络变化（" + reason + "）→ 重新确认地址");
                        onNetworkChanged(null);
                    }
                }, NET_CHECK_DELAY_MS);
            }
        });
    }

    /**
     * 网络变化后重新确认当前地址是否还可用；**只在不可用时才切换**（方案 A：能播就不动）。
     *
     * <p>① 先探当前地址，通就什么都不做（避免频繁重连断流 + 白费流量）；
     * ② 不通才把内网/外网两个地址都探一遍，选**探测通过且延迟低**的那个；
     * ③ 真的换了地址才回调 {@link AddressListener}。
     * 带 {@value #NET_CHECK_COOLDOWN_MS} 毫秒冷却，防抖动。
     */
    public void onNetworkChanged(final Done<Boolean> done) {
        if (client == null) {
            if (done != null) done.ok(Boolean.FALSE);
            return;
        }
        synchronized (this) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastNetCheckAt < NET_CHECK_COOLDOWN_MS) {
                if (done != null) done.ok(Boolean.FALSE);
                return;
            }
            lastNetCheckAt = now;
        }
        final Settings.Service svc = settings.currentService();
        run(new Work<Boolean>() {
            @Override
            public Boolean run() {
                PlayLog.init(appCtx);
                final String current = client.activeUrl();
                // ① 当前地址还通 —— 通常什么都不做（避免频繁重连断流 + 白费流量）。
                //    **但内网可达而当前走的不是内网时必须切回内网**：
                //    在家里访问外网地址等于绕公网一圈（实测延迟 200~1900ms），放歌会卡；
                //    以前只看「当前是否可用」，于是一旦切到外网就再也回不来
                //    （真机 2026-09-18 18:07–18:40 全程走外网放歌，用户反馈「音乐卡卡的」）。
                long t0 = android.os.SystemClock.elapsedRealtime();
                if (client.ping(3000)) {
                    long curLatency = android.os.SystemClock.elapsedRealtime() - t0;
                    String lanUrl = svc == null ? "" : norm(svc.lanUrl);
                    if (lanUrl.length() > 0 && !lanUrl.equals(current)) {
                        client.useUrl(lanUrl);
                        long s = android.os.SystemClock.elapsedRealtime();
                        boolean lanOk = client.ping(1200);
                        long lanLatency = android.os.SystemClock.elapsedRealtime() - s;
                        if (lanOk && lanLatency + 50 < curLatency) {
                            // 内网明显更快 → 切回去（正在播放的话 Player 会断点续播）
                            connectedUrl = lanUrl;
                            client.learnPrefix();
                            lastSwitchLatency = lanLatency;
                            PlayLog.w(TAG, "网络变化 → 内网可达，从外网切回内网（"
                                    + curLatency + "ms → " + lanLatency + "ms）");
                            return Boolean.TRUE;
                        }
                        client.useUrl(current);      // 内网不通或不更快 → 维持原地址
                        PlayLog.w(TAG, "网络变化 → 当前地址仍可用（延迟 " + curLatency + "ms），"
                                + (lanOk ? ("内网也不更快（" + lanLatency + "ms）") : "内网不可达")
                                + "，不切换");
                        return Boolean.FALSE;
                    }
                    PlayLog.w(TAG, "网络变化 → 当前地址仍可用（延迟 " + curLatency + "ms），不切换");
                    return Boolean.FALSE;
                }
                // ② 当前地址不可达：内外网各探一次，选延迟低的
                List<String> candidates = new ArrayList<String>();
                if (svc != null) {
                    String lan = norm(svc.lanUrl);
                    String wan = norm(svc.wanUrl);
                    if (lan.length() > 0) candidates.add(lan);
                    if (wan.length() > 0 && !wan.equals(lan)) candidates.add(wan);
                }
                String best = "";
                long bestLatency = -1;
                for (int i = 0; i < candidates.size(); i++) {
                    String url = candidates.get(i);
                    if (url.equals(current)) continue;
                    client.useUrl(url);
                    long s = android.os.SystemClock.elapsedRealtime();
                    boolean ok = client.ping(i == candidates.size() - 1 ? 8000 : 3000);
                    long lat = android.os.SystemClock.elapsedRealtime() - s;
                    PlayLog.w(TAG, "备选地址探测 " + PlayLog.safeUrl(url) + " → "
                            + (ok ? "通" : "不通") + "（" + lat + "ms）");
                    if (ok && (bestLatency < 0 || lat < bestLatency)) {
                        best = url;
                        bestLatency = lat;
                    }
                }
                if (best.length() == 0) {
                    // 两个地址都不通（可能真的没网）：恢复原地址，等下一次网络变化
                    client.useUrl(current);
                    PlayLog.w(TAG, "网络变化 → 内网/外网都不可达，保持原地址");
                    return Boolean.FALSE;
                }
                // ③ 真的换了地址
                client.useUrl(best);
                connectedUrl = best;
                client.learnPrefix();
                lastSwitchLatency = bestLatency;
                PlayLog.w(TAG, "网络变化 → 当前地址不可用 → 已切到" + labelOf(svc, best)
                        + "（延迟 " + bestLatency + "ms）");
                return Boolean.TRUE;
            }
        }, new Done<Boolean>() {
            @Override
            public void ok(Boolean switched) {
                if (switched != null && switched.booleanValue() && addressListener != null) {
                    addressListener.onAddressChanged(client == null ? "" : client.activeUrl(),
                            lastSwitchLatency);
                }
                if (done != null) done.ok(switched);
            }

            @Override
            public void fail(String message) {
                if (done != null) done.ok(Boolean.FALSE);
            }
        });
    }

    /** 去掉尾部斜杠（与 SubsonicClient 内部一致），便于比较「是不是同一个地址」 */
    private static String norm(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String labelOf(Settings.Service svc, String url) {
        if (svc == null) return "地址";
        if (norm(svc.lanUrl).equals(url)) return "内网地址";
        if (norm(svc.wanUrl).equals(url)) return "外网地址";
        return "地址";
    }

    // ---------------- 直链 ----------------

    public String coverUrl(String coverArtId, int size) {
        return client == null ? "" : client.coverUrl(coverArtId, size);
    }

    public String streamUrl(String songId) {
        return client == null ? "" : client.streamUrl(songId, settings.networkQuality());
    }

    public String downloadUrl(String songId) {
        return client == null ? "" : client.downloadUrl(songId);
    }

    /** 艺术家头像：本服务端 artist.coverArt 常为空，退而取该艺术家第一张专辑封面 */
    public void artistCover(final String artistId, final Done<String> done) {
        if (client == null || artistId == null || artistId.length() == 0) {
            if (done != null) done.ok("");
            return;
        }
        String key = "artistCover:" + artistId;
        String cached = fromCache(key, 10 * 60 * 1000L);
        if (cached != null) {
            if (done != null) done.ok(cached);
            return;
        }
        run(new Work<String>() {
            @Override
            public String run() throws Exception {
                List<Item> albums = client.getArtistAlbums(artistId);
                for (Item a : albums) {
                    if (a.coverArt != null && a.coverArt.length() > 0) return a.coverArt;
                }
                return "";
            }
        }, new Done<String>() {
            @Override
            public void ok(String value) {
                toCache(key, value);
                if (done != null) done.ok(value);
            }

            @Override
            public void fail(String message) {
                if (done != null) done.ok("");
            }
        });
    }

    // ---------------- 常用列表 ----------------

    public void albums(final String type, final int page, final int pageSize, Done<List<Item>> done) {
        final String key = "albums:" + type + ":" + page + ":" + pageSize;
        List<Item> cached = fromCache(key, 60 * 1000L);
        if (cached != null && done != null) {
            done.ok(cached);
            return;
        }
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getAlbums(type, pageSize, page * pageSize);
            }
        }, wrap(key, done));
    }

    public void artists(Done<List<Item>> done) {
        final String key = "artists";
        List<Item> cached = fromCache(key, 5 * 60 * 1000L);
        if (cached != null && done != null) {
            done.ok(cached);
            return;
        }
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getArtists();
            }
        }, wrap(key, done));
    }

    public void album(final String id, Done<Item> done) {
        final String key = "album:" + id;
        Item cached = fromCache(key, 5 * 60 * 1000L);
        if (cached != null && done != null) {
            done.ok(cached);
            return;
        }
        run(new Work<Item>() {
            @Override
            public Item run() throws Exception {
                return client.getAlbum(id);
            }
        }, wrap(key, done));
    }

    /** 专辑曲目（只取歌曲，用于「全部歌曲」渐进加载，避免整张专辑对象进缓存） */
    public void albumSongs(final String albumId, Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                Item a = client.getAlbum(albumId);
                return a == null || a.songs == null ? new ArrayList<Item>() : a.songs;
            }
        }, done);
    }

    public void artistAlbums(final String artistId, Done<List<Item>> done) {
        final String key = "artistAlbums:" + artistId;
        List<Item> cached = fromCache(key, 5 * 60 * 1000L);
        if (cached != null && done != null) {
            done.ok(cached);
            return;
        }
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getArtistAlbums(artistId);
            }
        }, wrap(key, done));
    }

    public void playlists(Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getPlaylists();
            }
        }, done);
    }

    public void playlist(final String id, Done<Item> done) {
        run(new Work<Item>() {
            @Override
            public Item run() throws Exception {
                return client.getPlaylist(id);
            }
        }, done);
    }

    public void genres(Done<List<Item>> done) {
        final String key = "genres";
        List<Item> cached = fromCache(key, 10 * 60 * 1000L);
        if (cached != null && done != null) {
            done.ok(cached);
            return;
        }
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getGenres();
            }
        }, wrap(key, done));
    }

    public void songsByGenre(final String genre, final int page, final int pageSize, Done<List<Item>> done) {
        final String key = "genreSongs:" + genre + ":" + page + ":" + pageSize;
        List<Item> cached = fromCache(key, 60 * 1000L);
        if (cached != null && done != null) {
            done.ok(cached);
            return;
        }
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getSongsByGenre(genre, pageSize, page * pageSize);
            }
        }, wrap(key, done));
    }

    public void starredSongs(Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getStarredSongs();
            }
        }, done);
    }

    public void starredAlbums(Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getStarredAlbums();
            }
        }, done);
    }

    public void starredArtists(Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getStarredArtists();
            }
        }, done);
    }

    public void search(final String q, final int count, Done<SubsonicClient.SearchResult> done) {
        run(new Work<SubsonicClient.SearchResult>() {
            @Override
            public SubsonicClient.SearchResult run() throws Exception {
                return client.search(q, count);
            }
        }, done);
    }

    public void bookmarks(Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getBookmarks();
            }
        }, done);
    }

    public void randomSongs(final int n, Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getRandomSongs(n);
            }
        }, done);
    }


    public void frequentAlbums(final int n, Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getAlbums("frequent", n, 0);
            }
        }, done);
    }

    public void newestAlbums(final int n, Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getAlbums("newest", n, 0);
            }
        }, done);
    }

    public void song(final String id, Done<Item> done) {
        run(new Work<Item>() {
            @Override
            public Item run() throws Exception {
                return client.getSong(id);
            }
        }, done);
    }

    // ---------------- 写操作 ----------------

    public void setStar(final String id, final boolean star, Done<Boolean> done) {
        run(new Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                cache.clear();   // 收藏状态影响收藏页
                if (star) client.star(id);
                else client.unstar(id);
                return Boolean.TRUE;
            }
        }, done);
    }

    public void setRating(final String id, final int rating, Done<Boolean> done) {
        run(new Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                client.setRating(id, rating);
                return Boolean.TRUE;
            }
        }, done);
    }

    public void createPlaylist(final String name, final List<String> songIds, Done<Item> done) {
        run(new Work<Item>() {
            @Override
            public Item run() throws Exception {
                return client.createPlaylist(name, songIds);
            }
        }, done);
    }

    public void addToPlaylist(final String playlistId, final List<String> songIds, Done<Boolean> done) {
        run(new Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                client.addToPlaylist(playlistId, songIds);
                return Boolean.TRUE;
            }
        }, done);
    }

    public void removeFromPlaylist(final String playlistId, final int[] indexes, Done<Boolean> done) {
        run(new Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                client.removeFromPlaylist(playlistId, indexes);
                return Boolean.TRUE;
            }
        }, done);
    }

    public void deletePlaylist(final String id, Done<Boolean> done) {
        run(new Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                client.deletePlaylist(id);
                return Boolean.TRUE;
            }
        }, done);
    }

    /** 播放上报：开始播放与播放过半各一次（scrobble 语义） */
    public void scrobble(final String songId, final boolean submission) {
        run(new Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                client.scrobble(songId, submission);
                return Boolean.TRUE;
            }
        }, null);
    }

    // ---------------- 全部歌曲（渐进式） ----------------

    public interface SongStream {
        /** 每加载到一批歌曲回调一次（主线程） */
        void onBatch(List<Item> songs, int loadedAlbums, int totalAlbums);

        void onDone(int totalSongs);

        void onError(String message);
    }

    /**
     * 「全部歌曲」：本服务端没有「所有歌曲」端点，只能按专辑展开。
     * 分页拉专辑（每页 40 张），对每张专辑并发取曲目，边取边回吐，
     * 用户无需等待全量完成即可滚动浏览。
     */
    public Task loadAllSongs(final SongStream cb) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                int offset = 0;
                final int pageSize = 40;
                int loadedAlbums = 0;
                int totalSongs = 0;
                try {
                    // 先拿一次总数（用于进度显示），失败不影响主流程
                    int totalAlbums = 0;
                    try {
                        org.json.JSONObject st = client.getScanStatus();
                        if (st != null) totalAlbums = st.optInt("albumCount", 0);
                    } catch (Exception ignored) {
                    }
                    while (!cancelled.get()) {
                        List<Item> albums = client.getAlbums("alphabeticalByArtist", pageSize, offset);
                        if (albums.isEmpty()) break;
                        final List<Item> batch = new ArrayList<Item>();
                        for (final Item al : albums) {
                            if (cancelled.get()) break;
                            try {
                                Item detail = client.getAlbum(al.id);
                                if (detail != null && detail.songs != null) {
                                    batch.addAll(detail.songs);
                                }
                            } catch (Exception ignored) {
                                // 单张专辑失败不影响整体
                            }
                            loadedAlbums++;
                        }
                        if (!batch.isEmpty()) {
                            totalSongs += batch.size();
                            final int la = loadedAlbums;
                            final int ta = totalAlbums;
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!cancelled.get()) cb.onBatch(batch, la, ta);
                                }
                            });
                        }
                        offset += pageSize;
                        if (albums.size() < pageSize) break;
                    }
                    final int ts = totalSongs;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled.get()) cb.onDone(ts);
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled.get()) cb.onError(friendly(e));
                        }
                    });
                }
            }
        });
        return new Task() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    // ---------------- 缓存包装 ----------------

    private <T> Done<T> wrap(final String key, final Done<T> done) {
        return new Done<T>() {
            @Override
            public void ok(T value) {
                toCache(key, value);
                if (done != null) done.ok(value);
            }

            @Override
            public void fail(String message) {
                if (done != null) done.fail(message);
            }
        };
    }
}
