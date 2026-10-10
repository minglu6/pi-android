package dev.pi.android.util;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Formats edit/write tool output as a colored unified-ish diff with optional line numbers.
 */
public final class DiffLite {
    private DiffLite() {}

    @NonNull
    public static CharSequence render(@Nullable String raw, int addColor, int delColor,
                                      int metaColor, int textColor) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = raw.split("\n", -1);
        int lineNo = 0;
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            int color = textColor;
            String prefix = "  ";
            String body = line;
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")
                    || line.startsWith("diff ")) {
                color = metaColor;
                prefix = "";
            } else if (line.startsWith("+")) {
                color = addColor;
                prefix = "+ ";
                body = line.substring(1);
                lineNo++;
            } else if (line.startsWith("-")) {
                color = delColor;
                prefix = "- ";
                body = line.substring(1);
                lineNo++;
            } else if (line.startsWith(" ")) {
                prefix = "  ";
                body = line.substring(1);
                lineNo++;
            } else {
                lineNo++;
            }
            int start = out.length();
            if (!prefix.isEmpty() && (line.startsWith("+") || line.startsWith("-") || line.startsWith(" "))) {
                out.append(String.format(java.util.Locale.US, "%4d ", lineNo));
                out.append(prefix);
                out.append(body);
            } else {
                out.append(prefix.isEmpty() ? line : prefix + body);
            }
            out.setSpan(new ForegroundColorSpan(color), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** True when output looks like a unified diff or add/del hunks. */
    public static boolean looksLikeDiff(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return false;
        int plus = 0, minus = 0;
        for (String line : raw.split("\n")) {
            if (line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("@@ ")) return true;
            if (line.startsWith("+") && !line.startsWith("+++")) plus++;
            if (line.startsWith("-") && !line.startsWith("---")) minus++;
        }
        return plus + minus >= 2;
    }
}
