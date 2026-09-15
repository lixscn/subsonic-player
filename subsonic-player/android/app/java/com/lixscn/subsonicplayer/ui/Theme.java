package com.lixscn.subsonicplayer.ui;

/**
 * 主题（配色）系统 —— 与桌面版 Subsonic Player 的 Web UI 观感保持一致。
 *
 * <p>数据来源：{@code subsonic-player/dotnet/src/SubsonicPlayer.Cef/WebAssets/styles.css}
 * 中的 {@code :root} 与 {@code html[data-theme="..."]} 规则，以及 {@code app.js} 里的
 * {@code THEMES} / {@code THEME_ORDER} / {@code ACCENT_THEMES}。颜色值全部写成
 * {@code 0xAARRGGBB} 字面量，不在运行时解析字符串（无 {@code Color.parseColor} 开销与异常）。</p>
 *
 * <h3>取值约定</h3>
 * <ul>
 *   <li>{@code bg} = CSS {@code --bg-app}；{@code text/textDim/textFaint} = {@code --text-primary/secondary/muted}；
 *       {@code accent} = {@code --accent}；{@code danger} = CSS 中收藏/删除态用的 {@code #EF4444}。</li>
 *   <li>{@code surface}/{@code surfaceAlt}/{@code border} 在 CSS 里是半透明玻璃色（{@code rgba(...,0.82~0.9)}），
 *       这里按「叠在该主题 {@code --bg-app} 之上」合成成不透明色，取的是桌面版在页面底色上的实际观感
 *       （Android 端不保证有实时模糊，不透明值比留 alpha 更可预期）。</li>
 *   <li>{@code accentText} 语义是「画在 accent 填充之上的文字色」（如按钮文字），不是 CSS 里那个
 *       「用强调色当文字色」的 {@code --accent-text}：深色主题取白色，浅色主题取深色，保证按钮文字可读。
 *       注意主题自带 accentText 固定按明暗取白/深色；而 {@link #withAccent} 覆盖强调色后，按
 *       {@link #onAccent(int)} 的亮度阈值重新判定（这些强调色都偏亮，因此深色主题下多半得到黑字），
 *       两者不同是刻意的：前者照搬桌面版主题观感，后者保证任意强调色上的对比度。</li>
 *   <li>{@code ok} 在 CSS 中没有对应变量（推导）：深色用 {@code #22C55E}，浅色用 {@code #16A34A}。</li>
 * </ul>
 *
 * <p>本类只含静态数据与纯函数：不持有 Context、不做 I/O、不使用 androidx 或任何第三方库，
 * 仅依赖 Java 8 与 Android Framework 基本类型。</p>
 */
public final class Theme {

    private Theme() {
    }

    /** 一套主题的全部颜色（ARGB int）。 */
    public static class Colors {
        public String id;
        public String label;      // 中文显示名，如「深邃黑」
        public boolean dark;      // 是否深色主题
        public int bg;            // 页面底色
        public int surface;       // 卡片/面板底色
        public int surfaceAlt;    // 次级面板/悬浮层
        public int border;        // 分隔线/描边
        public int text;          // 主文字
        public int textDim;       // 次要文字
        public int textFaint;     // 更弱的文字
        public int accent;        // 强调色（来自主题，可被强调色覆盖）
        public int accentText;    // 强调色上的文字色（保证对比度）
        public int danger;        // 错误/删除
        public int ok;            // 成功
    }

    // ============ 主题 id（与桌面版 THEME_ORDER 一致） ============
    public static final String ID_DARK = "dark";
    public static final String ID_LIGHT = "light";
    public static final String ID_FOREST = "forest";
    public static final String ID_MIDNIGHT = "midnight";
    public static final String ID_SUNSET = "sunset";
    public static final String ID_ROSE = "rose";

    /** 强调色列表里 index 0 的哨兵值：表示「跟随主题」，没有独立颜色。 */
    public static final int ACCENT_FOLLOW_THEME = 0;

