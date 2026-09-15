package com.lixscn.subsonicplayer.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份歌词数据：要么是同步歌词（{@link #lines} 非空，带时间轴），
 * 要么是纯文本歌词（{@link #plainText} 非空）。
 *
 * <p>纯 Java 实现，只依赖 Android Framework / Java 8 API。
 */
public class Lyrics {

    /** 一行同步歌词。 */
    public static class Line {
        /** 该行开始秒数（无时间轴时为 0）。 */
        public double startSec;
        /** 该行文本（可能为空行）。 */
        public String text;

        public Line(double startSec, String text) {
            this.startSec = startSec;
            this.text = text == null ? "" : text;
        }

        @Override
        public String toString() {
            return "Line{" + startSec + "s, " + text + "}";
        }
    }

    /** 展示用艺术家（可为 null）。 */
    public String displayArtist;
    /** 展示用标题（可为 null）。 */
    public String displayTitle;
    /** 非同步歌词原文；同步歌词时可为 null。 */
    public String plainText;
    /** 同步歌词行（可能为空）。 */
    public List<Line> lines = new ArrayList<Line>();

    /** 是否为同步歌词（有时间轴行）。 */
    public boolean isSynced() {
        return lines != null && !lines.isEmpty();
    }

    /** 无任何歌词内容时为 true。 */
    public boolean isEmpty() {
        if (isSynced()) {
            return false;
        }
        return plainText == null || plainText.trim().isEmpty();
    }

    /**
     * 返回当前播放位置应高亮的行索引：最后一个 startSec &lt;= positionSec 的行。
     * 找不到（位置早于第一行、无歌词行、位置为 NaN）返回 -1。
     */
    public int indexAt(double positionSec) {
        if (lines == null || lines.isEmpty() || Double.isNaN(positionSec)) {
            return -1;
        }
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Line line = lines.get(mid);
            double start = line == null ? 0d : line.startSec;
            if (start <= positionSec) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    @Override
    public String toString() {
        return "Lyrics{artist=" + displayArtist + ", title=" + displayTitle
                + ", synced=" + isSynced() + ", lines=" + (lines == null ? 0 : lines.size())
                + ", plain=" + (plainText == null ? 0 : plainText.length()) + "}";
    }
}
