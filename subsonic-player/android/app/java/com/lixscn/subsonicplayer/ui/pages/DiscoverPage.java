package com.lixscn.subsonicplayer.ui.pages;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.core.Settings;
import com.lixscn.subsonicplayer.ui.CircleCover;
import com.lixscn.subsonicplayer.ui.CoverLoader;
import com.lixscn.subsonicplayer.ui.Menus;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Pages;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 发现页（底部第一个 Tab，App 的门面）。
 *
 * <p>竖屏下整体可滚动，自上而下四段：
 * <ol>
 *   <li>问候语（按当前小时）+ 服务器名与连接状态；</li>
 *   <li>一行 4 个圆形快捷入口（随机播放 / 我的收藏 / 最近播放 / 搜索）；</li>
 *   <li>三行横向卡片（最近添加 / 常听专辑 / 随机推荐），点卡片进专辑详情，长按弹专辑菜单；</li>
 *   <li>精选歌曲（竖向 10 首，点击起播，行尾「⋮」弹歌曲菜单）。</li>
 * </ol>
 *
 * <p>每个区块各自独立加载与容错：某个区块失败只在该区块内显示「加载失败 / 重试」，
 * 不影响其它区块；刷新（主题切换、连接成功后的 {@code refreshCurrent()}）会重建整页，
 * 因此用自增的 {@code gen} 让在途的网络回调自动失效，避免旧数据打到新视图上。
 *
 * <p>只用 Android Framework + Java 8（无 androidx、无第三方库），全部程序化建 View。
 */
public class DiscoverPage extends Page {

    /** 横向卡片宽度 */
    private static final int CARD_W_DP = 120;
    /** 横向卡片封面边长 */
    private static final int CARD_COVER_DP = 120;
    /** 每行横向卡片的数量 */
    private static final int STRIP_SIZE = 12;
    /** 精选歌曲数量 */
    private static final int FEATURED_SIZE = 10;

    /** 滚动内容容器（build() 里重建） */
    private LinearLayout content;
    /** 精选歌曲数据（点击起播时用作队列） */
    private final List<Item> featured = new ArrayList<Item>();
    /** 视图代次：重建后旧回调作废 */
    private int gen;

    @Override
    public String title() {
        return "发现";
    }

    // ---------------- 组装 ----------------

    @Override
    protected View build() {
        final Theme.Colors c = Ui.colors(act);
        gen++;
        final int g = gen;
        featured.clear();

        content = Ui.column(act);
        content.setBackgroundColor(c.bg);
        content.setPadding(0, 0, 0, Ui.dp(act, 20));

        ScrollView scroll = new ScrollView(act);
        scroll.setBackgroundColor(c.bg);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addGreeting(c);
        addQuickActions(c);

        final LinearLayout newestBody = addSection("最近添加");
        final LinearLayout frequentBody = addSection("常听专辑");
        final LinearLayout randomBody = addSection("随机推荐");
        final LinearLayout songsBody = addSection("精选歌曲");

        loadNewest(newestBody, g);
        loadFrequent(frequentBody, g);
        loadRandomAlbums(randomBody, g);
        loadFeatured(songsBody, g);
        return scroll;
    }

    @Override
    public void onDestroy() {
        // 重建/销毁后，在途回调不再改视图
        gen++;
    }

    // ---------------- 1. 问候语 ----------------

