package com.lixscn.subsonicplayer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一媒体条目模型。
 *
 * 歌曲 / 专辑 / 艺术家 / 歌单 / 流派 共用一个类型，好处是列表页、网格页、行渲染、
 * 点击分发这些逻辑只写一份，各页面只需要给出「加载器 + 渲染方式」。
 */
public class Item {

    public static final int SONG = 0;
    public static final int ALBUM = 1;
    public static final int ARTIST = 2;
    public static final int PLAYLIST = 3;
    public static final int GENRE = 4;

    public int kind = SONG;

    public String id = "";
    public String title = "";
    /** 副标题：歌曲 =「艺术家 · 专辑」；专辑 = 艺术家；艺术家 = 「N 张专辑」 */
    public String subtitle = "";

    public String artist = "";
    public String artistId = "";
    public String album = "";
    public String albumId = "";
    /** 封面 id（本服务端形如 al-2804）；空表示无封面 */
    public String coverArt = "";
    /** 封面 URL 直链（服务端给了绝对地址时用，否则由 coverUrl(coverArt) 拼） */
    public String coverUrl = "";

    public String genre = "";
    public int durationSec;
    public int track;
    public int discNumber = 1;
    public int year;
    public int songCount;
    public int rating;
    public int bitrate;
    public long size;
    public long playCount;

    public String suffix = "";
    public String contentType = "";
    public String path = "";

    public boolean starred;
    public String starredAt = "";

    /** 在所属列表中的原始下标（队列跳转、歌单删除定位用） */
    public int listIndex = -1;

    /** 专辑/歌单详情携带的曲目列表（仅详情类接口填充） */
    public List<Item> songs;

    /** 书签续播位置（毫秒） */
    public long positionMs;

    public static Item song() {
        Item it = new Item();
        it.kind = SONG;
        return it;
    }

    public static Item album() {
        Item it = new Item();
        it.kind = ALBUM;
        return it;
    }

    public static Item artist() {
        Item it = new Item();
        it.kind = ARTIST;
        return it;
    }

    public static Item playlist() {
        Item it = new Item();
        it.kind = PLAYLIST;
        return it;
    }

    public static Item genre() {
        Item it = new Item();
        it.kind = GENRE;
        return it;
    }

    /** 时长文本 mm:ss（未知时长返回空串） */
    public String durationText() {
        if (durationSec <= 0) return "";
        int m = durationSec / 60;
        int s = durationSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    /** 专辑/歌单的时长文本：总时长 + 曲目数 */
    public String countText() {
        StringBuilder sb = new StringBuilder();
        if (songCount > 0) sb.append(songCount).append(" 首");
        if (durationSec > 0) {
            if (sb.length() > 0) sb.append(" · ");
            int total = durationSec;
            int h = total / 3600;
            int m = (total % 3600) / 60;
            if (h > 0) sb.append(h).append(" 小时 ").append(m).append(" 分");
            else sb.append(m).append(" 分钟");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Item{" + kind + " " + id + " " + title + "}";
    }

    public static List<Item> list() {
        return new ArrayList<Item>();
    }
}
