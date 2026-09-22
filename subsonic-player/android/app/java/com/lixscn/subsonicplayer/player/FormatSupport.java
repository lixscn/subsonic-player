package com.lixscn.subsonicplayer.player;

import com.lixscn.subsonicplayer.core.Item;

/**
 * 曲目格式能力判定：决定「这曲子该交给谁播」。
 *
 * <p><b>现行策略：单引擎。</b>只要 BASS 可用就一律走 BASS（见 {@link #engineFor}）。
 *
 * <p>为什么连 MP4/M4A 也走 BASS（它明明流式放不了）：
 * 本曲库的 m4a 绝大多数**不是 faststart**（moov 在文件末尾）。实测抓了一个文件做原子分析：
 * {@code ftyp@4, mdat@40, moov@2869516}（文件共 3018145 字节）—— 典型的「下载器直接产出」。
 *
 * <ul>
 *   <li><b>BASS</b> 的 URL 流式解码要求 moov 在前，遇到这种文件直接回
 *       {@code BASS_ERROR_UNSTREAMABLE(47)}（真机日志 {@code err=47}）。</li>
 *   <li><b>系统 MediaPlayer</b> 理论上有 HTTP Range 能力（服务端确实支持 206），
 *       实测却不可用：它为取尾部 moov 反复发起 Range 请求，每次 {@code IMediaHTTPConnection}
 *       事务要 0.5~11 秒（跨境隧道延迟），70 秒后仍停在 BUFFERING、position=0。
 *       这正是用户说的「大文件一直出现缓冲的界面」。</li>
 *   <li><b>正解</b>：让 BASS 报 47，上层「先整段下载到缓存、再本地建流播放」
 *       接管（{@code Player.downloadThenPlay}）。一条顺序连接下完整首，实测可行；
 *       而且缓存命中后重播瞬开。为了让切歌不用干等，预取逻辑对 MP4 家族
 *       改成**提前后台下载下一首**（{@code Player.prefetchMp4}）。</li>
 * </ul>
 *
 * <p>除 MP4 家族外的格式（mp3 / flac / wav / ogg / opus / ape / wv / dsd / aac）BASS 都能直接流式，
 * 统一交给它还能避开 MediaPlayer 的「假播放」（prepare 成功、报在播放、就是没声）。
 *
 * <p>BASS 不可用（so 没打进包 / 初始化失败）时退回 {@link #fallbackEngineFor}，保证至少还能放 mp3。
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
        if (song == null) return "";
        String s = song.suffix == null ? "" : song.suffix.trim().toLowerCase();
        if (s.length() == 0) {
            // ★ 服务端脏数据：**部分文件它自己不认识，suffix 直接给空**（实测 .ape 就是），
            //   但 contentType 是准的（audio/ape）。以前不看 contentType，于是这类曲子
            //   被当成「未知格式」→ 交给 BASS 建流 41 → 白下整首 28MB → 本地还是 41 → 这首就死了。
            s = fromContentType(song.contentType);
        }
        if (s.startsWith("wave")) return "wav";          // Wave / Wave[Id3]
        if (s.startsWith("mp4") || s.equals("m4a")) return "m4a";  // mp4a.40.2 等
        if (s.contains(".")) s = s.substring(0, s.indexOf('.'));   // mp4a.40.2 -> mp4a
        return s;
    }

    /**
     * 把 MIME（Subsonic 的 {@code contentType}）映射成后缀 —— {@link #suffixOf} 的兜底。
     * 服务端对 .ape / .dsf 这类「它自己不认识的格式」会返回空 suffix，但 MIME 是对的。
     */
    public static String fromContentType(String contentType) {
        if (contentType == null) return "";
        String c = contentType.trim().toLowerCase();
        int semi = c.indexOf(';');
        if (semi >= 0) c = c.substring(0, semi).trim();
        if (c.length() == 0) return "";
        if (c.equals("audio/mpeg") || c.equals("audio/mp3") || c.equals("audio/x-mp3")) return "mp3";
        if (c.equals("audio/mp4") || c.equals("audio/x-m4a") || c.equals("audio/m4a")) return "m4a";
        if (c.equals("audio/aac") || c.equals("audio/x-aac") || c.equals("audio/mp4a-latm")) return "aac";
        if (c.equals("audio/flac") || c.equals("audio/x-flac")) return "flac";
        if (c.equals("audio/ape") || c.equals("audio/x-ape") || c.equals("audio/monkeys-audio")) return "ape";
        if (c.equals("audio/wav") || c.equals("audio/x-wav") || c.equals("audio/wave") || c.equals("audio/vnd.wave")) return "wav";
        if (c.equals("audio/ogg") || c.equals("application/ogg") || c.equals("audio/x-ogg")) return "ogg";
        if (c.equals("audio/opus")) return "opus";
        if (c.equals("audio/wavpack") || c.equals("audio/x-wavpack") || c.equals("audio/x-wv")) return "wv";
        if (c.equals("audio/dsf") || c.equals("audio/x-dsf") || c.equals("audio/dff") || c.equals("audio/x-dff")) return "dsf";
        if (c.equals("audio/wma") || c.equals("audio/x-ms-wma")) return "wma";
        if (c.equals("audio/aiff") || c.equals("audio/x-aiff")) return "aiff";
        if (c.equals("audio/musepack") || c.equals("audio/x-musepack")) return "mpc";
        if (c.equals("audio/x-tta")) return "tta";
        return "";
    }

    /** 系统解码器基本没戏的容器 */
    public static boolean isExoticContainer(String suffix) {
        return "dsf".equals(suffix) || "dff".equals(suffix)
                || "ape".equals(suffix) || "wv".equals(suffix)
                || "mpc".equals(suffix) || "tta".equals(suffix)
                || "aif".equals(suffix) || "aiff".equals(suffix)
                // ALAC 从这份名单里移除了：Android 平台（AOSP）自带 ALAC 解码器，
                // 之前把 alac 列进来是没验证过的假设。现在 ALAC 走系统解码器（见 engineFor）。
                || "wma".equals(suffix);
    }


    /**
     * 是否属于 MP4 家族（MP4/M4A 容器）。
     *
     * <p>这些**不能流式播放**：本库的 m4a 多为 moov 在末尾（非 faststart），
     * BASS 的 URL 流式解码会直接回 {@code 47 UNSTREAMABLE}。
     * 只能「整段下载到缓存再本地播」（{@code Player.downloadThenPlay}），
     * 所以预取逻辑也要对它们改用「提前下载」而不是「提前建流」。详见类注释。
     */
    public static boolean isMp4Family(String suffix) {
        return "m4a".equals(suffix) || "mp4".equals(suffix) || "mp4a".equals(suffix)
                || "m4b".equals(suffix) || "3gp".equals(suffix)
                || "alac".equals(suffix);   // ALAC 同样装在 MP4 容器里（服务端把 codec 放在 suffix）
    }

    /**
     * 是不是 ALAC（Apple 无损）。
     *
     * <p>服务端对 m4a 会把 codec 放进 suffix（AAC 是 {@code mp4a.40.2}），ALAC 则是 {@code alac}；
     * contentType 两边都是 {@code audio/x-m4a}，分不出来。所以判 ALAC 要看 suffix 或 contentType 里有没有 alac。
     */
    public static boolean isAlac(Item song) {
        if (song == null) return false;
        String s = song.suffix == null ? "" : song.suffix.toLowerCase();
        String c = song.contentType == null ? "" : song.contentType.toLowerCase();
        return s.contains("alac") || c.contains("alac");
    }

    /**
     * 选择播放引擎 —— <b>单引擎：BASS 可用就一律 BASS</b>。
     *
     * <p>连 MP4/M4A 也交给 BASS：虽然它没法流式播（会报 47），但上层的
     * 「先下载后播」兜底能接管；而交给系统 MediaPlayer 更糟 ——
     * 实测（真机 logcat）MediaPlayer 会为取尾部 moov 反复做 Range 请求，
     * 每次 {@code IMediaHTTPConnection} 事务要 0.5~11 秒（经隧道跨境），
     * 70 秒后仍停在 BUFFERING、position=0 —— 用户看到的就是「一直缓冲」。
     *
     * @param song  曲目（读 suffix）
     * @param bassReady BASS 是否可用（{@code BassNative.available()}）
     */
    public static Engine engineFor(Item song, boolean bassReady) {
        // ALAC 例外：BASS 能不能解 ALAC 没验证过（本库原先没有 ALAC 文件），
        // 而 Android 平台自带 ALAC 解码器（AOSP 的 alac decoder + MP4 extractor；
        // 系统 MediaPlayer 能用 Range 请求取尾部 moov）。所以 ALAC 优先交给系统解码器。
        // 若实测系统解码器也放不了，再改成 BASS 或入库前转 FLAC（无损转无损）。
        if (isAlac(song)) return Engine.SYSTEM;
        if (bassReady) return Engine.BASS;
        return fallbackEngineFor(song);
    }

    /**
     * BASS 不可用时的兜底：老的「按后缀猜系统解码器能不能解」逻辑。
     * 只为「BASS 挂了也还能放 mp3」存在，不是正常路径。
     */
    private static Engine fallbackEngineFor(Item song) {
        String suffix = suffixOf(song);
        if (suffix.length() == 0) return Engine.SYSTEM;      // 后缀缺失：赌一把系统解码器
        if (isExoticContainer(suffix)) return Engine.NONE;   // DSD/APE/WavPack…没 BASS 就是没辙
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