    // ============ 通用色 ============
    /** 推导：CSS 中收藏 ♥ / 删除按钮的 hover 色 #EF4444，各主题通用。 */
    private static final int DANGER = 0xFFEF4444;
    /** 推导：深色主题的成功色（CSS 无对应变量），取绿 500。 */
    private static final int OK_DARK = 0xFF22C55E;
    /** 推导：浅色主题的成功色（CSS 无对应变量），取绿 600，浅底上足够清晰。 */
    private static final int OK_LIGHT = 0xFF16A34A;
    /** 深色主题下 accent 填充上的文字：白色。 */
    private static final int ON_ACCENT_LIGHT_TEXT = 0xFFFFFFFF;
    /** 浅色主题下 accent 填充上的文字：近黑（深色）。 */
    private static final int ON_ACCENT_DARK_TEXT = 0xFF101418;

    /** 亮度阈值：超过则用深色文字，否则用白色文字。 */
    private static final double LUMINANCE_THRESHOLD = 0.6;

    // ============ 6 套内置主题（顺序 = 设置页展示顺序 = THEME_ORDER） ============
    private static final Colors[] THEMES = new Colors[]{
            // ---- 深邃黑（dark，默认）：:root 即该主题 ----
            make(ID_DARK, "深邃黑", true,
                    0xFF0A0A0C,   // --bg-app: #0A0A0C
                    0xFF111114,   // --bg-surface: rgba(18,18,22,0.82) 叠在 bg 上
                    0xFF18181D,   // --bg-card: rgba(26,26,31,0.9) 叠在 bg 上
                    0xFF202026,   // --border: rgba(46,46,56,0.6) 叠在 bg 上
                    0xFFF5F5F7,   // --text-primary: #F5F5F7
                    0xFFA1A1AA,   // --text-secondary: #A1A1AA
                    0xFF6B6B76,   // --text-muted: #6B6B76
                    0xFF2DD4A7,   // --accent: #2DD4A7
                    ON_ACCENT_LIGHT_TEXT, // accentText：深色主题用白色
                    DANGER, OK_DARK),

            // ---- 月光白（light） ----
            make(ID_LIGHT, "月光白", false,
                    0xFFF5F5F7,   // --bg-app: #F5F5F7
                    0xFFFEFEFE,   // --bg-surface: rgba(255,255,255,0.85) 叠在 bg 上
                    0xFFEDEDF1,   // --bg-card: rgba(236,236,240,0.9) 叠在 bg 上
                    0xFFDFDFE4,   // --border: rgba(213,213,220,0.7) 叠在 bg 上
                    0xFF1A1A1F,   // --text-primary: #1A1A1F
                    0xFF6B6B76,   // --text-secondary: #6B6B76
                    0xFFA1A1AA,   // --text-muted: #A1A1AA
                    0xFF0FAE85,   // --accent: #0FAE85（浅色下略深的青绿）
                    0xFF0B6B4F,   // accentText：浅色主题必须用深色（取 CSS --accent-text #0B6B4F）
                    DANGER, OK_LIGHT),

            // ---- 森林绿（forest） ----
            make(ID_FOREST, "森林绿", true,
                    0xFF0A120C,   // --bg-app: #0A120C
                    0xFF111C14,   // --bg-surface: rgba(18,30,22,0.82) 叠在 bg 上
                    0xFF17261C,   // --bg-card: rgba(24,40,30,0.9) 叠在 bg 上
                    0xFF223128,   // --border: rgba(50,70,58,0.6) 叠在 bg 上
                    0xFFECF5EE,   // --text-primary: #ECF5EE
                    0xFFA7C0AE,   // --text-secondary: #A7C0AE
                    0xFF6E866F,   // --text-muted: #6E866F
                    0xFF34D399,   // --accent: #34D399
                    ON_ACCENT_LIGHT_TEXT,
                    DANGER, OK_DARK),

            // ---- 午夜蓝（midnight） ----
            make(ID_MIDNIGHT, "午夜蓝", true,
                    0xFF070A16,   // --bg-app: #070A16
                    0xFF0E1223,   // --bg-surface: rgba(16,20,38,0.82) 叠在 bg 上
                    0xFF151A2F,   // --bg-card: rgba(22,28,50,0.9) 叠在 bg 上
                    0xFF21283F,   // --border: rgba(50,60,90,0.6) 叠在 bg 上
                    0xFFEEF1FA,   // --text-primary: #EEF1FA
                    0xFFA7B0CC,   // --text-secondary: #A7B0CC
                    0xFF6E7796,   // --text-muted: #6E7796
                    0xFF60A5FA,   // --accent: #60A5FA
                    ON_ACCENT_LIGHT_TEXT,
                    DANGER, OK_DARK),

            // ---- 落日橙（sunset） ----
            make(ID_SUNSET, "落日橙", true,
                    0xFF140D08,   // --bg-app: #140D08
                    0xFF1C140D,   // --bg-surface: rgba(30,22,14,0.82) 叠在 bg 上
                    0xFF261C13,   // --bg-card: rgba(40,30,20,0.9) 叠在 bg 上
                    0xFF382D21,   // --border: rgba(80,66,50,0.6) 叠在 bg 上
                    0xFFFBF2E8,   // --text-primary: #FBF2E8
                    0xFFCBB4A0,   // --text-secondary: #CBB4A0
                    0xFF8E7E6C,   // --text-muted: #8E7E6C
                    0xFFFB923C,   // --accent: #FB923C
                    ON_ACCENT_LIGHT_TEXT,
                    DANGER, OK_DARK),

            // ---- 玫瑰紫（rose） ----
            make(ID_ROSE, "玫瑰紫", true,
                    0xFF120A12,   // --bg-app: #120A12
                    0xFF1C111A,   // --bg-surface: rgba(30,18,28,0.82) 叠在 bg 上
                    0xFF261824,   // --bg-card: rgba(40,26,38,0.9) 叠在 bg 上
                    0xFF3A2236,   // --border: rgba(90,54,84,0.55) 叠在 bg 上
                    0xFFFBEFF5,   // --text-primary: #FBEFF5
                    0xFFCEAFC4,   // --text-secondary: #CEAFC4
                    0xFF8E7085,   // --text-muted: #8E7085
                    0xFFF472B6,   // --accent: #F472B6
                    ON_ACCENT_LIGHT_TEXT,
                    DANGER, OK_DARK),
    };

