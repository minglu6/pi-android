package dev.pi.android.ui;

/**
 * 面板高度：其它 sheet / 溢出安全上限约 70–80% 屏高；
 * 斜杠命令列表视口对齐 Pi TUI {@code autocompleteMaxVisible} 默认 5 行。
 */
final class SheetHeight {

    /** 70–80% 屏高区间的取值：溢出安全上限，不是 slash 的有效高度。 */
    static final float LARGE = 0.75f;

    /** PickerSheet 无 IME 时的展开比例（相对屏高）。 */
    static final float PICKER = 0.78f;

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

    /** IME 是否盖住导航栏以上的区域（键盘打开）。 */
    static boolean imeVisible(int imeBottomPx, int sysBottomPx) {
        if (imeBottomPx < 0) {
            throw new IllegalArgumentException("imeBottomPx=" + imeBottomPx);
        }
        if (sysBottomPx < 0) {
            throw new IllegalArgumentException("sysBottomPx=" + sysBottomPx);
        }
        return imeBottomPx > sysBottomPx;
    }

    /**
     * Picker 底 padding：键盘打开时等于 IME inset，让内容底边贴齐键盘上沿。
     * 键盘收起时为 0——不在这里垫导航栏，避免和无键盘的 78% 外观打架。
     * <p>
     * 不能只把 {@code layout.height} 改成「键盘上方可见高度」却仍锚在屏幕底：
     * sheet 会整块缩进键盘后面（#63）。必须把 IME 高度垫在 sheet 底部。
     */
    static int pickerBottomPaddingPx(int imeBottomPx, int sysBottomPx) {
        return imeVisible(imeBottomPx, sysBottomPx) ? imeBottomPx : 0;
    }

    /**
     * Picker 内容区高度（不含 IME padding）。
     * 键盘打开：铺满 host 减去 IME；键盘收起：78% 屏高。
     */
    static int pickerContentPx(int screenPx, int hostPx, int imeBottomPx, int sysBottomPx) {
        if (hostPx <= 0) {
            throw new IllegalArgumentException("hostPx=" + hostPx);
        }
        int pad = pickerBottomPaddingPx(imeBottomPx, sysBottomPx);
        int available = hostPx - pad;
        if (available <= 0) {
            throw new IllegalStateException("picker available=" + available
                    + " hostPx=" + hostPx + " pad=" + pad);
        }
        int preferred = capPx(screenPx, PICKER);
        if (imeVisible(imeBottomPx, sysBottomPx)) {
            return available;
        }
        return Math.min(preferred, available);
    }

    /** design_bottom_sheet 总高度 = 内容 + IME 底 padding。 */
    static int pickerSheetHeightPx(int screenPx, int hostPx, int imeBottomPx, int sysBottomPx) {
        int content = pickerContentPx(screenPx, hostPx, imeBottomPx, sysBottomPx);
        int pad = pickerBottomPaddingPx(imeBottomPx, sysBottomPx);
        int height = Math.addExact(content, pad);
        if (height > hostPx) {
            throw new IllegalStateException("picker height=" + height + " hostPx=" + hostPx);
        }
        return height;
    }

    /**
     * 键盘打开且 sheet 铺到顶时，避开状态栏；78% 矮 sheet 顶部已在状态栏下则不垫。
     */
    static int pickerTopPaddingPx(int hostPx, int sheetHeightPx, int sysTopPx) {
        if (hostPx <= 0) {
            throw new IllegalArgumentException("hostPx=" + hostPx);
        }
        if (sheetHeightPx <= 0) {
            throw new IllegalArgumentException("sheetHeightPx=" + sheetHeightPx);
        }
        if (sysTopPx < 0) {
            throw new IllegalArgumentException("sysTopPx=" + sysTopPx);
        }
        int sheetTop = hostPx - sheetHeightPx;
        if (sheetTop < 0) {
            throw new IllegalStateException("sheetTop=" + sheetTop);
        }
        int overlap = sysTopPx - sheetTop;
        return Math.max(0, overlap);
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
