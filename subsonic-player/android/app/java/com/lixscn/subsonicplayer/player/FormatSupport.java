package com.lixscn.subsonicplayer.player;

import com.lixscn.subsonicplayer.core.Item;

/**
 * 曲目格式能力判定：决定「这曲子该交给谁播」。
 *
 * <p>背景：安卓系统解码器（MediaPlayer）只认常见格式，而服务端**不做转码**，
 * 原样把文件丢过来。实测曲库里有相当比例的 WAV（24/32bit、最高 4939kbps）、
 * DSD（.dsf，5644kbps）—— 这些在 MediaPlayer 上就是「点了没反应」。
 * BASS 可用时把它们交给 BASS（见 {@link player.bass.BassEngine}）。
 *
 * <p>判定依据主要看**后缀 + 码率**（服务端 song JSON 里有 suffix / bitRate；
 * 位深不一定有，所以不依赖它）。宁可靠码率保守一些：高码率的 wav/flac 一律走 BASS，
 * 省得先失败再重试、白等一轮。
 *
 * <p>只使用 Java 8（项目铁律）。
 */
public final class FormatSupport {

    /** 交给谁播 */
    public enum Engine {
        /** 系统 MediaPlayer（最常见，后台/通知栏链路最成熟） */
        SYSTEM,
        /** BASS（DSD / APE / WavPack / 高码率 WAV、FLAC） */
        BASS,
        /** 谁都不支持（例如 BASS 不可用时的 DSD） */
        NONE
    }

    /** 高于这个码率就认为「系统解码器多半搞不定」，交给 BASS */
    private static final int HIRES_KBPS = 1200;

    private FormatSupport() {
    }

    /** 规范化后缀：服务端可能给 "Wave[Id3]" 这种怪值 */
    public static String suffixOf(Item song) {
        if (song == null || song.suffix == null) return "";
        String s = song.suffix.trim().toLowerCase();
        if (s.startsWith("wave")) return "wav";          // Wave / Wave[Id3]
        if (s.startsWith("mp4") || s.equals("m4a")) return "m4a";  // mp4a.40.2 等
        if (s.contains(".")) s = s.substring(0, s.indexOf('.'));   // mp4a.40.2 -> mp4a
        return s;
    }

    /** 系统解码器基本没戏的容器 */
    public static boolean isExoticContainer(String suffix) {
        return "dsf".equals(suffix) || "dff".equals(suffix)
                || "ape".equals(suffix) || "wv".equals(suffix)
                || "mpc".equals(suffix) || "tta".equals(suffix)
                || "aif".equals(suffix) || "aiff".equals(suffix)
                || "alac".equals(suffix) || "wma".equals(suffix);
    }

    /** 系统解码器支持（但不代表高码率也稳） */
    public static boolean isCommonContainer(String suffix) {
        return "mp3".equals(suffix) || "m4a".equals(suffix) || "mp4a".equals(suffix)
                || "aac".equals(suffix) || "ogg".equals(suffix) || "oga".equals(suffix)
                || "opus".equals(suffix) || "flac".equals(suffix)
                || "wav".equals(suffix) || "m4b".equals(suffix) || "3gp".equals(suffix);
    }

    /**
     * 选择播放引擎。
     *
     * @param song  曲目（读 suffix / bitRate）
     * @param bassReady BASS 是否可用（{@code BassNative.available()}）
     */
    public static Engine engineFor(Item song, boolean bassReady) {
        String suffix = suffixOf(song);
        int kbps = song == null ? 0 : song.bitrate;

        if (suffix.length() == 0) {
            // 后缀缺失 —— 这是「列表接口不返回 suffix」的常见情况。
            // 实测教训：这类曲目交给系统解码器会出现「prepare 成功、报在播、就是没声」（假播放），
            // 而且进度可能照走，兜底检测也抓不到。BASS 能覆盖曲库全部格式（mp3/aac/flac/wav/dsd），
            // 所以后缀未知时直接交给 BASS，别再赌系统解码器。
            return bassReady ? Engine.BASS : Engine.SYSTEM;
        }
        if (isExoticContainer(suffix)) {
            return bassReady ? Engine.BASS : Engine.NONE;
        }
        // WAV / FLAC 一律交给 BASS。
        // 实测：系统解码器对这类文件常常「能 prepare、报告在播放，就是不出声/进度不走」
        // （尤其是 DSD 转出来的 WAV），光看报错是抓不到的 —— 所以不再靠码率猜，统一走 BASS。
        if ("wav".equals(suffix) || "flac".equals(suffix)) {
            return bassReady ? Engine.BASS : Engine.SYSTEM;
        }
        return Engine.SYSTEM;
    }

    /** 失败提示用语：告诉用户「是什么格式为什么不支持」，而不是干等 */
    public static String unsupportedReason(Item song) {
        String suffix = suffixOf(song);
        int kbps = song == null ? 0 : song.bitrate;
        if ("dsf".equals(suffix) || "dff".equals(suffix)) {
            return "DSD（." + suffix + "）编码，安卓系统解码器不支持";
        }
        if ("ape".equals(suffix)) return "APE（Monkey's Audio）编码，安卓系统解码器不支持";
        if ("wv".equals(suffix)) return "WavPack 编码，安卓系统解码器不支持";
        if ("wav".equals(suffix) && kbps >= HIRES_KBPS) {
            return "WAV 高码率（约 " + kbps + " kbps），安卓系统解码器无法解码";
        }
        if ("flac".equals(suffix) && kbps >= HIRES_KBPS) {
            return "FLAC 高码率（约 " + kbps + " kbps），设备解码器可能不支持";
        }
        if (suffix.length() == 0) return "无法识别格式（服务端未提供后缀）";
        return suffix.toUpperCase() + " 格式本站设备无法解码";
    }
}