    // ============ 可选强调色（对应 app.js 的 ACCENT_THEMES，顺序同桌面版） ============
    // 桌面版按「当前主题是否浅色」在 dark / light 两套里取值，这里同样保留两个变体。
    private static final String[] ACCENT_NAMES = new String[]{
            "跟随主题", "青绿", "紫", "玫红", "琥珀", "蓝",
    };
    /** 深色主题下各强调色（index 0 = 跟随主题，无独立颜色）。 */
    private static final int[] ACCENT_DARK = new int[]{
            ACCENT_FOLLOW_THEME, // 跟随主题
            0xFF2DD4A7,          // 青绿 teal  dark.accent  #2DD4A7
            0xFFA78BFA,          // 紫   purple dark.accent #A78BFA
            0xFFF472B6,          // 玫红 rose   dark.accent #F472B6
            0xFFFBBF24,          // 琥珀 amber  dark.accent #FBBF24
            0xFF60A5FA,          // 蓝   blue   dark.accent #60A5FA
    };
    /** 浅色主题下各强调色（index 0 = 跟随主题，无独立颜色）。 */
    private static final int[] ACCENT_LIGHT = new int[]{
            ACCENT_FOLLOW_THEME, // 跟随主题
            0xFF0FAE85,          // 青绿 teal   light.accent #0FAE85
            0xFF7C3AED,          // 紫   purple light.accent #7C3AED
            0xFFDB2777,          // 玫红 rose   light.accent #DB2777
            0xFFD97706,          // 琥珀 amber  light.accent #D97706
            0xFF2563EB,          // 蓝   blue   light.accent #2563EB
    };

    // ============ 查询 API ============

    /** 全部内置主题（顺序即设置页展示顺序）。返回数组副本，元素为主题缓存对象，请勿修改。 */
    public static Colors[] all() {
        Colors[] copy = new Colors[THEMES.length];
        System.arraycopy(THEMES, 0, copy, 0, THEMES.length);
        return copy;
    }

    /** 按 id 取主题，找不到（含 null）返回默认主题（dark）。 */
    public static Colors get(String id) {
        if (id != null) {
            for (int i = 0; i < THEMES.length; i++) {
                if (id.equals(THEMES[i].id)) {
                    return THEMES[i];
                }
            }
        }
        return THEMES[0]; // dark
    }

    /** 默认主题 id：dark。 */
    public static String defaultId() {
        return ID_DARK;
    }

