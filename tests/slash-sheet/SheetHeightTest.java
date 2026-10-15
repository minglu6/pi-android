package dev.pi.android.ui;

public final class SheetHeightTest {
    public static void main(String[] args) {
        int screen = 2400;
        if (SheetHeight.HALF != 0.5f) {
            throw new AssertionError("command menu cap must be half screen");
        }

        int half = SheetHeight.capPx(screen, SheetHeight.HALF);
        if (half != 1200) {
            throw new AssertionError("half of 2400 must be 1200, got " + half);
        }

        int shortList = SheetHeight.hugOrCap(screen, 320, SheetHeight.HALF);
        if (shortList != 320) {
            throw new AssertionError("short list must hug content, got " + shortList);
        }

        int longList = SheetHeight.hugOrCap(screen, 2200, SheetHeight.HALF);
        if (longList != half) {
            throw new AssertionError("long list must cap at half screen, got " + longList);
        }
        if (longList >= screen) {
            throw new AssertionError("capped height must leave tappable scrim");
        }

        try {
            SheetHeight.capPx(0, SheetHeight.HALF);
            throw new AssertionError("screenPx=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("screenPx=0")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.hugOrCap(screen, -1, SheetHeight.HALF);
            throw new AssertionError("contentPx<0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("contentPx=-1")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.capPx(screen, 0f);
            throw new AssertionError("ratio=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("ratio=0")) {
                throw new AssertionError(expected);
            }
        }
        try {
            SheetHeight.capPx(screen, 1.2f);
            throw new AssertionError("ratio>1 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("ratio=1.2")) {
                throw new AssertionError(expected);
            }
        }
        System.out.println("SheetHeightTest ok");
    }
}
