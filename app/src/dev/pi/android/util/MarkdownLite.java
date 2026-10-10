package dev.pi.android.util;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 极简 Markdown：`code`、**bold**、```fence```。
 * 代码 span 使用 {@link AppFonts#code(Context)}（MesloLGLDZ NF / 系统等宽）。
 */
public final class MarkdownLite {
    private MarkdownLite() {}

    /** 无 Context 时退回系统 monospace（旧调用兼容）。 */
    @NonNull
    public static CharSequence render(@Nullable String src, int codeBg, int codeFg) {
        return render(null, src, codeBg, codeFg);
    }

    @NonNull
    public static CharSequence render(@Nullable Context ctx, @Nullable String src, int codeBg, int codeFg) {
        if (src == null || src.isEmpty()) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("```", i)) {
                int end = src.indexOf("```", i + 3);
                if (end < 0) end = src.length();
                int start = out.length();
                String body = src.substring(i + 3, end);
                if (body.startsWith("\n")) body = body.substring(1);
                out.append(body);
                applyCode(ctx, out, start, out.length(), codeBg, codeFg);
                i = Math.min(src.length(), end + 3);
                continue;
            }
            if (src.charAt(i) == '`' ) {
                int end = src.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(src, i + 1, end);
                    applyCode(ctx, out, start, out.length(), codeBg, codeFg);
                    i = end + 1;
                    continue;
                }
            }
            if (src.startsWith("**", i)) {
                int end = src.indexOf("**", i + 2);
                if (end > i) {
                    int start = out.length();
                    out.append(src, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            out.append(src.charAt(i));
            i++;
        }
        return out;
    }

    private static void applyCode(@Nullable Context ctx, SpannableStringBuilder out,
                                  int start, int end, int codeBg, int codeFg) {
        if (start >= end) return;
        out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(codeFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Typeface tf = ctx != null ? AppFonts.code(ctx) : Typeface.MONOSPACE;
        out.setSpan(new CustomTypefaceSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class CustomTypefaceSpan extends MetricAffectingSpan {
        private final Typeface typeface;
        CustomTypefaceSpan(Typeface typeface) { this.typeface = typeface; }
        @Override public void updateDrawState(TextPaint tp) { tp.setTypeface(typeface); }
        @Override public void updateMeasureState(TextPaint tp) { tp.setTypeface(typeface); }
    }
}
