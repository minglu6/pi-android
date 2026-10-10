package dev.pi.android.util;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, dependency-free syntax tint for common languages.
 * Keyword / string / comment coloring only — good enough for chat code blocks.
 */
public final class SyntaxLite {
    private SyntaxLite() {}

    private static final Pattern STRING = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern LINE_COMMENT = Pattern.compile(
            "(?m)//.*?$|#.*?$");
    private static final Pattern BLOCK_COMMENT = Pattern.compile(
            "/\\*[\\s\\S]*?\\*/");
    private static final Pattern NUMBER = Pattern.compile(
            "\\b\\d+(?:\\.\\d+)?\\b");

    private static final Set<String> KW_COMMON = set(
            "if", "else", "for", "while", "return", "class", "interface", "struct",
            "public", "private", "protected", "static", "final", "const", "let", "var",
            "function", "def", "fn", "import", "from", "export", "package", "try", "catch",
            "throw", "new", "this", "super", "extends", "implements", "async", "await",
            "switch", "case", "break", "continue", "null", "true", "false", "undefined",
            "void", "int", "long", "float", "double", "boolean", "string", "type", "enum",
            "yield", "with", "as", "in", "of", "and", "or", "not", "None", "True", "False",
            "fun", "val", "var", "when", "object", "companion", "override", "suspend");

    @NonNull
    public static CharSequence highlight(@NonNull String code, @NonNull String lang,
                                         int keyword, int string, int comment, int number) {
        SpannableStringBuilder out = new SpannableStringBuilder(code);
        // Order: comments → strings → numbers → keywords (later spans win visually on overlap
        // if we apply non-overlapping ranges carefully; we skip already-spanned ranges).
        apply(out, BLOCK_COMMENT, comment);
        apply(out, LINE_COMMENT, comment);
        apply(out, STRING, string);
        apply(out, NUMBER, number);
        applyKeywords(out, keyword);
        return out;
    }

    private static void apply(SpannableStringBuilder out, Pattern p, int color) {
        Matcher m = p.matcher(out);
        while (m.find()) {
            if (hasColor(out, m.start(), m.end())) continue;
            out.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void applyKeywords(SpannableStringBuilder out, int color) {
        Matcher m = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(out);
        while (m.find()) {
            if (hasColor(out, m.start(), m.end())) continue;
            if (KW_COMMON.contains(m.group())) {
                out.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static boolean hasColor(SpannableStringBuilder out, int start, int end) {
        ForegroundColorSpan[] spans = out.getSpans(start, end, ForegroundColorSpan.class);
        return spans != null && spans.length > 0;
    }

    private static Set<String> set(String... words) {
        Set<String> s = new HashSet<>();
        for (String w : words) s.add(w);
        return s;
    }

    @NonNull
    public static String normalizeLang(@NonNull String raw) {
        String l = raw.trim().toLowerCase(Locale.ROOT);
        if (l.isEmpty()) return "text";
        if (l.equals("js") || l.equals("javascript") || l.equals("jsx")) return "javascript";
        if (l.equals("ts") || l.equals("typescript") || l.equals("tsx")) return "typescript";
        if (l.equals("py")) return "python";
        if (l.equals("kt")) return "kotlin";
        if (l.equals("rs")) return "rust";
        if (l.equals("sh") || l.equals("shell") || l.equals("zsh") || l.equals("bash")) return "bash";
        return l;
    }
}
