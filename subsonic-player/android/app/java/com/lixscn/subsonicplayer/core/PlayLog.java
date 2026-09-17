package com.lixscn.subsonicplayer.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 播放诊断日志：**同时**写 logcat 和落盘文件。
 *
 * <p>为什么需要落盘：MIUI 的系统噪声（gralloc4）会把 logcat 冲掉，
 * 真机出问题时往往已经看不到上下文了。落盘文件用的是**只追加 + 满则轮转**的策略，
 * 所以问题发生前后几十秒的完整过程都能留住，事后可以直接把文件导出来分析。
 *
 * <p>规则：
 * <ul>
 *   <li>统一 tag：{@code SPPlay}（logcat 里 {@code adb logcat -s SPPlay:* } 就能看全）</li>
 *   <li>落盘位置：{@code getExternalFilesDir(null)/logs/playback.log}（adb 可直接 pull）</li>
 *   <li>**绝不记录凭据**：URL 必须先经 {@link #safeUrl(String)} 脱敏</li>
 *   <li>全部 synchronized：播放回调可能来自多个线程</li>
 * </ul>
 */
public final class PlayLog {

    public static final String TAG = "SPPlay";

    private static final long MAX_BYTES = 2 * 1024 * 1024;   // 单文件 2MB 后轮转
    private static final String FILE_NAME = "playback.log";
    private static final String OLD_NAME = "playback.1.log";

    private static final Object LOCK = new Object();
    private static File file;
    private static SimpleDateFormat fmt;

    private PlayLog() {
    }

    /** 初始化（幂等）。appCtx 传入即可，内部只取外部私有目录，不需要任何权限。 */
    public static void init(Context ctx) {
        synchronized (LOCK) {
            if (file != null || ctx == null) return;
            try {
                File dir = new File(ctx.getExternalFilesDir(null), "logs");
                if (!dir.exists()) dir.mkdirs();
                file = new File(dir, FILE_NAME);
                fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
                write("---- 日志开始 ----");
            } catch (Throwable t) {
                Log.w(TAG, "PlayLog 初始化失败: " + t);
            }
        }
    }


    public static void i(String msg) {
        write("I " + msg);
    }

    /** 兼容原有 Log.w(TAG, msg) 的调用形式：tag 只用于 logcat，落盘统一记 SPPlay */
    public static void w(String tag, String msg) {
        write("W " + msg);
    }

    public static void w(String tag, String msg, Throwable t) {
        write("W " + msg + " | " + t);
    }

    public static void i(String tag, String msg) {
        write("I " + msg);
    }

    public static void w(String msg) {
        write("W " + msg);
    }

    public static void w(String msg, Throwable t) {
        write("W " + msg + " | " + t);
    }

    public static void e(String msg) {
        write("E " + msg);
    }

    /**
     * URL 脱敏：只保留 host + path + 关键参数 id，去掉认证参数（u/t/s/p/token…），
     * 避免把账号和 token 写进日志文件。见 android/AGENTS.md 第 9 条。
     */
    public static String safeUrl(String url) {
        if (url == null) return "null";
        try {
            int q = url.indexOf('?');
            if (q < 0) return url;
            String base = url.substring(0, q);
            StringBuilder keep = new StringBuilder();
            for (String kv : url.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                if ("id".equals(k) || "format".equals(k) || "maxBitRate".equals(k) || "size".equals(k)) {
                    if (keep.length() > 0) keep.append('&');
                    keep.append(kv);
                }
            }
            return keep.length() == 0 ? base + "?<auth-only>" : base + "?" + keep;
        } catch (Throwable t) {
            return "<url>";
        }
    }

    private static void write(String line) {
        synchronized (LOCK) {
            if (file == null) {
                // 没初始化也要能在 logcat 看到，否则早期日志会丢
                Log.w(TAG, line);
                return;
            }
            try {
                String ts = fmt == null ? "" : fmt.format(new Date());
                Log.w(TAG, line);                        // ① logcat（带 tag，方便实时看）
                if (file.length() > MAX_BYTES) rotate(); // ② 落盘（抗 logcat 被冲掉）
                FileOutputStream fos = new FileOutputStream(file, true);
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                w.write(ts + " " + line + "\n");
                w.flush();
                w.close();
            } catch (Throwable t) {
                Log.w(TAG, "PlayLog 写入失败: " + t);
            }
        }
    }

    private static void rotate() {
        try {
            File old = new File(file.getParentFile(), OLD_NAME);
            if (old.exists()) old.delete();
            file.renameTo(old);
            write("---- 日志轮转 ----");
        } catch (Throwable ignored) {
        }
    }
}
