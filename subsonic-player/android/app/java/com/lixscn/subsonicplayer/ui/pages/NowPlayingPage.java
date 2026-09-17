package com.lixscn.subsonicplayer.ui.pages;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.core.Lyrics;
import com.lixscn.subsonicplayer.core.LyricsProvider;
import com.lixscn.subsonicplayer.core.SubsonicClient;
import com.lixscn.subsonicplayer.player.Player;
import com.lixscn.subsonicplayer.ui.CircleCover;
import com.lixscn.subsonicplayer.ui.CoverLoader;
import com.lixscn.subsonicplayer.ui.Menus;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏「正在播放」页（竖屏手机）。
 *
 * <p>结构自上而下：收起按钮 + 标题 / 大封面 / 歌名·艺术家·专辑 / 进度条（可拖动定位）
 * / 播放控制（上一首·播放暂停·下一首 + 模式·收藏·音量）/ 下半区**整块留给歌词**。
 *
 * <p>歌词做卡拉 OK 滚动：当前行 accent 色 + 加粗放大，前后行按距离渐隐，
 * 行变化时把该行平滑滚动到可视区中间（不重建视图，只改已有 TextView 的属性）。
 * 歌词来源三级：服务端 getLyricsBySongId（OpenSubsonic）→ 服务端 getLyrics →
 * {@link LyricsProvider} 的网络兜底（LRCLIB / 网易云），全部在后台线程取，
 * 主线程回调，切歌用自增 token 丢弃过期结果。
 *
 * <p>播放队列已独立成页（见 {@link QueuePage}，入口在本页顶部工具栏的队列按钮），
 * 因此这里不再有「歌词 / 队列」Tab 与内嵌队列列表。
 *
 * <p>只使用 Android Framework + Java 8，全部程序化建 View，零第三方依赖。
 */
public class NowPlayingPage extends Page {

    // ---------------- 常量 ----------------

    /** 向服务端请求封面的尺寸参数 */
    private static final int COVER_REQUEST_SIZE = 640;

    /** 当前行 / 普通行的歌词字号（sp） */
    private static final float LYRIC_SIZE_ACTIVE = 19f;
    private static final float LYRIC_SIZE_NORMAL = 16f;

    // ---------------- 视图 ----------------

    private CircleCover cover;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    /** 「本地」标志：这首已有完整缓存，重播零流量 */
    private TextView localBadge;

    private SeekBar seekBar;
    private TextView posText;
    private TextView durText;
    /** 正在拖动进度条：期间进度与位置文本不跟随播放刷新 */
    private boolean dragging;

    private ImageView playBtn;
    private ImageView modeBtn;
    private ImageView starBtn;
    /** 上一次设置的播放按钮图标，避免每 500ms 重复 setImageResource */
    private int playIconRes;

    private View lyricsPane;
    /** 毛玻璃底图（专辑/歌手相片，低透明度铺满） */
    private ImageView bgArt;

    private ScrollView lyricsScroll;
    private LinearLayout lyricsBox;
    private final List<TextView> lineViews = new ArrayList<TextView>();

    // ---------------- 歌词状态 ----------------

    private final LyricsProvider lyricsProvider = new LyricsProvider();
    /** 当前渲染的歌词（可能是同步歌词或纯文本） */
    private Lyrics lyrics;
    /** 当前高亮行，-1 = 未开始 / 无同步歌词 */
    private int currentLine = -1;
    /** 切歌令牌：回调回来时对不上就丢弃，避免旧歌歌词覆盖新歌 */
    private int lyricsToken;
    /** 已经加载（或正在加载）歌词的歌曲 id，避免重复请求 */
    private String lyricsSongId = "";

    /** 监听器：Player 的回调都在主线程 */
    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onTrackChanged(Item song) {
            if (cover == null) return;
            syncTrack();
        }

