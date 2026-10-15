package dev.pi.android.ui;

public final class SheetHeightTest {
    public static void main(String[] args) {
        int screen = 2400;
        if (SheetHeight.LARGE < 0.70f || SheetHeight.LARGE > 0.80f) {
            throw new AssertionError("command menu must occupy ~70–80% screen, got " + SheetHeight.LARGE);
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
        System.out.println("SheetHeightTest ok");
    }
}