    private void addGreeting(Theme.Colors c) {
        LinearLayout head = Ui.column(act);
        Ui.pad(head, act, 16, 16, 16, 6);
        head.setBackgroundColor(c.bg);

        head.addView(Ui.bold(act, greeting(), 24, c.text));

        boolean connected = act.isConnected();
        LinearLayout line = Ui.row(act);
        line.setPadding(0, Ui.dp(act, 7), 0, 0);

        Settings.Service svc = lib.settings() == null ? null : lib.settings().currentService();
        String name = svc == null ? "未配置服务器" : svc.displayName();
        TextView server = Ui.text(act, name, 12, c.textDim);
        Ui.ellipsize(server);
        line.addView(server, Ui.lpWeight(1));

        View dot = new View(act);
        dot.setBackground(Ui.circle(connected ? c.ok : c.danger));
        LinearLayout.LayoutParams dlp = Ui.lp(Ui.dp(act, 7), Ui.dp(act, 7));
        dlp.rightMargin = Ui.dp(act, 6);
        line.addView(dot, dlp);

        String statText;
        if (connected) statText = "已连接";
        else if (lib.client() == null) statText = "未配置";
        else statText = "未连接";
        line.addView(Ui.text(act, statText, 12, connected ? c.ok : c.danger));

        head.addView(line);
        content.addView(head, Ui.lpMatchWrap());
    }

