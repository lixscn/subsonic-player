package com.lixscn.subsonicplayer.ui.pages;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.player.Player;
import com.lixscn.subsonicplayer.ui.CircleCover;
import com.lixscn.subsonicplayer.ui.CoverLoader;
import com.lixscn.subsonicplayer.ui.ItemAdapter;
import com.lixscn.subsonicplayer.ui.Menus;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 详情页（专辑 / 艺术家 / 歌单）。
 *
 * <p>对外只有 {@link #album}、{@link #artist}、{@link #playlist} 三个静态工厂方法，
 * 其余全是本文件内的私有实现（基类 + 匿名子类），页面之间不互相引用。
 *
 * <p>结构约定：
 * <ul>
 *   <li>专辑页 / 歌单页 = {@link ListView}（头部信息作为 header view）+ 自绘曲目行适配器，
 *       曲目可能有几百首，用 ListView 才能回收复用；点击行的下标即适配器下标，
 *       所以直接 {@code act.playNow(songs, position)} 就能「从该曲开始播放整张专辑/歌单」。</li>
 *   <li>艺术家页 = {@link ScrollView} + 全高 {@link GridView}（专辑数少，不需要回收）。</li>
 *   <li>三种页面都有 加载中 / 加载失败（可重试）/ 内容 三态，任何网络失败都不会白屏。</li>
 * </ul>
 */
public final class DetailPages {

    private DetailPages() {
    }

    /** 专辑详情：封面 + 专辑名 + 艺术家 + 年份/曲目数 + 播放/随机/下一首播放 按钮 + 曲目列表 */
    public static Page album(MainActivity act, String albumId) {
        return new AlbumPage(albumId == null ? "" : albumId);
    }

    /** 艺术家详情：封面 + 名字 + 专辑网格 + 全部歌曲入口 */
    public static Page artist(MainActivity act, String artistId, String name) {
        return new ArtistPage(artistId == null ? "" : artistId, name == null ? "" : name);
    }

    /** 歌单详情：封面 + 名字 + 曲目数 + 播放按钮 + 曲目列表（支持长按/⋮ 移除曲目） */
    public static Page playlist(MainActivity act, String playlistId, String name) {
        return new PlaylistPage(playlistId == null ? "" : playlistId, name == null ? "" : name);
    }

    // ==================================================================================
    // 基类：三态（加载中 / 失败可重试 / 内容）+ 顶栏同步 + 播放状态监听
    // ==================================================================================

    private abstract static class DetailPage extends Page {

        protected LinearLayout root;
        protected FrameLayout holder;
        protected String pageTitle = "";
        protected String pageSubtitle = "";

        /** 视图代次：onDestroy()/rebuild() 后自增，让在途的网络回调全部失效 */
        protected int generation;

        private boolean listening;
        private boolean topSynced;

        /** 播放状态：只关心「切歌 / 队列变化」，进度回调不做任何事（500ms 一次，太频繁） */
        private final Player.Listener playbackListener = new Player.Listener() {
            @Override
            public void onTrackChanged(Item song) {
                onPlaybackChanged();
            }

            @Override
            public void onProgress(boolean playing, int positionMs, int durationMs) {
            }

            @Override
            public void onQueueChanged() {
                onPlaybackChanged();
            }

            @Override
            public void onModeChanged(int mode) {
            }

            @Override
            public void onPlaybackError(String message) {
            }
        };

        @Override
        public String title() {
            return pageTitle;
        }

        @Override
        public String subtitle() {
            return pageSubtitle;
        }

        @Override
        protected View build() {
            generation++;
            topSynced = false;
            Theme.Colors c = Ui.colors(act);
            root = Ui.column(act);
            root.setBackgroundColor(c.bg);
            holder = new FrameLayout(act);
            root.addView(holder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            showLoading();
            addPlaybackListener();
            load();
            return root;
        }

        /** 子类实现：发起数据加载；成功调 showContent()，失败调 showError() */
        protected abstract void load();

        /** 子类实现：切歌 / 队列变化时刷新「正在播放」高亮 */
        protected void onPlaybackChanged() {
        }

        /** 回调是否已过期（页面被销毁或重建过） */
        protected boolean stale(int gen) {
            return gen != generation;
        }

        // ---------------- 三态 ----------------

        protected void showLoading() {
            if (holder == null) return;
            holder.removeAllViews();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            holder.addView(Ui.loading(act), lp);
        }

        /** 失败态：一定带「重试」，绝不白屏 */
        protected void showError(String message) {
            if (holder == null) return;
            holder.removeAllViews();
            final String detail = message == null ? "未知错误" : message;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            holder.addView(Ui.message(act, "加载失败", detail, "重试", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showLoading();
                    load();
                }
            }), lp);
        }

        protected void showContent(View content) {
            if (holder == null) return;
            holder.removeAllViews();
            holder.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // ---------------- 顶栏 ----------------

        /**
         * 顶栏标题/副标题由 MainActivity 渲染，只在 push/refreshCurrent 时读一次；
         * 详情页的名字要等数据回来才知道，所以拿到后触发一次 refreshCurrent() 重画顶栏。
         *
         * <p>注意：refreshCurrent() 会重建本页（重新 load()），因此这里做了两道闸门——
         * 「值确实变了」+「本代次只同步一次」，否则会无限重建。
         *
         * @param syncSubtitle 副标题变化是否也值得重建一次：
         *                     专辑/艺术家页的数据在 Library 里有缓存，重建是同步命中、页面不会闪；
         *                     歌单没有缓存，重建会再拉一次网络（闪一下加载态），
         *                     所以歌单页只在标题（名字）变化时才重建。
         */
        protected void syncTop(String title, String subtitle, boolean syncSubtitle) {
            String t = title == null ? "" : title;
            String sub = subtitle == null ? "" : subtitle;
            boolean titleChanged = t.length() > 0 && !t.equals(pageTitle);
            boolean subtitleChanged = !sub.equals(pageSubtitle);
            if (t.length() > 0) pageTitle = t;
            pageSubtitle = sub;
            if (topSynced) return;
            if (!titleChanged && !(syncSubtitle && subtitleChanged)) return;
            topSynced = true;
            final LinearLayout r = root;
            if (r == null) return;
            lib.onMain(new Runnable() {
                @Override
                public void run() {
                    // 已经不是栈顶（用户又点进了别的页面）就不要动别人的页面
                    if (act == null || r.getParent() == null) return;
                    act.refreshCurrent();
                }
            });
        }

        // ---------------- 播放 ----------------

        protected void playAll(List<Item> songs) {
            if (songs == null || songs.isEmpty()) {
                act.toast("没有可播放的曲目");
                return;
            }
            act.playNow(songs, 0);
        }

        protected void playFrom(List<Item> songs, int index) {
            if (songs == null || songs.isEmpty()) {
                act.toast("没有可播放的曲目");
                return;
            }
            act.playNow(songs, index);
        }

        /** 随机播放：随机挑一首作起点，顺序交给播放器的随机模式（它自己会乱序取下一首） */
        protected void shuffleAll(List<Item> songs) {
            if (songs == null || songs.isEmpty()) {
                act.toast("没有可播放的曲目");
                return;
            }
            act.playNow(songs, new Random().nextInt(songs.size()));
            player.setMode(Player.MODE_SHUFFLE);
        }

        /** 下一首播放：当前没有在播的内容时退化为直接播放，否则用户会看不到任何反馈 */
        protected void playAsNext(List<Item> songs) {
            if (songs == null || songs.isEmpty()) {
                act.toast("没有可播放的曲目");
                return;
            }
            if (player.current() == null) {
                act.playNow(songs, 0);
                return;
            }
            player.playNext(songs);
            act.toast("已加入下一首");
        }

        protected String currentSongId() {
            Item cur = player == null ? null : player.current();
            if (cur == null) return "";
            return cur.id == null ? "" : cur.id;
        }

        // ---------------- 播放监听 ----------------

        private void addPlaybackListener() {
            if (player != null && !listening) {
                player.addListener(playbackListener);
                listening = true;
            }
        }

        @Override
        public void onDestroy() {
            // 监听必须摘掉，否则页面被销毁后仍会被回调
            if (player != null && listening) {
                player.removeListener(playbackListener);
                listening = false;
            }
            generation++;   // 在途回调全部作废
        }

        // ---------------- 常用小工具 ----------------

        /** 空态文案块 */
        protected View emptyBlock(String text) {
            Theme.Colors c = Ui.colors(act);
            LinearLayout ll = Ui.column(act);
            ll.setGravity(Gravity.CENTER);
            Ui.pad(ll, act, 24, 26, 24, 26);
            TextView tv = Ui.text(act, text, 13.5f, c.textFaint);
            tv.setGravity(Gravity.CENTER);
            ll.addView(tv);
            return ll;
        }

        /** 竖向滚动容器（头部 + 少量内容时用；长列表请用 ListView） */
        protected ScrollView scrollOf(View content) {
            ScrollView sv = new ScrollView(act);
            sv.setVerticalScrollBarEnabled(false);
            sv.setFillViewport(true);
            sv.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return sv;
        }

        /**
         * 详情页骨架：头部作为 header view，曲目交给 ListView（长列表可回收复用）。
         * header/footer 都在 setAdapter 之前挂上，避免 ListView 事后重新包一层适配器。
         */
        protected ListView listWithHeader(View header, BaseAdapter adapter, View footer) {
            ListView lv = new ListView(act);
            lv.setDivider(null);
            lv.setDividerHeight(0);
            // 清掉系统默认点击高亮（MIUI 上是一块亮色闪烁），点击反馈交给行内涟漪
            lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
            lv.setPadding(0, 0, 0, Ui.dp(act, 10));
            lv.setClipToPadding(false);
            lv.setVerticalScrollBarEnabled(false);
            if (header != null) lv.addHeaderView(header, null, false);
            if (footer != null) lv.addFooterView(footer, null, false);
            lv.setAdapter(adapter);
            return lv;
        }

        /** 圆角药丸按钮（主按钮用强调色实心，次按钮用面板色） */
        protected View pill(String label, int iconRes, boolean primary, View.OnClickListener l) {
            Theme.Colors c = Ui.colors(act);
            int fg = primary ? c.accentText : c.text;
            LinearLayout box = Ui.row(act);
            box.setGravity(Gravity.CENTER);
            Ui.pad(box, act, 16, 9, 16, 9);
            box.setBackground(Ui.rect(primary ? c.accent : c.surfaceAlt, Ui.dp(act, 22)));
            if (iconRes != 0) {
                ImageView ic = Ui.icon(act, iconRes, 15, fg);
                LinearLayout.LayoutParams ilp = Ui.lp(Ui.dp(act, 17), Ui.dp(act, 17));
                ilp.rightMargin = Ui.dp(act, 6);
                box.addView(ic, ilp);
            }
            box.addView(Ui.text(act, label, 13.5f, fg));
            if (l != null) {
                box.setOnClickListener(l);
                Ui.tappable(box, act, fg);
            }
            return box;
        }

        /** 圆角/圆形封面，自带占位底色（CoverLoader 的 placeholderRes 传 0） */
        protected CircleCover coverView(int sizeDp, int radiusDp, boolean circle) {
            Theme.Colors c = Ui.colors(act);
            CircleCover v = new CircleCover(act);
            if (circle) v.setCircle(true);
            else v.setCornerRadius(Ui.dp(act, radiusDp));
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setBackground(circle ? Ui.circle(c.surfaceAlt) : Ui.rect(c.surfaceAlt, Ui.dp(act, radiusDp)));
            return v;
        }

        /**
         * 封面直链：优先用条目自己的 coverArt；专辑/歌单没有封面时退而取第一首曲目的封面
         * （服务端不少歌单没有 coverArt，但曲目有）。
         */
        protected String coverUrlOf(String coverArt, List<Item> songs) {
            String cover = s(coverArt);
            if (cover.length() == 0 && songs != null && !songs.isEmpty()) {
                Item first = songs.get(0);
                if (first != null) cover = s(first.coverArt);
            }
            return cover.length() == 0 ? "" : lib.coverUrl(cover, 512);
        }

        /** 曲目总数 / 总时长元信息，如「2024 · 12 首 · 45 分钟」 */
        protected String metaLine(int year, int count, int durationSec) {
            StringBuilder sb = new StringBuilder();
            if (year > 0) sb.append(year);
            if (count > 0) appendDot(sb).append(count).append(" 首");
            if (durationSec > 0) appendDot(sb).append(durationText(durationSec));
            return sb.toString();
        }
    }

    // ==================================================================================
    // 专辑详情
    // ==================================================================================

    private static final class AlbumPage extends DetailPage {

        private final String albumId;
        private TrackAdapter adapter;
        private String playingId = "";

        AlbumPage(String albumId) {
            this.albumId = albumId;
            this.pageTitle = "专辑";
        }

        @Override
        protected void load() {
            final int gen = generation;
            lib.album(albumId, new Library.Done<Item>() {
                @Override
                public void ok(Item album) {
                    if (stale(gen)) return;
                    if (album == null) {
                        showError("专辑不存在或已被删除");
                        return;
                    }
                    final List<Item> songs = album.songs == null
                            ? new ArrayList<Item>() : album.songs;
                    syncTop(album.title, s(album.artist), true);
                    View header = buildHeader(album, songs);
                    adapter = new TrackAdapter(act, Ui.colors(act), songs, true,
                            false, s(album.artist), new RowClick() {
                        @Override
                        public void onRow(Item song, int position) {
                            // 点击某一首 = 播放整张专辑并从该曲开始
                            playFrom(songs, position);
                        }

                        @Override
                        public void onMore(Item song, int position, View anchor) {
                            Menus.song(act, song);
                        }
                    });
                    playingId = currentSongId();
                    adapter.setPlayingId(playingId);
                    showContent(listWithHeader(header, adapter, songs.isEmpty()
                            ? emptyBlock("这张专辑没有曲目") : null));
                }

                @Override
                public void fail(String message) {
                    if (stale(gen)) return;
                    showError(message);
                }
            });
        }

        @Override
        protected void onPlaybackChanged() {
            if (adapter == null) return;
            String id = currentSongId();
            if (id.equals(playingId)) return;
            playingId = id;
            adapter.setPlayingId(id);
            adapter.notifyDataSetChanged();
        }

        private View buildHeader(final Item album, final List<Item> songs) {
            Theme.Colors c = Ui.colors(act);
            LinearLayout col = Ui.column(act);
            Ui.pad(col, act, 16, 10, 16, 4);

            // 大封面（约 150dp，圆角 14dp）
            LinearLayout coverRow = Ui.row(act);
            coverRow.setGravity(Gravity.CENTER);
            CircleCover cover = coverView(150, 14, false);
            String url = coverUrlOf(album.coverArt, songs);
            if (url.length() > 0) CoverLoader.get(act).load(url, cover, 0);
            coverRow.addView(cover, Ui.lp(Ui.dp(act, 150), Ui.dp(act, 150)));
            col.addView(coverRow, Ui.lpMatchWrap());

            // 专辑名（最多 2 行）
            TextView name = Ui.bold(act, s(album.title), 21, c.text);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = Ui.lpMatchWrap();
            nlp.topMargin = Ui.dp(act, 14);
            col.addView(name, nlp);

            // 艺术家（点击进艺术家页）
            if (s(album.artist).length() > 0) {
                final String artistName = s(album.artist);
                final String artistId = s(album.artistId);
                TextView artist = Ui.text(act, artistName, 14, c.accent);
                artist.setGravity(Gravity.CENTER);
                artist.setPadding(Ui.dp(act, 8), Ui.dp(act, 6), Ui.dp(act, 8), Ui.dp(act, 4));
                artist.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        act.openArtist(artistId, artistName);
                    }
                });
                Ui.tappable(artist, act, c.accent);
                col.addView(artist);
            }

            // 元信息：年份 · 曲目数 · 总时长
            int count = songs.isEmpty() ? album.songCount : songs.size();
            int dur = totalSeconds(songs);
            if (dur <= 0) dur = album.durationSec;
            TextView meta = Ui.text(act, metaLine(album.year, count, dur), 12.5f, c.textDim);
            meta.setGravity(Gravity.CENTER);
            meta.setPadding(0, Ui.dp(act, 4), 0, 0);
            col.addView(meta);

            // 操作按钮：播放 / 随机播放 / 下一首播放
            LinearLayout actions = Ui.row(act);
            actions.setGravity(Gravity.CENTER);
            actions.addView(pill("播放", R.drawable.ic_play_solid, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playAll(songs);
                }
            }));
            LinearLayout.LayoutParams p2 = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p2.leftMargin = Ui.dp(act, 8);
            actions.addView(pill("随机播放", 0, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shuffleAll(songs);
                }
            }), p2);
            LinearLayout.LayoutParams p3 = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p3.leftMargin = Ui.dp(act, 8);
            actions.addView(pill("下一首播放", R.drawable.ic_queue, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playAsNext(songs);
                }
            }), p3);
            LinearLayout.LayoutParams alp = Ui.lpMatchWrap();
            alp.topMargin = Ui.dp(act, 16);
            col.addView(actions, alp);

            if (!songs.isEmpty()) {
                LinearLayout.LayoutParams slp = Ui.lpMatchWrap();
                slp.topMargin = Ui.dp(act, 6);
                col.addView(Ui.sectionHeader(act, "曲目", songs.size() + " 首", null), slp);
            }
            return col;
        }
    }

    // ==================================================================================
    // 艺术家详情
    // ==================================================================================

    private static final class ArtistPage extends DetailPage {

        private static final int COLUMNS = 3;
        /** 「全部歌曲」只预览前几张专辑，见 loadPreview() 的取舍说明 */
        private static final int PREVIEW_ALBUMS = 3;
        private static final int PREVIEW_SONGS = 10;

        private final String artistId;
        private final String artistName;

        private List<Item> albums = new ArrayList<Item>();
        private final List<Item> preview = new ArrayList<Item>();
        private CircleCover avatar;
        private String avatarCoverId = "";
        private LinearLayout previewBox;
        private boolean previewLoaded;

        ArtistPage(String artistId, String name) {
            this.artistId = artistId;
            this.artistName = name;
            this.pageTitle = name.length() > 0 ? name : "艺术家";
        }

        @Override
        protected void load() {
            final int gen = generation;
            loadAvatar(gen);
            lib.artistAlbums(artistId, new Library.Done<List<Item>>() {
                @Override
                public void ok(List<Item> value) {
                    if (stale(gen)) return;
                    albums = value == null ? new ArrayList<Item>() : value;
                    String name = artistName;
                    if (name.length() == 0 && !albums.isEmpty()) name = s(albums.get(0).artist);
                    if (name.length() == 0) name = "艺术家";
                    syncTop(name, albums.isEmpty() ? "没有专辑" : albums.size() + " 张专辑", true);
                    showContent(scrollOf(buildContent()));
                    loadPreview(gen);
                }

                @Override
                public void fail(String message) {
                    if (stale(gen)) return;
                    showError(message);
                }
            });
        }

        @Override
        protected void onPlaybackChanged() {
            // 预览行不是 ListView 的 item（没有回收池），直接重画这 10 行最省事
            if (previewBox != null && !preview.isEmpty()) renderPreview(generation);
        }

        // ---------------- 头像 ----------------

        private void loadAvatar(final int gen) {
            avatarCoverId = "";
            lib.artistCover(artistId, new Library.Done<String>() {
                @Override
                public void ok(String value) {
                    if (stale(gen)) return;
                    avatarCoverId = value == null ? "" : value;
                    applyAvatar();
                }

                @Override
                public void fail(String message) {
                    if (stale(gen)) return;
                    avatarCoverId = "";
                }
            });
        }

        /** 拿不到封面就保留「名字首字」的圆形色块占位 */
        private void applyAvatar() {
            if (avatar == null || avatarCoverId.length() == 0) return;
            String url = lib.coverUrl(avatarCoverId, 512);
            if (url.length() > 0) CoverLoader.get(act).load(url, avatar, 0);
        }

        // ---------------- 页面内容 ----------------

        private View buildContent() {
            Theme.Colors c = Ui.colors(act);
            LinearLayout col = Ui.column(act);
            Ui.pad(col, act, 0, 8, 0, 8);

            // 头像（100dp 圆形）
            LinearLayout head = Ui.column(act);
            head.setGravity(Gravity.CENTER_HORIZONTAL);
            Ui.pad(head, act, 16, 8, 16, 4);

            FrameLayout avatarBox = new FrameLayout(act);
            TextView initial = Ui.text(act, initialOf(pageTitle), 36, c.accentText);
            initial.setGravity(Gravity.CENTER);
            initial.setBackground(Ui.circle(Ui.mix(c.accent, c.surface, 0.35f)));
            avatarBox.addView(initial, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            avatar = coverView(100, 50, true);
            avatarBox.addView(avatar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout avatarRow = Ui.row(act);
            avatarRow.setGravity(Gravity.CENTER);
            avatarRow.addView(avatarBox, Ui.lp(Ui.dp(act, 100), Ui.dp(act, 100)));
            head.addView(avatarRow, Ui.lpMatchWrap());
            applyAvatar();

            TextView name = Ui.bold(act, pageTitle, 21, c.text);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = Ui.lpMatchWrap();
            nlp.topMargin = Ui.dp(act, 14);
            head.addView(name, nlp);

            TextView count = Ui.text(act, albums.isEmpty() ? "没有专辑" : albums.size() + " 张专辑",
                    12.5f, c.textDim);
            count.setGravity(Gravity.CENTER);
            count.setPadding(0, Ui.dp(act, 5), 0, 0);
            head.addView(count);

            LinearLayout actions = Ui.row(act);
            actions.setGravity(Gravity.CENTER);
            actions.addView(pill("随机播放", R.drawable.ic_play_solid, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.shuffleArtist(artistId);
                }
            }));
            LinearLayout.LayoutParams alp = Ui.lpMatchWrap();
            alp.topMargin = Ui.dp(act, 14);
            head.addView(actions, alp);
            col.addView(head, Ui.lpMatchWrap());

            // 专辑网格
            if (albums.isEmpty()) {
                col.addView(emptyBlock("这位艺术家没有专辑"));
            } else {
                col.addView(Ui.sectionHeader(act, "专辑", albums.size() + " 张", null));
                GridView gv = new FullHeightGridView(act);
                gv.setNumColumns(COLUMNS);
                gv.setHorizontalSpacing(Ui.dp(act, 2));
                gv.setVerticalSpacing(Ui.dp(act, 8));
                gv.setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4));
                gv.setClipToPadding(false);
                gv.setVerticalScrollBarEnabled(false);
                gv.setAdapter(new ItemAdapter(act, albums, ItemAdapter.MODE_GRID, COLUMNS,
                        new ItemAdapter.Listener() {
                            @Override
                            public void onItemClick(Item item, int position) {
                                act.openAlbum(item.id);
                            }

                            @Override
                            public void onItemMore(Item item, int position, View anchor) {
                                Menus.album(act, item);
                            }
                        }));
                col.addView(gv, Ui.lpMatchWrap());
            }

            // 「全部歌曲」入口：标题右侧的「随机播放全部」复用 act.shuffleArtist()
            col.addView(Ui.sectionHeader(act, "全部歌曲", "随机播放全部", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.shuffleArtist(artistId);
                }
            }));
            previewBox = Ui.column(act);
            col.addView(previewBox, Ui.lpMatchWrap());
            previewBox.addView(emptyBlock("正在读取曲目…"));
            return col;
        }

        /**
         * 「全部歌曲」的取舍：本服务端没有「按艺术家取全部歌曲」的端点，
         * act.shuffleArtist() 又是拿来直接起播的（不能内联展示），
         * 所以这里退一步：只取前 {@link #PREVIEW_ALBUMS} 张专辑的曲目，
         * 内联展示前 {@link #PREVIEW_SONGS} 首当入口 + 预览，想听全量就点
         * 区块右侧的「随机播放全部」（内部走 act.shuffleArtist）。
         * 单张专辑取失败只跳过它，不影响已经拿到的内容。
         */
        private void loadPreview(final int gen) {
            // 重建（顶栏同步 / 主题切换）时曲目已经拿过，直接照原样重画，不重复打服务端
            if (previewLoaded) {
                renderPreview(gen);
                return;
            }
            preview.clear();   // 重试时从零开始，避免重复行
            final List<Item> source = new ArrayList<Item>();
            int take = Math.min(PREVIEW_ALBUMS, albums.size());
            for (int i = 0; i < take; i++) source.add(albums.get(i));
            if (source.isEmpty()) {
                renderPreview(gen);
                return;
            }
            final int[] pending = new int[]{source.size()};
            for (int i = 0; i < source.size(); i++) {
                final Item al = source.get(i);
                lib.albumSongs(al.id, new Library.Done<List<Item>>() {
                    @Override
                    public void ok(List<Item> value) {
                        if (stale(gen)) return;
                        if (value != null) preview.addAll(value);
                        if (--pending[0] == 0) renderPreview(gen);
                    }

                    @Override
                    public void fail(String message) {
                        if (stale(gen)) return;
                        if (--pending[0] == 0) renderPreview(gen);
                    }
                });
            }
        }

        private void renderPreview(int gen) {
            if (stale(gen) || previewBox == null) return;
            // 无论成功几首，都算这一轮的预览取完了（失败重试的入口在页面顶部，不在这里）
            previewLoaded = true;
            previewBox.removeAllViews();
            final int shown = Math.min(PREVIEW_SONGS, preview.size());
            if (shown == 0) {
                previewBox.addView(emptyBlock("没有可展示的曲目，点上方「随机播放」听全部"));
                return;
            }
            final String playing = currentSongId();
            for (int i = 0; i < shown; i++) {
                View row = trackRowView(act, Ui.colors(act), true);
                bindTrackRow(row, preview.get(i), i, Ui.colors(act), true, null, playing,
                        new RowClick() {
                            @Override
                            public void onRow(Item song, int position) {
                                playFrom(preview, position);
                            }

                            @Override
                            public void onMore(Item song, int position, View anchor) {
                                Menus.song(act, song);
                            }
                        });
                previewBox.addView(row, Ui.lpMatchWrap());
            }
            if (preview.size() > shown) {
                TextView more = Ui.text(act, "共 " + preview.size() + " 首（仅展示前 " + shown + " 首）",
                        12.5f, Ui.colors(act).textFaint);
                more.setGravity(Gravity.CENTER);
                Ui.pad(more, act, 16, 10, 16, 16);
                previewBox.addView(more);
            }
        }
    }

    // ==================================================================================
    // 歌单详情
    // ==================================================================================

    private static final class PlaylistPage extends DetailPage {

        private final String playlistId;
        private List<Item> songs = new ArrayList<Item>();
        private TrackAdapter adapter;
        private String playingId = "";

        PlaylistPage(String playlistId, String name) {
            this.playlistId = playlistId;
            this.pageTitle = name.length() > 0 ? name : "歌单";
        }

        @Override
        protected void load() {
            final int gen = generation;
            lib.playlist(playlistId, new Library.Done<Item>() {
                @Override
                public void ok(Item pl) {
                    if (stale(gen)) return;
                    if (pl == null) {
                        showError("歌单不存在或已被删除");
                        return;
                    }
                    songs = pl.songs == null ? new ArrayList<Item>() : pl.songs;
                    // 歌单没有缓存：副标题（曲目数）不值得为它重建一次页面
                    syncTop(pl.title, songs.size() > 0 ? songs.size() + " 首" : "", false);
                    View header = buildHeader(pl, songs);
                    adapter = new TrackAdapter(act, Ui.colors(act), songs, true, true, null,
                            new RowClick() {
                                @Override
                                public void onRow(Item song, int position) {
                                    playFrom(songs, position);
                                }

                                @Override
                                public void onMore(Item song, int position, View anchor) {
                                    songMenu(song, position);
                                }
                            });
                    playingId = currentSongId();
                    adapter.setPlayingId(playingId);
                    showContent(listWithHeader(header, adapter, songs.isEmpty()
                            ? emptyBlock("这个歌单还没有曲目") : null));
                }

                @Override
                public void fail(String message) {
                    if (stale(gen)) return;
                    showError(message);
                }
            });
        }

        @Override
        protected void onPlaybackChanged() {
            if (adapter == null) return;
            String id = currentSongId();
            if (id.equals(playingId)) return;
            playingId = id;
            adapter.setPlayingId(id);
            adapter.notifyDataSetChanged();
        }

        private View buildHeader(final Item pl, final List<Item> songs) {
            Theme.Colors c = Ui.colors(act);
            LinearLayout col = Ui.column(act);
            Ui.pad(col, act, 16, 10, 16, 4);

            LinearLayout coverRow = Ui.row(act);
            coverRow.setGravity(Gravity.CENTER);
            CircleCover cover = coverView(150, 14, false);
            String url = coverUrlOf(pl.coverArt, songs);
            if (url.length() > 0) CoverLoader.get(act).load(url, cover, 0);
            coverRow.addView(cover, Ui.lp(Ui.dp(act, 150), Ui.dp(act, 150)));
            col.addView(coverRow, Ui.lpMatchWrap());

            TextView name = Ui.bold(act, s(pl.title), 21, c.text);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = Ui.lpMatchWrap();
            nlp.topMargin = Ui.dp(act, 14);
            col.addView(name, nlp);

            int count = songs.isEmpty() ? pl.songCount : songs.size();
            int dur = totalSeconds(songs);
            if (dur <= 0) dur = pl.durationSec;
            TextView meta = Ui.text(act, metaLine(0, count, dur), 12.5f, c.textDim);
            meta.setGravity(Gravity.CENTER);
            meta.setPadding(0, Ui.dp(act, 6), 0, 0);
            col.addView(meta);

            LinearLayout actions = Ui.row(act);
            actions.setGravity(Gravity.CENTER);
            actions.addView(pill("播放", R.drawable.ic_play_solid, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playAll(songs);
                }
            }));
            LinearLayout.LayoutParams p2 = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p2.leftMargin = Ui.dp(act, 8);
            actions.addView(pill("下一首播放", R.drawable.ic_queue, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playAsNext(songs);
                }
            }), p2);
            LinearLayout.LayoutParams alp = Ui.lpMatchWrap();
            alp.topMargin = Ui.dp(act, 16);
            col.addView(actions, alp);

            if (!songs.isEmpty()) {
                LinearLayout.LayoutParams slp = Ui.lpMatchWrap();
                slp.topMargin = Ui.dp(act, 6);
                col.addView(Ui.sectionHeader(act, "曲目", songs.size() + " 首", null), slp);
            }
            return col;
        }

        // ---------------- 曲目菜单（含「从歌单移除」） ----------------

        /**
         * ⋮ / 长按菜单：第一项是本页独有的「从歌单移除」，
         * 其余歌曲操作（收藏、评分、队列、添加到歌单…）仍然走 Menus.song，
         * 避免把框架里已经写好的歌曲菜单再实现一遍。
         */
        private void songMenu(final Item song, final int position) {
            Ui.dialog(act)
                    .setTitle(s(song.title))
                    .setItems(new String[]{"从歌单移除", "更多操作…（收藏 / 评分 / 队列）"},
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    d.dismiss();
                                    if (which == 0) confirmRemove(song, position);
                                    else Menus.song(act, song);
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        }

        private void confirmRemove(final Item song, final int position) {
            Ui.confirm(act, "从歌单移除", "确定把「" + s(song.title) + "」从歌单移除？", "移除",
                    new Runnable() {
                        @Override
                        public void run() {
                            removeFromPlaylist(song, position);
                        }
                    });
        }

        private void removeFromPlaylist(Item song, int position) {
            // 服务端按「曲目在歌单中的下标」删除；Item.listIndex 就是解析时的原始下标
            int index = song.listIndex >= 0 ? song.listIndex : position;
            if (index < 0 || index >= songs.size()) {
                act.toast("曲目已变化，请重新打开歌单");
                return;
            }
            lib.removeFromPlaylist(playlistId, new int[]{index}, new Library.Done<Boolean>() {
                @Override
                public void ok(Boolean value) {
                    act.toast("已从歌单移除");
                    // 歌单没有缓存，重建即重新拉取，保证和服务器一致；
                    // 但只在还停留在本页时才刷新，否则会误刷栈顶的其它页面
                    if (root != null && root.getParent() != null) act.refreshCurrent();
                }

                @Override
                public void fail(String message) {
                    act.toast(message);
                }
            });
        }
    }

    // ==================================================================================
    // 曲目行（序号 + 标题 + 副标题 + 时长 + ⋮），ListView 与内联预览共用
    // ==================================================================================

    private interface RowClick {
        void onRow(Item song, int position);

        void onMore(Item song, int position, View anchor);
    }

    private static final class TrackRow {
        LinearLayout root;
        TextView number;
        TextView title;
        TextView subtitle;
        TextView duration;
        ImageView more;
    }

    private static final class TrackAdapter extends BaseAdapter {

        private final Context ctx;
        private final Theme.Colors colors;
        private final List<Item> songs;
        private final boolean showNumber;
        private final boolean showSubtitle;
        private final String groupArtist;
        private final RowClick callback;
        private String playingId = "";

        TrackAdapter(Context ctx, Theme.Colors colors, List<Item> songs, boolean showNumber,
                     boolean showSubtitle, String groupArtist, RowClick callback) {
            this.ctx = ctx;
            this.colors = colors;
            this.songs = songs;
            this.showNumber = showNumber;
            this.showSubtitle = showSubtitle;
            this.groupArtist = groupArtist;
            this.callback = callback;
        }

        void setPlayingId(String id) {
            this.playingId = id == null ? "" : id;
        }

        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int position) {
            return songs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) row = trackRowView(ctx, colors, showNumber);
            bindTrackRow(row, songs.get(position), position, colors, showSubtitle, groupArtist,
                    playingId, callback);
            return row;
        }
    }

    private static View trackRowView(Context ctx, Theme.Colors c, boolean showNumber) {
        TrackRow h = new TrackRow();
        LinearLayout row = Ui.row(ctx);
        Ui.pad(row, ctx, 14, 7, 8, 7);
        row.setMinimumHeight(Ui.dp(ctx, 56));

        if (showNumber) {
            TextView num = Ui.text(ctx, "", 12.5f, c.textFaint);
            num.setGravity(Gravity.CENTER);
            row.addView(num, Ui.lp(Ui.dp(ctx, 30), ViewGroup.LayoutParams.WRAP_CONTENT));
            h.number = num;
        }

        LinearLayout mid = Ui.column(ctx);
        mid.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 8), 0);
        TextView title = Ui.text(ctx, "", 15, c.text);
        Ui.ellipsize(title);
        TextView sub = Ui.text(ctx, "", 12, c.textDim);
        Ui.ellipsize(sub);
        sub.setPadding(0, Ui.dp(ctx, 3), 0, 0);
        mid.addView(title);
        mid.addView(sub);
        row.addView(mid, Ui.lpWeight(1));
        h.title = title;
        h.subtitle = sub;

        TextView dur = Ui.text(ctx, "", 12, c.textFaint);
        dur.setGravity(Gravity.CENTER);
        row.addView(dur);
        h.duration = dur;

        ImageView more = Ui.icon(ctx, R.drawable.ic_more, 20, c.textDim);
        row.addView(more, Ui.lp(Ui.dp(ctx, 36), Ui.dp(ctx, 36)));
        h.more = more;

        h.root = row;
        row.setTag(h);
        Ui.tappable(row, ctx, c.text);
        return row;
    }

    private static void bindTrackRow(View row, Item song, int position, Theme.Colors c,
                                     boolean showSubtitle, String groupArtist, String playingId,
                                     final RowClick cb) {
        if (song == null || !(row.getTag() instanceof TrackRow)) return;
        final TrackRow h = (TrackRow) row.getTag();
        boolean playing = playingId != null && playingId.length() > 0 && playingId.equals(song.id);

        if (h.number != null) {
            int n = song.track > 0 ? song.track : position + 1;
            h.number.setText(String.valueOf(n));
            h.number.setTextColor(playing ? c.accent : c.textFaint);
        }
        h.title.setText(s(song.title));
        h.title.setTextColor(playing ? c.accent : c.text);
        String sub = subtitleOf(song, showSubtitle, groupArtist);
        h.subtitle.setText(sub);
        h.subtitle.setVisibility(sub.length() == 0 ? View.GONE : View.VISIBLE);
        h.duration.setText(song.durationText());
        h.duration.setTextColor(playing ? c.accent : c.textFaint);

        final Item item = song;
        final int pos = position;
        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cb != null) cb.onRow(item, pos);
            }
        });
        h.root.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (cb != null) cb.onMore(item, pos, v);
                return true;
            }
        });
        h.more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cb != null) cb.onMore(item, pos, v);
            }
        });
    }

    /**
     * 副标题：歌单页显示「艺术家 · 专辑」；
     * 专辑页只在合辑（曲目艺术家和专辑艺术家不一致）时才显示歌手，避免每行重复同一个名字。
     */
    private static String subtitleOf(Item song, boolean showSubtitle, String groupArtist) {
        if (showSubtitle) {
            String it = s(song.subtitle);
            return it.length() > 0 ? it : s(song.artist);
        }
        String artist = s(song.artist);
        if (groupArtist != null && groupArtist.length() > 0 && artist.length() > 0
                && !groupArtist.equals(artist)) {
            return artist;
        }
        return "";
    }

    /**
     * ScrollView / ListView header 里的 GridView 只会被量到一行左右的高度
     * （GridView.onMeasure() 的 UNSPECIFIED 分支只算一个 childHeight），
     * 这里改成：先按 AT_MOST(最大尺寸) 让 GridView 把所有行都算进去，
     * 再用真实布局结果补齐差距 —— 网格卡的标题允许 1~2 行，
     * 各行真实高度不一定相同，光靠「首行高度 × 行数」会裁掉最后一行。
     */
    private static final class FullHeightGridView extends GridView {

        private int extraHeight;
        private int corrections;

        FullHeightGridView(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(View.MEASURED_SIZE_MASK,
                            View.MeasureSpec.AT_MOST));
            setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + extraHeight);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            int need = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() != View.GONE) need = Math.max(need, child.getBottom());
            }
            need += getPaddingBottom();
            final int delta = need - getHeight();
            if (delta > 0 && corrections < 4) {
                corrections++;
                extraHeight += delta;
                post(new Runnable() {
                    @Override
                    public void run() {
                        requestLayout();
                    }
                });
            }
        }
    }

    // ==================================================================================
    // 纯函数小工具
    // ==================================================================================

    /** null 安全的字符串取值（服务端字段可能缺失） */
    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static StringBuilder appendDot(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" · ");
        return sb;
    }

    /** 秒 → 「45 分钟」/「1 小时 5 分」 */
    private static String durationText(int sec) {
        if (sec <= 0) return "";
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        if (h > 0) return h + " 小时 " + m + " 分";
        if (m <= 0) return "不到 1 分钟";
        return m + " 分钟";
    }

    private static int totalSeconds(List<Item> songs) {
        if (songs == null || songs.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < songs.size(); i++) {
            Item it = songs.get(i);
            if (it != null && it.durationSec > 0) total += it.durationSec;
        }
        return total;
    }

    /** 名字首字（拿不到艺术家封面时的圆形色块占位用） */
    private static String initialOf(String name) {
        String t = s(name).trim();
        if (t.length() == 0) return "?";
        return t.substring(0, 1).toUpperCase();
    }
}
