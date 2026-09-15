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
                if (st == 0 && nearEnd && !bassCompletionHandled && bassStream != 0) {
                    bassCompletionHandled = true;
                    Log.w(TAG, "BASS 曲目结束 → 按模式切下一首");
                    main.removeCallbacks(ticker);
                    onTrackFinished();
                    return;
                }
                if (!nearEnd && (st == 0 || st == 3)) {
                    bassStallTicks++;
                    if (bassStallTicks >= 4) {           // 约 2 秒没恢复
                        bassStallTicks = 0;
                        Item sc = current();
                        if (sc != null && bassRetryCount < 3) {
                            bassRetryCount++;
                            int resumeAt = Math.max(0, positionMs);
                            Log.w(TAG, "BASS 断流，重连第 " + bassRetryCount + " 次 pos=" + resumeAt + " state=" + st);
                            notifyError("网络中断，正在重连（第 " + bassRetryCount + "/3 次）…");
                            restoreSeekMs = resumeAt;
                            startWithBass(sc, library.streamUrl(sc.id));
                            return;
                        }
                        if (sc != null && bassRetryCount >= 3) {
                            bassRetryCount = 4;          // 只提示一次
                            notifyError("网络中断，重连 3 次仍未成功，已停止");
                            Log.w(TAG, "BASS 重连失败，放弃 song=" + sc.id);
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
                                Log.w(TAG, "进度停滞，改用 BASS song=" + sc.id + " suffix=" + FormatSupport.suffixOf(sc));
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

    private void startCurrent(int seekMs) {
        Item cur = current();
        if (cur == null) return;
        restoreSeekMs = Math.max(0, seekMs);
        releasePlayer();
        durationMs = cur.durationSec > 0 ? cur.durationSec * 1000 : 0;
        positionMs = restoreSeekMs;
        scrobbledCurrent = false;
        playWhenReady = true;
        preparing = true;
        buffering = true;
        bassRetryCount = 0;
        bassStallTicks = 0;
        notifyTrack();
        notifyProgress();

        String url = library.streamUrl(cur.id);
        if (url == null || url.length() == 0) {
            preparing = false;
            notifyError("无法获取播放地址");
            return;
        }
        // ---- 引擎选择：系统解码器搞不定的格式交给 BASS ----
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
                                Log.w(TAG, "音频焦点被拒，未开始播放");
                                notifyError("无法获取音频焦点，已暂停（可能有其他应用正在播放）");
                                notifyProgress();
                                return;
                            }
                            m.start();
                            playing = true;
                            main.removeCallbacks(ticker);
                            main.post(ticker);
                        } catch (Exception e) {
                            Log.w(TAG, "start() 抛异常", e);
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
                    Log.w(TAG, "MediaPlayer 错误 what=" + what + " extra=" + extra
                            + " song=" + (current() == null ? "?" : current().id));
                    // ① 先判断是不是「系统解码器解不了这个格式」——是就改用 BASS 重试一次。
                    //    这是覆盖所有漏网格式的关键：不依赖码率/后缀的预判，失败即兜底。
                    final Item cur = current();
                    if (cur != null && BassNative.available() && !bassTriedTrackId.equals(cur.id)) {
                        bassTriedTrackId = cur.id;
                        String u = library.streamUrl(cur.id);
                        if (u != null && u.length() > 0) {
                            Log.w(TAG, "系统解码器失败，改用 BASS 重试 song=" + cur.id
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
            Log.w(TAG, "MediaPlayer 创建/配置失败: " + e, e);
            notifyError("无法播放：" + e.getMessage());
        }
    }

    /**
     * 用 BASS 播放当前曲目。
     *
     * <p>为什么需要：安卓系统解码器（MediaPlayer）解不了 DSD(.dsf)、APE、WavPack，
     * 以及高码率 WAV（24/32bit）—— 服务端又不转码，这些曲子在上面就是「点了没反应」。
     * BASS 自带这些解码器（见 jniLibs 里的 libbass*.so）。
     *
     * <p>只在这类格式上走这条路；其余曲目仍由 MediaPlayer 播放，通知栏/焦点链路完全不变。
     */
    private void startWithBass(Item song, String url) {
        try {
            // 先把预取句柄取出来：这样后面的 releasePlayer() 不会把它一起释放掉
            long preloaded = 0;
            if (song != null && song.id != null && song.id.equals(bassNextTrackId) && bassNextStream != 0) {
                preloaded = bassNextStream;
                bassNextStream = 0;
            }
            // 关键：先把 MediaPlayer 与旧 ticker 彻底停掉，再开 BASS。
            // 否则在「进度停滞 / 解码失败 → 切 BASS」这条路径上，MediaPlayer 可能仍在出声，
            // 两套引擎同时输出就是用户听到的「重音」。
            main.removeCallbacks(ticker);
            releasePlayer();          // 内部会 releaseBassStream() + mp.release()
            playing = false;
            if (!bassInited) {
                bassInited = BassNative.nativeInit(-1, 44100);
                if (!bassInited) {
                    preparing = false;
                    buffering = false;
                    notifyError("BASS 初始化失败（错误码 " + BassNative.nativeErrorCode() + "）");
                    return;
                }
            }
            releaseBassStream();
            bassStallTicks = 0;
            bassCompletionHandled = false;
            String suffix = FormatSupport.suffixOf(song);
            boolean dsd = "dsf".equals(suffix) || "dff".equals(suffix);
            if (preloaded != 0) {
                // 预取命中：直接用已经缓冲好的流，省掉建流+起播等待（大文件差别最明显）
                bassStream = preloaded;
                Log.w(TAG, "接管预取流 song=" + song.id + " suffix=" + suffix);
            } else {
                bassStream = dsd
                        ? BassNative.nativeDsdStreamCreateUrl(url)
                        : BassNative.nativeStreamCreateUrl(url);
            }
            if (bassNextTrackId.equals(song.id)) { bassNextTrackId = ""; }
            if (bassStream == 0) {
                int err = BassNative.nativeErrorCode();
                Log.w(TAG, "BASS 建流失败 code=" + err + " song=" + song.id);
                // 47=UNSTREAMABLE 41=FILEFORM 44=CODEC：
                // 典型是 MP4/M4A 的 moov 在文件末尾 —— 必须拿到整个文件才能解码，无法流式播放。
                // 这类文件下载到本地就能正常播，所以改成「先整段下载再播」（现在 CDN 4MB/s，2MB 半秒）。
                if (err == 47 || err == 41 || err == 44) {
                    downloadThenPlay(song, url);
                    return;
                }
                // 只有「文件本身的问题」才自动跳过：
                //   2 = FILEOPEN   服务器上没有这个文件（曲库记录与磁盘不一致）
                //   41 = FILEFORM  格式不支持   44 = CODEC 解码不可用   47 = UNSTREAMABLE 不可流式
                // 网络类错误（40 超时 / 24 无网络 / 10 SSL / 8 初始化…）一律不跳过 ——
                // 那是暂时性的：跳过会让用户在网络抖动时莫名丢歌，交给重连逻辑处理更合适。
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
            Log.w(TAG, "BASS 播放 song=" + song.id + " suffix=" + suffix
                    + " ok=" + ok + " dur=" + BassNative.nativeDurationSec(bassStream));
            if (!playing) {
                notifyError("BASS 播放失败（错误码 " + BassNative.nativeErrorCode() + "）");
            } else {
                main.removeCallbacks(ticker);
                main.post(ticker);
                library.scrobble(song.id, false);
            }
            notifyProgress();
        } catch (Throwable t) {
            preparing = false;
            buffering = false;
            Log.w(TAG, "BASS 播放异常", t);
            notifyError("BASS 播放异常：" + t.getMessage());
        }
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
     * 只在临近曲尾（40 秒内）才开始，避免长时间两条流同时占带宽；
     * 只预取 BASS 曲目 —— 系统 MediaPlayer 没办法后台预热另一条流。
     */
    private void preloadNext() {
        if (bassNextStream != 0) return;
        if (!BassNative.available()) return;
        if (durationMs <= 0 || positionMs < durationMs - 40000) return;
        Item cur = current();
        Item next = peekNext();
        if (next == null || cur == null || next.id.equals(cur.id)) return;
        if (FormatSupport.engineFor(next, true) != FormatSupport.Engine.BASS) return;
        String url = library.streamUrl(next.id);
        if (url == null || url.length() == 0) return;
        String suffix = FormatSupport.suffixOf(next);
        boolean dsd = "dsf".equals(suffix) || "dff".equals(suffix);
        long h = dsd ? BassNative.nativeDsdStreamCreateUrl(url)
                     : BassNative.nativeStreamCreateUrl(url);
        if (h == 0) return;
        bassNextStream = h;
        bassNextTrackId = next.id;
        Log.w(TAG, "已预取下一首 song=" + next.id + " suffix=" + suffix);
    }

    /** 释放预取流（没被接管时） */
    private void releaseBassNext() {
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

    /**
     * 「先下载后播」：用于 BASS 报 UNSTREAMABLE 的文件（如 moov 在末尾的 M4A）。
     * 下载到应用缓存目录，再用本地文件建流；同一首第二次播放直接命中缓存，秒开。
     */
    private void downloadThenPlay(final Item song, final String url) {
        try {
            // 下载前先按上限裁剪缓存（500MB，最久未使用的先删），避免越堆越多吃掉用户存储
            com.lixscn.subsonicplayer.core.MediaCache.prune(appCtx,
                    com.lixscn.subsonicplayer.core.MediaCache.MAX_BYTES);
            java.io.File dir = com.lixscn.subsonicplayer.core.MediaCache.dir(appCtx);
            String ext = FormatSupport.suffixOf(song);
            if (ext.length() == 0) ext = "bin";
            final java.io.File out = new java.io.File(dir, song.id + "." + ext);
            if (out.exists() && out.length() > 20000) {      // 命中缓存
                Log.w(TAG, "命中下载缓存 " + out.getName() + " (" + out.length() + " B)");
                startWithBassLocal(song, out.getAbsolutePath());
                return;
            }
            buffering = true;
            notifyError("此格式需先下载，正在下载…");
            notifyProgress();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    long total = 0;
                    java.io.File tmp = new java.io.File(out.getAbsolutePath() + ".part");
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(30000);
                        c.setInstanceFollowRedirects(true);
                        java.io.InputStream in = c.getInputStream();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
                        fos.flush(); fos.close(); in.close();
                        ok = total > 20000;
                        if (ok) { if (out.exists()) out.delete(); tmp.renameTo(out); }
                    } catch (Throwable e) {
                        Log.w(TAG, "下载失败: " + e, e);
                    }
                    final boolean okf = ok;
                    final long tot = total;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (okf) {
                                Log.w(TAG, "下载完成 " + (tot / 1024) + " KB → 本地播放 song=" + song.id);
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
                Log.w(TAG, "本地建流失败 code=" + err + " path=" + path);
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
            Log.w(TAG, "本地播放异常", e);
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
        Log.w(TAG, "跳过无法播放的曲目（" + why + "）连续失败=" + consecutivePlayFailures);
        notifyError(why + "，已自动跳过");
        nextAuto();
        return true;
    }

    private void nextAuto() {
        if (queue.isEmpty()) return;
        if (mode == MODE_REPEAT_ONE) {
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

    // ---------------- 云端队列同步 ----------------

    public void syncQueueToCloud() {
        if (queue.isEmpty()) return;
        final List<String> ids = new ArrayList<String>();
        for (Item it : queue) ids.add(it.id);
        final Item cur = current();
        final String curId = cur == null ? "" : cur.id;
        final long pos = positionMs;
        library.run(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                if (library.client() != null) {
                    library.client().savePlayQueue(ids, curId, pos);
                }
                return Boolean.TRUE;
            }
        }, null);
    }

    /** 从云端恢复队列（返回是否成功） */
    public void restoreQueueFromCloud(final Library.Done<Boolean> done) {
        library.run(new Library.Work<List<Item>>() {
            @Override
            public List<Item> run() {
                return library.client() == null ? null : library.client().getPlayQueue();
            }
        }, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                if (value == null || value.isEmpty()) {
                    if (done != null) done.ok(Boolean.FALSE);
                    return;
                }
                queue.clear();
                queue.addAll(value);
                index = 0;
                notifyQueue();
                notifyTrack();
                if (done != null) done.ok(Boolean.TRUE);
            }

            @Override
            public void fail(String message) {
                if (done != null) done.ok(Boolean.FALSE);
            }
        });
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
