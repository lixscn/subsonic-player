package com.lixscn.subsonicplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.player.PlaybackService;
import com.lixscn.subsonicplayer.player.Player;
import com.lixscn.subsonicplayer.ui.CircleCover;
import com.lixscn.subsonicplayer.ui.CoverLoader;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Pages;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 唯一的 Activity：应用外壳（顶栏 + 内容区 + 迷你播放条 + 底部导航）+ 页面路由。
 *
 * 页面全部由 {@link Pages} 工厂创建，页面之间不互相引用，只通过本类暴露的导航方法跳转。
 */
public class MainActivity extends Activity implements Player.Listener {

    public static final String TAB_DISCOVER = "discover";
    public static final String TAB_LIBRARY = "library";
    public static final String TAB_FAVORITES = "favorites";
    public static final String TAB_SEARCH = "search";
    public static final String TAB_SETTINGS = "settings";

    private static final String[] TABS = {TAB_DISCOVER, TAB_LIBRARY, TAB_FAVORITES, TAB_SEARCH, TAB_SETTINGS};
    private static final String[] TAB_LABELS = {"发现", "曲库", "收藏", "搜索", "设置"};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_discover, R.drawable.ic_library, R.drawable.ic_heart,
            R.drawable.ic_search, R.drawable.ic_settings
    };

    private LinearLayout root;
    private LinearLayout topBar;
    private ImageView backBtn;
    private TextView titleView;
    private TextView subtitleView;
    private FrameLayout content;
    /** 缓冲浮层（覆盖在页面之上，转圈 + 「正在缓冲…」） */
    private LinearLayout bufferingOverlay;
    private LinearLayout miniBar;
    private CircleCover miniCover;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView miniToggle;
    /** 内层横排（封面/文字/控制键） */
    private LinearLayout miniRow;
    /** 迷你条底部细进度线 */
    private ProgressBar miniProgress;
    private LinearLayout navBar;
    private final List<ImageView> navIcons = new ArrayList<ImageView>();
    private final List<TextView> navLabels = new ArrayList<TextView>();

    private final List<Page> stack = new ArrayList<Page>();
    private String currentTab = TAB_DISCOVER;
    private boolean connectionOk;
    private boolean connectTried;

    private Player player;
    private Library lib;

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.reload(this);
        lib = Library.get(this);
        player = Player.get(this);
        player.addListener(this);

        buildShell();
        // 诊断：确认 BASS 原生库随包可用（纯日志，不影响逻辑）
        android.util.Log.w("SubsonicPlayer", "BASS 原生库可用=" + com.lixscn.subsonicplayer.player.bass.BassNative.available());
        Ui.applySystemBars(this);

        // 恢复上次队列（不自动播放）
        player.restoreLastQueue();

        // 无配置时先尝试从外部目录导入配置文件（换机/批量部署/自动化联调用）
        if (!lib.isConfigured()) {
            int n = com.lixscn.subsonicplayer.core.ConfigImport.importIfPresent(this);
            if (n > 0) lib.rebuildClient();
        }

        if (!lib.isConfigured()) {
            // 首次使用：先给个空壳，直接进设置页填服务器
            switchTab(TAB_DISCOVER);
            push(Pages.settings(this));
        } else {
            // 先连上（确定内网/外网哪个可用）再建页面。
            // 反例：先 switchTab 会立刻用「还没确定的内网地址」发请求，
            // 手机在蜂窝数据上访问内网 IP 不会回 RST，只能等连接超时才失败 —— 冷启动会白等十几秒。
            showConnecting();
            connectThenDiscover();
        }
        handleIntent(getIntent());
    }

    /** 启动连接期间显示占位页，避免页面抢跑 */
    private void showConnecting() {
        stack.clear();
        Page p = new com.lixscn.subsonicplayer.ui.pages.PlaceholderPage("连接中", "正在连接音乐服务器…");
        p.attach(this);
        stack.add(p);
        showTop(p, false);
    }

    /** 连接完成后进发现页（失败也进，页面自带重试入口） */
    private void connectThenDiscover() {
        lib.connect(new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                connectionOk = true;
                switchTab(TAB_DISCOVER);
                if (lib.connectedUrl() != null && lib.connectedUrl().length() > 0) {
                    toast("已连接 " + lib.connectedUrl().replaceFirst("^https?://", ""));
                }
            }

            @Override
            public void fail(String message) {
                connectionOk = false;
                switchTab(TAB_DISCOVER);
                toast(message);
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("openNowPlaying", false)) {
            showNowPlaying();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Page p : stack) {
            // 只 resume 栈顶
        }
        if (!stack.isEmpty()) stack.get(stack.size() - 1).onResume();
        updateMiniPlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!stack.isEmpty()) stack.get(stack.size() - 1).onPause();
        player.saveState();
    }

    @Override
    protected void onDestroy() {
        player.removeListener(this);
        for (Page p : stack) p.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!stack.isEmpty() && stack.get(stack.size() - 1).onBack()) return;
        if (stack.size() > 1) {
            pop();
            return;
        }
        if (!TAB_DISCOVER.equals(currentTab)) {
            switchTab(TAB_DISCOVER);
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 外壳 ----------------

    private void buildShell() {
        com.lixscn.subsonicplayer.ui.Theme.Colors c = Ui.colors(this);

        root = Ui.column(this);
        root.setBackgroundColor(c.bg);

        // 顶栏
        topBar = Ui.row(this);
        topBar.setMinimumHeight(Ui.dp(this, 52));
        Ui.pad(topBar, this, 6, 6, 12, 6);
        topBar.setBackgroundColor(c.bg);
        backBtn = Ui.icon(this, R.drawable.ic_back, 22, c.text);
        LinearLayout.LayoutParams blp = Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40));
        topBar.addView(backBtn, blp);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pop();
            }
        });
        LinearLayout titleBox = Ui.column(this);
        titleBox.setPadding(Ui.dp(this, 4), 0, 0, 0);
        titleView = Ui.bold(this, "", 19, c.text);
        Ui.ellipsize(titleView);
        subtitleView = Ui.text(this, "", 12, c.textDim);
        Ui.ellipsize(subtitleView);
        titleBox.addView(titleView);
        titleBox.addView(subtitleView);
        topBar.addView(titleBox, Ui.lpWeight(1));
        root.addView(topBar, Ui.lpMatchWrap());

        // 内容区
        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, clp);

        // 缓冲浮层：取流要等几秒（蜂窝网络经反代回源尤其明显），
        // 不做可见反馈用户会以为「点了没反应/没声音」。做成覆盖整页的浮层，
        // 这样在播放页（不显示迷你条）里点歌也能看到。
        bufferingOverlay = Ui.row(this);
        bufferingOverlay.setBackground(Ui.rect(Ui.alpha(c.surface, 0.94f), Ui.dp(this, 999)));
        Ui.pad(bufferingOverlay, this, 14, 8, 16, 8);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminate(true);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(c.accent));
        bufferingOverlay.addView(spin, Ui.lp(Ui.dp(this, 18), Ui.dp(this, 18)));
        TextView btext = Ui.text(this, "正在缓冲…", 13.5f, c.text);
        btext.setPadding(Ui.dp(this, 10), 0, 0, 0);
        bufferingOverlay.addView(btext);
        bufferingOverlay.setVisibility(View.GONE);

        // 迷你播放条
        // 迷你条：垂直容器 = 上面一行内容 + 底部一条细进度线（进度线贴最下沿）
        miniBar = Ui.column(this);
        miniBar.setMinimumHeight(Ui.dp(this, 60));
        miniBar.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(this, 14)));
        miniRow = Ui.row(this);
        Ui.pad(miniRow, this, 10, 8, 6, 4);
        LinearLayout.LayoutParams mlp = Ui.lpMatchWrap();
        mlp.leftMargin = Ui.dp(this, 8);
        mlp.rightMargin = Ui.dp(this, 8);
        mlp.bottomMargin = Ui.dp(this, 6);
        miniBar.setLayoutParams(mlp);

        miniCover = new CircleCover(this);
        miniCover.setCornerRadius(Ui.dp(this, 8));
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniRow.addView(miniCover, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout textBox = Ui.column(this);
        textBox.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
        miniTitle = Ui.text(this, "", 14.5f, c.text);
        Ui.marquee(miniTitle);
        miniArtist = Ui.text(this, "", 12, c.textDim);
        Ui.ellipsize(miniArtist);
        textBox.addView(miniTitle);
        textBox.addView(miniArtist);
        miniRow.addView(textBox, Ui.lpWeight(1));

        final ImageView prevBtn = Ui.icon(this, R.drawable.ic_prev, 22, c.text);
        miniRow.addView(prevBtn, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.previous();
            }
        });

        miniToggle = Ui.icon(this, R.drawable.ic_play, 26, c.text);
        miniRow.addView(miniToggle, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 44)));
        miniToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureService();
                player.toggle();
            }
        });

        final ImageView nextBtn = Ui.icon(this, R.drawable.ic_next, 22, c.text);
        miniRow.addView(nextBtn, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.next();
            }
        });

        // 内层横排 + 底部进度线
        miniBar.addView(miniRow, Ui.lpMatchWrap());
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setMax(1000);
        miniProgress.setProgress(0);
        miniProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(c.accent));
        miniProgress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Ui.alpha(c.text, 0.16f)));
        try {
            // 缓存二级段用强调色的淡色（系统默认灰在这套配色里很突兀）
            miniProgress.setSecondaryProgressTintList(
                    android.content.res.ColorStateList.valueOf(Ui.alpha(c.accent, 0.35f)));
        } catch (Throwable ignored) {
        }
        miniProgress.setClickable(false);   // 不拦截点击，点条仍然进播放页
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
        plp.leftMargin = Ui.dp(this, 14);
        plp.rightMargin = Ui.dp(this, 14);
        plp.bottomMargin = Ui.dp(this, 3);
        miniBar.addView(miniProgress, plp);

        Ui.tappable(miniBar, this, c.text);
        miniBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNowPlaying();
            }
        });
        miniBar.setVisibility(View.GONE);
        root.addView(miniBar);

        // 底部导航
        // 注意：标签宽度必须 MATCH_PARENT + gravity=CENTER，用 WRAP_CONTENT 时不同 ROM
        // （实测 MIUI）会把首个标签顶到条目左边缘，看起来像「文字和图标没对上」。
        navBar = Ui.row(this);
        navBar.setBackgroundColor(c.surface);
        for (int i = 0; i < TABS.length; i++) {
            final String tab = TABS[i];
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            item.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            ImageView ic = Ui.icon(this, TAB_ICONS[i], 23, c.textDim);
            item.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            TextView lb = Ui.text(this, TAB_LABELS[i], 11, c.textDim);
            lb.setGravity(Gravity.CENTER);
            lb.setSingleLine(true);
            lb.setPadding(0, Ui.dp(this, 4), 0, 0);
            item.addView(lb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(tab);
                }
            });
            Ui.tappable(item, this, c.accent);
            navBar.addView(item, Ui.lpWeight(1));
            navIcons.add(ic);
            navLabels.add(lb);
        }
        // 高度给足：图标 24 + 间距 4 + 文字 ≈ 15 + 上下内边距 14 ≈ 57dp，再留余量
        navBar.setMinimumHeight(Ui.dp(this, 62));
        root.addView(navBar, Ui.lpMatchWrap());

        setContentView(root);
        setupSystemInsets();
        updateTabHighlight();
        updateTopBar();
    }

    /**
     * 自己处理状态栏/导航栏 inset（边到边布局）。
     *
     * 不能指望「窗口默认会被系统让开」：全屏手势机（实测 MIUI）导航栏 inset 很小甚至为 0，
     * 底部导航的图标文字会被手势条压住、贴着屏幕最下沿——正是要修的「不够位」。
     * 这里显式声明边到边，再把 inset 作为内边距补给顶栏与底部导航。
     */
    private void setupSystemInsets() {
        final View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                    bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                // 顶栏：状态栏高度 + 原来的 6dp
                topBar.setPadding(topBar.getPaddingLeft(), Ui.dp(MainActivity.this, 6) + top,
                        topBar.getPaddingRight(), Ui.dp(MainActivity.this, 6));
                // 底部导航：手势条高度 + 8dp，保证标签不贴屏幕底边
                navBar.setPadding(0, Ui.dp(MainActivity.this, 7), 0,
                        Ui.dp(MainActivity.this, 8) + bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    // ---------------- 主题 ----------------

    /** 主题切换后重建整个外壳与页面栈（保留导航位置） */
    public void applyTheme() {
        Ui.reload(this);
        int depth = stack.size();
        Page top = depth > 0 ? stack.get(depth - 1) : null;
        stack.clear();
        buildShell();
        Ui.applySystemBars(this);
        if (top != null) {
            stack.add(top);
            top.rebuild();
            content.removeAllViews();
            content.addView(top.view());
            reattachOverlay();
            updateTopBar();
            top.onResume();
        } else {
            switchTab(currentTab);
        }
        updateMiniPlayer();
    }

    // ---------------- 路由 ----------------

    public void switchTab(String tab) {
        currentTab = tab;
        while (stack.size() > 1) popNoResume();
        stack.clear();
        Page p = Pages.tab(this, tab);
        stack.add(p);
        p.attach(this);
        showTop(p, false);
        updateTabHighlight();
    }

    public void push(Page page) {
        if (!stack.isEmpty()) stack.get(stack.size() - 1).onPause();
        page.attach(this);
        stack.add(page);
        showTop(page, true);
    }

    public void pop() {
        if (stack.size() <= 1) return;
        Page top = stack.remove(stack.size() - 1);
        top.onPause();
        top.onDestroy();
        Page now = stack.get(stack.size() - 1);
        showTop(now, stack.size() > 1);
        now.onResume();
    }

    private void popNoResume() {
        if (stack.isEmpty()) return;
        Page top = stack.remove(stack.size() - 1);
        top.onPause();
        top.onDestroy();
    }

    /** 重建当前页（主题切换或数据刷新） */
    public void refreshCurrent() {
        if (stack.isEmpty()) return;
        Page top = stack.get(stack.size() - 1);
        top.rebuild();
        content.removeAllViews();
        content.addView(top.view());
        reattachOverlay();
        top.onResume();
    }

    private void showTop(Page page, boolean animate) {
        content.removeAllViews();
        content.addView(page.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reattachOverlay();
        updateTopBar();
        page.onResume();
    }

    /** 页面切换会清空内容区，需把缓冲浮层重新挂到最上层 */
    private void reattachOverlay() {
        if (bufferingOverlay == null) return;
        if (bufferingOverlay.getParent() == content) content.removeView(bufferingOverlay);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.dp(this, 22);
        content.addView(bufferingOverlay, lp);
        updateBufferingOverlay();
    }

    private void updateBufferingOverlay() {
        if (bufferingOverlay == null) return;
        boolean b = player != null && player.isBuffering() && player.current() != null;
        bufferingOverlay.setVisibility(b ? View.VISIBLE : View.GONE);
        if (b) bufferingOverlay.bringToFront();
    }

    private void updateTopBar() {
        com.lixscn.subsonicplayer.ui.Theme.Colors c = Ui.colors(this);
        boolean canBack = stack.size() > 1;
        backBtn.setVisibility(canBack ? View.VISIBLE : View.GONE);
        Page top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        titleView.setText(top == null ? "" : top.title());
        titleView.setTextColor(c.text);
        String sub = top == null ? null : top.subtitle();
        if (sub == null || sub.length() == 0) subtitleView.setVisibility(View.GONE);
        else {
            subtitleView.setVisibility(View.VISIBLE);
            subtitleView.setText(sub);
            subtitleView.setTextColor(c.textDim);
        }
        boolean showMini = top == null || top.showMiniPlayer();
        if (miniBar != null) miniBar.setVisibility(showMini && player.current() != null ? View.VISIBLE : View.GONE);
    }

    private void updateTabHighlight() {
        com.lixscn.subsonicplayer.ui.Theme.Colors c = Ui.colors(this);
        for (int i = 0; i < TABS.length; i++) {
            boolean active = TABS[i].equals(currentTab);
            navIcons.get(i).setColorFilter(active ? c.accent : c.textDim);
            navLabels.get(i).setTextColor(active ? c.accent : c.textDim);
        }
    }

    // ---------------- 对外 API（页面用） ----------------

    public Library library() {
        return lib;
    }

    public Player player() {
        return player;
    }

    public void toast(String msg) {
        Ui.toast(this, msg);
    }

    public void openAlbum(String albumId) {
        if (albumId == null || albumId.length() == 0) {
            toast("未知专辑");
            return;
        }
        push(Pages.albumDetail(this, albumId));
    }

    public void openArtist(String artistId) {
        if (artistId == null || artistId.length() == 0) {
            toast("未知艺术家");
            return;
        }
        push(Pages.artistDetail(this, artistId, ""));
    }

    public void openArtist(String artistId, String name) {
        if (artistId == null || artistId.length() == 0) {
            toast("未知艺术家");
            return;
        }
        push(Pages.artistDetail(this, artistId, name));
    }

    public void openPlaylist(String playlistId, String name) {
        push(Pages.playlistDetail(this, playlistId, name));
    }

    public void openGenre(String genre) {
        push(Pages.genreSongs(this, genre));
    }

    public void showNowPlaying() {
        if (player.current() == null) {
            toast("还没有播放内容");
            return;
        }
        // 已经在播放页则不重复入栈
        if (!stack.isEmpty() && stack.get(stack.size() - 1).title().equals("正在播放")) return;
        push(Pages.nowPlaying(this));
    }

    /** 播放一组歌曲（替换队列） */
    public void playNow(List<Item> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) {
            toast("没有可播放的歌曲");
            return;
        }
        ensureService();
        requestNotificationPermission();
        player.playList(songs, startIndex);
        recordHistory(songs.get(Math.max(0, Math.min(startIndex, songs.size() - 1))));
        updateMiniPlayer();
    }

    public void playNow(List<Item> songs) {
        playNow(songs, 0);
    }

    /** 播放专辑（asNext=true 时插到当前之后） */
    public void playAlbum(final String albumId, final boolean asNext) {
        lib.album(albumId, new Library.Done<Item>() {
            @Override
            public void ok(Item album) {
                if (album == null || album.songs == null || album.songs.isEmpty()) {
                    toast("该专辑没有曲目");
                    return;
                }
                if (asNext) {
                    player.playNext(album.songs);
                    toast("已加入下一首");
                } else {
                    playNow(album.songs, 0);
                }
            }

            @Override
            public void fail(String message) {
                toast(message);
            }
        });
    }

    public void queueAlbum(final String albumId) {
        lib.album(albumId, new Library.Done<Item>() {
            @Override
            public void ok(Item album) {
                if (album == null || album.songs == null) return;
                player.append(album.songs);
                toast("已加入队列");
            }

            @Override
            public void fail(String message) {
                toast(message);
            }
        });
    }

    public void playPlaylist(final String playlistId, final boolean asNext) {
        lib.playlist(playlistId, new Library.Done<Item>() {
            @Override
            public void ok(Item pl) {
                if (pl == null || pl.songs == null || pl.songs.isEmpty()) {
                    toast("歌单为空");
                    return;
                }
                if (asNext) {
                    player.playNext(pl.songs);
                    toast("已加入下一首");
                } else {
                    playNow(pl.songs, 0);
                }
            }

            @Override
            public void fail(String message) {
                toast(message);
            }
        });
    }

    /** 随机播放某艺术家的全部歌曲 */
    public void shuffleArtist(final String artistId) {
        lib.artistAlbums(artistId, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> albums) {
                if (albums == null || albums.isEmpty()) {
                    toast("该艺术家没有专辑");
                    return;
                }
                final List<Item> all = new ArrayList<Item>();
                final int[] pending = {albums.size()};
                for (final Item al : albums) {
                    lib.albumSongs(al.id, new Library.Done<List<Item>>() {
                        @Override
                        public void ok(List<Item> songs) {
                            all.addAll(songs);
                            pending[0]--;
                            if (pending[0] == 0) {
                                player.playList(all, new java.util.Random().nextInt(Math.max(1, all.size())));
                                player.setMode(Player.MODE_SHUFFLE);
                                ensureService();
                                updateMiniPlayer();
                            }
                        }

                        @Override
                        public void fail(String message) {
                            pending[0]--;
                        }
                    });
                }
            }

            @Override
            public void fail(String message) {
                toast(message);
            }
        });
    }

    /** 收藏变化：通知当前页刷新（收藏页要移除条目） */
    public void favoriteChanged() {
        if (stack.isEmpty()) return;
        Page top = stack.get(stack.size() - 1);
        if (TAB_FAVORITES.equals(currentTab) && stack.size() == 1) refreshCurrent();
    }

    public void bookmarkChanged() {
        if (TAB_LIBRARY.equals(currentTab) && stack.size() == 1) refreshCurrent();
    }


    // ---------------- 播放历史（本地记录） ----------------

    private void recordHistory(Item song) {
        if (song == null) return;
        try {
            android.content.SharedPreferences sp = getSharedPreferences("sp_history", MODE_PRIVATE);
            String raw = sp.getString("items", "[]");
            org.json.JSONArray arr;
            try {
                arr = new org.json.JSONArray(raw);
            } catch (Exception e) {
                arr = new org.json.JSONArray();
            }
            org.json.JSONArray out = new org.json.JSONArray();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", song.id);
            o.put("title", song.title);
            o.put("artist", song.artist);
            o.put("artistId", song.artistId);
            o.put("album", song.album);
            o.put("albumId", song.albumId);
            o.put("coverArt", song.coverArt);
            o.put("duration", song.durationSec);
            // 格式信息一起存：历史/队列恢复后「本地」标志、缓存文件名、DSD 建流都要用
            o.put("suffix", song.suffix);
            o.put("contentType", song.contentType);
            o.put("bitrate", song.bitrate);
            o.put("starred", song.starred);
            o.put("at", System.currentTimeMillis());
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < 100; i++) {
                org.json.JSONObject e = arr.optJSONObject(i);
                if (e != null && !song.id.equals(e.optString("id", ""))) out.put(e);
            }
            sp.edit().putString("items", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 连接 ----------------

    public void connect() {
        connectTried = true;
        lib.connect(new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean value) {
                connectionOk = true;
                updateTopBar();
                refreshCurrent();
                if (lib.settings().currentService() != null) {
                    String u = lib.connectedUrl().replaceFirst("^https?://", "");
                    toast("已连接 " + u);
                }
            }

            @Override
            public void fail(String message) {
                connectionOk = false;
                updateTopBar();
                refreshCurrent();
                toast(message);
            }
        });
    }

    public boolean isConnected() {
        return connectionOk;
    }

    // ---------------- 前台服务与权限 ----------------

    private void ensureService() {
        try {
            startService(new Intent(this, PlaybackService.class));
        } catch (Exception ignored) {
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    // ---------------- 迷你播放条 ----------------

    /** 迷你条底部进度线：跟随播放位置（新歌从 0 开始，天然复位） */
    private void updateMiniProgress(int positionMs, int durationMs) {
        if (miniProgress == null) return;
        Item cur = player.current();
        int total = durationMs > 0 ? durationMs
                : (cur == null ? 0 : cur.durationSec * 1000);
        if (total <= 0) {
            miniProgress.setProgress(0);
            miniProgress.setSecondaryProgress(0);
            return;
        }
        if (miniProgress.getMax() != total) miniProgress.setMax(total);
        miniProgress.setProgress(Math.max(0, Math.min(positionMs, total)));
        // 浅色二级段 = 「边播边存」已经缓存到哪（与播放页进度条同一语义）。
        // cachePercent 返回 -1 表示这首没在缓存 → 必须归零，否则上一首的缓存段会留在新歌上。
        int cp = cur == null ? -1 : player.cachePercent(cur.id);
        miniProgress.setSecondaryProgress(cp > 0 ? (int) (total * (cp / 100.0)) : 0);
    }

    private void updateMiniPlayer() {
        if (miniBar == null) return;
        com.lixscn.subsonicplayer.ui.Theme.Colors c = Ui.colors(this);
        Item cur = player.current();
        if (cur == null) {
            miniBar.setVisibility(View.GONE);
            return;
        }
        Page top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        boolean show = top == null || top.showMiniPlayer();
        miniBar.setVisibility(show ? View.VISIBLE : View.GONE);
        miniTitle.setText(cur.title);
        boolean buffering = player.isBuffering();
        String sub = cur.subtitle == null ? "" : cur.subtitle;
        miniArtist.setText(buffering ? (sub.length() > 0 ? sub + " · 缓冲中…" : "缓冲中…") : sub);
        if (buffering) {
            // 首播要等网络，用旋转图标明确告诉用户在加载，而不是「点了没反应」
            miniToggle.setImageResource(R.drawable.ic_refresh);
            miniToggle.setColorFilter(c.accent);
            miniToggle.setTag(R.drawable.ic_refresh);
        } else {
            miniToggle.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            miniToggle.setColorFilter(c.text);
            miniToggle.setTag(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        String url = cur.coverArt == null || cur.coverArt.length() == 0 ? "" : lib.coverUrl(cur.coverArt, 256);
        miniCover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(this, 8)));
        if (url.length() > 0) CoverLoader.get(this).load(url, miniCover, 0);
        else miniCover.setImageDrawable(null);
        updateBufferingOverlay();
    }

    // ---------------- Player.Listener ----------------

    @Override
    public void onTrackChanged(Item song) {
        updateMiniPlayer();
        if (song != null) recordHistory(song);
    }

    @Override
    public void onProgress(boolean playing, int positionMs, int durationMs) {
        updateBufferingOverlay();
        updateMiniProgress(positionMs, durationMs);
        if (miniToggle != null) {
            int res = player.isBuffering() ? R.drawable.ic_refresh
                    : (playing ? R.drawable.ic_pause : R.drawable.ic_play);
            if (miniToggle.getTag() == null || !miniToggle.getTag().equals(res)) {
                miniToggle.setImageResource(res);
                miniToggle.setTag(res);
                com.lixscn.subsonicplayer.ui.Theme.Colors c = Ui.colors(this);
                miniToggle.setColorFilter(player.isBuffering() ? c.accent : c.text);
            }
            // 缓冲状态变化时刷新副标题文案
            Item cur = player.current();
            if (cur != null) {
                boolean buffering = player.isBuffering();
                String sub = cur.subtitle == null ? "" : cur.subtitle;
                String want = buffering ? (sub.length() > 0 ? sub + " · 缓冲中…" : "缓冲中…") : sub;
                if (!want.equals(miniArtist.getText().toString())) miniArtist.setText(want);
            }
        }
    }

    @Override
    public void onQueueChanged() {
        updateMiniPlayer();
    }

    @Override
    public void onModeChanged(int mode) {
    }

    @Override
    public void onPlaybackError(String message) {
        toast(message);
    }
}
