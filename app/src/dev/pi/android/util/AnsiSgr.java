package dev.pi.android.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse ANSI SGR into colored/bold spans for extension status / widget text.
 * Also strips CSI/OSC sequences that lost their ESC byte (e.g. {@code [38;2;0;127;255m}).
 *
 * <p>Supported: reset, bold, 16-color / 256-color / truecolor fg &amp; bg.
 */
public final class AnsiSgr {
    private AnsiSgr() {}

    public static final class Run {
        public final int start;
        public final int end;
        @Nullable public final Integer fg;
        @Nullable public final Integer bg;
        public final boolean bold;

        public Run(int start, int end, @Nullable Integer fg, @Nullable Integer bg, boolean bold) {
            this.start = start;
            this.end = end;
            this.fg = fg;
            this.bg = bg;
            this.bold = bold;
        }
    }

    public static final class Parsed {
        @NonNull public final String text;
        @NonNull public final List<Run> runs;

        public Parsed(@NonNull String text, @NonNull List<Run> runs) {
            this.text = text;
            this.runs = runs;
        }
    }

    /** Strip all CSI/OSC (with or without ESC) and return plain text. */
    @NonNull
    public static String strip(@Nullable String raw) {
        return parse(raw).text;
    }

    @NonNull
    public static CharSequence render(@Nullable String raw) {
        Parsed p = parse(raw);
        SpannableStringBuilder out = new SpannableStringBuilder(p.text);
        for (Run r : p.runs) {
            if (r.start >= r.end || r.start < 0 || r.end > out.length()) continue;
            if (r.fg != null) {
                out.setSpan(new ForegroundColorSpan(r.fg), r.start, r.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.bg != null) {
                out.setSpan(new BackgroundColorSpan(r.bg), r.start, r.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.bold) {
                out.setSpan(new StyleSpan(Typeface.BOLD), r.start, r.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    @NonNull
    public static Parsed parse(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return new Parsed("", new ArrayList<>());
        StringBuilder text = new StringBuilder(raw.length());
        List<Run> runs = new ArrayList<>();
        Integer fg = null;
        Integer bg = null;
        boolean bold = false;
        int runStart = 0;

        int i = 0;
        final int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            boolean hasEsc = c == 0x1B;
            if (hasEsc) {
                int j = i + 1;
                if (j >= n) { i++; continue; }
                char intro = raw.charAt(j);
                if (intro == '[') {
                    j++;
                    int body = j;
                    while (j < n) {
                        char f = raw.charAt(j);
                        if (f >= 0x40 && f <= 0x7E) break;
                        j++;
                    }
                    if (j >= n) break;
                    char finalByte = raw.charAt(j);
                    String params = raw.substring(body, j);
                    if (finalByte == 'm') {
                        if (text.length() > runStart) {
                            runs.add(new Run(runStart, text.length(), fg, bg, bold));
                        }
                        Integer[] state = applySgr(params, fg, bg, bold);
                        fg = state[0];
                        bg = state[1];
                        bold = state[2] != null && state[2] == 1;
                        runStart = text.length();
                    }
                    i = j + 1;
                    continue;
                }
                if (intro == ']') {
                    j++;
                    while (j < n) {
                        char f = raw.charAt(j);
                        if (f == 0x07) { j++; break; }
                        if (f == 0x1B && j + 1 < n && raw.charAt(j + 1) == '\\') {
                            j += 2;
                            break;
                        }
                        j++;
                    }
                    i = j;
                    continue;
                }
                // Unknown ESC — drop the ESC byte only
                i++;
                continue;
            }
            // Bare CSI missing ESC: only strip SGR-shaped `[digits/semicolons m`
            // so normal text like `[info]` is preserved.
            if (c == '[') {
                int j = i + 1;
                while (j < n) {
                    char f = raw.charAt(j);
                    if ((f >= '0' && f <= '9') || f == ';') {
                        j++;
                        continue;
                    }
                    break;
                }
                if (j < n && raw.charAt(j) == 'm' && j > i + 1) {
                    String params = raw.substring(i + 1, j);
                    if (text.length() > runStart) {
                        runs.add(new Run(runStart, text.length(), fg, bg, bold));
                    }
                    Integer[] state = applySgr(params, fg, bg, bold);
                    fg = state[0];
                    bg = state[1];
                    bold = state[2] != null && state[2] == 1;
                    runStart = text.length();
                    i = j + 1;
                    continue;
                }
            }
            text.append(c);
            i++;
        }
        if (text.length() > runStart) {
            runs.add(new Run(runStart, text.length(), fg, bg, bold));
        }
        List<Run> styled = new ArrayList<>();
        for (Run r : runs) {
            if (r.fg != null || r.bg != null || r.bold) styled.add(r);
        }
        return new Parsed(text.toString(), styled);
    }

    /** Returns {fg, bg, boldAsInt} after applying SGR params. Package-visible for tests. */
    @NonNull
    static Integer[] applySgr(@NonNull String params,
                              @Nullable Integer fg, @Nullable Integer bg, boolean bold) {
        Integer curFg = fg;
        Integer curBg = bg;
        boolean curBold = bold;
        if (params.isEmpty() || "0".equals(params)) {
            return new Integer[]{null, null, 0};
        }
        String[] parts = params.split(";");
        int k = 0;
        while (k < parts.length) {
            int code = parseInt(parts[k], -1);
            if (code == 0) {
                curFg = null;
                curBg = null;
                curBold = false;
                k++;
            } else if (code == 1) {
                curBold = true;
                k++;
            } else if (code == 22) {
                curBold = false;
                k++;
            } else if (code == 39) {
                curFg = null;
                k++;
            } else if (code == 49) {
                curBg = null;
                k++;
            } else if (code >= 30 && code <= 37) {
                curFg = ansi16(code - 30, false);
                k++;
            } else if (code >= 90 && code <= 97) {
                curFg = ansi16(code - 90, true);
                k++;
            } else if (code >= 40 && code <= 47) {
                curBg = ansi16(code - 40, false);
                k++;
            } else if (code >= 100 && code <= 107) {
                curBg = ansi16(code - 100, true);
                k++;
            } else if (code == 38 || code == 48) {
                boolean isFg = code == 38;
                Integer color = null;
                if (k + 1 < parts.length) {
                    int mode = parseInt(parts[k + 1], -1);
                    if (mode == 5 && k + 2 < parts.length) {
                        color = ansi256(parseInt(parts[k + 2], 0));
                        k += 3;
                    } else if (mode == 2 && k + 4 < parts.length) {
                        color = rgb(
                                parseInt(parts[k + 2], 0),
                                parseInt(parts[k + 3], 0),
                                parseInt(parts[k + 4], 0));
                        k += 5;
                    } else {
                        k++;
                    }
                } else {
                    k++;
                }
                if (color != null) {
                    if (isFg) curFg = color;
                    else curBg = color;
                }
            } else {
                k++;
            }
        }
        return new Integer[]{curFg, curBg, curBold ? 1 : 0};
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000
                | ((clamp(r) & 0xFF) << 16)
                | ((clamp(g) & 0xFF) << 8)
                | (clamp(b) & 0xFF);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int ansi16(int index, boolean bright) {
        final int[] dark = {
                0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510,
                0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5
        };
        final int[] lite = {
                0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543,
                0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF
        };
        int i = Math.max(0, Math.min(7, index));
        return bright ? lite[i] : dark[i];
    }

    private static int ansi256(int n) {
        n = Math.max(0, Math.min(255, n));
        if (n < 16) return ansi16(n % 8, n >= 8);
        if (n < 232) {
            int i = n - 16;
            int r = i / 36, g = (i / 6) % 6, b = i % 6;
            return rgb(r == 0 ? 0 : 55 + r * 40, g == 0 ? 0 : 55 + g * 40, b == 0 ? 0 : 55 + b * 40);
        }
        int gray = 8 + (n - 232) * 10;
        return rgb(gray, gray, gray);
    }
}
