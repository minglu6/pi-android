package dev.pi.android.ui;

/**
 * Modal bottom sheet 高度：矮内容贴合，高内容按屏幕比例封顶。
 * 命令菜单默认半屏，必须留出可点的外部区域。
 */
final class SheetHeight {

    static final float HALF = 0.5f;

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
}
