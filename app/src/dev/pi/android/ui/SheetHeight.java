package dev.pi.android.ui;

/**
 * 面板高度：其它 sheet / 溢出安全上限约 70–80% 屏高；
 * 斜杠命令列表视口对齐 Pi TUI {@code autocompleteMaxVisible} 默认 5 行。
 */
final class SheetHeight {

    /** 70–80% 屏高区间的取值：溢出安全上限，不是 slash 的有效高度。 */
    static final float LARGE = 0.75f;

    /** Pi TUI Editor.autocompleteMaxVisible 默认值（官方 clamp 3–20）。 */
    static final int SLASH_MAX_VISIBLE = 5;

    private SheetHeight() {}

    static int capPx(int screenPx, float ratio) {
        if (screenPx <= 0) {
            throw new IllegalArgumentException("screenPx=" + screenPx);
        }
        if (ratio <= 0f || ratio > 1f) {
            throw new IllegalArgumentException("ratio=" + ratio);
        }
        int cap = Math.round(screenPx * ratio);
        if (cap <= 0) {
            throw new IllegalArgumentException("capPx=" + cap);
        }
        return cap;
    }

    static int hugOrCap(int screenPx, int contentPx, float ratio) {
        if (contentPx < 0) {
            throw new IllegalArgumentException("contentPx=" + contentPx);
        }
        return Math.min(contentPx, capPx(screenPx, ratio));
    }

    static int expandedOffsetPx(int parentPx, int screenPx, int minScrimPx) {
        if (parentPx <= 0) {
            throw new IllegalArgumentException("parentPx=" + parentPx);
        }
        if (minScrimPx <= 0) {
            throw new IllegalArgumentException("minScrimPx=" + minScrimPx);
        }
        int target = capPx(screenPx, LARGE);
        int offset = parentPx - target;
        if (offset < minScrimPx) offset = minScrimPx;
        if (offset >= parentPx) {
            throw new IllegalArgumentException("expandedOffset=" + offset + " parentPx=" + parentPx);
        }
        return offset;
    }

    /** Slash 列表可视行数上限；adapter 仍持有完整过滤结果。 */
    static int slashVisibleRowCap() {
        return SLASH_MAX_VISIBLE;
    }

    /**
     * Slash 列表视口高度 = 一行实测高度 × {@link #SLASH_MAX_VISIBLE} + list padding。
     * 不截断目录数据。
     */
    static int slashListViewportPx(int rowHeightPx, int listPaddingPx) {
        if (rowHeightPx <= 0) {
            throw new IllegalArgumentException("rowHeightPx=" + rowHeightPx);
        }
        if (listPaddingPx < 0) {
            throw new IllegalArgumentException("listPaddingPx=" + listPaddingPx);
        }
        int viewport = Math.multiplyExact(rowHeightPx, SLASH_MAX_VISIBLE) + listPaddingPx;
        if (viewport <= 0) {
            throw new IllegalStateException("slash viewport=" + viewport);
        }
        return viewport;
    }

    /**
     * 斜杠列表 maxHeight：5 行视口，且不超过 LARGE 屏高减去标题（溢出安全）。
     */
    static int slashListMaxPx(int screenPx, int headerPx, int rowHeightPx, int listPaddingPx) {
        if (headerPx < 0) {
            throw new IllegalArgumentException("headerPx=" + headerPx);
        }
        int viewport = slashListViewportPx(rowHeightPx, listPaddingPx);
        int safety = capPx(screenPx, LARGE) - headerPx;
        if (safety <= 0) {
            throw new IllegalStateException("slash list safety=" + safety);
        }
        int max = Math.min(viewport, safety);
        if (max <= 0) {
            throw new IllegalStateException("slash list maxHeight=" + max);
        }
        return max;
    }
}
