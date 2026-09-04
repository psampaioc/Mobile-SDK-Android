package com.dji.sdk.sample.operate.fpv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draggable, edge-snapping FPV overlay with a supplied content view (normally a SurfaceView).
 *
 * <p>The view has no DJI dependency. Add it to a {@link FrameLayout}, set its content with
 * {@link #setContentView(View)}, and set a non-zero {@link FrameLayout.LayoutParams} size. Its
 * position and collapse state are retained in private SharedPreferences for simple reinflation.</p>
 */
public final class FloatingFpvView extends FrameLayout {
    private static final String PREFERENCES = "operate_fpv_overlay";
    private static final String KEY_EDGE = "edge";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_COLLAPSED = "collapsed";

    private static final int EDGE_LEFT = 0;
    private static final int EDGE_RIGHT = 1;
    private static final int EDGE_TOP = 2;
    private static final int EDGE_BOTTOM = 3;

    private final SharedPreferences preferences;
    private final FrameLayout contentContainer;
    private final EdgeHandleView handle;
    private final int touchSlop;
    private final int safeMarginPx;
    private final int handleThicknessPx;
    private final int collapsedHandleLengthPx;

    private int usableTopPx;
    private int usableBottomPx;
    private int snappedEdge = EDGE_RIGHT;
    private float normalizedOffset = 1f;
    private boolean restored;
    private boolean collapsed;
    private boolean dragging;
    private float downRawX;
    private float downRawY;
    private float startX;
    private float startY;
    private int expandedWidth;
    private int expandedHeight;
    private boolean resizingForCollapseState;
    private final OnLayoutChangeListener parentLayoutChangeListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = right - left;
                int height = bottom - top;
                int oldWidth = oldRight - oldLeft;
                int oldHeight = oldBottom - oldTop;
                if (width != oldWidth || height != oldHeight) {
                    post(this::reapplyAfterParentLayout);
                }
            };

    public FloatingFpvView(@NonNull Context context) {
        this(context, null);
    }

    public FloatingFpvView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FloatingFpvView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        safeMarginPx = 0;
        handleThicknessPx = dp(18);
        collapsedHandleLengthPx = dp(78);

        setClipChildren(false);
        setClipToPadding(false);
        contentContainer = new FrameLayout(context);
        contentContainer.setOnTouchListener((view, event) -> {
            onTouchEvent(event);
            return true;
        });
        addView(contentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        handle = new EdgeHandleView(context);
        handle.setOnClickListener(view -> {
            if (!dragging) {
                setCollapsed(!collapsed);
            }
        });
        addView(handle, new FrameLayout.LayoutParams(handleThicknessPx, collapsedHandleLengthPx));
    }

    /** Replaces the monitor content. The supplied view is owned by this overlay after this call. */
    public void setContentView(@NonNull View content) {
        contentContainer.removeAllViews();
        if (content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }
        contentContainer.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Reserves screen space for persistent chrome or system insets. Values are relative to the
     * overlay parent. Call again if insets or the telemetry rail change.
     */
    public void setUsableVerticalBounds(int topPx, int bottomPx) {
        usableTopPx = Math.max(0, topPx);
        usableBottomPx = Math.max(0, bottomPx);
        if (getParent() != null) {
            snapToNearestEdge();
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean shouldCollapse) {
        if (collapsed == shouldCollapse) {
            return;
        }
        if (shouldCollapse) {
            expandedWidth = getWidth();
            expandedHeight = getHeight();
            collapsed = true;
            contentContainer.setVisibility(GONE);
            resizeForCollapsedHandle();
        } else {
            collapsed = false;
            contentContainer.setVisibility(VISIBLE);
            resizeForExpandedContent();
        }
        updateHandlePosition();
        persist();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        View parent = parentView();
        if (parent != null) {
            parent.addOnLayoutChangeListener(parentLayoutChangeListener);
        }
        post(this::restoreAfterParentLayout);
    }

    @Override protected void onDetachedFromWindow() {
        View parent = parentView();
        if (parent != null) {
            parent.removeOnLayoutChangeListener(parentLayoutChangeListener);
        }
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (!collapsed && !resizingForCollapseState && width >= dp(120) && height >= dp(80)) {
            expandedWidth = width;
            expandedHeight = height;
        }
        updateHandlePosition();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (collapsed) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startX = getX();
                startY = getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                }
                if (dragging) {
                    moveWithinUsableBounds(startX + dx, startY + dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    snapToNearestEdge();
                }
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void restoreAfterParentLayout() {
        if (restored || !(getParent() instanceof View)) {
            return;
        }
        View parent = (View) getParent();
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            post(this::restoreAfterParentLayout);
            return;
        }
        restored = true;
        snappedEdge = preferences.getInt(KEY_EDGE, EDGE_RIGHT);
        normalizedOffset = clamp(preferences.getFloat(KEY_OFFSET, 1f), 0f, 1f);
        collapsed = preferences.getBoolean(KEY_COLLAPSED, false);
        expandedWidth = getWidth();
        expandedHeight = getHeight();
        if (collapsed) {
            contentContainer.setVisibility(GONE);
            resizeForCollapsedHandle();
        }
        applySnappedPosition();
        updateHandlePosition();
    }

    /** Keeps the persisted edge/offset valid when the host rotates into landscape. */
    private void reapplyAfterParentLayout() {
        View parent = parentView();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        if (!restored) {
            restoreAfterParentLayout();
            return;
        }
        applySnappedPosition();
        updateHandlePosition();
    }

    private void moveWithinUsableBounds(float x, float y) {
        View parent = parentView();
        if (parent == null) {
            return;
        }
        setX(clamp(x, safeMarginPx, Math.max(safeMarginPx, parent.getWidth() - getWidth() - safeMarginPx)));
        setY(clamp(y, usableTopPx + safeMarginPx,
                Math.max(usableTopPx + safeMarginPx,
                        parent.getHeight() - usableBottomPx - getHeight() - safeMarginPx)));
    }

    private void snapToNearestEdge() {
        View parent = parentView();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        float leftDistance = Math.abs(getX() - safeMarginPx);
        float rightDistance = Math.abs(parent.getWidth() - safeMarginPx - getWidth() - getX());
        float topDistance = Math.abs(getY() - usableTopPx - safeMarginPx);
        float bottomDistance = Math.abs(parent.getHeight() - usableBottomPx - safeMarginPx - getHeight() - getY());
        float minimum = Math.min(Math.min(leftDistance, rightDistance), Math.min(topDistance, bottomDistance));
        if (minimum == leftDistance) {
            snappedEdge = EDGE_LEFT;
        } else if (minimum == rightDistance) {
            snappedEdge = EDGE_RIGHT;
        } else if (minimum == topDistance) {
            snappedEdge = EDGE_TOP;
        } else {
            snappedEdge = EDGE_BOTTOM;
        }
        if (snappedEdge == EDGE_LEFT || snappedEdge == EDGE_RIGHT) {
            normalizedOffset = fraction(getY() - usableTopPx - safeMarginPx,
                    parent.getHeight() - usableTopPx - usableBottomPx - getHeight() - 2 * safeMarginPx);
        } else {
            normalizedOffset = fraction(getX() - safeMarginPx,
                    parent.getWidth() - getWidth() - 2 * safeMarginPx);
        }
        applySnappedPosition();
        updateHandlePosition();
        persist();
    }

    private void applySnappedPosition() {
        View parent = parentView();
        if (parent == null) {
            return;
        }
        float maxX = Math.max(safeMarginPx, parent.getWidth() - getWidth() - safeMarginPx);
        float maxY = Math.max(usableTopPx + safeMarginPx,
                parent.getHeight() - usableBottomPx - getHeight() - safeMarginPx);
        if (snappedEdge == EDGE_LEFT) {
            setX(safeMarginPx);
            setY(usableTopPx + safeMarginPx + normalizedOffset * (maxY - usableTopPx - safeMarginPx));
        } else if (snappedEdge == EDGE_RIGHT) {
            setX(maxX);
            setY(usableTopPx + safeMarginPx + normalizedOffset * (maxY - usableTopPx - safeMarginPx));
        } else if (snappedEdge == EDGE_TOP) {
            setX(safeMarginPx + normalizedOffset * (maxX - safeMarginPx));
            setY(usableTopPx + safeMarginPx);
        } else {
            setX(safeMarginPx + normalizedOffset * (maxX - safeMarginPx));
            setY(maxY);
        }
    }

    private void resizeForCollapsedHandle() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (layoutParams == null) {
            return;
        }
        boolean horizontal = snappedEdge == EDGE_TOP || snappedEdge == EDGE_BOTTOM;
        layoutParams.width = horizontal ? collapsedHandleLengthPx : handleThicknessPx;
        layoutParams.height = horizontal ? handleThicknessPx : collapsedHandleLengthPx;
        resizingForCollapseState = true;
        setLayoutParams(layoutParams);
        post(() -> {
            resizingForCollapseState = false;
            applySnappedPosition();
        });
    }

    private void resizeForExpandedContent() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (layoutParams == null) {
            return;
        }
        layoutParams.width = expandedWidth > 0 ? expandedWidth : dp(320);
        layoutParams.height = expandedHeight > 0 ? expandedHeight : dp(180);
        resizingForCollapseState = true;
        setLayoutParams(layoutParams);
        post(() -> {
            resizingForCollapseState = false;
            applySnappedPosition();
        });
    }

    private void updateHandlePosition() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) handle.getLayoutParams();
        if (collapsed) {
            boolean horizontal = snappedEdge == EDGE_TOP || snappedEdge == EDGE_BOTTOM;
            params.width = horizontal ? collapsedHandleLengthPx : handleThicknessPx;
            params.height = horizontal ? handleThicknessPx : collapsedHandleLengthPx;
            params.leftMargin = 0;
            params.topMargin = 0;
        } else if (snappedEdge == EDGE_LEFT) {
            params.width = handleThicknessPx;
            params.height = Math.max(dp(72), getHeight() - dp(28));
            params.leftMargin = collapsed ? -handleThicknessPx / 2
                    : Math.max(0, getWidth() - handleThicknessPx / 2);
            params.topMargin = Math.max(0, (getHeight() - params.height) / 2);
        } else if (snappedEdge == EDGE_TOP || snappedEdge == EDGE_BOTTOM) {
            params.width = Math.max(dp(72), getWidth() - dp(28));
            params.height = handleThicknessPx;
            params.leftMargin = Math.max(0, (getWidth() - params.width) / 2);
            boolean atTop = snappedEdge == EDGE_TOP;
            params.topMargin = collapsed
                    ? (atTop ? -handleThicknessPx / 2 : Math.max(0, getHeight() - handleThicknessPx / 2))
                    : (atTop ? Math.max(0, getHeight() - handleThicknessPx / 2) : -handleThicknessPx / 2);
        } else {
            params.width = handleThicknessPx;
            params.height = Math.max(dp(72), getHeight() - dp(28));
            params.leftMargin = collapsed ? Math.max(0, getWidth() - handleThicknessPx / 2)
                    : -handleThicknessPx / 2;
            params.topMargin = Math.max(0, (getHeight() - params.height) / 2);
        }
        handle.setLayoutParams(params);
        int inwardDirection = snappedEdge == EDGE_LEFT ? EdgeHandleView.DIRECTION_RIGHT
                : snappedEdge == EDGE_RIGHT ? EdgeHandleView.DIRECTION_LEFT
                : snappedEdge == EDGE_TOP ? EdgeHandleView.DIRECTION_BOTTOM : EdgeHandleView.DIRECTION_TOP;
        int edgeDirection = snappedEdge == EDGE_LEFT ? EdgeHandleView.DIRECTION_LEFT
                : snappedEdge == EDGE_RIGHT ? EdgeHandleView.DIRECTION_RIGHT
                : snappedEdge == EDGE_TOP ? EdgeHandleView.DIRECTION_TOP : EdgeHandleView.DIRECTION_BOTTOM;
        handle.setDirection(collapsed ? inwardDirection : edgeDirection);
    }

    private void persist() {
        preferences.edit()
                .putInt(KEY_EDGE, snappedEdge)
                .putFloat(KEY_OFFSET, normalizedOffset)
                .putBoolean(KEY_COLLAPSED, collapsed)
                .apply();
    }

    @Nullable private View parentView() {
        return getParent() instanceof View ? (View) getParent() : null;
    }

    private float fraction(float value, float range) {
        return range <= 0f ? 0f : clamp(value / range, 0f, 1f);
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** Small dark chamfer tab drawn in code so no image asset is required. */
    private static final class EdgeHandleView extends View {
        static final int DIRECTION_LEFT = 0;
        static final int DIRECTION_RIGHT = 1;
        static final int DIRECTION_TOP = 2;
        static final int DIRECTION_BOTTOM = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int direction = DIRECTION_RIGHT;

        EdgeHandleView(Context context) {
            super(context);
            setClickable(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setElevation(8f);
            }
        }

        void setDirection(int value) {
            direction = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean horizontal = getWidth() > getHeight();
            canvas.save();
            if (horizontal) {
                // Reuse the exact left/right tab shape, rotated for top/bottom edges.
                canvas.translate(getWidth(), 0f);
                canvas.rotate(90f);
            }
            float width = horizontal ? getHeight() : getWidth();
            float height = horizontal ? getWidth() : getHeight();
            float cut = Math.min(width / 2f, height / 6f);
            paint.setColor(Color.rgb(83, 96, 106));
            int drawDirection = direction;
            if (horizontal) {
                if (direction == DIRECTION_TOP) drawDirection = DIRECTION_LEFT;
                else if (direction == DIRECTION_BOTTOM) drawDirection = DIRECTION_RIGHT;
            }
            // The tab's chamfer intentionally faces opposite its prior orientation; the
            // chevron direction remains independent and is not changed here.
            boolean mirrorChamfer = drawDirection != DIRECTION_LEFT;
            path.reset();
            if (mirrorChamfer) {
                path.moveTo(0f, 0f);
                path.lineTo(width - cut, 0f);
                path.lineTo(width, height / 2f);
                path.lineTo(width - cut, height);
                path.lineTo(0f, height);
            } else {
                path.moveTo(cut, 0f);
                path.lineTo(width, 0f);
                path.lineTo(width, height);
                path.lineTo(cut, height);
                path.lineTo(0f, height / 2f);
            }
            path.close();
            canvas.drawPath(path, paint);

            paint.setColor(Color.rgb(93, 210, 123));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, width / 10f));
            paint.setStrokeCap(Paint.Cap.SQUARE);
            path.reset();
            float centerX = width / 2f;
            float centerY = height / 2f;
            float span = Math.min(width, height) / 5f;
            if (drawDirection == DIRECTION_LEFT) {
                path.moveTo(centerX + span / 2f, centerY - span);
                path.lineTo(centerX - span / 2f, centerY);
                path.lineTo(centerX + span / 2f, centerY + span);
            } else if (drawDirection == DIRECTION_RIGHT) {
                path.moveTo(centerX - span / 2f, centerY - span);
                path.lineTo(centerX + span / 2f, centerY);
                path.lineTo(centerX - span / 2f, centerY + span);
            } else if (direction == DIRECTION_TOP) {
                path.moveTo(centerX - span, centerY + span / 2f);
                path.lineTo(centerX, centerY - span / 2f);
                path.lineTo(centerX + span, centerY + span / 2f);
            } else {
                path.moveTo(centerX - span, centerY - span / 2f);
                path.lineTo(centerX, centerY + span / 2f);
                path.lineTo(centerX + span, centerY - span / 2f);
            }
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
    }
}
