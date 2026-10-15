package dev.pi.android.ui;

/**
 * 命令面板高度：占大半屏（约 70–80%），并在输入框上方留出可点遮罩。
 */
final class SheetHeight {

    /** 70–80% 屏高区间的取值。 */
    static final float LARGE = 0.75f;

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
}
