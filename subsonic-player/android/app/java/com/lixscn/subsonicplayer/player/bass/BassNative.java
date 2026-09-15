package com.lixscn.subsonicplayer.player.bass;

/**
 * BASS 的 Java 侧声明（薄绑定，无业务逻辑）。
 *
 * <p>对应的 C 实现见 {@code app/jni/sp_bass.c}，编译脚本 {@code tools/build-native.ps1}。
 * 需要的原生库（放到 {@code app/jniLibs/<abi>/}）：
 * <ul>
 *   <li>{@code libspbass.so} —— 本项目的包装层（build-native.ps1 产出）</li>
 *   <li>{@code libbass.so} —— BASS 核心（官方 Android 包）</li>
 *   <li>{@code libbassdsd.so} —— DSD（.dsf/.dff），可选但建议带</li>
 *   <li>可选：libbassflac / libbassape / libbasswv / libbassopus / libbass_fx</li>
 * </ul>
 *
 * <p><b>设计约束</b>：BASS 是可选能力。库不存在时 {@link #available()} 返回 false，
 * 播放引擎自动退回系统 MediaPlayer —— 也就是说缺少原生库不会让 App 崩或不可用。
 *
 * <p>只使用 Java 8 语法（项目铁律）。JNI 方法名必须与 C 侧
 * {@code Java_com_lixscn_subsonicplayer_player_bass_BassNative_xxx} 严格对应，改名要同步改。
 */
public final class BassNative {

    private static boolean loaded;
    private static boolean loadChecked;

    private BassNative() {
    }

    /** 原生库是否可用（首次调用时尝试加载，之后缓存结果） */
    public static synchronized boolean available() {
        if (loadChecked) return loaded;
        loadChecked = true;
        try {
            System.loadLibrary("spbass");   // 依赖 libbass.so，由链接器按需要解析
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        return loaded;
    }

    // ---------------- 引擎 ----------------

    /** 初始化输出设备；device=-1 表示默认设备 */
    public static native boolean nativeInit(int device, int freq);

    /** 释放 BASS（进程退出或彻底关闭播放时调用） */
    public static native void nativeFree();

    /** SSL/HTTPS 插件是否加载成功（流地址是 https 时必须为 true） */
    public static native boolean nativeSslLoaded();

    /** 网络超时（毫秒） */
    public static native void nativeSetNetTimeout(int ms);

    // ---------------- 建流（返回句柄，0 = 失败） ----------------

    /** 从 URL 建流（鉴权参数直接写在 query 里） */
    public static native long nativeStreamCreateUrl(String url);

    /** 从本地文件建流（WAV 24/32bit、FLAC、APE、WavPack、Opus 等） */
    public static native long nativeStreamCreateFile(String path);

    /** DSD 直接流播（bassdsd 新版提供 URL 接口） */
    public static native long nativeDsdStreamCreateUrl(String url);

    /** DSD 本地文件（URL 版失败时的退路） */
    public static native long nativeDsdCreateFile(String path);

    // ---------------- 播放控制 ----------------

    public static native boolean nativePlay(long handle, boolean restart);

    public static native void nativePause(long handle);

    public static native void nativeStop(long handle);

    public static native void nativeFreeStream(long handle);

    /** 0=停止 1=播放 2=暂停 3=缓冲中 */
    public static native int nativeState(long handle);

    public static native double nativePositionSec(long handle);

    public static native double nativeDurationSec(long handle);

    public static native boolean nativeSeekSec(long handle, double sec);

    public static native void nativeSetVolume(long handle, float v);

    /** BASS 错误码（失败后立即取） */
    public static native int nativeErrorCode();

    /** 缓冲百分比，-1 表示不可用 */
    public static native int nativeBufferPercent(long handle);

    /** 是否因网速不足处于 stalled（用于「缓冲中…」提示） */
    public static native boolean nativeIsStalled(long handle);
}
