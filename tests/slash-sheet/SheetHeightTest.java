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

        if (SheetHeight.PICKER != 0.78f) {
            throw new AssertionError("PICKER ratio must stay 0.78, got " + SheetHeight.PICKER);
        }
        int host = 2400;
        int sysBottom = 80;
        int sysTop = 96;
        int preferred = SheetHeight.capPx(screen, SheetHeight.PICKER);
        if (preferred != 1872) {
            throw new AssertionError("78% of 2400 must be 1872, got " + preferred);
        }
        if (SheetHeight.imeVisible(0, sysBottom) || SheetHeight.imeVisible(sysBottom, sysBottom)) {
            throw new AssertionError("nav-only inset is not IME");
        }
        if (SheetHeight.pickerBottomPaddingPx(0, sysBottom) != 0
                || SheetHeight.pickerBottomPaddingPx(sysBottom, sysBottom) != 0) {
            throw new AssertionError("no-IME picker must not pad nav");
        }
        int closedContent = SheetHeight.pickerContentPx(screen, host, 0, sysBottom);
        int closedHeight = SheetHeight.pickerSheetHeightPx(screen, host, 0, sysBottom);
        if (closedContent != preferred || closedHeight != preferred) {
            throw new AssertionError("no-IME picker stays 78% screen, content="
                    + closedContent + " height=" + closedHeight);
        }
        if (SheetHeight.pickerTopPaddingPx(host, closedHeight, sysTop) != 0) {
            throw new AssertionError("78% sheet top is below status bar, must not pad");
        }

        int ime = 1000;
        if (!SheetHeight.imeVisible(ime, sysBottom)) {
            throw new AssertionError("ime 1000 must count as keyboard");
        }
        int imePad = SheetHeight.pickerBottomPaddingPx(ime, sysBottom);
        int content = SheetHeight.pickerContentPx(screen, host, ime, sysBottom);
        int height = SheetHeight.pickerSheetHeightPx(screen, host, ime, sysBottom);
        if (imePad != ime) {
            throw new AssertionError("IME padding must equal ime inset, got " + imePad);
        }
        if (content != host - ime) {
            throw new AssertionError("IME content must fill above keyboard, got " + content);
        }
        if (height != host) {
            throw new AssertionError("IME sheet height must be content+padding occupying IME zone, got "
                    + height);
        }
        if (height == content) {
            throw new AssertionError("#63-style shrink-without-padding leaves list behind keyboard");
        }
        if (content != 1400) {
            throw new AssertionError("1400px must remain above 1000px IME, got " + content);
        }
        int topPad = SheetHeight.pickerTopPaddingPx(host, height, sysTop);
        if (topPad != sysTop) {
            throw new AssertionError("full-bleed IME sheet must pad status bar, got " + topPad);
        }

        try {
            SheetHeight.pickerContentPx(screen, 0, ime, sysBottom);
            throw new AssertionError("hostPx=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("hostPx=0")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.pickerContentPx(screen, 900, 1000, sysBottom);
            throw new AssertionError("IME taller than host must throw");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("picker available=")) {
                throw new AssertionError(expected);
            }
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
