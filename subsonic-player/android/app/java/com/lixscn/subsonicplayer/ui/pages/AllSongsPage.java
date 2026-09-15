package com.lixscn.subsonicplayer.ui.pages;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.ui.ItemAdapter;
import com.lixscn.subsonicplayer.ui.Menus;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 全部歌曲页。
 *
 * 本服务端没有「所有歌曲」端点，只能按专辑展开；这里做成**渐进式加载**：
 * 后台按专辑分页取曲目，取到一批就立即插入列表，用户可以边看边滚，
 * 页尾显示「已加载 N / M 张专辑」的进度。
 */
public class AllSongsPage extends Page {

    private final List<Item> items = new ArrayList<Item>();
    private ItemAdapter adapter;
    private LinearLayout footer;
    private TextView footerText;
    private TextView headerText;
    private Library.Task task;

    @Override
    public String title() {
        return "全部歌曲";
    }

    @Override
    protected View build() {
        Theme.Colors c = Ui.colors(act);
        LinearLayout root = Ui.column(act);
        root.setBackgroundColor(c.bg);

        headerText = Ui.text(act, "正在读取专辑列表…", 12, c.textDim);
        headerText.setPadding(Ui.dp(act, 16), Ui.dp(act, 6), Ui.dp(act, 16), Ui.dp(act, 8));
        root.addView(headerText);

        ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        lv.setPadding(0, 0, 0, Ui.dp(act, 8));
        lv.setClipToPadding(false);
        lv.setVerticalScrollBarEnabled(false);
        adapter = new ItemAdapter(act, items, ItemAdapter.MODE_LIST, new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.playNow(items, position);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.song(act, item);
            }
        });
        lv.setAdapter(adapter);

        footer = Ui.row(act);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Ui.dp(act, 18), 0, Ui.dp(act, 28));
        footerText = Ui.text(act, "加载中…", 13, c.textFaint);
        footer.addView(footerText);
        lv.addFooterView(footer, null, false);

        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        startLoading();
        return root;
    }

    private void startLoading() {
        task = lib.loadAllSongs(new Library.SongStream() {
            @Override
            public void onBatch(List<Item> songs, int loadedAlbums, int totalAlbums) {
                items.addAll(songs);
                adapter.notifyDataSetChanged();
                updateHeader(loadedAlbums, totalAlbums);
            }

            @Override
            public void onDone(int totalSongs) {
                footerText.setText("— 共 " + totalSongs + " 首 —");
                headerText.setText("共 " + totalSongs + " 首歌曲");
            }

            @Override
            public void onError(String message) {
                footerText.setText("加载中断：" + message);
            }
        });
    }

    private void updateHeader(int loadedAlbums, int totalAlbums) {
        if (totalAlbums > 0) {
            headerText.setText("已展开 " + loadedAlbums + " / " + totalAlbums + " 张专辑 · 已加载 "
                    + items.size() + " 首");
        } else {
            headerText.setText("已展开 " + loadedAlbums + " 张专辑 · 已加载 " + items.size() + " 首");
        }
        footerText.setText("继续加载中…");
    }

    @Override
    public void onDestroy() {
        if (task != null) task.cancel();
    }
}