    /** 5:00-11:59 早上好；12:00-17:59 下午好；其余晚上好 */
    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "早上好";
        if (h >= 12 && h < 18) return "下午好";
        return "晚上好";
    }

    // ---------------- 2. 快捷入口 ----------------

    private void addQuickActions(Theme.Colors c) {
        LinearLayout row = Ui.row(act);
        Ui.pad(row, act, 6, 8, 6, 6);
        row.setBackgroundColor(c.bg);

        // 本工程没有 ic_shuffle，用 ic_play_solid 表达「随机播放」
        row.addView(quickAction(c, R.drawable.ic_play_solid, "随机播放", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                randomPlay();
            }
        }), Ui.lpWeight(1));
        row.addView(quickAction(c, R.drawable.ic_heart, "我的收藏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.switchTab(MainActivity.TAB_FAVORITES);
            }
        }), Ui.lpWeight(1));
        row.addView(quickAction(c, R.drawable.ic_history, "最近播放", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(Pages.history(act));
            }
        }), Ui.lpWeight(1));
        row.addView(quickAction(c, R.drawable.ic_search, "搜索", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.switchTab(MainActivity.TAB_SEARCH);
            }
        }), Ui.lpWeight(1));

        content.addView(row, Ui.lpMatchWrap());
    }

    /** 圆形图标 + 下方文字的一格 */
    private View quickAction(Theme.Colors c, int iconRes, String label, View.OnClickListener l) {
        LinearLayout item = Ui.column(act);
        item.setGravity(Gravity.CENTER);

        FrameLayout circle = new FrameLayout(act);
        circle.setBackground(Ui.circle(c.surfaceAlt));
        ImageView ic = Ui.icon(act, iconRes, 22, c.accent);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                Ui.dp(act, 22), Ui.dp(act, 22));
        ilp.gravity = Gravity.CENTER;
        circle.addView(ic, ilp);
        item.addView(circle, Ui.lp(Ui.dp(act, 54), Ui.dp(act, 54)));

        TextView tv = Ui.text(act, label, 11.5f, c.textDim);
        tv.setPadding(0, Ui.dp(act, 7), 0, 0);
        item.addView(tv);

        item.setOnClickListener(l);
        Ui.tappable(item, act, c.accent);
        return item;
    }

    /** 随机播放：拉 30 首随机歌曲整体起播 */
    private void randomPlay() {
        lib.randomSongs(30, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                if (value == null || value.isEmpty()) {
                    act.toast("没有可播放的歌曲");
                    return;
                }
                act.playNow(value, 0);
            }

            @Override
            public void fail(String message) {
                act.toast(message);
            }
        });
    }

    // ---------------- 3. 横向卡片行 ----------------

    /** 区块标题 + 区块容器；「更多」进入专辑列表页 */
    private LinearLayout addSection(String title) {
        View header = Ui.sectionHeader(act, title, "更多", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(Pages.albums(act));
            }
        });
        content.addView(header, Ui.lpMatchWrap());

        LinearLayout body = Ui.column(act);
        content.addView(body, Ui.lpMatchWrap());
        return body;
    }

    private void loadNewest(final LinearLayout body, final int g) {
        if (guardConfigured(body)) return;
        setLoading(body);
        lib.newestAlbums(STRIP_SIZE, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                if (g != gen) return;
                showCards(body, value);
            }

            @Override
            public void fail(String message) {
                if (g != gen) return;
                setFail(body, message, new Runnable() {
                    @Override
                    public void run() {
                        loadNewest(body, gen);
                    }
                });
            }
        });
    }

    private void loadFrequent(final LinearLayout body, final int g) {
        if (guardConfigured(body)) return;
        setLoading(body);
        lib.frequentAlbums(STRIP_SIZE, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                if (g != gen) return;
                showCards(body, value);
            }

            @Override
            public void fail(String message) {
                if (g != gen) return;
                setFail(body, message, new Runnable() {
                    @Override
                    public void run() {
                        loadFrequent(body, gen);
                    }
                });
            }
        });
    }

    private void loadRandomAlbums(final LinearLayout body, final int g) {
        if (guardConfigured(body)) return;
        setLoading(body);
        // albums(type, page, pageSize, done)：随机排序取第一页 12 张
        lib.albums("random", 0, STRIP_SIZE, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                if (g != gen) return;
                showCards(body, value);
            }

            @Override
            public void fail(String message) {
                if (g != gen) return;
                setFail(body, message, new Runnable() {
                    @Override
                    public void run() {
                        loadRandomAlbums(body, gen);
                    }
                });
            }
        });
    }

    private void showCards(LinearLayout body, List<Item> items) {
        body.removeAllViews();
        if (items == null || items.isEmpty()) {
            body.addView(Ui.message(act, "这里还没有内容", null, null, null), Ui.lpMatchWrap());
            return;
        }
        Theme.Colors c = Ui.colors(act);

        HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), 0);

        LinearLayout strip = Ui.row(act);
        strip.setGravity(Gravity.TOP);
        for (int i = 0; i < items.size(); i++) {
            strip.addView(albumCard(items.get(i), c));
        }
        hsv.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(hsv, Ui.lpMatchWrap());
    }

    /** 一张专辑卡片：120dp 圆角封面 + 2 行标题 + 1 行副标题 */
    private View albumCard(final Item item, Theme.Colors c) {
        LinearLayout card = Ui.column(act);
        LinearLayout.LayoutParams clp = Ui.lp(Ui.dp(act, CARD_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Ui.dp(act, 12);
        card.setLayoutParams(clp);
        card.setPadding(0, 0, 0, Ui.dp(act, 10));

        CircleCover cover = new CircleCover(act);
        cover.setCornerRadius(Ui.dp(act, 10));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 10)));
        card.addView(cover, Ui.lp(Ui.dp(act, CARD_COVER_DP), Ui.dp(act, CARD_COVER_DP)));
        String url = coverUrl(item);
        if (url.length() > 0) CoverLoader.get(act).load(url, cover, 0);

        TextView title = Ui.text(act, item.title, 13.5f, c.text);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = Ui.lpMatchWrap();
        tlp.topMargin = Ui.dp(act, 8);
        card.addView(title, tlp);

        TextView sub = Ui.text(act, item.subtitle == null ? "" : item.subtitle, 11.5f, c.textDim);
        Ui.ellipsize(sub);
        sub.setPadding(0, Ui.dp(act, 3), 0, 0);
        card.addView(sub);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.openAlbum(item.id);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Menus.album(act, item);
                return true;
            }
        });
        Ui.tappable(card, act, c.text);
        return card;
    }

    // ---------------- 4. 精选歌曲 ----------------

    private void loadFeatured(final LinearLayout body, final int g) {
        if (guardConfigured(body)) return;
        setLoading(body);
        lib.randomSongs(FEATURED_SIZE, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                if (g != gen) return;
                featured.clear();
                if (value != null) featured.addAll(value);
                showSongs(body);
            }

            @Override
            public void fail(String message) {
                if (g != gen) return;
                setFail(body, message, new Runnable() {
                    @Override
                    public void run() {
                        loadFeatured(body, gen);
                    }
                });
            }
        });
    }

    private void showSongs(LinearLayout body) {
        body.removeAllViews();
        if (featured.isEmpty()) {
            body.addView(Ui.message(act, "没有推荐的歌曲", null, null, null), Ui.lpMatchWrap());
            return;
        }
        Theme.Colors c = Ui.colors(act);
        for (int i = 0; i < featured.size(); i++) {
            body.addView(songRow(featured.get(i), i, c), Ui.lpMatchWrap());
        }
    }

    /** 一行歌曲：封面 + 标题/艺术家·专辑 + 时长 + 「⋮」 */
    private View songRow(final Item item, final int index, Theme.Colors c) {
        LinearLayout row = Ui.row(act);
        Ui.pad(row, act, 16, 7, 8, 7);
        row.setMinimumHeight(Ui.dp(act, 62));

        CircleCover cover = new CircleCover(act);
        cover.setCornerRadius(Ui.dp(act, 8));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 8)));
        row.addView(cover, Ui.lp(Ui.dp(act, 46), Ui.dp(act, 46)));
        String url = coverUrl(item);
        if (url.length() > 0) CoverLoader.get(act).load(url, cover, 0);

        LinearLayout mid = Ui.column(act);
        mid.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 8), 0);
        TextView title = Ui.text(act, item.title, 15, c.text);
        Ui.ellipsize(title);
        TextView sub = Ui.text(act, item.subtitle == null ? "" : item.subtitle, 12.5f, c.textDim);
        Ui.ellipsize(sub);
        sub.setPadding(0, Ui.dp(act, 3), 0, 0);
        mid.addView(title);
        mid.addView(sub);
        row.addView(mid, Ui.lpWeight(1));

        TextView dur = Ui.text(act, item.durationText(), 12, c.textFaint);
        dur.setGravity(Gravity.CENTER);
        row.addView(dur);

        ImageView more = Ui.icon(act, R.drawable.ic_more, 20, c.textDim);
        LinearLayout.LayoutParams mlp = Ui.lp(Ui.dp(act, 34), Ui.dp(act, 34));
        mlp.leftMargin = Ui.dp(act, 4);
        row.addView(more, mlp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.playNow(featured, index);
            }
        });
        Ui.tappable(row, act, c.text);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Menus.song(act, item);
            }
        });
        return row;
    }

    // ---------------- 区块状态 ----------------

    private void setLoading(LinearLayout body) {
        body.removeAllViews();
        body.addView(Ui.loading(act), Ui.lpMatchWrap());
    }

    private void setFail(LinearLayout body, String message, final Runnable retry) {
        setMessage(body, "加载失败", message, "重试", new Runnable() {
            @Override
            public void run() {
                if (retry != null) retry.run();
            }
        });
    }

    private void setMessage(LinearLayout body, String title, String detail, String action, final Runnable onAction) {
        body.removeAllViews();
        View v = Ui.message(act, title, detail, action, onAction == null ? null : new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onAction.run();
            }
        });
        body.addView(v, Ui.lpMatchWrap());
    }

    /** 未配置服务器时统一提示，并引导去设置页；返回 true 表示已处理 */
    private boolean guardConfigured(final LinearLayout body) {
        if (lib.client() != null) return false;
        setMessage(body, "尚未配置服务器", "请先在设置里添加音乐服务器", "去设置", new Runnable() {
            @Override
            public void run() {
                act.switchTab(MainActivity.TAB_SETTINGS);
            }
        });
        return true;
    }

    private String coverUrl(Item it) {
        if (it.coverUrl != null && it.coverUrl.length() > 0) return it.coverUrl;
        if (it.coverArt == null || it.coverArt.length() == 0) return "";
        return lib.coverUrl(it.coverArt, 512);
    }
}
