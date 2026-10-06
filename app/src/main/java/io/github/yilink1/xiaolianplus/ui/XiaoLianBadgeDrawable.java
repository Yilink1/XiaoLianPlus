package io.github.yilink1.xiaolianplus.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 笑联Plus 专属列表图标：极简细线圆圈加号 (Circle Plus)
 * 严格对齐宿主 AntUI / Ant Design 18dp 规范与 #333333 细线质感，
 * 彻底消除多余占位，实现与宿主“设置/实验室/更多”像素级绝对对齐。
 */
public class XiaoLianBadgeDrawable extends Drawable {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mSizePx;

    public XiaoLianBadgeDrawable(Context context) {
        this.mDensity = context.getResources().getDisplayMetrics().density;
        // 严格设定为 18dp（宿主官方图标原生尺寸）
        this.mSizePx = (int) (18 * mDensity + 0.5f);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSizePx;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            b = new Rect(0, 0, mSizePx, mSizePx);
        }
        float w = b.width();
        float h = b.height();
        float cx = b.left + w / 2f;
        float cy = b.top + h / 2f;

        float strokeWidth = 1.25f * mDensity;
        mPaint.setAntiAlias(true);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(Color.parseColor("#333333"));
        mPaint.setStrokeWidth(strokeWidth);

        // 1. 外层：14dp 极简细线正圆（与官方齿轮、六边形视觉体量完全一致）
        float r = 7.0f * mDensity;
        canvas.drawCircle(cx, cy, r, mPaint);

        // 2. 内层：纯净 Plus 细线十字加号（各臂长 3dp，总长 6dp）
        float arm = 3.0f * mDensity;
        canvas.drawLine(cx - arm, cy, cx + arm, cy, mPaint);
        canvas.drawLine(cx, cy - arm, cx, cy + arm, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
