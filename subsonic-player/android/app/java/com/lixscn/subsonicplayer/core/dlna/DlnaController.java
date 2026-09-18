package com.lixscn.subsonicplayer.core.dlna;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.core.PlayLog;
import com.lixscn.subsonicplayer.player.FormatSupport;
import com.lixscn.subsonicplayer.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * DLNA 推送的编排层（进程内单例）。
 *
 * <p>职责：记住「正在推到哪台设备、推的是哪首」；把 {@link Item} 变成 Subsonic 直链 + DIDL-Lite
 * 交给 {@link DlnaRenderer}；轮询远端状态/进度；一首放完自动推下一首（与本地播放的行为一致）。
 *
 * <p>与本地播放的关系：**开始推送时把本地播放暂停**（避免两台设备同时出声）；
 * 推送期间界面上的播放/暂停/上一首/下一首/进度都走远端。
 */
public final class DlnaController {

    private static final String TAG = "Dlna";
    private static final long POLL_INTERVAL_MS = 1500;
    /** 连续多少次轮询失败就认定设备掉了（约 8 秒）—— 否则会永远每 1.5 秒重试一次、界面卡在「正在推送」 */
    private static final int POLL_FAIL_LIMIT = 5;

    private static DlnaController sInst;

    public interface Listener {
        /** 远端状态/进度/设备变化（主线程） */
        void onCastChanged();

        /** 推送出错（主线程） */
        void onCastError(String message);
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();

    private DlnaDevice device;
    private DlnaRenderer renderer;
    private volatile boolean casting;
    private volatile String remoteState = "";
    private volatile long posMs;
    private volatile long durMs;
    private volatile String currentSongId = "";
    private volatile String currentTitle = "";
    private boolean polling;
    private int pollGen;
    /** 连续轮询失败次数（音箱断电/换网时会一直连不上，必须能自己收场） */
    private int pollFailures;
    /** 我们自己发的 Stop（用来区分「用户停的」和「这首放完了」） */
    private volatile boolean expectedStop;

