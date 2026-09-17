package com.lixscn.subsonicplayer.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用列表/网格页：专辑、艺术家、歌单、流派、收藏、历史、书签等都复用它。
 *
 * 分页策略与桌面版一致（服务端 size/offset），但移动端改为**滚动到底自动加载下一页**，
 * 并在列表尾部显示「加载中 / 没有更多了 / 点击重试」。
 */
public class ItemListPage extends Page {

    /** 数据加载器：page 从 0 开始 */
    public interface Loader {
        void load(int page, int pageSize, Library.Done<List<Item>> done);
    }

    /** 排序/筛选 chip */
    public static class Chip {
        public final String label;
        public final String key;

        public Chip(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    /** 页面配置 */
    public static class Config {
        public String title = "";
        public String subtitle = "";
        public Loader loader;
        public int pageSize = 50;
        public boolean grid;
        public int columns = 3;
        public String emptyText = "这里还没有内容";
        public String emptyHint = "";
        public List<Chip> chips;
        public String activeChipKey;
        /** chip 点击回调：由调用方负责换 loader 并调 reload() */
        public ChipListener chipListener;
        public ItemAdapter.Listener listener;
    }

    public interface ChipListener {
        void onChip(String key);
    }

    protected final Config config;
    protected LinearLayout rootView;
    protected LinearLayout chipBar;
    protected FrameLayout listHolder;
    protected AbsListView listView;
    protected ItemAdapter adapter;
    protected final List<Item> items = new ArrayList<Item>();
    protected LinearLayout footer;
    protected TextView footerText;
    protected TextView overlay;

    private int page;
    private boolean loading;
    private boolean noMore;
    private String error;
    /** 页面已销毁：此后到达的异步回调必须直接丢弃（否则会 NPE 闪退） */
    private boolean destroyed;

    /** 页面仍可安全更新 UI */
    private boolean alive() {
        return !destroyed && adapter != null && listHolder != null;
    }

    public ItemListPage(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    @Override
    public String title() {
        return config.title;
    }

    @Override
    public String subtitle() {
        return config.subtitle;
    }

    @Override
    protected View build() {
        Theme.Colors c = Ui.colors(act);
        rootView = Ui.column(act);
        rootView.setBackgroundColor(c.bg);

        if (config.chips != null && !config.chips.isEmpty()) {
            chipBar = Ui.chipRow(act);
            rootView.addView(chipBar, Ui.lpMatchWrap());
            renderChips();
        }

        listHolder = new FrameLayout(act);
        rootView.addView(listHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildList();
        reload();
        return rootView;
    }

    private void buildList() {
        if (config.grid) {
            GridView gv = new GridView(act);
            gv.setNumColumns(config.columns);
            gv.setHorizontalSpacing(Ui.dp(act, 2));
            gv.setVerticalSpacing(Ui.dp(act, 8));
            gv.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));
            gv.setClipToPadding(false);
            gv.setVerticalScrollBarEnabled(false);
            listView = gv;
        } else {
            ListView lv = new ListView(act);
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 8));
            lv.setClipToPadding(false);
            lv.setVerticalScrollBarEnabled(false);
            listView = lv;
        }
        // 去掉系统默认的点击高亮（蓝色块），点击反馈交给行内的 ripple
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new ItemAdapter(act, items, config.grid ? ItemAdapter.MODE_GRID : ItemAdapter.MODE_LIST,
                config.columns, config.listener);
        listView.setAdapter(adapter);
        // 自动切歌时也要刷新「正在播放」高亮（行高亮只在 getView 里算，没人通知就一直是旧的）
        watchPlaying(new Runnable() {
            @Override
            public void run() {
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });

        footer = Ui.row(act);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Ui.dp(act, 18), 0, Ui.dp(act, 26));
        footerText = Ui.text(act, "", 13, Ui.colors(act).textFaint);
        footer.addView(footerText);
        footer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (error != null) reload();
                else loadNext();
            }
        });
        if (listView instanceof ListView) {
            // GridView 不支持 header/footer，网格模式改用底部浮层提示
            ((ListView) listView).addFooterView(footer, null, false);
        } else {
            overlay = Ui.text(act, "", 12, Ui.colors(act).textDim);
            overlay.setBackground(Ui.rect(Ui.alpha(Ui.colors(act).surface, 0.92f), Ui.dp(act, 14)));
            overlay.setPadding(Ui.dp(act, 14), Ui.dp(act, 7), Ui.dp(act, 14), Ui.dp(act, 7));
            overlay.setVisibility(View.GONE);
            FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            olp.bottomMargin = Ui.dp(act, 14);
            listHolder.addView(overlay, olp);
        }

        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount == 0) return;
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 4) {
                    loadNext();
                }
            }
        });
        listHolder.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }


    private void renderChips() {
        chipBar.removeAllViews();
        for (final Chip chip : config.chips) {
            boolean active = chip.key.equals(config.activeChipKey);
            View v = Ui.chip(act, chip.label, active, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (config.chipListener != null) {
                        config.activeChipKey = chip.key;
                        config.chipListener.onChip(chip.key);
                        renderChips();
                    }
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 8);
            chipBar.addView(v, lp);
        }
    }

    /** 重新从第一页加载 */
    public void reload() {
        if (!alive()) return;
        page = 0;
        noMore = false;
        error = null;
        items.clear();
        adapter.notifyDataSetChanged();
        showState(null);
        loadNext();
    }

    /** 加载下一页 */
    public void loadNext() {
        if (loading || noMore || config.loader == null || !alive()) return;
        loading = true;
        // 首次加载（列表还空着）时给出居中转圈：外网慢时不然就是一片空白
        if (items.isEmpty()) showState("loading");
        updateFooter();
        final int requestPage = page;
        config.loader.load(requestPage, config.pageSize, new Library.Done<List<Item>>() {
            @Override
            public void ok(List<Item> value) {
                // 页面可能已被销毁/重建（网络慢时「点进去又马上返回」很常见）——必须先判断
                if (!alive()) return;
                loading = false;
                if (value == null || value.isEmpty()) {
                    noMore = true;
                    if (items.isEmpty()) showState("empty");
                } else {
                    items.addAll(value);
                    if (value.size() < config.pageSize) noMore = true;
                    page = requestPage + 1;
                    adapter.notifyDataSetChanged();
                    showState(null);
                }
                updateFooter();
            }

            @Override
            public void fail(String message) {
                if (!alive()) return;
                loading = false;
                error = message;
                if (items.isEmpty()) showState("error");
                updateFooter();
            }
        });
    }

    private void updateFooter() {
        String msg;
        if (loading) msg = "加载中…";
        else if (error != null) msg = "加载失败：" + error + "（点击重试）";
        else if (noMore) msg = "— 没有更多了 —";
        else msg = "上拉加载更多";

        if (listView instanceof ListView) {
            if (footer == null) return;
            if (items.isEmpty()) {
                footer.setVisibility(View.GONE);
                return;
            }
            footer.setVisibility(View.VISIBLE);
            footerText.setText(msg);
        } else if (overlay != null) {
            // 网格模式：只在加载中/出错时显示浮层，避免挡住内容
            if (loading || error != null) {
                overlay.setVisibility(View.VISIBLE);
                overlay.setText(msg);
            } else {
                overlay.setVisibility(View.GONE);
            }
        }
    }

    /** 在列表上方/位置显示空态、错误态 */
    protected void showState(String kind) {
        if (listHolder == null) return;
        // 移除除列表与浮层以外的状态视图
        for (int i = listHolder.getChildCount() - 1; i >= 0; i--) {
            View child = listHolder.getChildAt(i);
            if (child != listView && child != overlay) listHolder.removeViewAt(i);
        }
        if (kind == null) return;
        View v;
        if ("loading".equals(kind)) {
            // 首次加载：居中转圈（外网慢时不能是一片空白）
            v = Ui.loading(act);
        } else if ("empty".equals(kind)) {
            v = Ui.message(act, config.emptyText, config.emptyHint, null, null);
        } else {
            v = Ui.message(act, "加载失败", error == null ? "" : error, "重试", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    reload();
                }
            });
        }
        listHolder.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    public List<Item> items() {
        return items;
    }

    /** 主题切换后重建（保留已加载数据） */
    @Override
    public void rebuild() {
        List<Item> keep = new ArrayList<Item>(items);
        super.rebuild();
        destroyed = false;   // rebuild 会重新 build()，页面重新可用
        // build() 会重新 reload；这里改为把数据塞回去，避免闪烁
        items.clear();
        items.addAll(keep);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        unwatchPlaying();
        adapter = null;
    }
}
