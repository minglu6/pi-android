package dev.pi.android.ui;

public final class SheetHeightTest {
    public static void main(String[] args) {
        int screen = 2400;
        if (SheetHeight.LARGE < 0.70f || SheetHeight.LARGE > 0.80f) {
            throw new AssertionError("LARGE overflow cap must stay ~70–80% screen, got " + SheetHeight.LARGE);
        }
        if (SheetHeight.SLASH_MAX_VISIBLE != 5 || SheetHeight.slashVisibleRowCap() != 5) {
            throw new AssertionError("slash maxVisible must match Pi default 5, got "
                    + SheetHeight.SLASH_MAX_VISIBLE + "/" + SheetHeight.slashVisibleRowCap());
        }

        int large = SheetHeight.capPx(screen, SheetHeight.LARGE);
        if (large != 1800) {
            throw new AssertionError("75% of 2400 must be 1800, got " + large);
        }
        if (large >= screen) {
            throw new AssertionError("capped height must leave tappable scrim");
        }

        int shortList = SheetHeight.hugOrCap(screen, 320, SheetHeight.LARGE);
        if (shortList != 320) {
            throw new AssertionError("short list hug still works, got " + shortList);
        }

        int parent = 2000;
        int minScrim = 96;
        int offset = SheetHeight.expandedOffsetPx(parent, screen, minScrim);
        if (parent - offset != large) {
            throw new AssertionError("sheet should be 75% of screen when parent is tall enough, offset=" + offset);
        }

        int smallParent = 1000;
        int imeOffset = SheetHeight.expandedOffsetPx(smallParent, screen, minScrim);
        if (imeOffset != minScrim) {
            throw new AssertionError("IME-shrunk host must keep min scrim, got " + imeOffset);
        }
        if (imeOffset >= smallParent) {
            throw new AssertionError("offset must leave a visible sheet");
        }

        int row = 40;
        int pad = 8;
        int viewport = SheetHeight.slashListViewportPx(row, pad);
        if (viewport != 208) {
            throw new AssertionError("5×40 + 8 must be 208, got " + viewport);
        }
        int header = 36;
        int listMax = SheetHeight.slashListMaxPx(screen, header, row, pad);
        if (listMax != viewport) {
            throw new AssertionError("slash list must hug 5-row viewport, got " + listMax);
        }
        int fiveRowPanel = header + listMax;
        if (fiveRowPanel >= large) {
            throw new AssertionError("slash panel must not stretch to 75% screen, panel="
                    + fiveRowPanel + " large=" + large);
        }

        int hugeRow = 500;
        int safetyList = large - header;
        int capped = SheetHeight.slashListMaxPx(screen, header, hugeRow, 0);
        if (capped != safetyList) {
            throw new AssertionError("overflow must still cap at LARGE-header, got " + capped);
        }

        try {
            SheetHeight.capPx(0, SheetHeight.LARGE);
            throw new AssertionError("screenPx=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("screenPx=0")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.expandedOffsetPx(0, screen, minScrim);
            throw new AssertionError("parentPx=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("parentPx=0")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.slashListViewportPx(0, 0);
            throw new AssertionError("rowHeightPx=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("rowHeightPx=0")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.slashListMaxPx(screen, -1, row, 0);
            throw new AssertionError("headerPx<0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("headerPx=-1")) {
                throw new AssertionError(expected);
            }
        }
        System.out.println("SheetHeightTest ok");
    }
}