        @Override
        public void onProgress(boolean playing, int positionMs, int durationMs) {
            if (cover == null) return;
            updatePlayIcon(playing);
            updateProgress(positionMs, durationMs);
            updateLyricHighlight(positionMs);
            // 进度条的「浅色二级段」= 这首已经缓存到哪（边播边存进度）。
            // cachePercent 返回 -1 表示这首没在缓存 → 必须归零，否则上一首的缓存段会留在新歌上。
            Item cur = player.current();
            int cp = (cur == null) ? -1 : player.cachePercent(cur.id);
            if (seekBar != null) {
                seekBar.setSecondaryProgress(cp > 0 ? (int) (seekBar.getMax() * (cp / 100.0)) : 0);
            }
        }

        @Override
        public void onQueueChanged() {
            // 队列已独立成页（顶部工具栏的队列入口），本页不再显示队列
        }

        @Override
        public void onModeChanged(int mode) {
            if (modeBtn == null) return;
            updateModeIcon();
        }

        @Override
        public void onPlaybackError(String message) {
            act.toast(message);
        }
    };

    // ---------------- Page ----------------

    @Override
    public String title() {
        return "正在播放";
    }

    /** 全屏页：不显示迷你播放条 */
    @Override
    public boolean showMiniPlayer() {
        return false;
    }

    @Override
    public boolean onBack() {
        if (act == null) return false;
        act.pop();
        return true;
    }

    @Override
    protected View build() {
        final Theme.Colors c = Ui.colors(act);

        // 防御：万一 build 被重复调用，先摘掉旧注册（正常路径由 onDestroy 摘）
        player.removeListener(listener);
        player.addListener(listener);

        LinearLayout root = Ui.column(act);
        // 毛玻璃底图：外层 FrameLayout 放专辑/歌手相片（低透明 + 深色蒙层），内容叠在上面。
        // 相片用「服务端 120px 小图 → 客户端拉大」实现柔化：零依赖、无 RenderScript、开销极低。
        root.setBackgroundColor(0x00000000);
        final FrameLayout artWrap = new FrameLayout(act);
        artWrap.setBackgroundColor(c.bg);
        bgArt = new ImageView(act);
        bgArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgArt.setAlpha(0.5f);
        artWrap.addView(bgArt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(act);
        scrim.setBackgroundColor(Ui.alpha(c.bg, 0.52f));
        artWrap.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        artWrap.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildHeader(c), Ui.lpMatchWrap());
        root.addView(buildCoverAndTitle(c), Ui.lpMatchWrap());
        root.addView(buildProgress(c), Ui.lpMatchWrap());
        root.addView(buildControls(c), Ui.lpMatchWrap());
        // 队列已独立成页，本页下半区只放歌词，空间全给歌词
        View lyricsArea = buildLyricsArea(c);
        lyricsArea.setMinimumHeight(Ui.dp(act, 150));
        root.addView(lyricsArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 初始状态：曲目 / 进度 / 模式 / 歌词
        syncTrack();
        updatePlayIcon(player.isPlaying());
        updateProgress(player.positionMs(), player.durationMs());
        updateModeIcon();
        return artWrap;
    }

    @Override
    public void onResume() {
        if (cover == null) return;
        syncTrack();
        updatePlayIcon(player.isPlaying());
        updateProgress(player.positionMs(), player.durationMs());
        updateModeIcon();
    }

    @Override
    public void onDestroy() {
        player.removeListener(listener);
        lyricsProvider.cancelAll();
    }

    // ---------------- 上半区：顶部 / 封面 / 文本 ----------------

    /** 收起按钮 + 居中标题 + 更多 */
    private View buildHeader(Theme.Colors c) {
        LinearLayout head = Ui.row(act);
        head.setPadding(Ui.dp(act, 6), Ui.dp(act, 4), Ui.dp(act, 6), 0);

        FrameLayout collapse = new FrameLayout(act);
        LinearLayout.LayoutParams clp = Ui.lp(Ui.dp(act, 44), Ui.dp(act, 44));
        collapse.setLayoutParams(clp);
        ImageView chevron = createIcon(R.drawable.ic_chevron, 22, c.text);
        chevron.setRotation(90f); // ic_chevron 朝右 → 旋转 90° 变「收起」的向下箭头
        collapse.addView(chevron);
        collapse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.pop();
            }
        });
        Ui.tappable(collapse, act, c.text);
        head.addView(collapse);

        TextView label = Ui.text(act, "正在播放", 12.5f, c.textFaint);
        label.setGravity(Gravity.CENTER);
        head.addView(label, Ui.lpWeight(1));

        // 队列入口：本页下半区的队列区受屏幕高度限制只能当预览，这里给一个明确入口打开全屏队列
        FrameLayout queueBtn = new FrameLayout(act);
        queueBtn.setLayoutParams(Ui.lp(Ui.dp(act, 44), Ui.dp(act, 44)));
        queueBtn.addView(createIcon(R.drawable.ic_queue, 20, c.textDim));
        queueBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(com.lixscn.subsonicplayer.ui.Pages.queue(act));
            }
        });
        Ui.tappable(queueBtn, act, c.textDim);
        head.addView(queueBtn);

        FrameLayout more = new FrameLayout(act);
        more.setLayoutParams(Ui.lp(Ui.dp(act, 44), Ui.dp(act, 44)));
        more.addView(createIcon(R.drawable.ic_more, 20, c.textDim));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Item cur = player.current();
                if (cur == null) {
                    act.toast("还没有播放内容");
                    return;
                }
                Menus.song(act, cur);
            }
        });
        Ui.tappable(more, act, c.textDim);
        head.addView(more);
        return head;
    }

    /** 大封面 + 歌名 + 艺术家 + 专辑 */
    private View buildCoverAndTitle(Theme.Colors c) {
        LinearLayout box = Ui.column(act);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 4));

        // 封面：屏幕宽度的 60%，同时不超过屏高的 28%。
        // 竖屏手机上高度很紧张：封面必须给下面的歌词/队列区留出足够空间（否则下半区被挤成一条缝）。
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int side = Math.max(Ui.dp(act, 120), Math.min((int) (screenW * 0.60f), (int) (screenH * 0.28f)));

        cover = new CircleCover(act);
        cover.setCornerRadius(Ui.dp(act, 16));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 16)));
        box.addView(cover, Ui.lp(side, side));

        titleView = Ui.bold(act, "", 20, c.text);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setLineSpacing(Ui.dp(act, 2), 1f);
        LinearLayout.LayoutParams tlp = Ui.lpMatchWrap();
        tlp.topMargin = Ui.dp(act, 14);
        tlp.leftMargin = Ui.dp(act, 20);
        tlp.rightMargin = Ui.dp(act, 20);
        box.addView(titleView, tlp);

        artistView = Ui.text(act, "", 14, c.textDim);
        artistView.setGravity(Gravity.CENTER);
        Ui.ellipsize(artistView);
        artistView.setPadding(Ui.dp(act, 10), Ui.dp(act, 7), Ui.dp(act, 10), Ui.dp(act, 5));
        artistView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Item cur = player.current();
                if (cur == null) return;
                act.openArtist(cur.artistId, cur.artist);
            }
        });
        Ui.tappable(artistView, act, c.textDim);
        box.addView(artistView);

        albumView = Ui.text(act, "", 12.5f, c.textFaint);
        albumView.setGravity(Gravity.CENTER);
        Ui.ellipsize(albumView);
        albumView.setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4));
        albumView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Item cur = player.current();
                if (cur == null) return;
                act.openAlbum(cur.albumId);
            }
        });
        Ui.tappable(albumView, act, c.textFaint);
        box.addView(albumView);

        // 「本地」标志（已缓存整首）：居中放在专辑下面，没有缓存时完全不占位
        localBadge = com.lixscn.subsonicplayer.ui.ItemAdapter.localBadge(act, c);
        localBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 2);
        box.addView(localBadge, blp);

        return box;
    }

    // ---------------- 进度条 ----------------

    private View buildProgress(Theme.Colors c) {
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 14), Ui.dp(act, 2), Ui.dp(act, 14), 0);

        seekBar = new SeekBar(act);
        seekBar.setMax(1);
        try {
            seekBar.setProgressTintList(ColorStateList.valueOf(c.accent));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(c.border));
            seekBar.setThumbTintList(ColorStateList.valueOf(c.accent));
            // 缓存二级段：强调色的淡色（系统默认灰与这套配色不搭）
            seekBar.setSecondaryProgressTintList(
                    ColorStateList.valueOf(Ui.alpha(c.accent, 0.35f)));
        } catch (Throwable ignored) {
            // 极老的 ROM 上 tint 不可用，退回系统默认外观
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                // 拖动时只更新位置文本，不跟随播放进度
                if (fromUser) posText.setText(SubsonicClient.fmtPos(progress / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                player.seekTo(sb.getProgress());
            }
        });
        box.addView(seekBar, Ui.lpMatchWrap());

        LinearLayout times = Ui.row(act);
        times.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
        posText = Ui.text(act, "0:00", 11.5f, c.textDim);
        durText = Ui.text(act, "0:00", 11.5f, c.textFaint);
        times.addView(posText, Ui.lpWeight(1));
        times.addView(durText);
        box.addView(times, Ui.lpMatchWrap());
        return box;
    }

    // ---------------- 播放控制 ----------------

    private View buildControls(Theme.Colors c) {
        LinearLayout box = Ui.column(act);
        box.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 2));

        // 主控制行：上一首 / 播放暂停 / 下一首
        LinearLayout main = Ui.row(act);
        main.setGravity(Gravity.CENTER);
        main.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));

        FrameLayout prev = createBox(52);
        prev.addView(createIcon(R.drawable.ic_prev, 28, c.text));
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.previous();
            }
        });
        Ui.tappable(prev, act, c.text);
        LinearLayout.LayoutParams prevLp = Ui.lp(Ui.dp(act, 52), Ui.dp(act, 52));
        prevLp.rightMargin = Ui.dp(act, 10);
        main.addView(prev, prevLp);

        FrameLayout play = createBox(70);
        play.setBackground(Ui.circle(c.accent));
        playBtn = createIcon(R.drawable.ic_play, 32, c.accentText);
        playIconRes = R.drawable.ic_play;
        play.addView(playBtn);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.toggle();
            }
        });
        Ui.tappable(play, act, c.accentText);
        main.addView(play, Ui.lp(Ui.dp(act, 70), Ui.dp(act, 70)));

        FrameLayout next = createBox(52);
        next.addView(createIcon(R.drawable.ic_next, 28, c.text));
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.next();
            }
        });
        Ui.tappable(next, act, c.text);
        LinearLayout.LayoutParams nextLp = Ui.lp(Ui.dp(act, 52), Ui.dp(act, 52));
        nextLp.leftMargin = Ui.dp(act, 10);
        main.addView(next, nextLp);
        box.addView(main, Ui.lpMatchWrap());

        // 次要控制行：播放模式 / 收藏 / 音量
        LinearLayout sec = Ui.row(act);
        sec.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);

        FrameLayout modeSlot = createSlot();
        modeBtn = createIcon(R.drawable.ic_repeat, 22, c.textDim);
        modeSlot.addView(modeBtn);
        modeSlot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.cycleMode();
                act.toast(modeLabel(player.mode()));
            }
        });
        Ui.tappable(modeSlot, act, c.textDim);
        sec.addView(modeSlot);

        FrameLayout starSlot = createSlot();
        starBtn = createIcon(R.drawable.ic_heart, 22, c.textDim);
        starSlot.addView(starBtn);
        starSlot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleStar(player.current());
            }
        });
        Ui.tappable(starSlot, act, c.danger);
        sec.addView(starSlot);

        FrameLayout volSlot = createSlot();
        volSlot.addView(createIcon(R.drawable.ic_volume, 22, c.textDim));
        volSlot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVolumeDialog();
            }
        });
        Ui.tappable(volSlot, act, c.textDim);
        sec.addView(volSlot);
        box.addView(sec, Ui.lpMatchWrap());
        return box;
    }

    // ---------------- Tab 与下半区 ----------------

    /**
     * 下半区：只有歌词。
     *
     * 队列已经独立成页（顶部工具栏的队列入口），所以这里不再有「歌词 / 队列」标签，
     * 省下来的高度全部给歌词（原来两个面板挤在一起，队列只能显示两三行）。
     */
    private View buildLyricsArea(Theme.Colors c) {
        FrameLayout area = new FrameLayout(act);

        lyricsScroll = new ScrollView(act);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsBox = Ui.column(act);
        lyricsScroll.addView(lyricsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsScroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                applyLyricsPadding();
            }
        });
        lyricsPane = lyricsScroll;
        area.addView(lyricsPane, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return area;
    }

    // ---------------- 歌词 ----------------

    /**
     * 取歌词：先清空显示「加载中」，后台线程取服务端歌词（OpenSubsonic → 旧接口），
     * 再交给 {@link LyricsProvider}（它自己会做 LRCLIB / 网易云兜底），结果在主线程渲染。
     */
    private void loadLyrics(final Item song) {
        if (song == null) {
            lyricsSongId = "";
            renderLyrics(null);
            return;
        }
        final String id = song.id == null ? "" : song.id;
        if (id.length() > 0 && id.equals(lyricsSongId)) return; // 已加载 / 加载中

        lyricsSongId = id;
        lyricsToken++;
        final int token = lyricsToken;
        lyricsProvider.cancelAll(); // 取消上一首的兜底请求
        showLyricsMessage("歌词加载中…");

        final String artist = song.artist == null ? "" : song.artist;
        final String title = song.title == null ? "" : song.title;
        final int durationSec = song.durationSec;

        lib.run(new Library.Work<Lyrics>() {
            @Override
            public Lyrics run() {
                Lyrics server = null;
                // 1) OpenSubsonic 结构化歌词（阻塞方法，必须后台线程）
                try {
                    if (lib.client() != null && id.length() > 0) {
                        server = lib.client().getLyricsBySongId(id);
                    }
                } catch (Throwable ignored) {
                    server = null;
                }
                // 2) 旧接口兜底
                if (server == null || server.isEmpty()) {
                    try {
                        if (lib.client() != null) {
                            Lyrics alt = lib.client().getLyrics(artist, title);
                            if (alt != null && !alt.isEmpty()) server = alt;
                        }
                    } catch (Throwable ignored) {
                        // 保持 server 原值
                    }
                }
                return server;
            }
        }, new Library.Done<Lyrics>() {
            @Override
            public void ok(Lyrics value) {
                if (token != lyricsToken) return; // 已切歌，丢弃
                lyricsProvider.load(value, artist, title, durationSec, new LyricsProvider.Callback() {
                    @Override
                    public void onLyrics(Lyrics result) {
                        if (token != lyricsToken) return;
                        renderLyrics(result);
                    }
                });
            }

            @Override
            public void fail(String message) {
                if (token != lyricsToken) return;
                lyricsProvider.load(null, artist, title, durationSec, new LyricsProvider.Callback() {
                    @Override
                    public void onLyrics(Lyrics result) {
                        if (token != lyricsToken) return;
                        renderLyrics(result);
                    }
                });
            }
        });
    }

    /** 渲染歌词：同步歌词建行视图并立即定位；纯文本按行展示；无内容显示占位。 */
    private void renderLyrics(Lyrics result) {
        lyrics = result;
        currentLine = -1;
        lyricsBox.removeAllViews();
        lineViews.clear();

        if (result == null || result.isEmpty()) {
            showLyricsMessage("暂无歌词");
            return;
        }

        Theme.Colors c = Ui.colors(act);
        if (result.isSynced()) {
            for (int i = 0; i < result.lines.size(); i++) {
                Lyrics.Line line = result.lines.get(i);
                String text = line == null ? "" : line.text;
                if (text == null || text.trim().length() == 0) text = "♪";
                TextView tv = Ui.text(act, text, LYRIC_SIZE_NORMAL, c.textFaint);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(Ui.dp(act, 18), Ui.dp(act, 7), Ui.dp(act, 18), Ui.dp(act, 7));
                lyricsBox.addView(tv, Ui.lpMatchWrap());
                lineViews.add(tv);
            }
            lyricsScroll.scrollTo(0, 0);
            applyLyricsPadding();
            updateLyricHighlight(player.positionMs());
            return;
        }

        // 无时间轴：按行拆分展示，不高亮
        List<String> rows = splitLines(result.plainText);
        if (rows.isEmpty()) {
            showLyricsMessage("暂无歌词");
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView tv = Ui.text(act, rows.get(i), 15, c.textDim);
            tv.setGravity(Gravity.CENTER);
            tv.setLineSpacing(Ui.dp(act, 4), 1f);
            tv.setPadding(Ui.dp(act, 18), Ui.dp(act, 4), Ui.dp(act, 18), Ui.dp(act, 4));
            lyricsBox.addView(tv, Ui.lpMatchWrap());
        }
        lyricsScroll.scrollTo(0, 0);
        applyLyricsPadding();
    }

    private void showLyricsMessage(String message) {
        if (lyricsBox == null) return;
        lyrics = null;
        currentLine = -1;
        lineViews.clear();
        lyricsBox.removeAllViews();
        Theme.Colors c = Ui.colors(act);
        TextView tv = Ui.text(act, message, 14, c.textFaint);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(act, 24), Ui.dp(act, 40), Ui.dp(act, 24), Ui.dp(act, 40));
        lyricsBox.addView(tv, Ui.lpMatchWrap());
        applyLyricsPadding();
    }

    /** 同步歌词时给上下留出半个可视高度，才能把首/末行滚到中间 */
    private void applyLyricsPadding() {
        if (lyricsScroll == null || lyricsBox == null) return;
        int h = lyricsScroll.getHeight();
        if (h <= 0) return;
        boolean synced = lyrics != null && lyrics.isSynced() && !lineViews.isEmpty();
        int top = synced ? Math.max(0, h / 2 - Ui.dp(act, 18)) : Ui.dp(act, 16);
        int bottom = synced ? Math.max(Ui.dp(act, 24), h / 2) : Ui.dp(act, 40);
        if (lyricsBox.getPaddingTop() == top && lyricsBox.getPaddingBottom() == bottom) return;
        lyricsBox.setPadding(0, top, 0, bottom);
    }

    /** 按播放位置高亮当前行，并在行变化时把该行滚到中间。 */
    private void updateLyricHighlight(int positionMs) {
        if (lyrics == null || !lyrics.isSynced() || lineViews.isEmpty()) return;
        int index = lyrics.indexAt(positionMs / 1000.0);
        if (index == currentLine) return;
        currentLine = index;
        styleLyricLines();
        scrollLyricToCenter(index);
    }

    /** 只改已有 TextView 的属性：当前行高亮放大，前后行按距离渐隐。 */
    private void styleLyricLines() {
        Theme.Colors c = Ui.colors(act);
        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            if (i == currentLine) {
                tv.setTextColor(c.accent);
                tv.setTextSize(LYRIC_SIZE_ACTIVE);
                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                continue;
            }
            int distance = currentLine < 0 ? 99 : Math.abs(i - currentLine);
            int color;
            if (distance == 1) color = c.textDim;
            else if (distance == 2) color = Ui.alpha(c.textDim, 0.7f);
            else if (distance == 3) color = c.textFaint;
            else color = Ui.alpha(c.textFaint, 0.66f);
            tv.setTextColor(color);
            tv.setTextSize(LYRIC_SIZE_NORMAL);
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
    }

    private void scrollLyricToCenter(final int index) {
        if (index < 0 || index >= lineViews.size()) return;
        final View line = lineViews.get(index);
        final ScrollView sv = lyricsScroll;
        // post 一下：字号刚变过，等这次 layout 完成再取 getTop/getHeight
        sv.post(new Runnable() {
            @Override
            public void run() {
                int target = line.getTop() - sv.getHeight() / 2 + line.getHeight() / 2;
                sv.smoothScrollTo(0, Math.max(0, target));
            }
        });
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < raw.length; i++) out.add(raw[i]);
        while (!out.isEmpty() && out.get(0).trim().length() == 0) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).trim().length() == 0) out.remove(out.size() - 1);
        return out;
    }

    // ---------------- 状态同步 ----------------

    /** 换歌后刷新封面 / 文本 / 进度 / 收藏态 / 模式 / 队列高亮 / 歌词 */
    private void syncTrack() {
        if (cover == null) return;
        final Theme.Colors c = Ui.colors(act);
        final Item cur = player.current();

        if (cur == null) {
            titleView.setText("暂无播放内容");
            artistView.setText("");
            artistView.setVisibility(View.GONE);
            albumView.setText("");
            albumView.setVisibility(View.GONE);
            if (localBadge != null) localBadge.setVisibility(View.GONE);
            cover.setImageDrawable(null);
            cover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 16)));
            posText.setText(SubsonicClient.fmtPos(0));
            durText.setText(SubsonicClient.fmtPos(0));
            seekBar.setMax(1);
            seekBar.setProgress(0);
            updateStar();
            lyricsSongId = "";
            lyricsToken++;
            lyricsProvider.cancelAll();
            renderLyrics(null);
            return;
        }

        titleView.setText(cur.title == null ? "" : cur.title);
        artistView.setText(cur.artist == null ? "" : cur.artist);
        artistView.setVisibility(artistView.getText().length() > 0 ? View.VISIBLE : View.GONE);
        albumView.setText(cur.album == null ? "" : cur.album);
        albumView.setVisibility(albumView.getText().length() > 0 ? View.VISIBLE : View.GONE);
        // 「本地」标志：整首已缓存在本机（换歌时要跟着更新）
        if (localBadge != null) {
            localBadge.setVisibility(player.isCachedLocally(cur) ? View.VISIBLE : View.GONE);
        }

        String url = "";
        if (cur.coverArt != null && cur.coverArt.length() > 0) url = lib.coverUrl(cur.coverArt, COVER_REQUEST_SIZE);
        else if (cur.coverUrl != null) url = cur.coverUrl;
        cover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 16)));
        if (url != null && url.length() > 0) {
            CoverLoader.get(act).load(url, cover, 0);
        if (bgArt != null) {
            Item bgCur = player.current();
            if (bgCur != null && bgCur.coverArt != null && bgCur.coverArt.length() > 0) {
                CoverLoader.get(act).load(lib.coverUrl(bgCur.coverArt, 120), bgArt, 0);
            } else {
                bgArt.setImageDrawable(null);
            }
        }
        } else {
            cover.setImageDrawable(null);
        }

        updateStar();
        updateModeIcon();
        loadLyrics(cur);
    }

    /** 只更新播放按钮图标，且资源没变就不动 */
    private void updatePlayIcon(boolean playing) {
        if (playBtn == null) return;
        int res = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        if (res == playIconRes) return;
        playIconRes = res;
        playBtn.setImageResource(res);
    }

    /** 刷新进度条与时间文本（拖动中交给拖动逻辑，不跟随播放） */
    private void updateProgress(int positionMs, int durationMs) {
        if (seekBar == null) return;
        final Item cur = player.current();
        int fallback = cur == null ? 0 : cur.durationSec * 1000;
        int total = durationMs > 0 ? durationMs : fallback;
        int max = total > 0 ? total : 1;
        if (seekBar.getMax() != max) seekBar.setMax(max);

        if (!dragging) {
            int progress = Math.max(0, Math.min(positionMs, max));
            seekBar.setProgress(progress);
            posText.setText(SubsonicClient.fmtPos(progress / 1000));
        }
        durText.setText(SubsonicClient.fmtPos(total / 1000));
    }

    private void updateModeIcon() {
        if (modeBtn == null) return;
        Theme.Colors c = Ui.colors(act);
        int mode = player.mode();
        int res;
        int tint;
        if (mode == Player.MODE_SHUFFLE) {
            res = R.drawable.ic_shuffle;
            tint = c.accent;
        } else if (mode == Player.MODE_REPEAT_ONE) {
            res = R.drawable.ic_repeat_one;
            tint = c.accent;
        } else if (mode == Player.MODE_REPEAT_ALL) {
            res = R.drawable.ic_repeat;
            tint = c.accent;
        } else {
            res = R.drawable.ic_repeat;
            tint = c.textDim;
        }
        modeBtn.setImageResource(res);
        modeBtn.setColorFilter(tint);
    }

    private void updateStar() {
        if (starBtn == null) return;
        Theme.Colors c = Ui.colors(act);
        Item cur = player.current();
        boolean starred = cur != null && cur.starred;
        starBtn.setColorFilter(starred ? c.danger : c.textDim);
    }

    private void toggleStar(final Item item) {
        if (item == null || item.id == null || item.id.length() == 0) return;
        final boolean target = !item.starred;
        lib.setStar(item.id, target, new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                item.starred = target;
                updateStar();
                act.favoriteChanged();
                act.toast(target ? "已收藏" : "已取消收藏");
            }

            @Override
            public void fail(String message) {
                act.toast(message);
            }
        });
    }

    private static String modeLabel(int mode) {
        if (mode == Player.MODE_SHUFFLE) return "随机播放";
        if (mode == Player.MODE_REPEAT_ALL) return "列表循环";
        if (mode == Player.MODE_REPEAT_ONE) return "单曲循环";
        return "顺序播放";
    }

    // ---------------- 小工具 ----------------

    /** 方形图标按钮容器（固定边长，图标居中） */
    private FrameLayout createBox(float sizeDp) {
        FrameLayout fl = new FrameLayout(act);
        fl.setLayoutParams(Ui.lp(Ui.dp(act, sizeDp), Ui.dp(act, sizeDp)));
        return fl;
    }

    /** 次要控制行的等分槽位（宽 48dp 高，横向平分） */
    private FrameLayout createSlot() {
        FrameLayout fl = new FrameLayout(act);
        fl.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(act, 48), 1f));
        return fl;
    }

    /** 居中图标（LayoutParams 由 FrameLayout 接管，不能直接用 Ui.icon 的 LinearLayout 参数） */
    private ImageView createIcon(int res, float sizeDp, int tint) {
        ImageView iv = Ui.icon(act, res, sizeDp, tint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(act, sizeDp), Ui.dp(act, sizeDp));
        lp.gravity = Gravity.CENTER;
        iv.setLayoutParams(lp);
        return iv;
    }

    private void showVolumeDialog() {
        Theme.Colors c = Ui.colors(act);
        LinearLayout wrap = Ui.column(act);
        wrap.setPadding(Ui.dp(act, 24), Ui.dp(act, 10), Ui.dp(act, 24), 0);
        final TextView label = Ui.text(act, "音量 " + player.volumePercent() + "%", 13, c.textDim);
        final SeekBar bar = new SeekBar(act);
        bar.setMax(100);
        bar.setProgress(player.volumePercent());
        try {
            bar.setProgressTintList(ColorStateList.valueOf(c.accent));
            bar.setThumbTintList(ColorStateList.valueOf(c.accent));
        } catch (Throwable ignored) {
            // 忽略：不影响功能
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                player.setVolumePercent(progress);
                label.setText("音量 " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        wrap.addView(label, Ui.lpMatchWrap());
        wrap.addView(bar, Ui.lpMatchWrap());
        Ui.dialog(act).setTitle("音量").setView(wrap).setPositiveButton("完成", null).show();
    }
}