    /** 可选强调色列表（不含「跟随主题」）。null 项表示「跟随主题」——本实现中为第 0 项。 */
    public static String[] accentNames() {
        String[] copy = new String[ACCENT_NAMES.length];
        System.arraycopy(ACCENT_NAMES, 0, copy, 0, ACCENT_NAMES.length);
        return copy;
    }

    /**
     * 对应颜色；index 0（跟随主题）返回 {@link #ACCENT_FOLLOW_THEME}（0，表示无独立颜色）。
     * 越界同样返回 0。此重载按「深色主题」取值，浅色主题请用
     * {@link #accentColor(int, boolean)}。
     */
    public static int accentColor(int index) {
        return accentColor(index, true);
    }

    /** 对应颜色，按主题明暗取桌面版定义的 dark / light 变体；index 0 或越界返回 0。 */
    public static int accentColor(int index, boolean darkTheme) {
        int[] table = darkTheme ? ACCENT_DARK : ACCENT_LIGHT;
        if (index <= 0 || index >= table.length) {
            return ACCENT_FOLLOW_THEME;
        }
        return table[index];
    }

    /**
     * 应用强调色覆盖：返回一份新的 {@link Colors}（不改动缓存的原主题对象）。
     *
     * @param base        基础主题，null 时按默认主题处理
     * @param accentIndex 0 = 跟随主题（原样复制）；&gt;0 用 {@link #accentColor(int, boolean)}
     *                    覆盖 accent 并按亮度重算 accentText
     */
    public static Colors withAccent(Colors base, int accentIndex) {
        Colors src = (base == null) ? get(defaultId()) : base;
        Colors out = copy(src);
        int color = accentColor(accentIndex, src.dark);
        if (color != ACCENT_FOLLOW_THEME) {
            out.accent = color;
            out.accentText = onAccent(color);
        }
        return out;
    }

    /**
     * 状态栏 / 导航栏配色：在 {@code bg} 基础上略深（深色主题）或略暗（浅色主题），
     * 与页面底色形成轻微层次。返回不透明 ARGB。
     */
    public static int statusBarColor(Colors c) {
        if (c == null) {
            return 0xFF000000;
        }
        // 深色主题压暗到 82%，浅色主题压暗到 94%（浅色下再暗就显脏）
        double factor = c.dark ? 0.82 : 0.94;
        int r = clampChannel((int) Math.round(((c.bg >> 16) & 0xFF) * factor));
        int g = clampChannel((int) Math.round(((c.bg >> 8) & 0xFF) * factor));
        int b = clampChannel((int) Math.round((c.bg & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 感知亮度（0.299R + 0.587G + 0.114B，归一化到 0~1）。
     * 不使用 {@code Color.luminance()}（需要 API 24+）。
     */
    public static double luminance(int color) {
        double r = (color >> 16) & 0xFF;
        double g = (color >> 8) & 0xFF;
        double b = color & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    /** 强调色填充上应使用的文字色：亮度 &gt; 0.6 用黑，否则用白。 */
    public static int onAccent(int accent) {
        return luminance(accent) > LUMINANCE_THRESHOLD ? 0xFF000000 : 0xFFFFFFFF;
    }

    // ============ 内部工具 ============

    private static Colors copy(Colors src) {
        Colors c = new Colors();
        c.id = src.id;
        c.label = src.label;
        c.dark = src.dark;
        c.bg = src.bg;
        c.surface = src.surface;
        c.surfaceAlt = src.surfaceAlt;
        c.border = src.border;
        c.text = src.text;
        c.textDim = src.textDim;
        c.textFaint = src.textFaint;
        c.accent = src.accent;
        c.accentText = src.accentText;
        c.danger = src.danger;
        c.ok = src.ok;
        return c;
    }

    private static int clampChannel(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }

    private static Colors make(String id, String label, boolean dark,
                               int bg, int surface, int surfaceAlt, int border,
                               int text, int textDim, int textFaint,
                               int accent, int accentText, int danger, int ok) {
        Colors c = new Colors();
        c.id = id;
        c.label = label;
        c.dark = dark;
        c.bg = bg;
        c.surface = surface;
        c.surfaceAlt = surfaceAlt;
        c.border = border;
        c.text = text;
        c.textDim = textDim;
        c.textFaint = textFaint;
        c.accent = accent;
        c.accentText = accentText;
        c.danger = danger;
        c.ok = ok;
        return c;
    }
}
