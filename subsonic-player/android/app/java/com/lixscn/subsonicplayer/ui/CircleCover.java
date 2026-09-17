package com.lixscn.subsonicplayer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.lixscn.subsonicplayer.R;

/**
 * 圆角 / 圆形封面 ImageView（专辑、艺术家图用）。
 *
 * <p>实现方式：不裁源位图，而是在 {@link #onDraw(Canvas)} 里用
 * {@link BitmapShader} + {@link Paint} 直接画圆或圆角矩形——这样任意 View 尺寸、
 * 任意源图比例都不会变形，也不会像「预先生成圆角 Bitmap」那样每换一次尺寸就重造一张图。
 *
 * <p>缩放策略：
 * <ul>
 *   <li>{@link #setCircle(boolean)} 为 true：无论 scaleType 如何，都按短边做正方形
 *       center-crop 后画正圆（View 非正方形时居中，保证是圆而不是椭圆）。</li>
 *   <li>否则遵循 scaleType 的常见取值：{@code FIT_CENTER}/{@code CENTER_INSIDE} 完整显示，
 *       {@code FIT_XY} 拉伸铺满，其余（{@code CENTER_CROP}、默认值以外的其它、{@code MATRIX}）
 *       一律按 center-crop 铺满内容区并裁圆角。</li>
 * </ul>
 *
 * <p>只使用 Android Framework API，零第三方依赖。
 */
public class CircleCover extends ImageView {

    /** 默认圆角 12dp */
    private static final float DEFAULT_CORNER_RADIUS_DP = 12f;

    private final Paint mPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix mShaderMatrix = new Matrix();
    private final RectF mDstRect = new RectF();

    /** 当前要画的源位图（由 setImageBitmap/setImageDrawable 提交） */
    private Bitmap mSource;
    /** mSource 对应的 shader，懒创建 */
    private BitmapShader mShader;
    /** 构建 mShader 时用的位图，用来判断是否需要重建 */
    private Bitmap mShaderSource;

    private float mCornerRadius;
    private boolean mCircle;

    /** 没有封面时画在中间的占位图标（懒创建 + 记下当前主题色，主题变了重建） */
    private Drawable mPlaceholderIcon;
    private int mPlaceholderTint;

    public CircleCover(Context context) {
        this(context, null);
    }

    public CircleCover(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CircleCover(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mCornerRadius = DEFAULT_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density;
    }

    /** 设置圆角半径（px）。{@link #setCircle(boolean)} 为 true 时该项无效。 */
    public void setCornerRadius(float px) {
        if (px < 0f) {
            px = 0f;
        }
        if (mCornerRadius == px && !mCircle) {
            applyShapeToBackground();
            return;
        }
        mCornerRadius = px;
        mCircle = false;
        applyShapeToBackground();
        invalidate();
    }

    /** true = 画正圆（覆盖圆角设置）；false = 回到圆角矩形。 */
    public void setCircle(boolean circle) {
        boolean changed = (mCircle != circle);
        mCircle = circle;
        applyShapeToBackground();
        if (changed) {
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // 底色（background）形状跟随
    // ------------------------------------------------------------------

    @Override
    public void setBackground(Drawable background) {
        super.setBackground(background);
        applyShapeToBackground();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyShapeToBackground();
    }

    /**
     * 让「底色」跟当前形状一致。
     *
     * <p>为什么需要：各页面给封面铺的底色是**圆角矩形**（{@code Ui.rect(surfaceAlt, 8dp)}），
     * 而艺术家头像是**正圆**。底色不跟着变圆的话四角会露出来 —— 没封面时占位图和底色同色，
     * 结果看起来是个圆角方块，跟有封面时的圆形不一致。
     */
    private void applyShapeToBackground() {
        Drawable bg = getBackground();
        if (!(bg instanceof GradientDrawable)) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;   // 还没 layout，onSizeChanged 里会再调一次
        }
        try {
            ((GradientDrawable) bg).setCornerRadius(mCircle
                    ? Math.min(w, h) / 2f
                    : Math.min(mCornerRadius, Math.min(w, h) / 2f));
        } catch (Throwable ignored) {
            // 某些 ROM 上 setCornerRadius 可能抛，忽略即可（最多退化成圆角矩形）
        }
    }

    // ------------------------------------------------------------------
    // 拦截图片来源：位图直接用，Drawable 先转位图
    // ------------------------------------------------------------------

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        updateSource(bm);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateSource(drawableToBitmap(drawable));
    }

    @Override
    public void setImageResource(int resId) {
        super.setImageResource(resId);
        updateSource(drawableToBitmap(getDrawable()));
    }

    @Override
    public void setImageAlpha(int alpha) {
        super.setImageAlpha(alpha);
        mPaint.setAlpha(alpha);
        invalidate();
    }

    private void updateSource(Bitmap bmp) {
        if (bmp == mSource) {
            return;
        }
        mSource = bmp;
        mShader = null;
        mShaderSource = null;
        invalidate();
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        final Bitmap bmp = mSource;
        if (bmp == null || bmp.isRecycled()) {
            // 没有封面（服务端没给 coverArt / 加载失败 / 还没加载完）→ 画占位，**不留空白**。
            drawPlaceholder(canvas);
            return;
        }

        final int bw = bmp.getWidth();
        final int bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) {
            super.onDraw(canvas);
            return;
        }

        final int padLeft = getPaddingLeft();
        final int padTop = getPaddingTop();
        final int vw = getWidth() - padLeft - getPaddingRight();
        final int vh = getHeight() - padTop - getPaddingBottom();
        if (vw <= 0 || vh <= 0) {
            super.onDraw(canvas);
            return;
        }

        // 1) 目标矩形 + shader 缩放系数
        float left;
        float top;
        float right;
        float bottom;
        float sx;
        float sy;

        if (mCircle) {
            // 短边正方形 + center-crop，保证画出来是真的圆
            float side = Math.min(vw, vh);
            left = padLeft + (vw - side) / 2f;
            top = padTop + (vh - side) / 2f;
            right = left + side;
            bottom = top + side;
            float s = Math.max(side / bw, side / bh);
            sx = s;
            sy = s;
        } else {
            ScaleType st = getScaleType();
            if (st == ScaleType.FIT_XY) {
                sx = (float) vw / bw;
                sy = (float) vh / bh;
                left = padLeft;
                top = padTop;
                right = padLeft + vw;
                bottom = padTop + vh;
            } else if (st == ScaleType.FIT_CENTER || st == ScaleType.CENTER_INSIDE) {
                float s = Math.min((float) vw / bw, (float) vh / bh);
                float dw = bw * s;
                float dh = bh * s;
                left = padLeft + (vw - dw) / 2f;
                top = padTop + (vh - dh) / 2f;
                right = left + dw;
                bottom = top + dh;
                sx = s;
                sy = s;
            } else {
                // CENTER_CROP / CENTER / MATRIX / FIT_START / FIT_END 等统一按铺满处理
                float s = Math.max((float) vw / bw, (float) vh / bh);
                left = padLeft;
                top = padTop;
                right = padLeft + vw;
                bottom = padTop + vh;
                sx = s;
                sy = s;
            }
        }
        mDstRect.set(left, top, right, bottom);

        // 2) shader（源图变了才重建）
        if (mShader == null || mShaderSource != bmp) {
            mShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mShaderSource = bmp;
            mPaint.setShader(mShader);
        }
        // 把位图坐标映射到目标矩形：p' = S * p + t
        mShaderMatrix.setScale(sx, sy);
        mShaderMatrix.postTranslate(left, top);
        mShader.setLocalMatrix(mShaderMatrix);

        // 3) 画形状（形状即裁剪，无需 clipPath）
        if (mCircle) {
            float r = Math.min(mDstRect.width(), mDstRect.height()) / 2f;
            canvas.drawCircle(mDstRect.centerX(), mDstRect.centerY(), r, mPaint);
        } else {
            float r = Math.min(mCornerRadius, Math.min(mDstRect.width(), mDstRect.height()) / 2f);
            if (r > 0f) {
                canvas.drawRoundRect(mDstRect, r, r, mPaint);
            } else {
                canvas.drawRect(mDstRect, mPaint);
            }
        }
    }

