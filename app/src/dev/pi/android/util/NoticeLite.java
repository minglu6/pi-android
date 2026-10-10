package dev.pi.android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pi.android.model.ChatMessage;

/**
 * Build Pocket-style notice cards from extension_notify / stream errors / raw Pi copy.
 * Chinese titles when we generate them; server English kept as detail.
 */
public final class NoticeLite {
    private static final Pattern RUN_SLASH = Pattern.compile(
            "(?i)(?:^|\\n)\\s*Run\\s+(/[\\w.-]+)\\s+to\\s+fix\\.?\\s*$");
    private static final Pattern MCP_HEAD = Pattern.compile(
            "(?i)^\\s*MCP\\s+servers?\\s+need\\s+attention\\s*:\\s*(.*)$", Pattern.DOTALL);

    private NoticeLite() {}

    @NonNull
    public static ChatMessage.NoticeData fromNotify(@Nullable String message, @Nullable String notifyType) {
        String raw = stripDecor(message);
        String level = levelOf(notifyType, raw);
        ParsedSlash slash = extractSlash(raw);
        String body = slash.body;

        Matcher mcp = MCP_HEAD.matcher(body);
        if (mcp.find()) {
            String detail = mcp.group(1) == null ? "" : mcp.group(1).trim();
            String actionCmd = slash.command.isEmpty() ? "/mcp" : slash.command;
            return new ChatMessage.NoticeData(level,
                    "MCP 服务需要处理",
                    detail,
                    "运行 " + actionCmd,
                    actionCmd);
        }

        String title = defaultTitle(level);
        return new ChatMessage.NoticeData(level, title, body,
                slash.command.isEmpty() ? "" : "运行 " + slash.command,
                slash.command);
    }

    @NonNull
    public static ChatMessage.NoticeData error(@Nullable String detail) {
        return fromNotify(detail, ChatMessage.NoticeData.LEVEL_ERROR);
    }

    @NonNull
    public static ChatMessage.NoticeData warning(@Nullable String detail) {
        return fromNotify(detail, ChatMessage.NoticeData.LEVEL_WARNING);
    }

    @NonNull
    public static ChatMessage.NoticeData info(@Nullable String detail) {
        return fromNotify(detail, ChatMessage.NoticeData.LEVEL_INFO);
    }

    /** Parse legacy SYSTEM chip text ("⚠ …") into a notice card. */
    @NonNull
    public static ChatMessage.NoticeData fromLegacyText(@Nullable String text) {
        String raw = stripDecor(text);
        String level = ChatMessage.NoticeData.LEVEL_INFO;
        if (text != null) {
            if (text.startsWith("⚠") || text.startsWith("✗")) level = ChatMessage.NoticeData.LEVEL_ERROR;
            else if (text.startsWith("△") || text.startsWith("!")) level = ChatMessage.NoticeData.LEVEL_WARNING;
        }
        return fromNotify(raw, level);
    }

    private static String levelOf(@Nullable String notifyType, String raw) {
        String t = notifyType == null ? "" : notifyType.trim().toLowerCase(Locale.US);
        if ("error".equals(t) || "err".equals(t) || "danger".equals(t)) {
            return ChatMessage.NoticeData.LEVEL_ERROR;
        }
        if ("warning".equals(t) || "warn".equals(t) || "caution".equals(t)) {
            return ChatMessage.NoticeData.LEVEL_WARNING;
        }
        if ("info".equals(t) || "success".equals(t) || "ok".equals(t)) {
            return ChatMessage.NoticeData.LEVEL_INFO;
        }
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("failed") || lower.contains("error") || lower.contains("timeout")
                || lower.contains("timed out")) {
            return ChatMessage.NoticeData.LEVEL_WARNING;
        }
        return ChatMessage.NoticeData.LEVEL_INFO;
    }

    private static String defaultTitle(String level) {
        if (ChatMessage.NoticeData.LEVEL_ERROR.equals(level)) return "出错了";
        if (ChatMessage.NoticeData.LEVEL_WARNING.equals(level)) return "需要注意";
        return "提示";
    }

    private static String stripDecor(@Nullable String message) {
        if (message == null) return "";
        String s = message.trim();
        if (s.startsWith("⚠")) s = s.substring(1).trim();
        else if (s.startsWith("△")) s = s.substring(1).trim();
        else if (s.startsWith("•")) s = s.substring(1).trim();
        else if (s.startsWith("✗")) s = s.substring(1).trim();
        return s;
    }

    private static ParsedSlash extractSlash(String raw) {
        Matcher m = RUN_SLASH.matcher(raw);
        if (!m.find()) return new ParsedSlash(raw.trim(), "");
        String cmd = m.group(1);
        String body = raw.substring(0, m.start()).trim();
        return new ParsedSlash(body, cmd == null ? "" : cmd);
    }

    private static final class ParsedSlash {
        final String body;
        final String command;
        ParsedSlash(String body, String command) {
            this.body = body;
            this.command = command;
        }
    }
}
