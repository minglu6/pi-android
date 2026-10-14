package dev.pi.android.ui;

public final class CardScrollCapTest {
    public static void main(String[] args) {
        int max = 400;

        int unspecified = CardScrollCap.clampHeight(
                CardScrollCap.make(0, CardScrollCap.UNSPECIFIED), max);
        assert CardScrollCap.mode(unspecified) == CardScrollCap.AT_MOST : "UNSPECIFIED must become AT_MOST";
        assert CardScrollCap.size(unspecified) == max : "UNSPECIFIED cap is max, not a fixed EXACTLY box";

        int shortAtMost = CardScrollCap.clampHeight(
                CardScrollCap.make(120, CardScrollCap.AT_MOST), max);
        assert CardScrollCap.mode(shortAtMost) == CardScrollCap.AT_MOST;
        assert CardScrollCap.size(shortAtMost) == 120 : "short content must keep its AT_MOST size";

        int longAtMost = CardScrollCap.clampHeight(
                CardScrollCap.make(900, CardScrollCap.AT_MOST), max);
        assert CardScrollCap.mode(longAtMost) == CardScrollCap.AT_MOST;
        assert CardScrollCap.size(longAtMost) == max;

        int shortExact = CardScrollCap.clampHeight(
                CardScrollCap.make(120, CardScrollCap.EXACTLY), max);
        assert CardScrollCap.mode(shortExact) == CardScrollCap.EXACTLY;
        assert CardScrollCap.size(shortExact) == 120;

        int longExact = CardScrollCap.clampHeight(
                CardScrollCap.make(900, CardScrollCap.EXACTLY), max);
        assert CardScrollCap.mode(longExact) == CardScrollCap.EXACTLY;
        assert CardScrollCap.size(longExact) == max : "must not reserve 40% screen when content is shorter";

        try {
            CardScrollCap.clampHeight(CardScrollCap.make(0, CardScrollCap.UNSPECIFIED), 0);
            throw new AssertionError("maxHeightPx=0 must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("maxHeightPx=0")) {
                throw new AssertionError(expected);
            }
        }
        System.out.println("CardScrollCapTest ok");
    }
}
