package com.lixscn.subsonicplayer.ui.pages;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lixscn.subsonicplayer.R;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.ui.ItemListPage;
import com.lixscn.subsonicplayer.ui.Page;
import com.lixscn.subsonicplayer.ui.Pages;
import com.lixscn.subsonicplayer.ui.Theme;
import com.lixscn.subsonicplayer.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 曲库页：以分组列表形式进入各分类（专辑 / 艺术家 / 歌曲 / 歌单 / 风格 / 历史 / 书签）。
 *
 * 手机竖屏放不下桌面版的 11 个侧边入口，所以收敛到「底部 5 个 Tab + 曲库二级入口」。
 */
public class LibraryPage extends Page {

    private static class Entry {
        int icon;
        String label;
        String hint;
        Runnable action;

        Entry(int icon, String label, String hint, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.hint = hint;
            this.action = action;
        }
    }

    @Override
    public String title() {
        return "曲库";
    }

    @Override
    protected View build() {
        Theme.Colors c = Ui.colors(act);
        LinearLayout root = Ui.column(act);
        root.setBackgroundColor(c.bg);

        // 服务器概况
        final LinearLayout head = Ui.column(act);
        Ui.pad(head, act, 16, 8, 16, 12);
        final TextView serverName = Ui.bold(act, "", 15, c.text);
        final TextView serverStat = Ui.text(act, "", 12, c.textDim);
        serverStat.setPadding(0, Ui.dp(act, 3), 0, 0);
        head.addView(serverName);
        head.addView(serverStat);
        root.addView(head);
        loadServerStat(serverName, serverStat);

        final List<Entry> entries = new ArrayList<Entry>();
        entries.add(new Entry(R.drawable.ic_album, "专辑", "全部专辑，按封面浏览", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.albums(act));
            }
        }));
        entries.add(new Entry(R.drawable.ic_artist, "艺术家", "按艺术家浏览", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.artists(act));
            }
        }));
        entries.add(new Entry(R.drawable.ic_song, "歌曲", "全部歌曲（按专辑展开）", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.allSongs(act));
            }
        }));
        entries.add(new Entry(R.drawable.ic_playlist, "歌单", "服务端歌单", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.playlists(act));
            }
        }));
        entries.add(new Entry(R.drawable.ic_genre, "风格", "按流派浏览", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.genres(act));
            }
        }));
        entries.add(new Entry(R.drawable.ic_history, "最近播放", "本机播放记录", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.history(act));
            }
        }));
        entries.add(new Entry(R.drawable.ic_bookmark, "书签 / 续播", "上次听到的位置", new Runnable() {
            @Override
            public void run() {
                act.push(Pages.bookmarks(act));
            }
        }));

        for (Entry e : entries) {
            root.addView(row(e, c));
        }
        return root;
    }

    private View row(final Entry e, Theme.Colors c) {
        LinearLayout ll = Ui.row(act);
        Ui.pad(ll, act, 16, 14, 16, 14);
        ll.setBackgroundColor(c.bg);

        LinearLayout iconBox = Ui.row(act);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(Ui.rect(c.surfaceAlt, Ui.dp(act, 12)));
        ImageView ic = Ui.icon(act, e.icon, 22, c.accent);
        iconBox.addView(ic);
        ll.addView(iconBox, Ui.lp(Ui.dp(act, 42), Ui.dp(act, 42)));

        LinearLayout mid = Ui.column(act);
        mid.setPadding(Ui.dp(act, 14), 0, 0, 0);
        TextView label = Ui.text(act, e.label, 15.5f, c.text);
        TextView hint = Ui.text(act, e.hint, 12, c.textDim);
        hint.setPadding(0, Ui.dp(act, 3), 0, 0);
        mid.addView(label);
        mid.addView(hint);
        ll.addView(mid, Ui.lpWeight(1));

        ll.addView(Ui.icon(act, R.drawable.ic_chevron, 18, c.textFaint),
                Ui.lp(Ui.dp(act, 20), Ui.dp(act, 20)));
        ll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.action.run();
            }
        });
        Ui.tappable(ll, act, c.text);
        return ll;
    }

    private void loadServerStat(final TextView name, final TextView stat) {
        com.lixscn.subsonicplayer.core.Settings.Service svc = lib.settings().currentService();
        name.setText(svc == null ? "未配置服务器" : svc.displayName());
        if (svc == null) {
            stat.setText("点击右下角「设置」添加服务器");
            return;
        }
        stat.setText("正在读取曲库概况…");
        lib.run(new Library.Work<String>() {
            @Override
            public String run() {
                if (lib.client() == null) return "";
                org.json.JSONObject st = lib.client().getScanStatus();
                if (st == null) return "";
                // 本服务端用 count 表示歌曲总数（标准字段是 songCount），两个都兼容
                int songs = st.optInt("songCount", 0);
                if (songs == 0) songs = st.optInt("count", 0);
                return songs + " 首歌曲 · "
                        + st.optInt("albumCount", 0) + " 张专辑 · "
                        + st.optInt("artistCount", 0) + " 位艺术家";
            }
        }, new Library.Done<String>() {
            @Override
            public void ok(String value) {
                stat.setText(value == null || value.length() == 0
                        ? lib.connectedUrl().replaceFirst("^https?://", "") : value);
            }

            @Override
            public void fail(String message) {
                stat.setText(message);
            }
        });
    }
}
