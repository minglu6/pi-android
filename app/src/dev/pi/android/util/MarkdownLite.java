package dev.pi.android.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 极简 Markdown：粗体 / 行内代码 / 围栏代码块。不引入第三方库，够日常聊天用。
 */
public final class MarkdownLite {
    private MarkdownLite() {}

    public static CharSequence render(String raw, int codeBg, int codeFg) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        final int n = raw.length();
        while (i < n) {
            if (raw.startsWith("```", i)) {
                int endFence = raw.indexOf("```", i + 3);
                if (endFence < 0) {
                    out.append(raw.substring(i));
                    break;
                }
                int lineEnd = raw.indexOf('\n', i + 3);
                int contentStart = (lineEnd >= 0 && lineEnd < endFence) ? lineEnd + 1 : i + 3;
                String code = raw.substring(contentStart, endFence);
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                int start = out.length();
                out.append(code);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(codeFg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = endFence + 3;
                if (i < n && raw.charAt(i) == '\n') i++;
                continue;
            }
            if (raw.charAt(i) == '`' && (i + 1 < n && raw.charAt(i + 1) != '`')) {
                int end = raw.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(raw, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(codeFg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (raw.startsWith("**", i)) {
                int end = raw.indexOf("**", i + 2);
                if (end > i) {
                    int start = out.length();
                    out.append(raw, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            out.append(raw.charAt(i));
            i++;
        }
        return out;
    }
}
