package dev.pi.android.util;

import androidx.annotation.Nullable;

/**
 * 会话列表标题/预览清洗：去掉 HTML 注释、pi-timestamp 等元数据泄漏。
 */
public final class SessionText {
    private SessionText() {}

    /** 去掉注释与标签后压空白；无实质内容时返回空串。 */
    public static String clean(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)</?[a-z][^>]*>", " ")
                .replaceAll("(?i)\\bpi-timestamp\\s*:\\s*\\S+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "(无预览)".equals(s)) return "";
        return s;
    }

    public static String ellipsize(String text, int maxChars) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= maxChars) return t;
        return t.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    /**
     * 标题优先：显式 name → 清洗后的 preview →「新对话」。
     * 预览行：清洗后的 preview（可与标题相同则留空给 UI 隐藏）。
     */
    public static String title(@Nullable String name, @Nullable String preview) {
        String n = clean(name);
        if (!n.isEmpty()) return ellipsize(n, 40);
        String p = clean(preview);
        if (!p.isEmpty()) return ellipsize(p, 40);
        return "新对话";
    }

    public static String previewLine(@Nullable String name, @Nullable String preview) {
        String p = clean(preview);
        if (p.isEmpty()) return "";
        String n = clean(name);
        if (!n.isEmpty() && p.equals(n)) return "";
        return ellipsize(p, 72);
    }
}
