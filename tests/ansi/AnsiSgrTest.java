import dev.pi.android.util.AnsiSgr;

/** Pure-Java unit tests for {@link AnsiSgr} (no Android runtime needed for parse/strip). */
public final class AnsiSgrTest {
    public static void main(String[] args) {
        // Truecolor with ESC
        String truecolor = "\u001b[38;2;0;127;255m$0.541\u001b[0m";
        AnsiSgr.Parsed p = AnsiSgr.parse(truecolor);
        assertEquals("$0.541", p.text);
        assert p.runs.size() == 1 : "expected one styled run, got " + p.runs.size();
        assert p.runs.get(0).fg != null && (p.runs.get(0).fg & 0xFFFFFF) == 0x007FFF
                : "fg=" + Integer.toHexString(p.runs.get(0).fg);

        // Bare CSI missing ESC (the App bug fragment)
        String bare = "mc: 30.5K · [38;2;0;127;255mc… $0.06";
        assertEquals("mc: 30.5K · c… $0.06", AnsiSgr.strip(bare));

        // 16-color + bold + reset
        String bold = "\u001b[1;32mOK\u001b[0m done";
        p = AnsiSgr.parse(bold);
        assertEquals("OK done", p.text);
        assert p.runs.size() == 1;
        assert p.runs.get(0).bold;
        assert p.runs.get(0).end == 2;

        // 256-color fg
        String c256 = "\u001b[38;5;196mX\u001b[39m";
        p = AnsiSgr.parse(c256);
        assertEquals("X", p.text);
        assert p.runs.size() == 1 && p.runs.get(0).fg != null;

        // OSC title strip
        String osc = "\u001b]0;title\u0007hello";
        assertEquals("hello", AnsiSgr.strip(osc));

        // Multi-entry style like TUI footer fragments
        String line = "\u001b[38;2;0;127;255m↑175k ↓6.3k\u001b[0m R1.3M";
        assertEquals("↑175k ↓6.3k R1.3M", AnsiSgr.strip(line));

        // Empty / null
        assertEquals("", AnsiSgr.strip(null));
        assertEquals("", AnsiSgr.strip(""));

        System.out.println("AnsiSgrTest OK");
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
