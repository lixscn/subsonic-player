package com.lixscn.subsonicplayer.ui.pages;

import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.player.Player;
import com.lixscn.subsonicplayer.ui.ItemAdapter;
import com.lixscn.subsonicplayer.ui.Menus;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏「播放队列」页（正在播放的歌单）。
 *
 * 播放页下半区的队列区受屏幕高度限制，只能当预览用；这里给一个完整的列表页：
 * 当前曲目标注「正在播放」、点击切歌、每行可移除 / 上下移动 / 下一首播放，另有随机、清空、存为歌单。
 */
public class QueuePage extends Page implements Player.Listener {

    private final List<Item> items = new ArrayList<Item>();      // 展示用（副本，可安全改副标题）
    private final List<Item> originals = new ArrayList<Item>();  // 队列里的原始对象（保持引用同一性）
    private ItemAdapter adapter;
    private ListView list;
    private TextView countText;
    private View emptyView;
    /** 页面已销毁：异步回调不得再动 UI */
    private boolean destroyed;

    @Override
    public String title() {
        return "播放队列";
    }

    @Override
    public boolean showMiniPlayer() {
        return false;   // 本页就是队列，不需要再叠一个迷你条
    }

    @Override
    protected View build() {
        Theme.Colors c = Ui.colors(act);
        LinearLayout root = Ui.column(act);
        root.setBackgroundColor(c.bg);

        // 头部：共 N 首 + 随机 / 存为歌单 / 清空
        LinearLayout head = Ui.row(act);
        Ui.pad(head, act, 16, 10, 8, 10);
        countText = Ui.text(act, "", 13.5f, c.textDim);
        head.addView(countText, Ui.lpWeight(1));
        head.addView(textButton("随机", c, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.shuffleQueue();
                sync();
                act.toast("已打乱队列");
            }
        }));
        head.addView(textButton("存为歌单", c, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAsPlaylist();
            }
        }));
        head.addView(iconButton(R.drawable.ic_trash, c.danger, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player.queue().isEmpty()) return;
                Ui.confirm(act, "清空播放队列", "只会清空当前播放队列，不会删除服务器上的歌曲。", "清空", new Runnable() {
                    @Override
                    public void run() {
                        player.clearQueue();
                        sync();
                    }
                });
            }
        }));
        root.addView(head, Ui.lpMatchWrap());

        // 列表 + 空态
        FrameLayout holder = new FrameLayout(act);
        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));   // 清掉系统默认高亮，点击反馈用行内涟漪
        list.setPadding(0, 0, 0, Ui.dp(act, 10));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new ItemAdapter(act, items, ItemAdapter.MODE_LIST, new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                player.playAt(position);   // 切歌
                sync();
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                rowMenu(position);
            }
        });
        list.setAdapter(adapter);
        holder.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = Ui.message(act, "播放队列是空的", "去曲库挑几首歌，点一下就会进到这里", null, null);
        emptyView.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        holder.addView(emptyView, elp);

        root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        player.addListener(this);
        sync();
        return root;
    }

    // ---------------- 行菜单 ----------------

    private void rowMenu(final int position) {
        final List<Item> q = player.queue();
        if (position < 0 || position >= q.size()) return;
        final Item song = q.get(position);
        final boolean canUp = position > 0;
        final boolean canDown = position < q.size() - 1;
        List<String> opts = new ArrayList<String>();
        opts.add("立即播放");
        opts.add("从队列移除");
        if (canUp) opts.add("上移");
        if (canDown) opts.add("下移");
        opts.add("收藏 / 更多操作…");
        final String[] arr = opts.toArray(new String[0]);
        Ui.dialog(act).setTitle(song.title).setItems(arr, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                d.dismiss();
                String label = arr[which];
                if ("立即播放".equals(label)) {
                    player.playAt(position);
                } else if ("从队列移除".equals(label)) {
                    player.removeAt(position);
                    act.toast("已从队列移除");
                } else if ("上移".equals(label)) {
                    player.move(position, position - 1);
                } else if ("下移".equals(label)) {
                    player.move(position, position + 1);
                } else {
                    Menus.song(act, song);   // 传队列里的原始对象，保证「从队列移除」等按引用判断的逻辑正确
                }
                sync();
            }
        }).setNegativeButton("取消", null).show();
    }

    // ---------------- 存为歌单 ----------------

    private void saveAsPlaylist() {
        final List<Item> q = player.queue();
        if (q.isEmpty()) {
            act.toast("队列是空的");
            return;
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
        String def = "播放队列 " + fmt.format(new java.util.Date());
        Ui.input(act, "存为歌单（" + q.size() + " 首）", "歌单名称", def, new Ui.OnInput() {
            @Override
            public void onInput(String text) {
                if (text == null || text.trim().length() == 0) return;
                final List<String> ids = new ArrayList<String>();
                for (Item it : q) ids.add(it.id);
                lib.createPlaylist(text.trim(), ids, new Library.Done<Item>() {
                    @Override
                    public void ok(Item value) {
                        act.toast("已保存歌单「" + (value == null ? text.trim() : value.title) + "」（" + ids.size() + " 首）");
                    }

                    @Override
                    public void fail(String message) {
                        act.toast(message);
                    }
                });
            }
        });
    }

    // ---------------- 列表同步 ----------------

    private void sync() {
        if (destroyed || adapter == null || countText == null) return;
        List<Item> q = player.queue();
        Item cur = player.current();
        String curId = cur == null ? "" : cur.id;
        items.clear();
        originals.clear();
        for (int i = 0; i < q.size(); i++) {
            Item s = q.get(i);
            originals.add(s);
            Item c = copyOf(s);
            StringBuilder sb = new StringBuilder(c.subtitle == null ? "" : c.subtitle);
            if (s.id.equals(curId)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("正在播放");
            }
            c.subtitle = sb.toString();
            items.add(c);
        }
        if (countText != null) countText.setText("共 " + q.size() + " 首");
        if (emptyView != null) emptyView.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        if (list != null) list.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    /** 列表展示用副本：避免把「正在播放」这类标记写到 Player 持有的对象上（会影响迷你条等其它界面） */
    private static Item copyOf(Item s) {
        Item c = Item.song();
        c.kind = s.kind;
        c.id = s.id;
        c.title = s.title;
        c.subtitle = s.subtitle;
        c.artist = s.artist;
        c.artistId = s.artistId;
        c.album = s.album;
        c.albumId = s.albumId;
        c.coverArt = s.coverArt;
        c.coverUrl = s.coverUrl;
        c.durationSec = s.durationSec;
        c.track = s.track;
        c.starred = s.starred;
        c.listIndex = s.listIndex;
        return c;
    }

    // ---------------- 控件工厂 ----------------

    private View textButton(String label, Theme.Colors c, View.OnClickListener l) {
        TextView tv = Ui.text(act, label, 13.5f, c.accent);
        tv.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        tv.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 16)));
        LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(act, 8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        Ui.tappable(tv, act, c.accent, 16f);
        return tv;
    }

    private View iconButton(int res, int tint, View.OnClickListener l) {
        FrameLayout fl = new FrameLayout(act);
        LinearLayout.LayoutParams lp = Ui.lp(Ui.dp(act, 40), Ui.dp(act, 40));
        lp.leftMargin = Ui.dp(act, 4);
        fl.setLayoutParams(lp);
        android.widget.ImageView iv = Ui.icon(act, res, 20, tint);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(act, 20), Ui.dp(act, 20));
        ilp.gravity = Gravity.CENTER;
        fl.addView(iv, ilp);
        fl.setOnClickListener(l);
        Ui.tappable(fl, act, tint, 100f);
        return fl;
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onResume() {
        sync();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        player.removeListener(this);
    }

    // ---------------- Player.Listener ----------------

    @Override
    public void onTrackChanged(Item song) {
        sync();
    }

    @Override
    public void onProgress(boolean playing, int positionMs, int durationMs) {
        // 队列页不跟随进度刷新（避免每 500ms 重建列表）
    }

    @Override
    public void onQueueChanged() {
        sync();
    }

    @Override
    public void onModeChanged(int mode) {
    }

    @Override
    public void onPlaybackError(String message) {
    }
}
