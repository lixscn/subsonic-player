package com.lixscn.subsonicplayer.core;

import android.content.Context;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 音乐文件缓存管理。
 *
 * <p>为什么需要：有些文件 BASS 无法流式播放（典型是 MP4/M4A 的 moov 在文件末尾），
 * 我们改成「整段下载到缓存再本地播」（见 {@code Player.downloadThenPlay}）。
 * 这类缓存如果不管理，会随着听歌持续增长、悄悄吃掉用户存储 ✗
 *
 * <p>策略：
 * <ul>
 *   <li>总量上限 {@link #MAX_BYTES}（默认 500MB），超出按"最久未使用"删除直到低于上限 ✓</li>
 *   <li>下载前调用 {@link #prune} 做一次，避免边下边爆 ✓</li>
 *   <li>对外提供 {@link #sizeBytes} / {@link #clear}，供设置页显示与手动清理 ✓</li>
 * </ul>
 *
 * <p>只使用 Java 8 语法（项目铁律）。
 */
public final class MediaCache {

    /** 缓存目录名（与 Player 里使用的一致） */
    private static final String DIR = "sp-cache";

    /**
     * 缓存总上限：**4GB**（2026-09-17 由 500MB 调高）。
     *
     * <p>为什么要这么大：现在流式播放的曲目也会「边播边存」进这里，重播零流量。
     * 但库里 flac 一首就 30~50MB，500MB 只装得下十来首，缓存形同虚设 —— 所以放宽到 4GB。
     * 超出后仍按「最久未使用」淘汰，不会无限涨。
     */
    public static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

    private MediaCache() {
    }

    /** 缓存目录（不存在则创建） */
    /**
     * 缓存目录：放 filesDir 而不是 cacheDir。
     *
     * <p>放 cacheDir 的话系统在存储紧张时会**整个删掉**，用户攒的「零流量」资本随时归零、
     * 下次全部重下（这恰恰是最费电的一条路）。filesDir 不会被动清，
     * 由我们自己的 4GB LRU（{@link #prune}）负责回收。
     */
    private static File cacheDir(Context ctx) {
        File base = ctx.getFilesDir();
        if (base == null) base = ctx.getCacheDir();
        File d = new File(base, DIR);
        // 老版本放在 cacheDir 里的缓存：同分区 rename 是瞬时的，先迁移再建目录
        try {
            File legacy = new File(ctx.getCacheDir(), DIR);
            if (!d.exists() && legacy.isDirectory() && legacy.renameTo(d)) {
                com.lixscn.subsonicplayer.core.PlayLog.w("MediaCache", "缓存目录已迁移到 filesDir");
            }
        } catch (Throwable ignored) {
        }
        if (!d.exists()) d.mkdirs();
        return d;
    }
    public static File dir(Context ctx) {
        File d = cacheDir(ctx);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 当前缓存占用（字节） */
    public static long sizeBytes(Context ctx) {
        File d = cacheDir(ctx);
        if (!d.isDirectory()) return 0;
        long total = 0;
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) total += f.length();
            }
        }
        return total;
    }

    /** 已缓存的曲目数 */
    public static int count(Context ctx) {
        File d = cacheDir(ctx);
        File[] files = d.listFiles();
        int n = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().endsWith(".part")) n++;
            }
        }
        return n;
    }

    /** 人类可读的大小（给设置页用） */
    public static String sizeText(Context ctx) {
        long b = sizeBytes(ctx);
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /**
     * 按上限裁剪：超过 {@code maxBytes} 就删除"最久未使用"的文件（按最后修改时间升序）。
     * 顺带清掉下载中断留下的 .part 残片（超过 1 小时没动的）。
     */
    public static void prune(Context ctx, long maxBytes) {
        File d = cacheDir(ctx);
        if (!d.isDirectory()) return;
        File[] files = d.listFiles();
        if (files == null || files.length == 0) return;

        long total = 0;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (!f.isFile()) continue;
            // 中断的下载残片：超过 1 小时直接删
            if (f.getName().endsWith(".part") && now - f.lastModified() > 3600_000L) {
                f.delete();
                continue;
            }
            total += f.length();
        }
        if (total <= maxBytes) return;

        // 最久未使用的先删
        File[] keep = d.listFiles();
        if (keep == null) return;
        Arrays.sort(keep, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : keep) {
            if (total <= maxBytes) break;
            if (!f.isFile() || f.getName().endsWith(".part")) continue;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    /** 清空全部缓存，返回释放的字节数 */
    public static long clear(Context ctx) {
        File d = cacheDir(ctx);
        if (!d.isDirectory()) return 0;
        long freed = 0;
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    long len = f.length();
                    if (f.delete()) freed += len;
                }
            }
        }
        return freed;
    }
}
