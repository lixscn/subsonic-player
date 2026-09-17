package com.lixscn.subsonicplayer.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import com.lixscn.subsonicplayer.player.bass.BassNative;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.lixscn.subsonicplayer.core.PlayLog;

import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.core.SubsonicClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放引擎（进程内单例）。
 *
 * 队列 / 播放模式 / 播放位置记忆都在这里；前台服务与通知栏只是它的展示层，
 * 因此 UI 可以直接观察本对象，不必绑定 Service。
 *
 * 播放模式：0 顺序播放（播完停止）1 随机 2 列表循环 3 单曲循环
 */
public class Player {

    private static final String TAG = "Player";
    private static final String PREF = "sp_player";

    public static final int MODE_SEQUENTIAL = 0;
    public static final int MODE_SHUFFLE = 1;
    public static final int MODE_REPEAT_ALL = 2;
    public static final int MODE_REPEAT_ONE = 3;

    private static Player sInst;

    public interface Listener {
        /** 切歌（含首次播放） */
        void onTrackChanged(Item song);

        /** 播放状态/进度变化（约 500ms 一次） */
        void onProgress(boolean playing, int positionMs, int durationMs);

        /** 队列内容变化 */
        void onQueueChanged();

        void onModeChanged(int mode);

        /** 播放出错（自动跳过或停止） */
        void onPlaybackError(String message);
    }

    /**
     * 音频焦点由 Player 自己持有（不放 Service）。
     *
     * 原因：直接点歌起播时 Service 可能还没创建完，把焦点申请放在 Service 里会有竞态，
     * 结果就是「媒体会话在播、进度在走，但系统不给声音」（实测 MIUI，dumpsys audio 里焦点栈为空）。
     * 因此申请动作前移到真正 start() 之前，且由 Player 独立完成。
     */
    private final AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;

