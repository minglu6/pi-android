package dev.pi.android.ui;

/**
 * 卡片内 NestedScrollView 的高度上限。
 * 与 {@link android.view.View.MeasureSpec} 同一套 bit：矮内容 AT_MOST 贴合，
 * 高内容封顶；禁止把上限写成 EXACTLY 固定高度。
 */
final class CardScrollCap {

    /** {@link android.view.View.MeasureSpec#MODE_SHIFT}. */
    static final int MODE_SHIFT = 30;
    static final int MODE_MASK = 0x3 << MODE_SHIFT;
    static final int UNSPECIFIED = 0;
    static final int EXACTLY = 1 << MODE_SHIFT;
    static final int AT_MOST = 2 << MODE_SHIFT;

    private CardScrollCap() {}

    static int size(int spec) {
        return spec & ~MODE_MASK;
    }

    static int mode(int spec) {
        return spec & MODE_MASK;
    }

    static int make(int size, int mode) {
        return (size & ~MODE_MASK) | (mode & MODE_MASK);
    }

    static int clampHeight(int heightMeasureSpec, int maxHeightPx) {
        if (maxHeightPx <= 0) {
            throw new IllegalArgumentException("maxHeightPx=" + maxHeightPx);
        }
        int mode = mode(heightMeasureSpec);
        int size = size(heightMeasureSpec);
        if (mode == UNSPECIFIED) {
            return make(maxHeightPx, AT_MOST);
        }
        return make(Math.min(size, maxHeightPx), mode);
    }
}
