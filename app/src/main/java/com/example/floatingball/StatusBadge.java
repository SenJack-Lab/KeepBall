package com.example.floatingball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Center-of-ball status glyph: green play (alive), red pause (dead),
 * amber pause (unknown). Drawn, not themed, so it works on any wallpaper.
 */
class StatusBadge extends View {

    public static final int ALIVE = 1;
    public static final int DEAD = 0;
    public static final int UNKNOWN = -1;

    private final Paint mCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private int mState = UNKNOWN;

    StatusBadge(Context context) {
        super(context);
        mGlyph.setColor(0xFFFFFFFF);
        mGlyph.setStyle(Paint.Style.FILL);
    }

    StatusBadge(Context context, AttributeSet attrs) {
        this(context);
    }

    void setState(int state) {
        if (mState != state) {
            mState = state;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        switch (mState) {
            case ALIVE:
                mCircle.setColor(0xFF2E7D32);
                break;
            case DEAD:
                mCircle.setColor(0xFFC62828);
                break;
            default:
                mCircle.setColor(0xFFF9A825);
        }
        c.drawCircle(cx, cy, r, mCircle);

        float g = r * 0.55f; // glyph half-extent
        mPath.reset();
        if (mState == ALIVE) {
            // play triangle
            mPath.moveTo(cx - g * 0.7f, cy - g);
            mPath.lineTo(cx - g * 0.7f, cy + g);
            mPath.lineTo(cx + g, cy);
            mPath.close();
            c.drawPath(mPath, mGlyph);
        } else {
            // pause bars
            float bw = g * 0.55f;
            c.drawRoundRect(cx - g - bw / 2, cy - g, cx - g + bw / 2, cy + g, bw / 3, bw / 3, mGlyph);
            c.drawRoundRect(cx + g - bw / 2, cy - g, cx + g + bw / 2, cy + g, bw / 3, bw / 3, mGlyph);
        }
    }
}
