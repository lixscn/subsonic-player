package com.lixscn.subsonicplayer.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lixscn.subsonicplayer.MainActivity;
import com.lixscn.subsonicplayer.core.Item;
import com.lixscn.subsonicplayer.core.Library;
import com.lixscn.subsonicplayer.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 条目操作菜单（歌曲/专辑/艺术家/歌单的「⋮」与长按）。
 *
 * 媒体管理（收藏、评分、加入歌单、队列编辑）全部收敛在这里，
 * 各页面只要在 onItemMore 里调用对应方法即可。
 */
public final class Menus {

    private Menus() {
    }


    private static void show(final MainActivity act, String title, final String[] items, final Handler h) {
        AlertDialog.Builder b = Ui.dialog(act);
        if (title != null && title.length() > 0) b.setTitle(title);
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
                h.onPick(which);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private interface Handler {
        void onPick(int index);
    }

    // ---------------- 歌曲 ----------------

    public static void song(final MainActivity act, final Item song) {
        final Player player = Player.get(act);
        final Library lib = Library.get(act);
        boolean inQueue = player.queue().contains(song);
        List<String> opts = new ArrayList<String>();
        opts.add("立即播放");
        opts.add("下一首播放");
        opts.add("加入播放队列");
        opts.add(song.starred ? "取消收藏" : "收藏");
        opts.add("评分");
        opts.add("添加到歌单");
        opts.add("查看专辑");
        opts.add("查看艺术家");
        opts.add("保存为续播书签");
        opts.add("复制播放链接");
        if (inQueue) opts.add("从队列移除");

        final String[] items = opts.toArray(new String[0]);
        show(act, song.title, items, new Handler() {
            @Override
            public void onPick(int i) {
                String label = items[i];
                if ("立即播放".equals(label)) {
                    act.playNow(single(song));
                } else if ("下一首播放".equals(label)) {
                    player.playNext(single(song));
                    act.toast("已加入下一首");
                } else if ("加入播放队列".equals(label)) {
                    player.append(single(song));
                    act.toast("已加入队列");
                } else if ("收藏".equals(label) || "取消收藏".equals(label)) {
                    toggleStar(act, song);
                } else if ("评分".equals(label)) {
                    rating(act, song);
                } else if ("添加到歌单".equals(label)) {
                    addToPlaylist(act, song);
                } else if ("查看专辑".equals(label)) {
                    act.openAlbum(song.albumId);
                } else if ("查看艺术家".equals(label)) {
                    act.openArtist(song.artistId);
                } else if ("保存为续播书签".equals(label)) {
                    int pos = player.current() != null && player.current().id.equals(song.id)
                            ? player.positionMs() : 0;
                    player.saveBookmark(song, pos);
                    act.toast(pos > 0 ? "已保存书签" : "已保存书签（从头开始）");
                    act.bookmarkChanged();
                } else if ("复制播放链接".equals(label)) {
                    copy(act, lib.streamUrl(song.id));
                } else if ("从队列移除".equals(label)) {
                    int idx = player.queue().indexOf(song);
                    if (idx >= 0) player.removeAt(idx);
                }
            }
        });
    }

    private static List<Item> single(Item it) {
        List<Item> l = new ArrayList<Item>();
        l.add(it);
        return l;
    }

    // ---------------- 专辑 ----------------

    public static void album(final MainActivity act, final Item album) {
        String[] items = {"立即播放", "下一首播放", "加入播放队列", album.starred ? "取消收藏" : "收藏",
                "查看艺术家", "复制分享链接"};
        show(act, album.title, items, new Handler() {
            @Override
            public void onPick(int i) {
                switch (i) {
                    case 0:
                        act.playAlbum(album.id, false);
                        break;
                    case 1:
                        act.playAlbum(album.id, true);
                        break;
                    case 2:
                        act.queueAlbum(album.id);
                        break;
                    case 3:
                        toggleStar(act, album);
                        break;
                    case 4:
                        act.openArtist(album.artistId);
                        break;
                    default:
                        copy(act, act.library().coverUrl(album.coverArt, 300));
                        break;
                }
            }
        });
    }

    // ---------------- 艺术家 ----------------

    public static void artist(final MainActivity act, final Item artist) {
        String[] items = {"查看专辑", "查看歌曲", "随机播放该艺术家", artist.starred ? "取消收藏" : "收藏"};
        show(act, artist.title, items, new Handler() {
            @Override
            public void onPick(int i) {
                switch (i) {
                    case 0:
                    case 1:
                        act.openArtist(artist.id);
                        break;
                    case 2:
                        act.shuffleArtist(artist.id);
                        break;
                    default:
                        toggleStar(act, artist);
                        break;
                }
            }
        });
    }

    // ---------------- 歌单 ----------------

    public static void playlist(final MainActivity act, final Item pl) {
        String[] items = {"播放", "下一首播放", "重命名", "删除歌单"};
        show(act, pl.title, items, new Handler() {
            @Override
            public void onPick(int i) {
                switch (i) {
                    case 0:
                        act.playPlaylist(pl.id, false);
                        break;
                    case 1:
                        act.playPlaylist(pl.id, true);
                        break;
                    case 2:
                        Ui.input(act, "重命名歌单", "歌单名称", pl.title, new Ui.OnInput() {
                            @Override
                            public void onInput(final String text) {
                                if (text == null || text.trim().length() == 0) return;
                                Library.get(act).run(new Library.Work<Boolean>() {
                                    @Override
                                    public Boolean run() throws Exception {
                                        Library.get(act).client().renamePlaylist(pl.id, text.trim());
                                        return Boolean.TRUE;
                                    }
                                }, new Library.Done<Boolean>() {
                                    @Override
                                    public void ok(Boolean v) {
                                        act.toast("已重命名");
                                        act.refreshCurrent();
                                    }

                                    @Override
                                    public void fail(String m) {
                                        act.toast(m);
                                    }
                                });
                            }
                        });
                        break;
                    default:
                        Ui.confirm(act, "删除歌单", "确定删除「" + pl.title + "」？该操作不可撤销。", "删除", new Runnable() {
                            @Override
                            public void run() {
                                Library.get(act).deletePlaylist(pl.id, new Library.Done<Boolean>() {
                                    @Override
                                    public void ok(Boolean v) {
                                        act.toast("已删除");
                                        act.pop();
                                    }

                                    @Override
                                    public void fail(String m) {
                                        act.toast(m);
                                    }
                                });
                            }
                        });
                        break;
                }
            }
        });
    }

    // ---------------- 复用操作 ----------------

    public static void toggleStar(final MainActivity act, final Item it) {
        final boolean target = !it.starred;
        Library.get(act).setStar(it.id, target, new Library.Done<Boolean>() {
            @Override
            public void ok(Boolean v) {
                it.starred = target;
                act.toast(target ? "已收藏" : "已取消收藏");
                act.favoriteChanged();
            }

            @Override
            public void fail(String m) {
                act.toast(m);
            }
        });
    }

    public static void rating(final MainActivity act, final Item song) {
        final String[] labels = {"0 星（清除）", "1 星", "2 星", "3 星", "4 星", "5 星"};
        show(act, "评分：" + song.title, labels, new Handler() {
            @Override
            public void onPick(int i) {
                Library.get(act).setRating(song.id, i, new Library.Done<Boolean>() {
                    @Override
                    public void ok(Boolean v) {
                        act.toast("已评分");
                    }

                    @Override
                    public void fail(String m) {
                        act.toast(m);
                    }
                });
            }
        });
    }

    /** 添加到歌单：列出已有歌单 + 新建 */
    public static void addToPlaylist(final MainActivity act, final Item song) {
        final Library lib = Library.get(act);
        lib.playlists(new Library.Done<List<Item>>() {
            @Override
            public void ok(final List<Item> pls) {
                final String[] items = new String[pls.size() + 1];
                items[0] = "＋ 新建歌单…";
                for (int i = 0; i < pls.size(); i++) {
                    items[i + 1] = pls.get(i).title + "（" + pls.get(i).songCount + " 首）";
                }
                show(act, "添加到歌单", items, new Handler() {
                    @Override
                    public void onPick(int i) {
                        if (i == 0) {
                            Ui.input(act, "新建歌单", "歌单名称", "", new Ui.OnInput() {
                                @Override
                                public void onInput(String text) {
                                    if (text == null || text.trim().length() == 0) return;
                                    List<String> ids = new ArrayList<String>();
                                    ids.add(song.id);
                                    lib.createPlaylist(text.trim(), ids, new Library.Done<Item>() {
                                        @Override
                                        public void ok(Item v) {
                                            act.toast("已创建并添加");
                                        }

                                        @Override
                                        public void fail(String m) {
                                            act.toast(m);
                                        }
                                    });
                                }
                            });
                        } else {
                            final Item pl = pls.get(i - 1);
                            List<String> ids = new ArrayList<String>();
                            ids.add(song.id);
                            lib.addToPlaylist(pl.id, ids, new Library.Done<Boolean>() {
                                @Override
                                public void ok(Boolean v) {
                                    act.toast("已添加到「" + pl.title + "」");
                                }

                                @Override
                                public void fail(String m) {
                                    act.toast(m);
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void fail(String message) {
                act.toast(message);
            }
        });
    }


    public static void copy(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("subsonic", text));
            Ui.toast(ctx, "已复制");
        } catch (Exception e) {
            Ui.toast(ctx, "复制失败");
        }
    }
}
