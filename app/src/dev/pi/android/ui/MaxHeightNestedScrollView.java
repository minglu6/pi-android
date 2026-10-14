package dev.pi.android.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * wrap_content 卡片滚动区：矮内容贴合实测高度，仅超过 {@link #setMaxHeight(int)}
 * 时内部滚动。禁止把上限写成固定 height，否则短输出下方会空出一整块。
 */
public final class MaxHeightNestedScrollView extends NestedScrollView {

    private int maxHeightPx = Integer.MAX_VALUE;

    public MaxHeightNestedScrollView(@NonNull Context context) {
        super(context);
    }

    public MaxHeightNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeight(int maxHeightPx) {
        if (maxHeightPx <= 0) {
            throw new IllegalArgumentException("maxHeightPx=" + maxHeightPx);
        }
        if (this.maxHeightPx == maxHeightPx) return;
        this.maxHeightPx = maxHeightPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, CardScrollCap.clampHeight(heightMeasureSpec, maxHeightPx));
    }
}