    private DlnaController(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static synchronized DlnaController get(Context ctx) {
        if (sInst == null) sInst = new DlnaController(ctx);
        return sInst;
    }

    public static synchronized DlnaController peek() {
        return sInst;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onCastChanged();
                }
            });
        }
    }

    private void notifyError(final String msg) {
        PlayLog.w(TAG, "推送出错：" + msg);
        for (final Listener l : new ArrayList<Listener>(listeners)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onCastError(msg);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public boolean isCasting() {
        return casting;
    }

    public DlnaDevice device() {
        return device;
    }

    /** 远端传输状态：PLAYING / PAUSED_PLAYBACK / STOPPED…（空串 = 未知） */
    public String remoteState() {
        return remoteState;
    }

    public boolean remotePlaying() {
        return "PLAYING".equals(remoteState) || "TRANSITIONING".equals(remoteState);
    }

    public long positionMs() {
        return posMs;
    }

    public long durationMs() {
        return durMs;
    }

    public String currentSongId() {
        return currentSongId;
    }

    public String currentTitle() {
        return currentTitle;
    }

    /** 界面显示用：正在推送到哪台设备 */
    public String statusText() {
        if (!casting || device == null) return "";
        return "正在推送到 " + device.displayName();
    }

    // ------------------------------------------------------------------
    // 发现 / 连接
    // ------------------------------------------------------------------

    public DlnaDiscovery.Scan scan(int waitMs, DlnaDiscovery.Callback cb) {
        return DlnaDiscovery.scan(appCtx, waitMs, cb);
    }

    /** 连上某台设备（只做可达性检查），成功后记住它 */
    public void connect(final DlnaDevice d, final Library.Done<Boolean> done) {
        if (d == null) {
            if (done != null) done.fail("设备为空");
            return;
        }
        Library.get(appCtx).run(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                DlnaRenderer r = new DlnaRenderer(d);
                return Boolean.valueOf(r.reachable());
            }
        }, new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                if (value != null && value.booleanValue()) {
                    device = d;
                    renderer = new DlnaRenderer(d);
                    PlayLog.w(TAG, "已连接 " + d.displayName() + " (" + d.host + ")");
                    notifyChanged();
                    if (done != null) done.ok(Boolean.TRUE);
                } else {
                    if (done != null) done.fail("设备无响应（GetTransportInfo 失败）");
                }
            }

            @Override
            public void fail(String message) {
                if (done != null) done.fail(message);
            }
        });
    }

    // ------------------------------------------------------------------
    // 推送播放
    // ------------------------------------------------------------------

    /** 把某首歌推到当前设备播放（先 SetAVTransportURI 再 Play） */
    public void cast(final Item song, final Library.Done<Boolean> done) {
        if (song == null || renderer == null) {
            if (done != null) done.fail("还没有选择 DLNA 设备");
            return;
        }
        // 本地先停：否则手机和音箱同时出声
        try {
            Player p = Player.peek();
            if (p != null) p.pause();
        } catch (Throwable ignored) {
        }

        final String url = streamUrl(song);
        if (url == null || url.length() == 0) {
            if (done != null) done.fail("拿不到播放地址");
            return;
        }
        final String didl = didl(song, url);
        final String sid = song.id == null ? "" : song.id;
        final String title = song.title == null ? "" : song.title;
        PlayLog.w(TAG, "推送 song=" + sid + " \"" + title + "\" → " + device.displayName()
                + " url=" + PlayLog.safeUrl(url));

        Library.get(appCtx).run(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                expectedStop = false;
                if (!renderer.setAvTransportUri(url, didl)) return Boolean.FALSE;
                if (!renderer.play()) return Boolean.FALSE;
                return Boolean.TRUE;
            }
        }, new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                if (value != null && value.booleanValue()) {
                    casting = true;
                    currentSongId = sid;
                    currentTitle = title;
                    posMs = 0;
                    durMs = song.durationSec > 0 ? song.durationSec * 1000L : 0;
                    remoteState = "PLAYING";
                    startPolling();
                    notifyChanged();
                    if (done != null) done.ok(Boolean.TRUE);
                } else {
                    if (done != null) done.fail("设备拒绝了这首（SetAVTransportURI/Play 失败）");
                }
            }

            @Override
            public void fail(String message) {
                if (done != null) done.fail(message);
            }
        });
    }

    public void play() {
        remoteAction(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                return Boolean.valueOf(renderer != null && renderer.play());
            }
        }, "播放");
    }

    public void pause() {
        remoteAction(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                return Boolean.valueOf(renderer != null && renderer.pause());
            }
        }, "暂停");
    }

    public void seek(final long ms) {
        remoteAction(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                return Boolean.valueOf(renderer != null && renderer.seek(ms));
            }
        }, "定位");
    }

    public int volume() {
        return renderer == null ? -1 : renderer.volume();
    }

    public void setVolume(final int v) {
        remoteAction(new Library.Work<Boolean>() {
            @Override
            public Boolean run() {
                return Boolean.valueOf(renderer != null && renderer.setVolume(v));
            }
        }, "调音量");
    }

    /** 推送的上一首/下一首：推进本地队列索引（不本地起播），再推给音箱 */
    public void skip(final boolean forward) {
        Player p = Player.peek();
        if (p == null) return;
        Item next = p.advanceForCast(forward);
        if (next == null) {
            PlayLog.w(TAG, "队列到头了，停止推送");
            stopCasting(true);
            return;
        }
        cast(next, null);
    }

    /** 停止推送：可选把远端也 Stop */
    public void stopCasting(final boolean stopRemote) {
        final DlnaRenderer r = renderer;
        boolean wasCasting = casting;
        casting = false;
        remoteState = "";
        currentSongId = "";
        currentTitle = "";
        stopPolling();
        notifyChanged();
        if (stopRemote && r != null && wasCasting) {
            expectedStop = true;
            Library.get(appCtx).run(new Library.Work<Boolean>() {
                @Override
                public Boolean run() {
                    r.stop();
                    return Boolean.TRUE;
                }
            }, null);
        }
    }

    /** 只是断开（忘记设备） */
    public void forgetDevice() {
        stopCasting(true);
        device = null;
        renderer = null;
        notifyChanged();
    }

    /**
     * 交互类动作（播放/暂停/定位/音量）：跑在后台线程，失败只提示、不改变推送状态
     * —— 音箱在切状态时会短暂拒绝请求，不该因此把「正在推送」标记清掉。
     */
    private void remoteAction(final Library.Work<Boolean> work, final String what) {
        if (renderer == null) {
            notifyError("还没有选择 DLNA 设备");
            return;
        }
        Library.get(appCtx).run(work, new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                if (value == null || !value.booleanValue()) {
                    notifyError(what + "失败（设备没有响应）");
                } else {
                    notifyChanged();
                }
            }

            @Override
            public void fail(String message) {
                notifyError(what + "失败：" + message);
            }
        });
    }

    // ------------------------------------------------------------------
    // 轮询远端状态 / 进度 / 自动下一首
    // ------------------------------------------------------------------

    private void startPolling() {
        // ★ 每次都换 gen 重新起循环（不能因为 polling 已 true 就直接 return）：
        //   「远端播完 → 自动推下一首」那条路径里 pollOnce 是 return 掉的，
        //   若这里不重新起，轮询就永久停摆 —— 表现为进度不动、也不再自动切歌。
        polling = true;
        pollFailures = 0;           // 新一轮推送：失败计数清零
        final int gen = ++pollGen;
        main.post(new Runnable() {
            @Override
            public void run() {
                pollOnce(gen);
            }
        });
    }

    private void stopPolling() {
        polling = false;
        pollGen++;
    }

    private void pollOnce(final int gen) {
        if (!polling || gen != pollGen || renderer == null) return;
        Library.get(appCtx).run(new Library.Work<String[]>() {
            @Override
            public String[] run() {
                String st = renderer.transportState();
                long[] pd = renderer.positionAndDuration();
                return new String[]{
                        st == null ? "" : st,
                        pd == null ? "-1" : String.valueOf(pd[0]),
                        pd == null ? "-1" : String.valueOf(pd[1])
                };
            }
        }, new Library.Done<String[]>() {
            @Override
            public void ok(String[] v) {
                if (!polling || gen != pollGen) return;
                if (v == null || v[0].length() == 0) {
                    // 单次失败不当回事（音箱切状态时会短暂拒绝请求），但连着失败就要收场
                    pollFailed();
                    if (polling && gen == pollGen) scheduleNextPoll(gen);
                    return;
                }
                pollFailures = 0;
                String prev = remoteState;
                remoteState = v[0];
                if (!"-1".equals(v[1])) posMs = Long.parseLong(v[1]);
                if (!"-1".equals(v[2]) && Long.parseLong(v[2]) > 0) durMs = Long.parseLong(v[2]);

                boolean justStopped = "STOPPED".equals(remoteState)
                        && ("PLAYING".equals(prev) || "TRANSITIONING".equals(prev));
                if (justStopped) {
                    if (expectedStop) {
                        expectedStop = false;
                        PlayLog.w(TAG, "远端已停止（我们自己发的 Stop）");
                    } else {
                        // 这首放完了 → 推下一首（和本地播放一样自动切歌）
                        PlayLog.w(TAG, "远端播完 → 自动推下一首（pos=" + posMs + "/" + durMs + "）");
                        skip(true);
                        return;
                    }
                }
                notifyChanged();
                scheduleNextPoll(gen);
            }

            @Override
            public void fail(String message) {
                if (!polling || gen != pollGen) return;
                pollFailed();
                if (polling && gen == pollGen) scheduleNextPoll(gen);
            }
        });
    }

    /**
     * 轮询失败累计：连着 {@link #POLL_FAIL_LIMIT} 次连不上就自动结束推送。
     *
     * <p>没有这一步的话，音箱关掉/换网之后 App 会每 1.5 秒永远重试下去，
     * 界面一直停在「推送到 XXX」，用户既没法继续也没法退出（真机反馈过）。
     */
    private void pollFailed() {
        pollFailures++;
        if (pollFailures == 1) {
            PlayLog.w(TAG, "轮询失败（音箱暂时没响应，继续观察）");
        }
        if (pollFailures >= POLL_FAIL_LIMIT) {
            String nm = device == null ? "DLNA 设备" : device.displayName();
            PlayLog.w(TAG, "连续 " + pollFailures + " 次连不上 " + nm + " → 自动结束推送");
            stopCasting(false);          // 设备都不可达了，别再去发 Stop
            notifyError("连不上 " + nm + " 了，已停止推送");
        }
    }

    private void scheduleNextPoll(final int gen) {
        if (!polling || gen != pollGen) return;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollOnce(gen);
            }
        }, POLL_INTERVAL_MS);
    }

    // ------------------------------------------------------------------
    // 组装给音箱的元数据
    // ------------------------------------------------------------------

    /** 音箱要自己去拉的直链（Subsonic 的 stream，鉴权参数已在里面） */
    private String streamUrl(Item song) {
        try {
            return Library.get(appCtx).streamUrl(song.id);
        } catch (Throwable t) {
            return "";
        }
    }

    /** DIDL-Lite：音箱靠它显示歌名/艺人/专辑，很多设备缺了它直接不播 */
    static String didl(Item song, String url) {
        String mime = mimeOf(song);
        StringBuilder sb = new StringBuilder(512);
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
                .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
                .append("<item id=\"").append(x(song.id)).append("\" parentID=\"0\" restricted=\"1\">")
                .append("<dc:title>").append(x(song.title)).append("</dc:title>")
                .append("<dc:creator>").append(x(song.artist)).append("</dc:creator>")
                .append("<upnp:artist>").append(x(song.artist)).append("</upnp:artist>")
                .append("<upnp:album>").append(x(song.album)).append("</upnp:album>")
                .append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>");
        if (song.durationSec > 0) {
            sb.append("<upnp:duration>").append(DlnaRenderer.hms(song.durationSec * 1000L)).append("</upnp:duration>");
        }
        sb.append("<res protocolInfo=\"http-get:*:").append(mime).append(":*\"");
        if (song.durationSec > 0) {
            sb.append(" duration=\"").append(DlnaRenderer.hms(song.durationSec * 1000L)).append("\"");
        }
        sb.append(">").append(x(url)).append("</res>")
                .append("</item></DIDL-Lite>");
        return sb.toString();
    }

    /** 给音箱的 MIME：优先用服务端给的 contentType，退回按后缀映射 */
    static String mimeOf(Item song) {
        if (song.contentType != null && song.contentType.length() > 0) return song.contentType;
        String suffix = FormatSupport.suffixOf(song);
        if ("mp3".equals(suffix)) return "audio/mpeg";
        if ("m4a".equals(suffix) || "mp4".equals(suffix) || "aac".equals(suffix)) return "audio/mp4";
        if ("flac".equals(suffix)) return "audio/flac";
        if ("wav".equals(suffix)) return "audio/wav";
        if ("ogg".equals(suffix) || "opus".equals(suffix)) return "audio/ogg";
        if ("ape".equals(suffix)) return "audio/x-ape";
        if ("wv".equals(suffix)) return "audio/x-wavpack";
        if ("dsf".equals(suffix) || "dff".equals(suffix)) return "audio/x-dsf";
        return "audio/mpeg";
    }

    private static String x(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
