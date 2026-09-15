package com.lixscn.subsonicplayer.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lixscn.subsonicplayer.core.Settings;

/**
 * 界面工具集：尺寸换算、主题色取用、圆角/涟漪背景、常用控件工厂。
 *
 * 全部用程序化建 View（不写 layout XML），好处是切换主题时可以直接重建，
 * 不必依赖主题资源重载。
 */
public final class Ui {

    private static Theme.Colors sColors;

    private Ui() {
    }

    // ---------------- 主题 ----------------

    public static Theme.Colors colors(Context ctx) {
        if (sColors == null) reload(ctx);
        return sColors;
    }

    public static void reload(Context ctx) {
        Settings s = Settings.get(ctx);
        sColors = Theme.withAccent(Theme.get(s.themeId()), s.accentIndex());
    }

    public static boolean isDark(Context ctx) {
        return colors(ctx).dark;
    }

    // ---------------- 尺寸 ----------------

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    // ---------------- 背景 ----------------

    public static GradientDrawable rect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable rect(int color, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable d = rect(color, radiusPx);
        if (strokePx > 0) d.setStroke((int) strokePx, strokeColor);
        return d;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /**
     * 点击反馈（涟漪）。
     *
     * 关键点：必须给 RippleDrawable 传 **mask**，否则涟漪不受视图形状约束，
     * 会从触点扩散成一个圆形光点（用户看到的就是「一个快速放大的光点」，很突兀）。
     * 传了 mask 之后就是标准的「整个行/卡片范围内变淡」反馈。
     *
     * @param cornerDp 圆角半径（与控件自身圆角一致，避免圆角处溢出）；0 = 直角
     */
    public static void tappable(View v, Context c) {
        tappable(v, c, colors(c).text, 0f);
    }

    public static void tappable(View v, Context c, int rippleBase) {
        tappable(v, c, rippleBase, 0f);
    }

    public static void tappable(View v, Context c, int rippleBase, float cornerDp) {
        Drawable content = v.getBackground();
        // 颜色很淡：深色主题用 10% 白，浅色主题用 8% 黑
        int ripple = colors(c).dark ? 0x1AFFFFFF : 0x14000000;
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        if (cornerDp > 0) mask.setCornerRadius(dp(c, cornerDp));
        try {
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), content, mask));
        } catch (Throwable t) {
            // 极端情况下退回原背景
        }
    }

    // ---------------- 文本 ----------------

    public static TextView text(Context c, CharSequence s, float sizeSp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static TextView bold(Context c, CharSequence s, float sizeSp, int color) {
        TextView tv = text(c, s, sizeSp, color);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    /** 单行省略 */
    public static void ellipsize(TextView tv) {
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    /** 跑马灯（迷你播放条用，只有被选中时才滚动，所以需要 setSelected(true)） */
    public static void marquee(TextView tv) {
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tv.setMarqueeRepeatLimit(-1);
        tv.setSelected(true);
    }

    // ---------------- 图标 ----------------

    public static ImageView icon(Context c, int res, float sizeDp, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        int px = dp(c, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 圆形图标按钮（点击区域比图标大，方便手指点） */
    public static FrameLayout iconButton(Context c, int res, float iconDp, float buttonDp, int tint, View.OnClickListener l) {
        FrameLayout fl = new FrameLayout(c);
        int b = dp(c, buttonDp);
        fl.setLayoutParams(new LinearLayout.LayoutParams(b, b));
        ImageView iv = icon(c, res, iconDp, tint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp));
        lp.gravity = Gravity.CENTER;
        iv.setLayoutParams(lp);
        fl.addView(iv);
        if (l != null) {
            fl.setOnClickListener(l);
            tappable(fl, c, tint, 100f);   // 圆形按钮：mask 也用圆
        }
        return fl;
    }

    // ---------------- 容器 ----------------

    public static LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    public static LinearLayout column(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /** 带权重的固定宽度（如 0 宽 + 0.5 权重） */
    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(colors(c).border);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f))));
        return v;
    }

    public static View gap(Context c, float dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dp)));
        return v;
    }

    /** 区块标题（「最近添加」「全部歌曲」这类） */
    public static View sectionHeader(Context c, String title, String action, View.OnClickListener onAction) {
        Theme.Colors t = colors(c);
        LinearLayout ll = row(c);
        ll.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 8));
        TextView tv = bold(c, title, 17, t.text);
        ll.addView(tv, lpWeight(1));
        if (action != null) {
            TextView a = text(c, action, 13, t.accent);
            a.setPadding(dp(c, 8), dp(c, 4), dp(c, 4), dp(c, 4));
            if (onAction != null) {
                a.setOnClickListener(onAction);
                tappable(a, c, t.accent);
            }
            ll.addView(a);
        }
        return ll;
    }

    /** 小圆角标签（流派、音质、年份等） */
    public static TextView chip(Context c, String label, boolean active, View.OnClickListener l) {
        Theme.Colors t = colors(c);
        TextView tv = text(c, label, 13, active ? t.accentText : t.textDim);
        tv.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        tv.setBackground(rect(active ? t.accent : t.surfaceAlt, dp(c, 16)));
        if (l != null) {
            tv.setOnClickListener(l);
            tappable(tv, c, active ? t.accentText : t.textDim, 16f);
        }
        return tv;
    }

    /** 横向 chip 行（排序切换用） */
    public static LinearLayout chipRow(Context c) {
        LinearLayout ll = row(c);
        ll.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return ll;
    }

    // ---------------- 状态视图 ----------------

    public static View loading(Context c) {
        FrameLayout fl = new FrameLayout(c);
        fl.setLayoutParams(lpMatchWrap());
        ProgressBar pb = new ProgressBar(c);
        pb.setIndeterminateTintList(ColorStateList.valueOf(colors(c).accent));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, 36), dp(c, 36));
        lp.gravity = Gravity.CENTER;
        fl.addView(pb, lp);
        fl.setPadding(0, dp(c, 32), 0, dp(c, 32));
        return fl;
    }

    public static View message(Context c, String title, String detail, String actionLabel, View.OnClickListener onAction) {
        Theme.Colors t = colors(c);
        LinearLayout ll = column(c);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(dp(c, 32), dp(c, 48), dp(c, 32), dp(c, 48));
        TextView tv = bold(c, title, 16, t.textDim);
        tv.setGravity(Gravity.CENTER);
        ll.addView(tv);
        if (detail != null && detail.length() > 0) {
            TextView d = text(c, detail, 13, t.textFaint);
            d.setGravity(Gravity.CENTER);
            d.setPadding(0, dp(c, 8), 0, 0);
            ll.addView(d);
        }
        if (actionLabel != null) {
            TextView a = text(c, actionLabel, 14, t.accent);
            a.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
            a.setBackground(rect(t.surfaceAlt, dp(c, 18)));
            LinearLayout.LayoutParams alp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(c, 16);
            ll.addView(a, alp);
            if (onAction != null) {
                a.setOnClickListener(onAction);
                tappable(a, c, t.accent);
            }
        }
        return ll;
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 对话框 ----------------

    private static int dialogTheme(Context c) {
        return colors(c).dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, dialogTheme(c));
    }

    public static void confirm(Context c, String title, String message, String okLabel, final Runnable onOk) {
        dialog(c).setTitle(title).setMessage(message)
                .setPositiveButton(okLabel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onOk != null) onOk.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public interface OnInput {
        void onInput(String text);
    }

    /** 单行文本输入对话框 */
    public static void input(Context c, String title, String hint, String initial, final OnInput cb) {
        Theme.Colors t = colors(c);
        final EditText et = new EditText(c);
        et.setHint(hint);
        et.setText(initial == null ? "" : initial);
        et.setTextColor(t.text);
        et.setHintTextColor(t.textFaint);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(rect(t.surfaceAlt, dp(c, 8), t.border, dp(c, 1)));
        et.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        wrap.addView(et, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog(c).setTitle(title).setView(wrap)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (cb != null) cb.onInput(et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单选列表对话框，返回选中下标 */
    public static void choose(Context c, String title, final String[] items, int checked, final OnInput cb) {
        dialog(c).setTitle(title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (cb != null) cb.onInput(String.valueOf(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 尺寸辅助 ----------------

    /** 给 View 加内边距（dp） */
    public static void pad(View v, Context c, float l, float t, float r, float b) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    /** 设置状态栏/导航栏颜色跟随主题 */
    public static void applySystemBars(Activity a) {
        Theme.Colors t = colors(a);
        try {
            a.getWindow().setStatusBarColor(Theme.statusBarColor(t));
            a.getWindow().setNavigationBarColor(t.bg);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                View decor = a.getWindow().getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (t.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
        }
    }

    public static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, (int) (a * 255)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    public static int mix(int a, int b, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb((int) (ar + (br - ar) * r), (int) (ag + (bg - ag) * r), (int) (ab + (bb - ab) * r));
    }
}
