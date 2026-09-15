package com.lixscn.subsonicplayer.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 解析器：把 LRC 文本解析成带时间轴的歌词行。
 *
 * <p>识别 {@code [mm:ss.xx]} 与 {@code [mm:ss:xx]} 两种时间标签（分钟 1~3 位、秒 2 位、
 * 小数 1~3 位，按毫秒右补零），同一行的多个时间标签会展开成多行。
 * 与桌面版 C# 的 ParseLrc 行为保持一致：无时间轴、以及去掉标签后为空文本的行不产生歌词行。
 *
 * <p>纯 Java 实现，只依赖 Java 8 API。
 */
public class LrcParser {

    /** 时间标签：[mm:ss]、[mm:ss.xx]、[mm:ss:xx]。 */
    private static final Pattern TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    /**
     * 解析 LRC 文本为同步歌词行（按时间升序）。
     *
     * @param lrc LRC 文本，可为 null
     * @return 歌词行列表；无时间轴时返回空列表（不会返回 null）
     */
    public static List<Lyrics.Line> parse(String lrc) {
        List<Lyrics.Line> out = new ArrayList<Lyrics.Line>();
        if (lrc == null || lrc.isEmpty()) {
            return out;
        }

        String[] rawLines = lrc.split("\n");
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line.indexOf('\r') >= 0) {
                line = line.replace("\r", "");
            }
            if (line.isEmpty()) {
                continue;
            }

            Matcher m = TAG.matcher(line);
            if (!m.find()) {
                continue;
            }

            // 去掉所有时间标签后剩下的即该行文本
            String text = TAG.matcher(line).replaceAll("").trim();
            if (text.isEmpty()) {
                continue;
            }

            // 同一行多个时间标签：[00:10.00][01:20.00]歌词 → 展开成两行
            m.reset();
            while (m.find()) {
                int min = parseIntSafe(m.group(1), 0);
                int sec = parseIntSafe(m.group(2), 0);
                int frac = 0;
                String fracGroup = m.group(3);
                if (fracGroup != null) {
                    // .5 → 500ms；.50 → 500ms；.500 → 500ms
                    StringBuilder sb = new StringBuilder(fracGroup);
                    while (sb.length() < 3) {
                        sb.append('0');
                    }
                    if (sb.length() > 3) {
                        sb.setLength(3);
                    }
                    frac = parseIntSafe(sb.toString(), 0);
                }
                double seconds = min * 60 + sec + frac / 1000.0;
                out.add(new Lyrics.Line(seconds, text));
            }
        }

        // 稳定排序（Collections.sort 为稳定排序），时间相同的行保持原顺序
        Collections.sort(out, new Comparator<Lyrics.Line>() {
            @Override
            public int compare(Lyrics.Line a, Lyrics.Line b) {
                if (a == b) {
                    return 0;
                }
                if (a == null) {
                    return -1;
                }
                if (b == null) {
                    return 1;
                }
                return Double.compare(a.startSec, b.startSec);
            }
        });
        return out;
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
