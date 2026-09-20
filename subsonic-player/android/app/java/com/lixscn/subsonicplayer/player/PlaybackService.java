package com.lixscn.subsonicplayer.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Http;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;

/**
 * 后台播放服务：前台服务 + MediaSession + 通知栏/锁屏控制。
 *
 * 播放逻辑在 {@link Player}（进程内单例），本服务只负责：
 * 1. 以前台服务身份保活，保证息屏/切后台后继续播放；
 * 2. 向系统暴露 MediaSession（通知栏、锁屏、蓝牙耳机按键）；
 * 3. 处理音频焦点与耳机拔出。
 */
public class PlaybackService extends Service implements Player.Listener {

    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "sp_playback";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_PLAY_PAUSE = "com.lixscn.subsonicplayer.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.lixscn.subsonicplayer.NEXT";
    public static final String ACTION_PREV = "com.lixscn.subsonicplayer.PREV";
    public static final String ACTION_STOP = "com.lixscn.subsonicplayer.STOP";

    private Player player;
    private Library library;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean foregroundStarted;

    private Bitmap artwork;
    private String artworkSongId = "";

    private BroadcastReceiver noisyReceiver;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** DLNA 推送状态变化：通知栏/锁屏也要跟着刷新（推送中本地心跳是停的） */
    private final com.lixscn.subsonicplayer.core.dlna.DlnaController.Listener dlnaListener =
            new com.lixscn.subsonicplayer.core.dlna.DlnaController.Listener() {
                @Override
                public void onCastChanged() {
                    updateSessionState();
                    updateNotification();
                }

                @Override
                public void onCastError(String message) {
                    updateNotification();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        library = Library.get(this);
        player = Player.get(this);
        player.addListener(this);
        com.lixscn.subsonicplayer.core.dlna.DlnaController.get(this).addListener(dlnaListener);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
        setupSession();
        setupNoisyReceiver();
        // 系统（MIUI 省电）杀掉进程后用 START_STICKY 把服务拉起来时，自动接上刚才那首
        player.restoreLastQueue();
        player.autoResumeIfKilled();
    }

    /** 用户从最近任务里划掉 App：这是主动收场，别再自动续播 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        player.markUserStopped();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action != null) {
            com.lixscn.subsonicplayer.core.dlna.DlnaController dc =
                    com.lixscn.subsonicplayer.core.dlna.DlnaController.peek();
            boolean casting = dc != null && dc.isCasting();
            if (ACTION_PLAY_PAUSE.equals(action)) {
                // 推送中：通知栏/耳机键操作的是**音箱**（否则会出现「手机显示暂停、音箱却在放」）
                if (casting) {
                    if (dc.remotePlaying()) dc.pause();
                    else dc.play();
                } else if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.resume();   // 焦点申请由 Player 内部完成
                }
            } else if (ACTION_NEXT.equals(action)) {
                if (casting) dc.skip(true);
                else player.next();
            } else if (ACTION_PREV.equals(action)) {
                if (casting) dc.skip(false);
                else player.previous();
            } else if (ACTION_STOP.equals(action)) {
                if (casting) {
                    // 通知栏的「停止」= 要安静，别再交回手机接着放
                    dc.stopCastingSilently();
                }
                player.pause();
                player.markUserStopped();     // 用户主动停止 → 之后冷启动不要自动续播
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        startForegroundCompat();
        updateNotification();
        // START_STICKY：被系统回收后尽量恢复（播放状态已持久化）
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        com.lixscn.subsonicplayer.core.dlna.DlnaController dc =
                com.lixscn.subsonicplayer.core.dlna.DlnaController.peek();
        if (dc != null) dc.removeListener(dlnaListener);
        player.removeListener(this);
        if (noisyReceiver != null) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "播放控制",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private PendingIntent serviceAction(String action) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private Notification buildNotification() {
        Item cur = player.current();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("openNowPlaying", true);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        String title = cur == null ? "未在播放" : cur.title;
        String sub = cur == null ? "" : cur.subtitle;

        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(player.isPlaying());

        if (artwork != null && cur != null && cur.id.equals(artworkSongId)) {
            b.setLargeIcon(artwork);
        }

        PendingIntent prev = serviceAction(ACTION_PREV);
        PendingIntent toggle = serviceAction(ACTION_PLAY_PAUSE);
        PendingIntent next = serviceAction(ACTION_NEXT);

        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_prev), "上一首", prev).build());
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                        player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play), "播放/暂停", toggle).build());
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_next), "下一首", next).build());

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);

        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "通知更新失败: " + e.getMessage());
        }
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            foregroundStarted = true;
        } catch (Exception e) {
            Log.w(TAG, "startForeground 失败: " + e.getMessage());
        }
    }

    private void stopForegroundCompat() {
        if (foregroundStarted) {
            try {
                stopForeground(true);
            } catch (Exception ignored) {
            }
            foregroundStarted = false;
        }
    }

    // ---------------- MediaSession ----------------

    private void setupSession() {
        session = new MediaSession(this, "SubsonicPlayer");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                player.resume();
            }

            @Override
            public void onPause() {
                player.pause();
            }

            @Override
            public void onSkipToNext() {
                player.next();
            }

            @Override
            public void onSkipToPrevious() {
                player.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                player.seekTo((int) pos);
            }

            @Override
            public void onStop() {
                player.pause();
                stopForegroundCompat();
                stopSelf();
            }
        });
        session.setActive(true);
        updateSessionState();
    }

    private void updateSessionState() {
        if (session == null) return;
        Item cur = player.current();
        // 推送中：通知栏/锁屏要反映**音箱**在唱（否则手机说「暂停」、音箱却在放，状态对不上）
        com.lixscn.subsonicplayer.core.dlna.DlnaController dc =
                com.lixscn.subsonicplayer.core.dlna.DlnaController.peek();
        boolean casting = dc != null && dc.isCasting();
        String artist = cur == null ? "" : cur.artist;
        if (casting) {
            String nm = dc.device() == null ? "DLNA 设备" : dc.device().displayName();
            artist = artist.length() == 0 ? ("推送到 " + nm) : (artist + " · 推送到 " + nm);
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, cur == null ? "" : cur.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, cur == null ? "" : cur.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        casting ? dc.durationMs() : player.durationMs());
        if (artwork != null && cur != null && cur.id.equals(artworkSongId)) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        session.setMetadata(md.build());

        boolean playing = casting ? dc.remotePlaying() : player.isPlaying();
        long pos = casting ? dc.positionMs() : player.positionMs();
        int state = playing ? PlaybackState.STATE_PLAYING
                : (player.current() == null ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED);
        PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(state, pos, playing ? 1f : 0f);
        session.setPlaybackState(pb.build());
    }

    // ---------------- 音频焦点 ----------------
    // 焦点已移交给 Player 自己持有（见 Player.acquireFocus）：直接点歌起播时 Service 可能还没创建完，
    // 放在这里会有竞态，导致「在播但没声音」。Service 只负责耳机拔出暂停等系统广播。

    private void setupNoisyReceiver() {
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    // 拔耳机 / 蓝牙断开都会走这里。必须留一行日志：
                    // 否则事后看 playback.log 完全分不清「用户/蓝牙把它暂停了」还是「卡死没切歌」。
                    com.lixscn.subsonicplayer.core.PlayLog.w(TAG, "音频输出断开（耳机/蓝牙 becoming-noisy）→ 暂停");
                    player.pause();
                }
            }
        };
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    // ---------------- Player.Listener ----------------

    @Override
    public void onTrackChanged(Item song) {
        if (song != null && !song.id.equals(artworkSongId)) {
            artwork = null;
            artworkSongId = song.id;
            loadArtwork(song);
        }
        updateSessionState();
        updateNotification();
    }

    @Override
    public void onProgress(boolean playing, int positionMs, int durationMs) {
        // ★ MediaSession 的 setPlaybackState / setMetadata 每次都是一次 binder 调用（会惊动 system_server）。
        //   原来跟着 500ms 心跳做**全量重建**（metadata + state = 2 次 binder/500ms）。
        //   现在：状态没变且距上次不足 1 秒就跳过；metadata 只在曲目/推送状态真正变化时才重建
        //   （见 onTrackChanged / onCastChanged 里的 updateSessionState）。
        long now = android.os.SystemClock.elapsedRealtime();
        if (playing != lastSessionPlaying || now - lastSessionAt >= 1000) {
            lastSessionPlaying = playing;
            lastSessionAt = now;
            updateSessionProgress(playing, positionMs);
        }
        // 播放状态切换时刷新通知（按钮图标需要变）
        if (playing != lastPlaying) {
            lastPlaying = playing;
            updateNotification();
        }
        if (!playing && positionMs == 0) {
            // 队列结束/停止
            updateNotification();
        }
    }

    /** 只推位置与状态，不重建 metadata（省一次 binder 与一次对象分配） */
    private void updateSessionProgress(boolean playing, long positionMs) {
        if (session == null) return;
        com.lixscn.subsonicplayer.core.dlna.DlnaController dc =
                com.lixscn.subsonicplayer.core.dlna.DlnaController.peek();
        boolean casting = dc != null && dc.isCasting();
        boolean p = casting ? dc.remotePlaying() : playing;
        long pos = casting ? dc.positionMs() : positionMs;
        int state = p ? PlaybackState.STATE_PLAYING
                : (player.current() == null ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(state, pos, p ? 1f : 0f)
                .build());
    }

    private boolean lastPlaying;
    private boolean lastSessionPlaying;
    private long lastSessionAt;

    @Override
    public void onQueueChanged() {
        updateNotification();
    }

    @Override
    public void onModeChanged(int mode) {
    }

    @Override
    public void onPlaybackError(String message) {
        updateNotification();
    }

    /** 异步取封面用于通知栏/锁屏 */
    private void loadArtwork(final Item song) {
        if (song.coverArt == null || song.coverArt.length() == 0) return;
        final String url = library.coverUrl(song.coverArt, 512);
        if (url.length() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = Http.getBytes(url, 15000);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp != null) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                artwork = bmp;
                                updateSessionState();
                                updateNotification();
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }, "artwork-load").start();
    }
}
