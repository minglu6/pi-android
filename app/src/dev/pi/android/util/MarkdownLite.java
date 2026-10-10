package dev.pi.android.util;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 极简 Markdown：粗体 / 行内代码 / 围栏代码块。不引入第三方库，够日常聊天用。
 * 代码 span 优先使用 {@link AppFonts#code(Context)}（MesloLGLDZ NF / 系统等宽）。
 */
public final class MarkdownLite {
    private MarkdownLite() {}

    /** 无 Context 时退回系统 monospace（旧调用兼容）。 */
    @NonNull
    public static CharSequence render(@Nullable String raw, int codeBg, int codeFg) {
        return render(null, raw, codeBg, codeFg);
    }

    @NonNull
    public static CharSequence render(@Nullable Context ctx, @Nullable String raw, int codeBg, int codeFg) {
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
                applyCode(ctx, out, start, out.length(), codeBg, codeFg);
                i = endFence + 3;
                if (i < n && raw.charAt(i) == '\n') i++;
                continue;
            }
            if (raw.charAt(i) == '`' && (i + 1 < n && raw.charAt(i + 1) != '`')) {
                int end = raw.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(raw, i + 1, end);
                    applyCode(ctx, out, start, out.length(), codeBg, codeFg);
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

    private static void applyCode(@Nullable Context ctx, SpannableStringBuilder out,
                                  int start, int end, int codeBg, int codeFg) {
        if (start >= end) return;
        Typeface tf = ctx != null ? AppFonts.code(ctx) : Typeface.MONOSPACE;
        out.setSpan(new CustomTypefaceSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(codeFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class CustomTypefaceSpan extends MetricAffectingSpan {
        private final Typeface typeface;

        CustomTypefaceSpan(Typeface typeface) {
            this.typeface = typeface;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setTypeface(typeface);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            tp.setTypeface(typeface);
        }
    }
}
