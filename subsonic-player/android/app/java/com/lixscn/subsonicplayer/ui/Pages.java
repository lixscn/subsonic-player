package com.lixscn.subsonicplayer.ui;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.ui.pages.LibraryPage;
import com.lixscn.subsonicplayer.ui.pages.PlaceholderPage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 页面工厂：所有页面的创建入口。
 *
 * 好处是页面之间零耦合 —— 跳转只经过 MainActivity 的导航方法与本工厂，
 * 页面类彼此不 import。
 */
public final class Pages {

    private Pages() {
    }

    // ---------------- Tab 页 ----------------

    public static Page tab(MainActivity act, String tabId) {
        if (MainActivity.TAB_LIBRARY.equals(tabId)) return new LibraryPage();
        if (MainActivity.TAB_SETTINGS.equals(tabId)) return new com.lixscn.subsonicplayer.ui.pages.SettingsPage();
        if (MainActivity.TAB_FAVORITES.equals(tabId)) return favorites(act);
        if (MainActivity.TAB_SEARCH.equals(tabId)) return new com.lixscn.subsonicplayer.ui.pages.SearchPage();
        return new com.lixscn.subsonicplayer.ui.pages.DiscoverPage();
    }

    // ---------------- 列表页 ----------------

    /** 专辑（网格 + 排序） */
    public static Page albums(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "专辑";
        cfg.grid = true;
        cfg.columns = 3;
        cfg.pageSize = 30;
        cfg.emptyText = "没有专辑";
        cfg.chips = new ArrayList<ItemListPage.Chip>();
        cfg.chips.add(new ItemListPage.Chip("按艺术家", "alphabeticalByArtist"));
        cfg.chips.add(new ItemListPage.Chip("按名称", "alphabeticalByName"));
        cfg.chips.add(new ItemListPage.Chip("最近添加", "newest"));
        cfg.chips.add(new ItemListPage.Chip("常听", "frequent"));
        cfg.chips.add(new ItemListPage.Chip("随机", "random"));
        cfg.activeChipKey = act.library().settings().librarySort();
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                act.library().albums(cfg.activeChipKey, p, size, done);
            }
        };
        cfg.chipListener = new ItemListPage.ChipListener() {
            @Override
            public void onChip(String key) {
                act.library().settings().setLibrarySort(key);
                page.reload();
            }
        };
        cfg.listener = new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.openAlbum(item.id);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.album(act, item);
            }
        };
        return page;
    }

    /** 艺术家（单次全量 + 字母分组） */
    public static Page artists(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "艺术家";
        cfg.pageSize = 1000;
        cfg.emptyText = "没有艺术家";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                if (p > 0) {
                    done.ok(new ArrayList<Item>());
                    return;
                }
                act.library().artists(done);
            }
        };
        cfg.listener = new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.openArtist(item.id, item.title);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.artist(act, item);
            }
        };
        return page;
    }

    /** 歌单 */
    public static Page playlists(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "歌单";
        cfg.emptyText = "还没有歌单";
        cfg.emptyHint = "在歌曲的「⋮」里可以添加到歌单";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                act.library().playlists(done);
            }
        };
        cfg.listener = new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.openPlaylist(item.id, item.title);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.playlist(act, item);
            }
        };
        return page;
    }

    /** 风格 */
    public static Page genres(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "风格";
        cfg.emptyText = "服务端未提供流派信息";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                act.library().genres(done);
            }
        };
        cfg.listener = new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.openGenre(item.title);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                act.openGenre(item.title);
            }
        };
        return page;
    }

    /** 某风格的歌曲 */
    public static Page genreSongs(final MainActivity act, final String genre) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = genre;
        cfg.pageSize = 50;
        cfg.emptyText = "该风格下没有歌曲";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                act.library().songsByGenre(genre, p, size, done);
            }
        };
        cfg.listener = songListener(act, page);
        return page;
    }

    /** 收藏（歌曲 / 专辑 / 艺术家 三个 chip） */
    public static Page favorites(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "收藏";
        cfg.emptyText = "还没有收藏";
        cfg.emptyHint = "点歌曲行尾的「⋮」可以收藏";
        cfg.chips = new ArrayList<ItemListPage.Chip>();
        cfg.chips.add(new ItemListPage.Chip("歌曲", "songs"));
        cfg.chips.add(new ItemListPage.Chip("专辑", "albums"));
        cfg.chips.add(new ItemListPage.Chip("艺术家", "artists"));
        cfg.activeChipKey = "songs";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                if ("albums".equals(cfg.activeChipKey)) act.library().starredAlbums(done);
                else if ("artists".equals(cfg.activeChipKey)) act.library().starredArtists(done);
                else act.library().starredSongs(done);
            }
        };
        cfg.chipListener = new ItemListPage.ChipListener() {
            @Override
            public void onChip(String key) {
                page.reload();
            }
        };
        cfg.listener = new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                if (item.kind == Item.ARTIST) act.openArtist(item.id, item.title);
                else if (item.kind == Item.ALBUM) act.openAlbum(item.id);
                else act.playNow(page.items(), position);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                if (item.kind == Item.ARTIST) Menus.artist(act, item);
                else if (item.kind == Item.ALBUM) Menus.album(act, item);
                else Menus.song(act, item);
            }
        };
        return page;
    }

    /** 最近播放（本机记录） */
    public static Page history(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "最近播放";
        cfg.pageSize = 200;
        cfg.emptyText = "还没有播放记录";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                if (p > 0) {
                    done.ok(new ArrayList<Item>());
                    return;
                }
                done.ok(localHistory(act));
            }
        };
        cfg.listener = songListener(act, page);
        return page;
    }

    /** 书签 / 续播（本机保存的播放位置） */
    public static Page bookmarks(final MainActivity act) {
        final ItemListPage.Config cfg = new ItemListPage.Config();
        cfg.title = "书签 / 续播";
        cfg.pageSize = 200;
        cfg.emptyText = "还没有书签";
        cfg.emptyHint = "播放时在歌曲「⋮」里选择「保存为续播书签」";
        final ItemListPage page = new ItemListPage(cfg);
        cfg.loader = new ItemListPage.Loader() {
            @Override
            public void load(int p, int size, Library.Done<List<Item>> done) {
                if (p > 0) {
                    done.ok(new ArrayList<Item>());
                    return;
                }
                done.ok(localBookmarks(act));
            }
        };
        cfg.listener = new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                // 从书签位置续播
                Library lib = act.library();
                lib.run(new Library.Work<List<Item>>() {
                    @Override
                    public List<Item> run() throws Exception {
                        Item s = lib.client().getSong(item.id);
                        List<Item> l = new ArrayList<Item>();
                        if (s != null) l.add(s);
                        return l;
                    }
                }, new Library.Done<List<Item>>() {
                    @Override
                    public void ok(List<Item> value) {
                        if (value == null || value.isEmpty()) {
                            act.toast("歌曲已不存在");
                            return;
                        }
                        act.playNow(value, 0);
                        act.player().seekTo((int) item.positionMs);
                    }

                    @Override
                    public void fail(String message) {
                        act.toast(message);
                    }
                });
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.song(act, item);
            }
        };
        return page;
    }

    /** 全部歌曲（渐进式加载，单独实现） */
    public static Page allSongs(final MainActivity act) {
        return new com.lixscn.subsonicplayer.ui.pages.AllSongsPage();
    }

    // ---------------- 详情页 ----------------

    public static Page albumDetail(MainActivity act, String albumId) {
        return com.lixscn.subsonicplayer.ui.pages.DetailPages.album(act, albumId);
    }

    public static Page artistDetail(MainActivity act, String artistId, String name) {
        return com.lixscn.subsonicplayer.ui.pages.DetailPages.artist(act, artistId, name);
    }

    public static Page playlistDetail(MainActivity act, String playlistId, String name) {
        return com.lixscn.subsonicplayer.ui.pages.DetailPages.playlist(act, playlistId, name);
    }

    public static Page nowPlaying(MainActivity act) {
        return new com.lixscn.subsonicplayer.ui.pages.NowPlayingPage();
    }

    /** 全屏播放队列（当前正在播放的歌单） */
    public static Page queue(MainActivity act) {
        return new com.lixscn.subsonicplayer.ui.pages.QueuePage();
    }

    public static Page settings(MainActivity act) {
        return new com.lixscn.subsonicplayer.ui.pages.SettingsPage();
    }

    // ---------------- 复用 ----------------

    /** 歌曲列表点击：在该列表内起播 */
    public static ItemAdapter.Listener songListener(final MainActivity act, final ItemListPage page) {
        return new ItemAdapter.Listener() {
            @Override
            public void onItemClick(Item item, int position) {
                act.playNow(page.items(), position);
            }

            @Override
            public void onItemMore(Item item, int position, View anchor) {
                Menus.song(act, item);
            }
        };
    }

    /** 本机播放历史（MainActivity 记录，最多 100 条） */
    public static List<Item> localHistory(MainActivity act) {
        List<Item> out = new ArrayList<Item>();
        try {
            SharedPreferences sp = act.getSharedPreferences("sp_history", 0);
            JSONArray arr = new JSONArray(sp.getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(songFromJson(o, 0));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机书签 */
    public static List<Item> localBookmarks(MainActivity act) {
        List<Item> out = new ArrayList<Item>();
        try {
            SharedPreferences sp = act.getSharedPreferences("sp_history", 0);
            JSONArray arr = new JSONArray(sp.getString("bookmarks", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(songFromJson(o, o.optLong("position", 0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static Item songFromJson(JSONObject o, long positionMs) {
        Item it = Item.song();
        it.id = o.optString("id", "");
        it.title = o.optString("title", "");
        it.artist = o.optString("artist", "");
        it.artistId = o.optString("artistId", "");
        it.album = o.optString("album", "");
        it.albumId = o.optString("albumId", "");
        it.coverArt = o.optString("coverArt", "");
        it.durationSec = o.optInt("duration", 0);
        // 历史/书签里也存了格式信息（服务端对 APE/DSD 只给 contentType 不给 suffix）
        it.suffix = o.optString("suffix", "");
        it.contentType = o.optString("contentType", "");
        it.bitrate = o.optInt("bitrate", 0);
        it.starred = o.optBoolean("starred", false);
        it.positionMs = positionMs;
        StringBuilder sb = new StringBuilder();
        if (it.artist.length() > 0) sb.append(it.artist);
        if (it.album.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(it.album);
        }
        it.subtitle = sb.toString();
        return it;
    }

}
