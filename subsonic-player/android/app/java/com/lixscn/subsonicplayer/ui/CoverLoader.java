package com.lixscn.subsonicplayer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 封面图异步加载器（只用 Android Framework + Java 8 API，零第三方依赖）。
 *
 * <p>两级缓存：
 * <ul>
 *   <li>内存：{@link LruCache}，容量 = {@code Runtime.maxMemory() / 8}，按 {@code Bitmap.getByteCount()} 计量；
 *       键为 {@code url + "#" + 目标最大边}（同一 URL 的列表图 512 与详情图 1024 分开存，避免互相顶掉）。</li>
 *   <li>磁盘：{@code context.getCacheDir()/covers/<URL 的 MD5>.img}，超过 100MB 时按最后修改时间淘汰最旧文件
 *       （降到 90MB 以下，留出余量避免每次写盘都触发淘汰）。</li>
 * </ul>
 *
 * <p>防错位：{@link #load(String, ImageView, int)} 会给 ImageView 打一个 keyed tag（本次请求的 token），
 * 结果回到主线程时 tag 已不是这个 token 就整张丢弃，因此 ViewHolder 复用不会串图。
 * 同一 URL 的并发请求合并成一次下载（{@code Map<String, List<Task>>}），下载完按各自尺寸解码后一起分发。
 *
 * <p>线程模型：固定 4 线程池做「下载 + 解码 + 磁盘淘汰」；所有回调统一
 * {@code Handler(Looper.getMainLooper())} post 到主线程（包括内存命中）。
 *
 * <p>约束：{@link #load(String, ImageView, int)} 必须在主线程调用（要动 ImageView）；
 * 任何下载/解码失败都只回调 {@code null}，绝不抛异常。
 */
public class CoverLoader {

    /** 结果回调；bmp 可能为 null（下载失败 / 不是图片 / 解码失败）。 */
    public interface Callback {
        void onBitmap(Bitmap bmp);
    }

    private static final String TAG = "CoverLoader";

    /** 列表/网格封面的目标最大边 */
    private static final int SIZE_LIST = 512;
    /** 详情页预取的目标最大边 */
    private static final int SIZE_LARGE = 1024;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "SubsonicPlayer-Android";
    private static final int HTTP_OK = 200;

    /** 单张封面下载上限，防止 URL 参数异常时把磁盘/内存撑爆 */
    private static final long MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L;
    private static final long DISK_MAX_BYTES = 100L * 1024L * 1024L;
    private static final long DISK_TRIM_TO_BYTES = 90L * 1024L * 1024L;

    private static final int POOL_SIZE = 4;
    private static final int MAX_SAMPLE = 1 << 12;
    private static final int COPY_BUF = 8192;

    /**
     * ImageView 上标记「本次请求 token」的 keyed tag。
     * 必须是应用资源 id 形态（高字节 >= 0x02），否则 View.setTag(int, Object) 会抛 IllegalArgumentException。
     */
    private static final int TAG_KEY = 0x7f0f0001;

    /** 失败负缓存：404 这类确定性失败压久一点，网络抖动只压几秒，避免滚动时反复回源。 */
    private static final long FAIL_TTL_HARD_MS = 10L * 60L * 1000L;
    private static final long FAIL_TTL_SOFT_MS = 3L * 1000L;
    private static final int FAIL_MAP_MAX = 512;

    private static volatile CoverLoader sInstance;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mMemory;
    private final File mDiskDir;
    private final ExecutorService mExecutor;

    private final Object mLock = new Object();
    /** 同一 URL 的等待队列：第一个请求触发下载，其余挂在 list 上等结果 */
    private final Map<String, List<Task>> mPending = new HashMap<String, List<Task>>();
    /** url -> 失败解禁时间戳 */
    private final Map<String, Long> mFailUntil = new HashMap<String, Long>();

    private CoverLoader(Context appContext) {
        int cacheSize = (int) Math.min((long) Integer.MAX_VALUE,
                Runtime.getRuntime().maxMemory() / 8);
        mMemory = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getByteCount();
            }
        };

        File base = appContext.getCacheDir();
        if (base == null) {
            base = appContext.getFilesDir();
        }
        File dir = new File(base, "covers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        mDiskDir = dir;

        mExecutor = Executors.newFixedThreadPool(POOL_SIZE, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "CoverLoader-" + seq.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });

        // 上次进程遗留的缓存可能已经超限，启动时在后台补一次淘汰（不占主线程）
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    trimDiskCache();
                }
            });
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 进程内单例；首次调用请放在主线程（构造 Handler 需要主 Looper）。 */
    public static CoverLoader get(Context ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        CoverLoader inst = sInstance;
        if (inst == null) {
            synchronized (CoverLoader.class) {
                inst = sInstance;
                if (inst == null) {
                    Context app = ctx.getApplicationContext();
                    if (app == null) {
                        app = ctx;
                    }
                    inst = new CoverLoader(app);
                    sInstance = inst;
                }
            }
        }
        return inst;
    }

    /**
     * 绑定图片到 ImageView（自动处理复用错位：内部给 ImageView 打 tag 标记本次请求，结果回来时 tag 不匹配就丢弃）。
     *
     * @param url            完整 URL；null/空 时直接显示 placeholder
     * @param target         目标 ImageView
     * @param placeholderRes 占位图资源 id（可为 0 表示不设置）
     */
    public void load(String url, ImageView target, int placeholderRes) {
        if (target == null) {
            return;
        }
        if (isBlank(url)) {
            target.setTag(TAG_KEY, null);
            if (placeholderRes != 0) {
                target.setImageResource(placeholderRes);
            }
            return;
        }

        // 绑定标记用「本次请求的 URL」而不是新对象：
        // 结果回来时只要这个 View 当前绑定的仍是同一个 URL 就照常显示。
        // 早期用 new Object() 做 token 时，GridView 在慢网络下会反复重绑同一行，
        // 每次重绑都把上一次在途请求的结果作废 → 形成「封面永远显示不出来」的活锁（局域网快，暴露不出来）。
        String token = url;
        target.setTag(TAG_KEY, token);
        if (placeholderRes != 0) {
            target.setImageResource(placeholderRes);
        }

        String key = memKey(url, SIZE_LIST);
        Bitmap hit = mMemory.get(key);
        if (hit != null) {
            // 内存命中也要走 post，保证回调时序一致
            postToView(target, token, hit, placeholderRes);
            return;
        }
        if (isRecentFailure(url)) {
            // 最近刚失败过（比如这张专辑本来就没封面），保持 placeholder，不再发起请求
            return;
        }
        enqueue(new Task(url, SIZE_LIST, null, target, token, placeholderRes));
    }

    /** 预取（不绑定 View），用于详情页提前加载大图。cb 可为 null（只预热缓存）。 */
    public void prefetch(String url, Callback cb) {
        if (isBlank(url)) {
            postToCallback(cb, null);
            return;
        }
        String key = memKey(url, SIZE_LARGE);
        Bitmap hit = mMemory.get(key);
        if (hit != null) {
            postToCallback(cb, hit);
            return;
        }
        if (isRecentFailure(url)) {
            postToCallback(cb, null);
            return;
        }
        enqueue(new Task(url, SIZE_LARGE, cb, null, null, 0));
    }

    /** 清空内存缓存（设置里切换服务器时调用）。 */
    public void clearMemory() {
        mMemory.evictAll();
        synchronized (mLock) {
            mFailUntil.clear();
        }
    }

    /** 磁盘缓存占用字节数（设置里展示/清理用）。 */
    public long diskCacheSize() {
        long total = 0;
        try {
            File[] files = mDiskDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    total += files[i].length();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "diskCacheSize failed", t);
        }
        return total;
    }

    /** 清空磁盘缓存（与 {@link #clearMemory()} 相互独立，切换服务器时建议两个都调）。 */
    public void clearDisk() {
        try {
            File[] files = mDiskDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    if (f.isDirectory()) {
                        deleteRecursively(f);
                    } else {
                        f.delete();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearDisk failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 请求合并
    // ------------------------------------------------------------------

    private void enqueue(Task task) {
        boolean first = false;
        synchronized (mLock) {
            List<Task> list = mPending.get(task.url);
            if (list == null) {
                list = new ArrayList<Task>(4);
                mPending.put(task.url, list);
                first = true;
            }
            list.add(task);
        }
        if (first) {
            try {
                mExecutor.execute(new FetchJob(task.url));
            } catch (Throwable t) {
                // 线程池被拒（极端情况）也要保证回调拿到 null，而不是一直挂着
                finishWithFailure(task.url, -1);
            }
        }
    }

    /** 一个 URL 一个任务：下载（或读磁盘）一次，再按各请求的尺寸解码分发。 */
    private final class FetchJob implements Runnable {

        private final String mUrl;

        FetchJob(String url) {
            mUrl = url;
        }

        @Override
        public void run() {
            File file = diskFile(mUrl);
            boolean fresh = false;
            try {
                if (!(file.isFile() && file.length() > 0)) {
                    int code = download(mUrl, file);
                    if (code != HTTP_OK) {
                        finishWithFailure(mUrl, code);
                        return;
                    }
                    fresh = true;
                    trimDiskCache();
                }
                finishWithBitmap(mUrl, file, fresh);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "decode oom: " + mUrl, e);
                finishWithFailure(mUrl, -2);
            } catch (Throwable t) {
                Log.w(TAG, "fetch failed: " + mUrl, t);
                finishWithFailure(mUrl, -1);
            }
        }
    }

    /** 图片已就绪（网络下载或磁盘命中）：逐请求解码 + 分发。 */
    private void finishWithBitmap(String url, File file, boolean fresh) {
        List<Task> tasks;
        synchronized (mLock) {
            tasks = mPending.remove(url);
        }
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int ok = 0;
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            String key = memKey(url, t.size);
            Bitmap bmp = mMemory.get(key);
            if (bmp == null) {
                bmp = decodeFile(file, t.size);
                if (bmp != null) {
                    mMemory.put(key, bmp);
                    ok++;
                }
            } else {
                ok++;
            }
            t.deliver(bmp);
        }
        if (ok == 0) {
            // 全都解不出来：多半是服务端返回了 XML/JSON 错误页，删掉坏文件并压一小段负缓存
            if (fresh) {
                safeDelete(file);
            }
            markFailure(url, FAIL_TTL_SOFT_MS);
        } else {
            clearFailure(url);
        }
    }

    /** 下载失败：所有等待者拿 null（-> 显示 placeholder）。 */
    private void finishWithFailure(String url, int httpCode) {
        List<Task> tasks;
        synchronized (mLock) {
            tasks = mPending.remove(url);
        }
        boolean hard = (httpCode == 400 || httpCode == 403 || httpCode == 404 || httpCode == 410);
        markFailure(url, hard ? FAIL_TTL_HARD_MS : FAIL_TTL_SOFT_MS);
        if (tasks == null) {
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).deliver(null);
        }
    }

    // ------------------------------------------------------------------
    // 主线程分发
    // ------------------------------------------------------------------

    private void postToView(final ImageView target, final String token, final Bitmap bmp,
                            final int placeholderRes) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                // tag 与本次请求的 URL 不一致 = View 已被复用给别的 URL，整张丢弃（防串图）
                Object cur = target.getTag(TAG_KEY);
                if (token == null || !token.equals(cur)) {
                    return;
                }
                if (bmp != null && !bmp.isRecycled()) {
                    target.setImageBitmap(bmp);
                } else if (placeholderRes != 0) {
                    target.setImageResource(placeholderRes);
                }
            }
        });
    }

    private void postToCallback(final Callback cb, final Bitmap bmp) {
        if (cb == null) {
            return;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onBitmap(bmp);
                } catch (Throwable t) {
                    Log.w(TAG, "callback threw", t);
                }
            }
        });
    }

    /** 一次请求：绑定 View 的（load）或只回调的（prefetch）。 */
    private final class Task {

        final String url;
        final int size;
        final Callback cb;
        final ImageView view;
        /** 绑定时写入 View 的标记（= 请求 URL）；用于结果回来时判断该 View 是否仍绑定同一张图 */
        final String token;
        final int placeholderRes;

        Task(String url, int size, Callback cb, ImageView view, String token, int placeholderRes) {
            this.url = url;
            this.size = size;
            this.cb = cb;
            this.view = view;
            this.token = token;
            this.placeholderRes = placeholderRes;
        }

        void deliver(Bitmap bmp) {
            if (view != null) {
                postToView(view, token, bmp, placeholderRes);
            } else {
                postToCallback(cb, bmp);
            }
        }
    }

    // ------------------------------------------------------------------
    // 下载 / 解码
    // ------------------------------------------------------------------

    /** 下载到 dest；成功返回 200，失败返回 HTTP 状态码，-1 表示本地 IO 或其它异常。 */
    private int download(String url, File dest) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        File tmp = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HTTP_OK) {
                return code;
            }

            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // 临时名带线程 id：极端情况下两个线程同时下同一 URL 也不会互相覆盖
            tmp = new File(parent, dest.getName() + "." + Thread.currentThread().getId() + ".tmp");
            out = new FileOutputStream(tmp);
            in = conn.getInputStream();
            byte[] buf = new byte[COPY_BUF];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("cover too large: " + total + " bytes");
                }
            }
            out.flush();
            out.close();
            out = null;

            if (!tmp.renameTo(dest)) {
                safeDelete(dest);
                if (!tmp.renameTo(dest)) {
                    safeDelete(tmp);
                    return -1;
                }
            }
            tmp = null;
            return HTTP_OK;
        } catch (Throwable t) {
            Log.w(TAG, "download failed: " + url, t);
            return -1;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            safeDelete(tmp);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 按目标最大边下采样解码；解不出来返回 null（不抛）。 */
    private Bitmap decodeFile(File file, int reqSize) {
        String path = file.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(path, bounds);
        } catch (Throwable t) {
            return null;
        }
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) {
            return null; // 不是图片
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = computeSampleSize(w, h, reqSize);
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            // 再降一档重试，仍然不行就交给 placeholder
            BitmapFactory.Options retry = new BitmapFactory.Options();
            retry.inSampleSize = Math.min(MAX_SAMPLE, opts.inSampleSize * 2);
            retry.inScaled = false;
            try {
                return BitmapFactory.decodeFile(path, retry);
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 2 的幂次采样率，使解码结果最大边 >= reqSize（即落在 [reqSize, 2*reqSize) 内，不做放大）。 */
    private static int computeSampleSize(int w, int h, int reqSize) {
        if (reqSize <= 0) {
            return 1;
        }
        int maxDim = Math.max(w, h);
        int sample = 1;
        while (sample < MAX_SAMPLE && (maxDim / (sample * 2)) >= reqSize) {
            sample *= 2;
        }
        return sample;
    }

    // ------------------------------------------------------------------
    // 磁盘缓存
    // ------------------------------------------------------------------

    private File diskFile(String url) {
        return new File(mDiskDir, md5(url) + ".img");
    }

    /** 超过 100MB 时按最后修改时间从最旧开始删，降到 90MB 以下。 */
    private void trimDiskCache() {
        try {
            File[] files = mDiskDir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            long total = 0L;
            for (int i = 0; i < files.length; i++) {
                total += files[i].length();
            }
            if (total <= DISK_MAX_BYTES) {
                return;
            }
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long x = a.lastModified();
                    long y = b.lastModified();
                    if (x < y) {
                        return -1;
                    }
                    if (x > y) {
                        return 1;
                    }
                    return 0;
                }
            });
            for (int i = 0; i < files.length; i++) {
                if (total <= DISK_TRIM_TO_BYTES) {
                    break;
                }
                long len = files[i].length();
                if (files[i].delete()) {
                    total -= len;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "trimDiskCache failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String memKey(String url, int size) {
        return url + "#" + size;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** URL -> MD5 十六进制（十六进制自己查表转，不依赖 Java 17 的 HexFormat）。 */
    private static String md5(String s) {
        byte[] data;
        try {
            data = s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            data = s.getBytes();
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data);
            return toHex(md.digest());
        } catch (Exception e) {
            // 理论上不会发生；退化实现保证不抛
            return "h" + Integer.toHexString(s.hashCode());
        }
    }

    private static String toHex(byte[] bytes) {
        final char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    private void markFailure(String url, long ttlMs) {
        synchronized (mLock) {
            if (mFailUntil.size() > FAIL_MAP_MAX) {
                mFailUntil.clear();
            }
            mFailUntil.put(url, Long.valueOf(System.currentTimeMillis() + ttlMs));
        }
    }

    private void clearFailure(String url) {
        synchronized (mLock) {
            mFailUntil.remove(url);
        }
    }

    private boolean isRecentFailure(String url) {
        synchronized (mLock) {
            Long until = mFailUntil.get(url);
            if (until == null) {
                return false;
            }
            if (until.longValue() <= System.currentTimeMillis()) {
                mFailUntil.remove(url);
                return false;
            }
            return true;
        }
    }

    private static void safeDelete(File f) {
        if (f == null) {
            return;
        }
        try {
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    deleteRecursively(files[i]);
                } else {
                    files[i].delete();
                }
            }
        }
        dir.delete();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