    // ------------------------------------------------------------------
    // 占位图（没有封面时）
    // ------------------------------------------------------------------

    /**
     * 画「没有封面」的占位：和真实封面同样的形状（圆角矩形 / 正圆）+ 居中音符图标。
     *
     * <p>形状规则与 {@link #onDraw(Canvas)} 保持一致（圆形按短边取正方形居中），
     * 所以列表 / 网格 / 播放页 / 迷你条 尺寸不同也不会变形。
     */
    private void drawPlaceholder(Canvas canvas) {
        final int padLeft = getPaddingLeft();
        final int padTop = getPaddingTop();
        final int vw = getWidth() - padLeft - getPaddingRight();
        final int vh = getHeight() - padTop - getPaddingBottom();
        if (vw <= 0 || vh <= 0) {
            return;
        }

        final Context c = getContext();
        final Theme.Colors t = Ui.colors(c);

        // 关键：上一次画真实封面时 mPaint 上挂着 BitmapShader，这里必须摘掉
        mPaint.setShader(null);
        mPaint.setAlpha(255);
        mPaint.setColor(t.surfaceAlt);

        if (mCircle) {
            float side = Math.min(vw, vh);
            float cx = padLeft + vw / 2f;
            float cy = padTop + vh / 2f;
            canvas.drawCircle(cx, cy, side / 2f, mPaint);
        } else {
            float r = Math.min(mCornerRadius, Math.min(vw, vh) / 2f);
            mDstRect.set(padLeft, padTop, padLeft + vw, padTop + vh);
            if (r > 0f) {
                canvas.drawRoundRect(mDstRect, r, r, mPaint);
            } else {
                canvas.drawRect(mDstRect, mPaint);
            }
        }

        // 居中音符（矢量，任意尺寸都清晰）
        final int tint = Ui.alpha(t.textFaint, 0.9f);
        if (mPlaceholderIcon == null || mPlaceholderTint != tint) {
            try {
                mPlaceholderIcon = c.getDrawable(R.drawable.ic_song).mutate();
                mPlaceholderIcon.setTint(tint);
                mPlaceholderTint = tint;
            } catch (Throwable ignored) {
                mPlaceholderIcon = null;
            }
        }
        if (mPlaceholderIcon != null) {
            int side = Math.max(Ui.dp(c, 16), Math.round(Math.min(vw, vh) * 0.40f));
            int cx = padLeft + vw / 2;
            int cy = padTop + vh / 2;
            mPlaceholderIcon.setBounds(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2);
            mPlaceholderIcon.draw(canvas);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** Drawable -> Bitmap（占位图、ColorDrawable、VectorDrawable 都能兜住）。失败返回 null。 */
    private Bitmap drawableToBitmap(Drawable d) {
        if (d == null) {
            return null;
        }
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) {
                return b;
            }
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = getWidth() - getPaddingLeft() - getPaddingRight();
            h = getHeight() - getPaddingTop() - getPaddingBottom();
        }
        if (w <= 0 || h <= 0) {
            // 还没 layout（比如刚 setImageResource 占位图）时的兜底尺寸
            w = 256;
            h = 256;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
