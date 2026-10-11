package dev.pi.android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pocket-style rich fence kinds for assistant replies (SVG / HTML / diff).
 * Mirrors pi-pocket {@code richKind()} without a JS runtime.
 */
public final class RichBlocks {
    private RichBlocks() {}

    public enum Kind {
        NONE,
        SVG,
        HTML,
        DIFF
    }

    @NonNull
    public static Kind kind(@Nullable String lang, @Nullable String body) {
        String l = lang == null ? "" : lang.trim().toLowerCase();
        String b = body == null ? "" : body.trim();
        if ("svg".equals(l) || (("xml".equals(l) || l.isEmpty()) && looksLikeSvg(b))) {
            return Kind.SVG;
        }
        if ("html".equals(l) || "htm".equals(l) || "xhtml".equals(l)
                || (l.isEmpty() && looksLikeHtml(b))) {
            return Kind.HTML;
        }
        if ("diff".equals(l) || "patch".equals(l)) {
            return Kind.DIFF;
        }
        return Kind.NONE;
    }

    public static boolean looksLikeSvg(@NonNull String body) {
        String t = body.trim();
        if (t.regionMatches(true, 0, "<?xml", 0, 5)) {
            int gt = t.indexOf('>');
            if (gt > 0) t = t.substring(gt + 1).trim();
        }
        return t.regionMatches(true, 0, "<svg", 0, 4)
                && (t.length() == 4 || Character.isWhitespace(t.charAt(4)) || t.charAt(4) == '>');
    }

    public static boolean looksLikeHtml(@NonNull String body) {
        String t = body.trim();
        if (t.regionMatches(true, 0, "<!doctype", 0, 9)) return true;
        if (t.regionMatches(true, 0, "<html", 0, 5)) return true;
        return t.regionMatches(true, 0, "<head", 0, 5)
                || t.regionMatches(true, 0, "<body", 0, 5);
    }

    /** Strip scripts / handlers for sandboxed HTML preview (JS off). */
    @NonNull
    public static String sanitizeHtmlPreview(@NonNull String source) {
        String s = source;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        s = s.replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "");
        s = s.replaceAll("(?is)<object[^>]*>.*?</object>", "");
        s = s.replaceAll("(?is)<embed[^>]*>.*?</embed>", "");
        s = s.replaceAll("(?is)<link[^>]*>", "");
        s = s.replaceAll("(?is)<base[^>]*>", "");
        s = s.replaceAll("(?is)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?is)<meta[^>]*http-equiv[^>]*>", "");
        return s;
    }

    @NonNull
    public static String previewDocument(@NonNull String body) {
        String inner = sanitizeHtmlPreview(body);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\""
                + "default-src 'none'; style-src 'unsafe-inline'; img-src data:; "
                + "font-src 'none'; media-src 'none'; frame-src 'none'; script-src 'none';"
                + "\">"
                + "<style>html,body{margin:0;padding:8px;background:#111;color:#e8e8e8;"
                + "font:13px/1.4 -apple-system,sans-serif;word-wrap:break-word;}"
                + "img{max-width:100%;height:auto;}</style></head><body>"
                + inner + "</body></html>";
    }
}
