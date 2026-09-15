package com.lixscn.subsonicplayer.core;

import android.content.Context;
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

    /** 带取消的执行：cancel 后即使完成也不再回调 */
    public <T> Task runCancelable(final Work<T> work, final Done<T> done) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T v = work.run();
                    if (cancelled.get()) return;
                    if (done != null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!cancelled.get()) done.ok(v);
                            }
                        });
                    }
                } catch (final Exception e) {
                    if (cancelled.get()) return;
                    if (done != null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!cancelled.get()) done.fail(friendly(e));
                            }
                        });
                    }
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

    /** 把已有列表数据塞进缓存（详情页返回列表页时复用） */
    public void putCache(String key, Object value) {
        toCache(key, value);
    }

    public Object getCache(String key, long ttlMs) {
        return fromCache(key, ttlMs);
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

    public void recentAlbums(final int n, Done<List<Item>> done) {
        run(new Work<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return client.getAlbums("recent", n, 0);
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
