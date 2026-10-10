package dev.pi.android.util;

import androidx.annotation.Nullable;

/**
 * 会话列表标题/预览清洗：去掉 HTML 注释、pi-timestamp 等元数据泄漏。
 * 标题由 TextView 单行 ellipsize，此处不再硬截断。
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

    /**
     * 标题优先：显式 name → 清洗后的 preview →「新对话」。
     * 不在此截断——列表 TextView 单行 end-ellipsize 按可用宽度裁切。
     */
    public static String title(@Nullable String name, @Nullable String preview) {
        String n = clean(name);
        if (!n.isEmpty()) return n;
        String p = clean(preview);
        if (!p.isEmpty()) return p;
        return "新对话";
    }

    /**
     * 预览行：与标题重复则返回空（UI 隐藏）。
     * 无显式 name 时 title 已用 preview，预览行必须隐藏。
     */
    public static String previewLine(@Nullable String name, @Nullable String preview) {
        String p = clean(preview);
        if (p.isEmpty()) return "";
        String n = clean(name);
        if (n.isEmpty()) return "";
        if (p.equals(n)) return "";
        return p;
    }
}