    private boolean acquireFocus() {
        if (audioManager == null) return true;
        if (hasFocus) return true;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            int result;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                            @Override
                            public void onAudioFocusChange(int change) {
                                handleFocusChange(change);
                            }
                        })
                        .build();
                result = audioManager.requestAudioFocus(focusRequest);
            } else {
                result = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            hasFocus = true;   // 焦点服务异常时不阻塞播放
        }
        return hasFocus;
    }

    private void abandonFocus() {
        if (!hasFocus) return;
        hasFocus = false;
        if (audioManager == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignored) {
        }
    }

    /** 是否持有音频焦点（UI/服务可查询） */
    public boolean hasAudioFocus() {
        return hasFocus;
    }

    private void handleFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            hasFocus = false;
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            setDuckFactor(0.3f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            setDuckFactor(1f);
        }
    }

    private final Context appCtx;
    private final Library library;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Random random = new Random();

    private MediaPlayer mp;
    /** BASS 流句柄：非 0 = 当前曲目走 BASS 引擎（DSD/APE/WavPack/高码率 WAV·FLAC） */
    private long bassStream;
    /** BASS 输出设备是否已初始化（进程内一次即可） */
    private static boolean bassInited;
    /** 已用 BASS 重试过的曲目 id（同曲只兜底一次，避免死循环） */
    private String bassTriedTrackId = "";
    /** 连续多少拍进度没前进（识别系统解码器的「假播放」） */
    private int stalledTicks;
    /** BASS 断流检测计数与重试次数 */
    private int bassStallTicks;
    /** 连续"无法播放"的次数：用于自动跳歌时做上限保护，避免整库失效时疯狂跳 */
    private int consecutivePlayFailures;
    /** 是否已对本次 BASS 曲目做过"结束"收尾（防重复触发下一首） */
    private boolean bassCompletionHandled;
    private int bassRetryCount;
    /** BASS 建流线程（BASS_StreamCreateURL 会同步做 DNS/TCP/TLS，占主线程会 ANR，真机日志已抓到） */
    private final java.util.concurrent.ExecutorService bassExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 建流代次：切歌/停止时自增；后台建流回来对不上就丢弃这条流 */
    private int bassGen;
    /** 是否正在后台预取下一首（避免重复发起） */
    private boolean bassPreloading;
    /**
     * 正在后台建流的曲目 id。
     * 建流是异步的，这期间 {@code bassStream} 仍是 0；UI/通知栏/焦点回调此时再喊一次播放，
     * 就会再起一条流。真机日志抓到过 0.5 秒内起 8 次、把整页歌刷过去的「跳歌雪崩」，
     * 靠这个字段拦掉同曲重复起播。
     */
    private String bassPendingTrackId = "";
    /** 本曲因「网络类错误」重试了几次（切歌清零）：链路是抖的，重试往往就成了 */
    private int bassNetRetry;
    /** 上一拍读到的 BASS 位置：用于「已到末尾且不再前进」的兜底结束判定 */
    private int bassLastPos;
    /** 到末尾后连续没前进的拍数（约 2 秒 → 判曲目结束） */
    private int bassEndTicks;
    /** 预取代次：切歌/释放时自增；后台预取回来对不上就丢弃，避免留下野流 */
    private int preloadGen;
    /** 已经安排过「后台预下载」的曲目 id（MP4 家族用；失败也不重试，见 prefetchMp4） */
    private volatile String prefetchingTrackId = "";
    /** 「边播边存」进度：正在缓存的曲目 id + 已下载字节 + 总字节（给进度条画浅色缓冲段用） */
    private volatile String cacheSongId = "";
    private volatile int cacheDownloaded;
    private volatile int cacheTotal;
    /** 预取的下一首（BASS 流句柄 + 曲目 id）：后台先缓冲好，切歌时直接接管 */
    private long bassNextStream;
    private String bassNextTrackId = "";
    private final List<Item> queue = new ArrayList<Item>();
    private int index = -1;
    private int mode = MODE_SEQUENTIAL;
    private boolean preparing;
    private boolean playWhenReady;
    private boolean playing;
    /**
     * 是否正在缓冲（已发起 prepareAsync、还没 prepared）。
     * 蜂窝网络经反代回源时首播要几秒，界面必须显示出来，否则用户会以为「点了没声音」。
     */
    private volatile boolean buffering;
    /** 已经为该曲目做过一次「换地址重试」，避免失败后无限重试 */
    private String retriedTrackId = "";
    private int durationMs;
    private int positionMs;
    private int volumePercent = 100;
    /** 临时压低系数（音频焦点被 duck 时用，不写入设置） */
    private float duckFactor = 1f;
    private boolean scrobbledCurrent;
    private int restoreSeekMs;
    /** 随机模式的历史顺序，保证「上一首」可用 */
    private final List<Integer> shuffleHistory = new ArrayList<Integer>();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (bassStream != 0) {
                // BASS 引擎：位置/时长直接从 BASS 读，其余上报链路完全复用
                positionMs();
                durationMs();
                // 断流检测：网络中断时 BASS 会把流置为 STOPPED(0)，网速跟不上则是 STALLED(3)。
                // 只要还没播到曲尾，就自动重连并从断点续播（最多 3 次）。
                int st = BassNative.nativeState(bassStream);
                boolean nearEnd = durationMs > 0 && positionMs >= durationMs - 1500;
                // 曲目自然结束：BASS 没有 onCompletion 回调（MediaPlayer 才有），
                // 所以必须在这里补上收尾逻辑 —— 否则播完就停在那，不会自动下一首。
                //
                // ⚠️ 只判 st==0 是不够的（真机 2026-09-17 的日志抓到了漏判）：
                // 歌放完时 BASS 有时停在 STALLED(3)（在等一个永远不会再来的数据包），
                // 而下面的断流分支又故意跳过 nearEnd —— 两条分支都不触发，
                // 界面就永远停在最后一秒、不切歌（用户反馈「不会自动下一首」）。
                // 兜底：已经到末尾，且连续 4 拍（约 2 秒）位置不再前进 → 判结束。
                if (nearEnd && !bassCompletionHandled && bassStream != 0) {
                    if (st == 0) {
                        bassEndTicks = 4;                    // BASS 自己说停了，直接判结束
                    } else if (positionMs > bassLastPos) {
                        bassEndTicks = 0;                    // 还在走，正常播
                    } else {
                        bassEndTicks++;
                    }
                    if (bassEndTicks >= 4) {
                        bassCompletionHandled = true;
                        PlayLog.w(TAG, "BASS 曲目结束 → 按模式切下一首 st=" + st
                                + " pos=" + positionMs + "/" + durationMs);
                        main.removeCallbacks(ticker);
                        onTrackFinished();
                        return;
                    }
                } else {
                    bassEndTicks = 0;
                }
                bassLastPos = positionMs;
                if (!nearEnd && (st == 0 || st == 3)) {
                    bassStallTicks++;
                    if (bassStallTicks >= 4) {           // 约 2 秒没恢复
                        bassStallTicks = 0;
                        Item sc = current();
                        if (sc != null && bassRetryCount < 3) {
                            bassRetryCount++;
                            int resumeAt = Math.max(0, positionMs);
                            PlayLog.w(TAG, "BASS 断流，重连第 " + bassRetryCount + " 次 pos=" + resumeAt + " state=" + st);
                            notifyError("网络中断，正在重连（第 " + bassRetryCount + "/3 次）…");
                            restoreSeekMs = resumeAt;
                            startWithBass(sc, library.streamUrl(sc.id));
                            return;
                        }
                        if (sc != null && bassRetryCount >= 3) {
                            bassRetryCount = 4;          // 只提示一次
                            notifyError("网络中断，重连 3 次仍未成功，已停止");
                            PlayLog.w(TAG, "BASS 重连失败，放弃 song=" + sc.id);
                        }
                    }
                } else {
                    bassStallTicks = 0;
                }
                maybeScrobble();
                preloadNext();
                notifyProgress();
                if (isPlaying()) main.postDelayed(this, 500);
                return;
            }
            if (mp != null && playing) {
                try {
                    int prevPos = positionMs;
                    positionMs = mp.getCurrentPosition();
                    // 停滞检测：报在播放但进度不动（约 3 秒）→ 判定为「假播放」，
                    // 改用 BASS 重试。系统解码器对 DSD 转出来的 WAV 就是这个症状。
                    if (positionMs <= prevPos && positionMs < 3000) {
                        stalledTicks++;
                        if (stalledTicks >= 6 && BassNative.available()) {
                            Item sc = current();
                            if (sc != null && !bassTriedTrackId.equals(sc.id)) {
                                bassTriedTrackId = sc.id;
                                stalledTicks = 0;
                                String su = library.streamUrl(sc.id);
                                PlayLog.w(TAG, "进度停滞，改用 BASS song=" + sc.id + " suffix=" + FormatSupport.suffixOf(sc));
                                if (su != null && su.length() > 0) { startWithBass(sc, su); return; }
                            }
                        }
                    } else {
                        stalledTicks = 0;
                    }
                    if (durationMs <= 0) {
                        int d = mp.getDuration();
                        if (d > 0) durationMs = d;
                    }
                } catch (Exception ignored) {
                }
                maybeScrobble();
                notifyProgress();
                main.postDelayed(this, 500);
            }
        }
    };

    private Player(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.library = Library.get(appCtx);
        this.sp = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.audioManager = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        this.mode = library.settings().playMode();
        this.volumePercent = library.settings().volume();
        // 切网后地址可能已被 Library 换掉（方案 A：只在当前地址真的不可用时才换）：
        // 正在播的那条流还挂在旧地址上，必须用新地址断点续播。
        library.setAddressListener(new Library.AddressListener() {
            @Override
            public void onAddressChanged(String url, long latencyMs) {
                handleAddressChanged(url, latencyMs);
            }
        });
    }

    public static synchronized Player get(Context ctx) {
        if (sInst == null) sInst = new Player(ctx);
        return sInst;
    }

    /** 已有实例时取用（服务/UI 都可能先到），不存在则用 ctx 创建 */
    public static synchronized Player peek() {
        return sInst;
    }

    // ---------------- 监听 ----------------

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyTrack() {
        final Item cur = current();
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onTrackChanged(cur);
                }
            });
        }
    }

    private void notifyProgress() {
        final boolean p = playing;
        final int pos = positionMs;
        final int dur = durationMs;
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onProgress(p, pos, dur);
                }
            });
        }
    }

    private void notifyQueue() {
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onQueueChanged();
                }
            });
        }
    }

    private void notifyMode() {
        final int m = mode;
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onModeChanged(m);
                }
            });
        }
    }

    private void notifyError(final String msg) {
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onPlaybackError(msg);
                }
            });
        }
    }

    // ---------------- 状态读取 ----------------

    public List<Item> queue() {
        return queue;
    }

    public int index() {
        return index;
    }

    public Item current() {
        if (index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }

    public boolean isPlaying() {
        if (bassStream != 0) return BassNative.nativeState(bassStream) == 1;   // 1 = BASS_ACTIVE_PLAYING
        return playing;
    }

    /** 是否正在缓冲（首播等待网络时 UI 应显示提示） */
    public boolean isBuffering() {
        if (bassStream != 0) return BassNative.nativeIsStalled(bassStream);
        return buffering;
    }

    public int positionMs() {
        if (bassStream != 0) {
            double s = BassNative.nativePositionSec(bassStream);
            if (s >= 0) positionMs = (int) (s * 1000);
        }
        return positionMs;
    }

    public int durationMs() {
        if (bassStream != 0) {
            double s = BassNative.nativeDurationSec(bassStream);
            if (s > 0) durationMs = (int) (s * 1000);
        }
        return durationMs;
    }

    public int mode() {
        return mode;
    }

    // ---------------- 队列构造 ----------------

    /** 用新列表替换队列并从头/指定位置播放 */
    public void playList(List<Item> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;
        queue.clear();
        queue.addAll(songs);
        shuffleHistory.clear();
        index = Math.max(0, Math.min(startIndex, queue.size() - 1));
        notifyQueue();
        startCurrent(0);
    }

    /** 插到当前曲目之后（「下一首播放」） */
    public void playNext(List<Item> songs) {
        if (songs == null || songs.isEmpty()) return;
        int at = index < 0 ? queue.size() : index + 1;
        queue.addAll(at, songs);
        notifyQueue();
    }

    public void append(List<Item> songs) {
        if (songs == null || songs.isEmpty()) return;
        queue.addAll(songs);
        notifyQueue();
        if (index < 0) {
            index = 0;
            startCurrent(0);
        }
    }

    public void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        boolean wasCurrent = i == index;
        queue.remove(i);
        if (queue.isEmpty()) {
            index = -1;
            stopInternal();
            notifyQueue();
            notifyTrack();
            return;
        }
        if (i < index) index--;
        else if (wasCurrent) {
            if (index >= queue.size()) index = queue.size() - 1;
            startCurrent(0);
        }
        notifyQueue();
    }

    public void move(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size() || from == to) return;
        Item it = queue.remove(from);
        queue.add(to, it);
        if (index == from) index = to;
        else if (from < index && to >= index) index--;
        else if (from > index && to <= index) index++;
        notifyQueue();
    }

    public void clearQueue() {
        queue.clear();
        index = -1;
        stopInternal();
        notifyQueue();
        notifyTrack();
    }

    // ---------------- 播放控制 ----------------

    /**
     * 网络切换、且地址**真的**换了（Library 回调，主线程）：正在播/正在缓冲就用新地址断点续播。
     *
     * <p>方案 A 只在当前地址不可用时才换地址，所以走到这里就意味着旧地址已经不通、
     * 手上的流多半也断了 —— 直接用新地址重建，用户听到的是几秒的续播而不是「莫名停止」。
     * 没在播放（暂停/停止）时只更新地址，下次起播自然会用新的。
     */
    private void handleAddressChanged(String url, long latencyMs) {
        Item cur = current();
        if (!playing && !buffering) {
            PlayLog.w(TAG, "地址已切换（未在播放，仅更新地址）" + PlayLog.safeUrl(url));
            return;
        }
        if (cur == null) return;
        int resumeAt = Math.max(0, positionMs);
        PlayLog.w(TAG, "地址已切换 → 断点续播 song=" + cur.id + " pos=" + resumeAt
                + " 延迟=" + latencyMs + "ms url=" + PlayLog.safeUrl(url));
        notifyError("网络已切换，正在用新地址续播…");
        startCurrent(resumeAt);
    }

    /**
     * 确保前台播放服务在跑。
     *
     * <p>通知栏 / 锁屏控制、以及「切到后台不被系统回收」都靠这个服务。以前只有
     * {@code MainActivity.playNow()} 那条路会 {@code startService} —— 从**播放队列页**点歌、
     * 按耳机键切歌、从云端恢复队列后起播，都绕过了它，于是**没有通知栏、退到后台还可能被杀**。
     * 现在收敛到「真正起播」这一个点（startCurrent 是唯一入口），所有播放路径都覆盖。
     */
    private void ensureService() {
        try {
            appCtx.startService(new android.content.Intent(appCtx, PlaybackService.class));
        } catch (Throwable ignored) {
        }
    }

    private void startCurrent(int seekMs) {
        Item cur = current();
        if (cur == null) return;
        ensureService();
        restoreSeekMs = Math.max(0, seekMs);
        releasePlayer();
        durationMs = cur.durationSec > 0 ? cur.durationSec * 1000 : 0;
        positionMs = restoreSeekMs;
        scrobbledCurrent = false;
        playWhenReady = true;
        preparing = true;
        buffering = true;
        bassRetryCount = 0;
        bassNetRetry = 0;
        bassLastPos = 0;
        bassEndTicks = 0;
        bassStallTicks = 0;
        notifyTrack();
        notifyProgress();

        String url = library.streamUrl(cur.id);
        if (url == null || url.length() == 0) {
            preparing = false;
            buffering = false;      // 必须一起清：否则界面永远停在「缓冲中…」（用户以为在加载，其实什么都没发生）
            notifyError("无法获取播放地址");
            return;
        }
        // ---- 引擎选择：BASS 优先，MP4/M4A 交给系统解码器（它能用 Range 请求取尾部 moov） ----
        FormatSupport.Engine engine = FormatSupport.engineFor(cur, BassNative.available());
        if (engine == FormatSupport.Engine.BASS) {
            startWithBass(cur, url);
            return;
        }
        if (engine == FormatSupport.Engine.NONE) {
            preparing = false;
            buffering = false;
            notifyError("无法播放：" + FormatSupport.unsupportedReason(cur));
            return;
        }
        PlayLog.init(appCtx);
        PlayLog.i("engine=SYSTEM song=" + cur.id + " title=" + (cur.title == null ? "" : cur.title)
                + " suffix=" + FormatSupport.suffixOf(cur) + " bitrate=" + cur.bitrate
                + " url=" + PlayLog.safeUrl(url));
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    preparing = false;
                    buffering = false;
                    int d = 0;
                    try {
                        d = m.getDuration();
                    } catch (Exception ignored) {
                    }
                    if (d > 0) durationMs = d;
                    if (restoreSeekMs > 0 && restoreSeekMs < durationMs - 1000) {
                        try {
                            m.seekTo(restoreSeekMs);
                        } catch (Exception ignored) {
                        }
                    }
                    applyVolume();
                    Log.i(TAG, "onPrepared dur=" + d + " playWhenReady=" + playWhenReady + " state=" + (mp == null ? "null" : "ok"));
                    if (playWhenReady) {
                        try {
                            // 关键：先拿音频焦点再出声，否则部分 ROM（MIUI）不出声音
                            if (!acquireFocus()) {
                                playing = false;
                                PlayLog.w(TAG, "音频焦点被拒，未开始播放");
                                notifyError("无法获取音频焦点，已暂停（可能有其他应用正在播放）");
                                notifyProgress();
                                return;
                            }
                            m.start();
                            playing = true;
                            main.removeCallbacks(ticker);
                            main.post(ticker);
                        } catch (Exception e) {
                            PlayLog.w(TAG, "start() 抛异常", e);
                            notifyError("播放失败：" + e.getMessage());
                        }
                    }
                    notifyProgress();
                    // 上报「正在播放」
                    Item cur = current();
                    if (cur != null) library.scrobble(cur.id, false);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    onTrackFinished();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    preparing = false;
                    playing = false;
                    buffering = false;
                    PlayLog.w(TAG, "MediaPlayer 错误 what=" + what + " extra=" + extra
                            + " song=" + (current() == null ? "?" : current().id));
                    // ① 先判断是不是「系统解码器解不了这个格式」——是就改用 BASS 重试一次。
                    //    这是覆盖所有漏网格式的关键：不依赖码率/后缀的预判，失败即兜底。
                    final Item cur = current();
                    if (cur != null && BassNative.available() && !bassTriedTrackId.equals(cur.id)) {
                        bassTriedTrackId = cur.id;
                        String u = library.streamUrl(cur.id);
                        if (u != null && u.length() > 0) {
                            PlayLog.w(TAG, "系统解码器失败，改用 BASS 重试 song=" + cur.id
                                    + " suffix=" + FormatSupport.suffixOf(cur)
                                    + " what=" + what + " extra=" + extra);
                            notifyError("系统解码器不支持，正在用 BASS 重试…");
                            startWithBass(cur, u);
                            return true;
                        }
                    }
                    // ② 取流失败先别急着跳过：很可能是当前用的地址（内网/外网）切网后不可达，
                    //    换另一个地址重试一次同一首，成功率高很多。
                    if (cur != null && !cur.id.equals(retriedTrackId)) {
                        retriedTrackId = cur.id;
                        notifyError("取流失败，正在切换网络地址重试…");
                        library.reconnectAlternate(new Library.Done<Boolean>() {
                            @Override
                            public void ok(Boolean switched) {
                                if (switched != null && switched.booleanValue()) {
                                    startCurrent(positionMs);
                                } else {
                                    notifyError("播放出错（格式或网络问题），已跳过");
                                    nextAuto();
                                }
                            }

                            @Override
                            public void fail(String message) {
                                notifyError("播放出错（格式或网络问题），已跳过");
                                nextAuto();
                            }
                        });
                        return true;
                    }
                    notifyError("播放出错（格式可能不受支持），已跳过");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            nextAuto();
                        }
                    });
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            playing = false;
            buffering = false;
            PlayLog.w(TAG, "MediaPlayer 创建/配置失败: " + e, e);
            notifyError("无法播放：" + e.getMessage());
        }
    }

    /**
     * 用 BASS 播放当前曲目 —— 除 MP4/M4A 外所有格式的常规路径。
     *
     * <p>走这条路的理由：系统解码器解不了 DSD(.dsf)/APE/WavPack/高码率 WAV/FLAC，
     * 而且对一部分文件会「假播放」（prepare 成功、报在播放、就是没声）。
     * BASS 自带这些解码器（见 jniLibs 里的 libbass*.so）。
     *
     * <p>为什么 MP4/M4A 反而**不**走这里：本库的 m4a 多为 moov 在文件末尾（非 faststart），
     * BASS 的 URL 流式解码会直接回 {@code 47 UNSTREAMABLE}；系统 MediaPlayer 能用 HTTP Range
     * 请求取回尾部 moov 再播。分流规则见 {@link FormatSupport#engineFor}，理由见 FormatSupport 类注释。
     */
    private void startWithBass(final Item song, final String url) {
        // 拦掉「同一首在建流途中被重复起播」——否则每次都会新起一条流并作废上一条，
        // 界面表现为歌曲被飞快跳过（真机日志：0.5 秒内 8 次）。
        if (buffering && song != null && song.id != null && song.id.equals(bassPendingTrackId)) {
            PlayLog.w(TAG, "建流已在途，忽略重复起播 song=" + song.id + " gen=" + bassGen);
            return;
        }
        // 缓存优先：这首已经在 sp-cache 里（之前边播边存过 / m4a 下载过）→ 直接本地播，**零流量**
        java.io.File hit = cachedFileIfAny(song);
        if (hit != null && hit.exists() && hit.length() > 20000) {
            PlayLog.w(TAG, "命中缓存，本地播放（零流量）" + hit.getName() + " " + (hit.length() / 1024) + " KB");
            main.removeCallbacks(ticker);
            releasePlayer();
            playing = false;
            releaseBassStream();
            bassStallTicks = 0;
            bassCompletionHandled = false;
            bassPendingTrackId = "";
            startWithBassLocal(song, hit.getAbsolutePath());
            return;
        }
        // 先把预取句柄取出来：这样后面的 releasePlayer() 不会把它一起释放掉
        final long preloaded;
        if (song != null && song.id != null && song.id.equals(bassNextTrackId) && bassNextStream != 0) {
            preloaded = bassNextStream;
            bassNextStream = 0;
        } else {
            preloaded = 0;
        }
        // 关键：先把 MediaPlayer 与旧 ticker 彻底停掉，再开 BASS。
        // 否则在「进度停滞 / 解码失败 → 切 BASS」这条路径上，MediaPlayer 可能仍在出声，
        // 两套引擎同时输出就是用户听到的「重音」。
        main.removeCallbacks(ticker);
        releasePlayer();          // 内部会 releaseBassStream() + mp.release()
        playing = false;
        releaseBassStream();
        bassStallTicks = 0;
        bassCompletionHandled = false;

        // BASS_StreamCreateURL 会同步做 DNS 解析 → TCP → TLS 握手（移动网络下要数秒）。
        // 它原先跑在主线程，真机日志抓到了 ANR：
        //   native: getaddrinfo → BASS_StreamCreateURL → nativeStreamCreateUrl → startWithBass
        // 现在主线程只做状态切换，建流丢到后台线程，完成后回主线程收尾。
        final int gen = ++bassGen;
        bassPendingTrackId = song.id == null ? "" : song.id;
        final String suffix = FormatSupport.suffixOf(song);
        final boolean dsd = "dsf".equals(suffix) || "dff".equals(suffix);
        PlayLog.init(appCtx);
        PlayLog.i("engine=BASS gen=" + gen + " song=" + song.id
                + " title=" + (song.title == null ? "" : song.title)
                + " suffix=" + suffix + " dsd=" + dsd
                + " preloaded=" + (preloaded != 0)
                + " bitrate=" + song.bitrate
                + " url=" + PlayLog.safeUrl(url));
        buffering = true;                    // 等待期间显示「缓冲中…」，而不是界面卡死
        notifyProgress();
        bassExec.execute(new Runnable() {
            @Override
            public void run() {
                long h = preloaded;
                int createErr = 0;
                boolean initFail = false;
                long t0 = android.os.SystemClock.elapsedRealtime();
                try {
                    if (h == 0) {
                        if (!bassInited) {
                            bassInited = BassNative.nativeInit(-1, 44100);
                            if (!bassInited) initFail = true;
                        }
                        if (!initFail) {
                            h = dsd ? BassNative.nativeDsdStreamCreateUrl(url)
                                    : BassNative.nativeStreamCreateUrl(url);
                            // 错误码是「按线程」保存的：必须在建流这个线程里取，回主线程再问已经晚了
                            if (h == 0) createErr = BassNative.nativeErrorCode();
                        }
                    }
                } catch (Throwable t) {
                    createErr = -1;
                }
                PlayLog.i("创建完成 gen=" + gen + " handle=" + h + " err=" + createErr
                        + " initFail=" + initFail
                        + " 耗时=" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms"
                        + " 线程=" + Thread.currentThread().getName());
                final long fh = h;
                final int ferr = createErr;
                final boolean finitFail = initFail;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != bassGen) {
                            PlayLog.w("丢弃过期建流 gen=" + gen + " 当前=" + bassGen + " handle=" + fh);
                            // 建流期间用户已经切歌/停止：丢掉这条流，否则会和新流一起出声
                            if (fh != 0) {
                                try {
                                    BassNative.nativeStop(fh);
                                    BassNative.nativeFreeStream(fh);
                                } catch (Throwable ignored) {
                                }
                            }
                            return;
                        }
                        finishBassStart(song, url, fh, ferr, finitFail, preloaded != 0);
                    }
                });
            }
        });
    }

    /** 建流完成后回到主线程的收尾（即原 startWithBass 的后半段逻辑） */
    private void finishBassStart(Item song, String url, long h, int createErr, boolean initFail, boolean usedPreload) {
        bassPendingTrackId = "";
        try {
            if (initFail) {
                preparing = false;
                buffering = false;
                notifyError("BASS 初始化失败（错误码 " + BassNative.nativeErrorCode() + "）");
                return;
            }
            bassStream = h;
            if (usedPreload && bassStream != 0) {
                // 预取命中：直接用已经缓冲好的流，省掉建流+起播等待（大文件差别最明显）
                PlayLog.w(TAG, "接管预取流 song=" + song.id + " suffix=" + FormatSupport.suffixOf(song));
            }
            if (bassNextTrackId.equals(song.id)) { bassNextTrackId = ""; }
            if (bassStream == 0) {
                int err = createErr != 0 ? createErr : BassNative.nativeErrorCode();
                PlayLog.w(TAG, "BASS 建流失败 code=" + err + " song=" + song.id);
                // 47=UNSTREAMABLE 41=FILEFORM 44=CODEC：
                // 典型是 MP4/M4A 的 moov 在文件末尾 —— 必须拿到整个文件才能解码，无法流式播放。
                // 这类文件下载到本地就能正常播，所以改成「先整段下载再播」（现在 CDN 4MB/s，2MB 半秒）。
                if (err == 47 || err == 41 || err == 44) {
                    downloadThenPlay(song, url);
                    return;
                }
                // ★ 网络类错误（40 超时 / 32 无网络 / 10 SSL / 48 协议 / 49 拒绝）先重试，别急着判死刑。
                // 实测手机到 Cloudflare 这一跳会在 20KB/s ⇄ 450KB/s 之间剧烈波动，
                // 建流赶上低谷就超时；以前直接报「打不开此曲」并停下 —— 用户看到的就是「无故停了不播放」。
                if (isNetworkError(err) && bassNetRetry < 3) {
                    bassNetRetry++;
                    final int attempt = bassNetRetry;
                    PlayLog.w(TAG, "BASS 网络错误 code=" + err + "，第 " + attempt + "/3 次重试 song=" + song.id
                            + (attempt == 1 ? "（先换地址）" : "（同一地址）"));
                    buffering = true;
                    notifyProgress();
                    if (attempt == 1) {
                        // 第 1 次重试**先换地址**：切网后（出门/回家）旧地址多半已经不可达，
                        // 在原地重试 3 次只会白耗二十多秒 —— 用户看到的就是「一出门就播不了」。
                        notifyError("网络异常，正在切换地址重试…");
                        library.reconnectAlternate(new Library.Done<Boolean>() {
                            @Override
                            public void ok(Boolean switched) {
                                if (switched != null && switched.booleanValue()) {
                                    PlayLog.w(TAG, "已换到新地址，重试 song=" + song.id);
                                } else {
                                    PlayLog.w(TAG, "没有可用备选地址，原地重试 song=" + song.id);
                                }
                                retryBassAfterDelay(song, attempt);
                            }

                            @Override
                            public void fail(String message) {
                                retryBassAfterDelay(song, attempt);
                            }
                        });
                    } else {
                        notifyError("网络异常，正在重试（第 " + attempt + "/3 次）…");
                        retryBassAfterDelay(song, attempt);
                    }
                    return;
                }
                // 只有「文件本身的问题」才自动跳过：
                //   2 = FILEOPEN   服务器上没有这个文件（曲库记录与磁盘不一致）
                //   41 = FILEFORM  格式不支持   44 = CODEC 解码不可用   47 = UNSTREAMABLE 不可流式
                // 网络类错误一律不跳过 —— 跳过会让用户在网络抖动时莫名丢歌。
                if (err == 2 || err == 41 || err == 44 || err == 47) {
                    if (skipBrokenTrack("这首歌在服务器上找不到或无法解码（错误码 " + err + "）")) return;
                }
                preparing = false;
                buffering = false;
                notifyError("BASS 打不开此曲（错误码 " + err + "）：" + FormatSupport.unsupportedReason(song));
                return;
            }
            BassNative.nativeSetVolume(bassStream,
                    Math.max(0f, Math.min(1f, volumePercent / 100f)) * duckFactor);
            // URL 流刚建好时这一次 seek 经常失败（BASS 还没拿到足够数据做「秒 ↔ 字节」换算），
            // 所以只当「尽力而为」，真正的定位交给起播后的 seekAfterStart 重试。
            if (restoreSeekMs > 0) BassNative.nativeSeekSec(bassStream, restoreSeekMs / 1000.0);

            preparing = false;
            if (playWhenReady && !acquireFocus()) {
                playing = false;
                buffering = false;
                notifyError("无法获取音频焦点，已暂停（可能有其他应用正在播放）");
                return;
            }
            boolean ok = BassNative.nativePlay(bassStream, true);
            playing = ok && playWhenReady;
            buffering = false;
            PlayLog.w(TAG, "BASS 播放 song=" + song.id + " suffix=" + FormatSupport.suffixOf(song)
                    + " ok=" + ok + " dur=" + BassNative.nativeDurationSec(bassStream));
            if (!playing) {
                notifyError("BASS 播放失败（错误码 " + BassNative.nativeErrorCode() + "）");
            } else {
                main.removeCallbacks(ticker);
                main.post(ticker);
                library.scrobble(song.id, false);
                cacheInBackground(song);   // 边播边存：下次重播零流量（受流量模式约束）
                if (restoreSeekMs > 0) seekAfterStart(bassStream, restoreSeekMs);
            }
            notifyProgress();
        } catch (Throwable t) {
            preparing = false;
            buffering = false;
            PlayLog.w(TAG, "BASS 播放异常", t);
            notifyError("BASS 播放异常：" + t.getMessage());
        }
    }

    /**
     * 起播后再补一次「断点续播」定位（URL 流专用）。
     *
     * <p>为什么需要：{@code BASS_ChannelSetPosition} 在 URL 流刚建好时经常返回 false
     * ——BASS 还没缓冲到足够的数据做「秒 ↔ 字节」换算。真机实测切网续播会因此**从 0 开始**，
     * 用户听到的是「歌从头放了」。这里在起播后按 0.6s、1.2s… 退避重试几次，到位就停。
     */
    private void seekAfterStart(final long handle, final int targetMs) {
        final int[] tries = {0};
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (handle == 0 || bassStream != handle) return;      // 已经切歌/停止
                if (tries[0] > 0) {
                    long nowMs = Math.round(BassNative.nativePositionSec(handle) * 1000);
                    if (nowMs >= targetMs - 4000) return;             // 已经到位，不再打扰
                }
                boolean ok = BassNative.nativeSeekSec(handle, targetMs / 1000.0);
                tries[0]++;
                PlayLog.w(TAG, "续播定位第 " + tries[0] + " 次 target=" + targetMs + "ms ok=" + ok
                        + " pos=" + Math.round(BassNative.nativePositionSec(handle) * 1000) + "ms");
                if (tries[0] < 6) main.postDelayed(this, 600L * tries[0]);
            }
        };
        main.postDelayed(r, 300);
    }

    /**
     * 推测「下一首」（不改动播放状态）。
     * 随机模式无法预知，就不预取；单曲循环要重播本首，也无需另取。
     */
    private Item peekNext() {
        if (queue.size() < 2 || index < 0 || index >= queue.size()) return null;
        if (mode == MODE_SHUFFLE) return null;
        if (mode == MODE_REPEAT_ONE) return queue.get(index);
        int ni = index + 1;
        if (ni >= queue.size()) {
            if (mode != MODE_REPEAT_ALL) return null;
            ni = 0;
        }
        return queue.get(ni);
    }

    /**
     * 提前把下一首的 BASS 流建好（BASS 会立刻在后台下载缓冲），切歌时直接接管。
     * 只在临近曲尾（40 秒内）才开始，避免长时间两条流同时占带宽。
     *
     * <p>现在全曲库都走 BASS，所以每首歌临近结尾都会预取 —— 而
     * {@code BASS_StreamCreateURL} 是**同步阻塞**的（DNS→TCP→TLS，移动网要数秒），
     * 放主线程就是 ANR（真机已抓到过）。因此建流同样丢到 {@link #bassExec}，
     * 主线程只负责发起与收留结果。
     */
    private void preloadNext() {
        if (!BassNative.available()) return;
        if (!mayUseDataForPrefetch()) return;   // 省流模式下蜂窝网不做任何预取
        Item cur = current();
        final Item next = peekNext();
        if (next == null || cur == null || next.id.equals(cur.id)) return;
        // MP4/M4A 流式放不了（moov 在尾部）：预取改成「提前把整首下好」。
        // 它比建流慢得多（整首 vs 几秒缓冲），所以要早开始 —— 只等当前曲目起播 5 秒就让带宽。
        if (FormatSupport.isMp4Family(FormatSupport.suffixOf(next))) {
            // m4a 的预取是「整首下载」，很费流量：开始太早，一旦用户切歌就整首都白下了。
            // 实测 3.7MB 约 13 秒下完，所以留最后 2 分钟开始就够，别一开播就下。
            if (durationMs <= 0 || positionMs < durationMs - 120000) return;
            prefetchMp4(next);
            return;
        }
        if (bassNextStream != 0 || bassPreloading) return;
        // 流式预取只在曲尾 15 秒内开始（原先 40 秒）：越早开始，切歌/停止时浪费的流量越多
        if (durationMs <= 0 || positionMs < durationMs - 15000) return;
        if (FormatSupport.engineFor(next, true) != FormatSupport.Engine.BASS) return;
        final String url = library.streamUrl(next.id);
        if (url == null || url.length() == 0) return;
        final String suffix = FormatSupport.suffixOf(next);
        final boolean dsd = "dsf".equals(suffix) || "dff".equals(suffix);
        final int pgen = ++preloadGen;
        bassPreloading = true;
        PlayLog.i("预取发起 song=" + next.id + " suffix=" + suffix + " dsd=" + dsd);
        bassExec.execute(new Runnable() {
            @Override
            public void run() {
                long h = 0;
                int err = 0;
                long t0 = android.os.SystemClock.elapsedRealtime();
                try {
                    h = dsd ? BassNative.nativeDsdStreamCreateUrl(url)
                            : BassNative.nativeStreamCreateUrl(url);
                    if (h == 0) err = BassNative.nativeErrorCode();   // 错误码按线程保存，必须在这条线程取
                } catch (Throwable t) {
                    err = -1;
                }
                final long fh = h;
                final int ferr = err;
                final long cost = android.os.SystemClock.elapsedRealtime() - t0;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        bassPreloading = false;
                        if (fh == 0) {
                            // 预取失败不影响当前播放：切歌时现建流就行，只是慢一点
                            PlayLog.i("预取失败 song=" + next.id + " err=" + ferr + " 耗时=" + cost + "ms");
                            return;
                        }
                        if (pgen != preloadGen || bassNextStream != 0) {
                            PlayLog.w("丢弃过期预取 song=" + next.id + " handle=" + fh);
                            try {
                                BassNative.nativeStop(fh);
                                BassNative.nativeFreeStream(fh);
                            } catch (Throwable ignored) {
                            }
                            return;
                        }
                        bassNextStream = fh;
                        bassNextTrackId = next.id;
                        PlayLog.i("预取完成 song=" + next.id + " handle=" + fh + " 耗时=" + cost + "ms");
                    }
                });
            }
        });
    }

    /** 释放预取流（没被接管时） */
    private void releaseBassNext() {
        preloadGen++;          // 让还在后台建的预取流作废
        bassPreloading = false;
        if (bassNextStream != 0) {
            try {
                BassNative.nativeStop(bassNextStream);
                BassNative.nativeFreeStream(bassNextStream);
            } catch (Throwable ignored) {
            }
            bassNextStream = 0;
            bassNextTrackId = "";
        }
    }

    /** 只查缓存文件在不在（不做容量裁剪，可以放心在主线程调用） */
    private java.io.File cachedFileIfAny(Item song) {
        if (song == null || song.id == null) return null;
        try {
            java.io.File dir = com.lixscn.subsonicplayer.core.MediaCache.dir(appCtx);
            String ext = FormatSupport.suffixOf(song);
            if (ext.length() == 0) ext = "bin";
            java.io.File f = new java.io.File(dir, song.id + "." + ext);
            if (f.isFile()) return f;
            // 后缀是从 contentType 兜底出来的、或以前服务端没给后缀时，老缓存的文件名是 <id>.bin。
            // 名字对不上就当「没缓存」会白丢已经下好的数据（还会重新走一遍网络），所以回退查一次。
            if (!"bin".equals(ext)) {
                java.io.File legacy = new java.io.File(dir, song.id + ".bin");
                if (legacy.isFile()) return legacy;
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这首是否已有**完整的本地缓存**（{@code sp-cache} 里的整首文件）。
     *
     * <p>给界面画「本地」标志用：只做一次 stat，主线程可以调。
     * 正在下载（只有 .part）不算 —— 那时还没法离线播。
     */
    public boolean isCachedLocally(Item song) {
        java.io.File f = cachedFileIfAny(song);
        return f != null && f.isFile() && f.length() > 20000;
    }

    /** 当前活动网络是否计费（蜂窝 / 热点）。判断不出来时按「计费」处理 —— 省流量优先。 */
    private boolean isMeteredNetwork() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 当前是否允许「预取 / 缓存」。受设置里的流量模式约束：
     * {@code 0}=省流（蜂窝不做，默认） {@code 1}=标准（都做） {@code 2}=关闭（都不做）。
     */
    private boolean mayUseDataForPrefetch() {
        int m = library.settings().dataMode();
        if (m == 2) return false;
        if (m == 0) return !isMeteredNetwork();
        return true;
    }

    /**
     * 「边播边存」：正在流式播放的曲子，后台整首存进 {@code sp-cache}，下次重播**零流量**。
     *
     * <p>代价是首次会多下一遍（用户已确认接受），所以只在 {@link #mayUseDataForPrefetch()} 允许时做
     * —— 默认「省流」模式即**只在 WiFi 下**才缓存，蜂窝永远不额外花流量。
     */
    /**
     * 正在后台缓存的曲目进度（0-100）；{@code -1} 表示这首没有在缓存。给播放页进度条画「已缓存」用。
     */
    public int cachePercent(String songId) {
        if (songId == null || !songId.equals(cacheSongId)) return -1;
        if (cacheTotal <= 0) return 0;
        int p = (int) (cacheDownloaded * 100L / cacheTotal);
        return Math.max(0, Math.min(100, p));
    }

    private void cacheInBackground(final Item song) {
        if (song == null || song.id == null) return;
        if (!mayUseDataForPrefetch()) return;
        if (song.id.equals(prefetchingTrackId)) return;              // 已经在下载 / 已经下过
        java.io.File probe = cachedFileIfAny(song);
        if (probe != null && probe.exists() && probe.length() > 20000) return;   // 已缓存
        final String url = library.streamUrl(song.id);
        if (url == null || url.length() == 0) return;
        prefetchingTrackId = song.id;
        cacheSongId = song.id;          // 进度条据此显示「正在缓存这首」的进度
        cacheDownloaded = 0;
        cacheTotal = 0;
        PlayLog.i("边播边存发起 song=" + song.id);
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File out = cacheFileFor(song);               // 含容量裁剪，放后台线程做
                if (out == null || (out.exists() && out.length() > 20000)) return;
                downloadToFile(url, out, "边播边存", song);
            }
        }).start();
    }

    /** 曲目在下载缓存里的目标文件（顺带做一次容量裁剪，避免越下越多吃掉用户存储） */
    private java.io.File cacheFileFor(Item song) {
        if (song == null || song.id == null) return null;
        try {
            com.lixscn.subsonicplayer.core.MediaCache.prune(appCtx,
                    com.lixscn.subsonicplayer.core.MediaCache.MAX_BYTES);
            java.io.File dir = com.lixscn.subsonicplayer.core.MediaCache.dir(appCtx);
            String ext = FormatSupport.suffixOf(song);
            if (ext.length() == 0) ext = "bin";
            return new java.io.File(dir, song.id + "." + ext);
        } catch (Throwable t) {
            PlayLog.w(TAG, "缓存目录不可用", t);
            return null;
        }
    }

    /**
     * BASS 网络错误后的延迟重试（「先换地址」与「原地重试」共用）。
     *
     * <p>URL 在这里才现取：等待期间地址可能已经被换成内网/外网另一个，
     * 直接用 captured 的旧 URL 重试等于白换。
     */
    private void retryBassAfterDelay(final Item song, final int attempt) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                Item c = current();
                if (c == null || song.id == null || !song.id.equals(c.id)) return;
                startWithBass(song, library.streamUrl(song.id));
            }
        }, 1200L * attempt);
    }

    /**
     * BASS 错误码里属于「这一跳暂时不通」的：值得稍后重试，而不是判定这首放不了。
     * 40=TIMEOUT 32=NONET 10=SSL 48=PROTOCOL 49=DENIED。
     */
    private static boolean isNetworkError(int err) {
        return err == 40 || err == 32 || err == 10 || err == 48 || err == 49;
    }

    /**
     * 把 url 整段下到 out（先写 .part 再改名）。可在任意线程调用。
     *
     * <p>为什么不用 MediaPlayer 的流式方案：mp4 家族要取尾部 moov，它会反复发 Range 请求，
     * 每次连接跨境要 0.5~11 秒；一条顺序连接直接下完整首反而快得多。
     *
     * <p>失败会整个重来一次：链路是抖的（实测 20KB/s ⇄ 450KB/s），重试往往就成了。
     * 重试前会**按曲目重新解析一次地址**：切网换地址后旧地址（多半是内网）已经不通，
     * 拿旧 URL 再撞一次 8 秒超时纯属浪费（真机日志里满屏这种「边播边存下载失败」）。
     *
     * @return 是否成功（文件 > 20KB 才算成功）
     */
    private boolean downloadToFile(String url, java.io.File out, String tag, Item song) {
        String useUrl = url;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt > 1 && song != null && song.id != null) {
                String fresh = library.streamUrl(song.id);
                if (fresh != null && fresh.length() > 0 && !fresh.equals(useUrl)) {
                    PlayLog.w(TAG, tag + "重试改用新地址 " + PlayLog.safeUrl(fresh));
                    useUrl = fresh;
                }
            }
            long total = 0;
            java.io.File tmp = new java.io.File(out.getAbsolutePath() + ".part");
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(useUrl).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                java.io.InputStream in = c.getInputStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                final int contentLen = c.getContentLength();     // 进度条要用总长（未知则 0）
                if (contentLen > 0) { cacheTotal = contentLen; cacheDownloaded = 0; }
                byte[] buf = new byte[32768];
                int n;
                long t0 = android.os.SystemClock.elapsedRealtime();
                // 后台下载（预取/边播边存）限速：实测不限速时均速只有 114KB/s 却仍在和播放抢带宽，
                // 会把正在播放的流饿死 → 卡顿。前台下载（用户正等着听）不限速。
                final long maxBps = "前台".equals(tag) ? 0L : 80L * 1024;
                boolean aborted = false;
                int tick = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                    cacheDownloaded = (int) Math.min(total, Integer.MAX_VALUE);   // 进度条实时值
                    // 省流模式：下载途中切到蜂窝（WiFi 出门/断开）不该继续把整首下完 ——
                    // 用户设置「省流（仅 WiFi）」就是这个意思，不查的话一出门就偷偷吃掉几十 MB。
                    // 每 ~1MB 查一次（计费判定要走 ConnectivityManager，别每 32KB 都问）。
                    if (maxBps > 0 && ++tick >= 32) {
                        tick = 0;
                        if (!mayUseDataForPrefetch()) {
                            aborted = true;
                            break;
                        }
                    }
                    if (maxBps > 0) {
                        long el = android.os.SystemClock.elapsedRealtime() - t0;
                        long want = total * 1000L / maxBps;      // 按这个速度本该用掉的时间
                        if (want > el) {
                            try {
                                Thread.sleep(Math.min(300L, want - el));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                fos.flush();
                fos.close();
                in.close();
                if (aborted) {
                    PlayLog.w(TAG, tag + "下载中止：网络已切到蜂窝（省流模式）"
                            + "，已下 " + (total / 1024) + " KB");
                    tmp.delete();
                    return false;
                }
                long ms = Math.max(1, android.os.SystemClock.elapsedRealtime() - t0);
                if (total > 20000) {
                    if (out.exists()) out.delete();
                    if (tmp.renameTo(out)) {
                        PlayLog.i(tag + "下载完成 " + out.getName() + " " + (total / 1024) + " KB 耗时="
                                + ms + "ms 均速=" + (total / ms) + " KB/s");
                        return true;
                    }
                }
                PlayLog.w(TAG, tag + "下载不完整，只有 " + total + " B（第 " + attempt + " 次）");
            } catch (Throwable e) {
                PlayLog.w(TAG, tag + "下载失败（第 " + attempt + " 次）", e);
            }
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 后台把下一首（MP4/M4A 这类流式放不了的）整段下好。
     *
     * <p>动机：这类文件只能「先下载后播」，首次播放要等整首下完（手机上 3MB 约 38 秒）。
     * 与其等切歌时让用户干等，不如现在就下 —— 切歌时 {@link #downloadThenPlay} 命中缓存瞬开。
     * 失败不重试（记在 {@link #prefetchingTrackId} 里），免得每 500ms 的 ticker 反复发线程；
     * 真要放的时候前台下载还会再兜一次。
     */
    private void prefetchMp4(final Item song) {
        if (song == null || song.id == null) return;
        if (song.id.equals(prefetchingTrackId)) return;        // 已经下过/正在下
        final java.io.File out = cacheFileFor(song);
        if (out == null) return;
        if (out.exists() && out.length() > 20000) return;      // 已缓存
        final String url = library.streamUrl(song.id);
        if (url == null || url.length() == 0) return;
        prefetchingTrackId = song.id;
        PlayLog.i("预下载发起 song=" + song.id + " suffix=" + FormatSupport.suffixOf(song));
        new Thread(new Runnable() {
            @Override
            public void run() {
                downloadToFile(url, out, "预", song);
            }
        }).start();
    }

    /**
     * 「先下载后播」：用于 BASS 报 UNSTREAMABLE 的文件（如 moov 在末尾的 M4A）。
     * 下载到应用缓存目录，再用本地文件建流；后台预下载过或播过一次就直接命中缓存，秒开。
     */
    private void downloadThenPlay(final Item song, final String url) {
        try {
            final java.io.File out = cacheFileFor(song);
            if (out == null) {
                preparing = false;
                buffering = false;
                notifyError("无法准备下载：缓存目录不可用");
                return;
            }
            if (out.exists() && out.length() > 20000) {      // 命中缓存（含后台预下载好的）
                PlayLog.w(TAG, "命中下载缓存 " + out.getName() + " (" + out.length() + " B)");
                startWithBassLocal(song, out.getAbsolutePath());
                return;
            }
            buffering = true;
            notifyError("此格式需先下载，正在下载…");
            notifyProgress();
            final int gen = bassGen;   // 下载期间用户可能已经切歌：对不上就丢弃，别抢播
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean okf = downloadToFile(url, out, "前台", song);
                    final long tot = out.length();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (okf) {
                                if (gen != bassGen) {
                                    PlayLog.w(TAG, "丢弃过期下载（期间已切歌）song=" + song.id);
                                    return;
                                }
                                PlayLog.w(TAG, "下载完成 " + (tot / 1024) + " KB → 本地播放 song=" + song.id);
                                startWithBassLocal(song, out.getAbsolutePath());
                            } else {
                                buffering = false;
                                notifyError("下载失败，无法播放此曲");
                                notifyProgress();
                            }
                        }
                    });
                }
            }).start();
        } catch (Throwable e) {
            preparing = false;
            buffering = false;
            notifyError("无法准备下载：" + e.getMessage());
        }
    }

    /** 用本地文件建 BASS 流（供「先下载后播」使用） */
    private void startWithBassLocal(Item song, String path) {
        try {
            // ★★ BASS_Init 是懒执行的（原先只在「后台建流」那条路上调用），
            // 而「缓存命中 → 本地播放」是直接建本地流、绕过了它 ——
            // 于是首次播放（BASS 尚未初始化）必然报 **错误码 8 = BASS_ERROR_INIT**。
            // 真机反馈「本地播放失败(错误码 8)」就是这个。建本地流前先确保初始化。
            if (!bassInited) {
                bassInited = BassNative.nativeInit(-1, 44100);
                if (!bassInited) {
                    preparing = false;
                    buffering = false;
                    PlayLog.w(TAG, "本地播放前 BASS 初始化失败");
                    notifyError("BASS 初始化失败（错误码 " + BassNative.nativeErrorCode() + "）");
                    return;
                }
            }
            releasePlayer();
            bassStallTicks = 0;
            bassCompletionHandled = false;
            String suffix = FormatSupport.suffixOf(song);
            boolean dsd = "dsf".equals(suffix) || "dff".equals(suffix);
            bassStream = dsd ? BassNative.nativeDsdCreateFile(path)
                             : BassNative.nativeStreamCreateFile(path);
            if (bassStream == 0) {
                int err = BassNative.nativeErrorCode();
                preparing = false;
                buffering = false;
                PlayLog.w(TAG, "本地建流失败 code=" + err + " path=" + path);
                notifyError("本地播放失败（错误码 " + err + "）");
                return;
            }
            BassNative.nativeSetVolume(bassStream,
                    Math.max(0f, Math.min(1f, volumePercent / 100f)) * duckFactor);
            if (restoreSeekMs > 0) BassNative.nativeSeekSec(bassStream, restoreSeekMs / 1000.0);
            preparing = false;
            if (playWhenReady && !acquireFocus()) {
                playing = false;
                buffering = false;
                notifyError("无法获取音频焦点，已暂停（可能有其他应用正在播放）");
                return;
            }
            boolean ok = BassNative.nativePlay(bassStream, true);
            playing = ok && playWhenReady;
            buffering = false;
            if (playing) {
                main.removeCallbacks(ticker);
                main.post(ticker);
                library.scrobble(song.id, false);
            }
            notifyProgress();
        } catch (Throwable e) {
            preparing = false;
            buffering = false;
            PlayLog.w(TAG, "本地播放异常", e);
            notifyError("本地播放异常：" + e.getMessage());
        }
    }

    private void releaseBassStream() {
        if (bassStream != 0) {
            try {
                BassNative.nativeStop(bassStream);
                BassNative.nativeFreeStream(bassStream);
            } catch (Throwable ignored) {
            }
            bassStream = 0;
        }
    }

    private void applyVolume() {
        float v0 = Math.max(0f, Math.min(1f, volumePercent / 100f)) * duckFactor;
        if (bassStream != 0) {
            BassNative.nativeSetVolume(bassStream, v0);
            return;
        }
        if (mp == null) return;
        try {
            float v = Math.max(0f, Math.min(1f, volumePercent / 100f)) * duckFactor;
            mp.setVolume(v, v);
            Log.i(TAG, "setVolume=" + v + " (percent=" + volumePercent + " duck=" + duckFactor + ")");
        } catch (Exception ignored) {
        }
    }

    /** 临时压低音量（音频焦点被 duck 时用）：只影响本次输出，不写入设置 */
    public void setDuckFactor(float factor) {
        duckFactor = Math.max(0f, Math.min(1f, factor));
        applyVolume();
    }

    public void toggle() {
        if (playing) pause();
        else resume();
    }

    public void pause() {
        playWhenReady = false;
        if (bassStream != 0) BassNative.nativePause(bassStream);
        if (mp != null && playing) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
        playing = false;
        main.removeCallbacks(ticker);
        abandonFocus();
        notifyProgress();
        saveState();
    }

    public void resume() {
        Item cur = current();
        if (cur == null) {
            // 队列为空：尝试恢复上次队列
            if (!restoreLastQueue()) return;
            startCurrent(restoreSeekMs);
            return;
        }
        playWhenReady = true;
        // 建流还在后台线程跑（bassStream 尚未赋值）：这次 resume 只记下「想播」即可。
        // 否则会再起一条流、作废原来那条 —— 反复 resume 就变成跳歌雪崩。
        if (bassStream == 0 && mp == null && (preparing || buffering)) {
            notifyProgress();
            return;
        }
        if (bassStream != 0) {
            if (!acquireFocus()) return;
            BassNative.nativePlay(bassStream, false);
            playing = true;
            main.removeCallbacks(ticker);
            main.post(ticker);
            notifyProgress();
            return;
        }
        if (mp == null) {
            startCurrent(positionMs);
            return;
        }
        if (preparing) return;
        try {
            if (!acquireFocus()) return;
            mp.start();
            playing = true;
            main.removeCallbacks(ticker);
            main.post(ticker);
            Item c = current();
            if (c != null) library.scrobble(c.id, false);
        } catch (Exception e) {
            // 播放器状态异常，重建
            startCurrent(positionMs);
        }
        notifyProgress();
    }

    public void stop() {
        playWhenReady = false;
        stopInternal();
        notifyProgress();
        saveState();
    }

    private void stopInternal() {
        playing = false;
        buffering = false;
        main.removeCallbacks(ticker);
        releasePlayer();
        abandonFocus();
        positionMs = 0;
        durationMs = 0;
    }

    private void releasePlayer() {
        releaseBassStream();
        releaseBassNext();
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    public void next() {
        if (queue.isEmpty()) return;
        if (mode == MODE_SHUFFLE) {
            shuffleHistory.add(index);
            index = randomIndex();
        } else {
            index++;
            if (index >= queue.size()) {
                if (mode == MODE_REPEAT_ALL || mode == MODE_SEQUENTIAL) index = 0;
                else index = queue.size() - 1;
            }
        }
        startCurrent(0);
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (positionMs > 5000) {
            seekTo(0);
            return;
        }
        if (mode == MODE_SHUFFLE && !shuffleHistory.isEmpty()) {
            index = shuffleHistory.remove(shuffleHistory.size() - 1);
            if (index < 0 || index >= queue.size()) index = 0;
        } else {
            index--;
            if (index < 0) index = queue.size() - 1;
        }
        startCurrent(0);
    }

    /** 自动续播（曲目播完/出错跳过） */
    /**
     * 「服务器上找不到文件 / 打不开」这类错误：提示 + 自动跳下一首。
     * 连续失败 5 次就停下报错 —— 否则整库失效时会疯狂跳歌、用户不知道发生了什么。
     * @return true 表示已接管（调用方应直接 return，不要再报错）
     */
    private boolean skipBrokenTrack(String why) {
        if (consecutivePlayFailures >= 5) {
            notifyError("连续 5 首都无法播放，已停止（" + why + "）");
            return false;
        }
        consecutivePlayFailures++;
        PlayLog.w(TAG, "跳过无法播放的曲目（" + why + "）连续失败=" + consecutivePlayFailures);
        notifyError(why + "，已自动跳过");
        nextAuto();
        return true;
    }

    /**
     * 单曲循环：把当前这条流 seek 回开头接着播，成功返回 true。
     *
     * <p>为什么要复用而不是重建：BASS 的流只在内存里缓冲（30 秒余量），播完不落盘，
     * 重建 = **整首歌重新下载一遍**。循环一首 1620kbps 的 flac，一圈就是几十 MB 的纯重复流量。
     */
    private boolean replayCurrentBySeek() {
        if (bassStream == 0) return false;
        try {
            BassNative.nativeSeekSec(bassStream, 0);
            if (!acquireFocus()) return false;
            if (!BassNative.nativePlay(bassStream, false)) return false;
            positionMs = 0;
            bassCompletionHandled = false;
            bassEndTicks = 0;
            bassLastPos = 0;
            bassStallTicks = 0;
            bassRetryCount = 0;
            bassNetRetry = 0;
            scrobbledCurrent = false;
            playWhenReady = true;
            playing = true;
            PlayLog.w(TAG, "单曲循环：复用同一条流（省掉一次整首下载）");
            notifyProgress();
            main.removeCallbacks(ticker);
            main.post(ticker);
            return true;
        } catch (Throwable t) {
            PlayLog.w(TAG, "复用流转失败，改为重建", t);
            return false;
        }
    }

    private void nextAuto() {
        if (queue.isEmpty()) return;
        if (mode == MODE_REPEAT_ONE) {
            if (replayCurrentBySeek()) return;   // 能复用就复用，别重新下载
            startCurrent(0);
            return;
        }
        if (mode == MODE_SHUFFLE) {
            shuffleHistory.add(index);
            index = randomIndex();
            startCurrent(0);
            return;
        }
        index++;
        if (index >= queue.size()) {
            if (mode == MODE_REPEAT_ALL) {
                index = 0;
                startCurrent(0);
            } else {
                index = queue.size() - 1;
                stopInternal();
                notifyTrack();
                notifyProgress();
                saveState();
            }
            return;
        }
        startCurrent(0);
    }

    private void onTrackFinished() {
        consecutivePlayFailures = 0;   // 正常播完一首 → 失败计数清零
        Item cur = current();
        if (cur != null) library.scrobble(cur.id, true);
        positionMs = durationMs;
        notifyProgress();
        nextAuto();
    }

    private int randomIndex() {
        if (queue.size() <= 1) return 0;
        int r = random.nextInt(queue.size());
        if (r == index) r = (r + 1) % queue.size();
        return r;
    }

    public void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        startCurrent(0);
    }

    public void seekTo(int ms) {
        positionMs = Math.max(0, ms);
        if (bassStream != 0) {
            BassNative.nativeSeekSec(bassStream, positionMs / 1000.0);
            notifyProgress();
            return;
        }
        if (mp != null && !preparing) {
            try {
                mp.seekTo(positionMs);
            } catch (Exception ignored) {
            }
        }
        notifyProgress();
    }

    public void seekRatio(float ratio) {
        int d = durationMs > 0 ? durationMs : (current() != null ? current().durationSec * 1000 : 0);
        if (d <= 0) return;
        seekTo((int) (d * Math.max(0f, Math.min(1f, ratio))));
    }

    public void setMode(int m) {
        mode = ((m % 4) + 4) % 4;
        library.settings().setPlayMode(mode);
        notifyMode();
    }

    public void cycleMode() {
        setMode(mode + 1);
    }

    public void setVolumePercent(int percent) {
        volumePercent = Math.max(0, Math.min(100, percent));
        library.settings().setVolume(volumePercent);
        applyVolume();
    }

    public int volumePercent() {
        return volumePercent;
    }

    // ---------------- scrobble / 位置记忆 ----------------

    private void maybeScrobble() {
        if (scrobbledCurrent || durationMs <= 0) return;
        Item cur = current();
        if (cur == null) return;
        // 播放超过一半或 4 分钟即上报一次
        if (positionMs > durationMs / 2 || positionMs > 4 * 60 * 1000) {
            scrobbledCurrent = true;
            library.scrobble(cur.id, true);
        }
    }

    /** 持久化「当前曲目 + 位置 + 队列」 */
    public void saveState() {
        try {
            JSONObject o = new JSONObject();
            Item cur = current();
            o.put("songId", cur == null ? "" : cur.id);
            o.put("position", positionMs);
            o.put("index", index);
            JSONArray q = new JSONArray();
            for (Item it : queue) {
                JSONObject s = new JSONObject();
                s.put("id", it.id);
                s.put("title", it.title);
                s.put("artist", it.artist);
                s.put("artistId", it.artistId);
                s.put("album", it.album);
                s.put("albumId", it.albumId);
                s.put("coverArt", it.coverArt);
                s.put("duration", it.durationSec);
                // ★ 格式信息必须一起存：服务端对 APE/DSD 只给 contentType 不给 suffix，
                //   恢复队列时若丢掉它们，「本地」标志、缓存文件名、DSD 专用建流函数就全错了。
                s.put("suffix", it.suffix);
                s.put("contentType", it.contentType);
                s.put("bitrate", it.bitrate);
                s.put("starred", it.starred);
                q.put(s);
            }
            o.put("queue", q);
            sp.edit().putString("state", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 启动时恢复上次队列与位置（不自动播放） */
    public boolean restoreLastQueue() {
        try {
            String raw = sp.getString("state", "");
            if (raw == null || raw.length() == 0) return false;
            JSONObject o = new JSONObject(raw);
            JSONArray q = o.optJSONArray("queue");
            if (q == null || q.length() == 0) return false;
            queue.clear();
            for (int i = 0; i < q.length(); i++) {
                JSONObject s = q.optJSONObject(i);
                if (s == null) continue;
                Item it = Item.song();
                it.id = s.optString("id", "");
                it.title = s.optString("title", "");
                it.artist = s.optString("artist", "");
                it.artistId = s.optString("artistId", "");
                it.album = s.optString("album", "");
                it.albumId = s.optString("albumId", "");
                it.coverArt = s.optString("coverArt", "");
                it.durationSec = s.optInt("duration", 0);
                it.suffix = s.optString("suffix", "");
                it.contentType = s.optString("contentType", "");
                it.bitrate = s.optInt("bitrate", 0);
                it.starred = s.optBoolean("starred", false);
                StringBuilder sb = new StringBuilder();
                if (it.artist.length() > 0) sb.append(it.artist);
                if (it.album.length() > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(it.album);
                }
                it.subtitle = sb.toString();
                queue.add(it);
            }
            index = Math.max(0, Math.min(o.optInt("index", 0), queue.size() - 1));
            restoreSeekMs = o.optInt("position", 0);
            positionMs = restoreSeekMs;
            notifyQueue();
            notifyTrack();
            notifyProgress();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 本地书签：把「歌曲 + 播放位置」存到本机（与 Pages.localBookmarks 的格式一致） */
    public void saveBookmark(Item song, int positionMs) {
        if (song == null || song.id == null || song.id.length() == 0) return;
        try {
            SharedPreferences bsp = appCtx.getSharedPreferences("sp_history", Context.MODE_PRIVATE);
            JSONArray in;
            try {
                in = new JSONArray(bsp.getString("bookmarks", "[]"));
            } catch (Exception e) {
                in = new JSONArray();
            }
            JSONArray out = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("id", song.id);
            o.put("title", song.title);
            o.put("artist", song.artist);
            o.put("artistId", song.artistId);
            o.put("album", song.album);
            o.put("albumId", song.albumId);
            o.put("coverArt", song.coverArt);
            o.put("duration", song.durationSec);
            o.put("suffix", song.suffix);
            o.put("contentType", song.contentType);
            o.put("bitrate", song.bitrate);
            o.put("position", positionMs);
            o.put("at", System.currentTimeMillis());
            out.put(o);
            for (int i = 0; i < in.length() && out.length() < 100; i++) {
                JSONObject e = in.optJSONObject(i);
                if (e != null && !song.id.equals(e.optString("id", ""))) out.put(e);
            }
            bsp.edit().putString("bookmarks", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public int loadBookmark(String songId) {
        return sp.getInt("bm_" + songId, 0);
    }

    /** 打乱队列 */
    public void shuffleQueue() {
        if (queue.size() <= 1) return;
        Item cur = current();
        Collections.shuffle(queue, random);
        if (cur != null) index = queue.indexOf(cur);
        notifyQueue();
    }
}
