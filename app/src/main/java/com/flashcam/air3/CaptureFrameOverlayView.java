package com.flashcam.air3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom overlay view that draws a semi-transparent shade around a clear rectangle.
 * The clear rectangle represents the actual capture area.
 * Taps outside the clear area should be ignored for focus purposes.
 */
public class CaptureFrameOverlayView extends View {

    private static final int COLOR_ORANGE = 0xFFFF6600;

    private final Paint shadePaint = new Paint();
    private final Paint borderPaint = new Paint();
    private RectF clearRect = new RectF();
    private boolean showOverlay = false;

    public CaptureFrameOverlayView(Context context) {
        super(context);
        init();
    }

    public CaptureFrameOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptureFrameOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        shadePaint.setColor(0x88000000); // semi-transparent black
        shadePaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(COLOR_ORANGE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
    }

    public void setClearRect(RectF rect) {
        this.clearRect = rect;
        this.showOverlay = true;
        invalidate();
    }

    public void hideOverlay() {
        this.showOverlay = false;
        invalidate();
    }

    public boolean isInsideClearRect(float x, float y) {
        if (!showOverlay) return true;
        return clearRect.contains(x, y);
    }

    public RectF getClearRect() {
        return clearRect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!showOverlay) return;

        int w = getWidth(), h = getHeight();

        // Draw shade on all four sides of the clear rect
        // Top
        canvas.drawRect(0, 0, w, clearRect.top, shadePaint);
        // Bottom
        canvas.drawRect(0, clearRect.bottom, w, h, shadePaint);
        // Left
        canvas.drawRect(0, clearRect.top, clearRect.left, clearRect.bottom, shadePaint);
        // Right
        canvas.drawRect(clearRect.right, clearRect.top, w, clearRect.bottom, shadePaint);

        // Draw border around clear rect
        canvas.drawRect(clearRect, borderPaint);
    }
}
