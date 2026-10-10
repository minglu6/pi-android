package dev.pi.android.model;

/** 聊天列表里的单条消息（用户 / Pi / 工具 / 系统提示）。 */
public class ChatMessage {
    public enum Role { USER, AGENT, TOOL, SYSTEM }

    public final Role role;
    public final String text;
    public final long timestampMs;

    public ChatMessage(Role role, String text) {
        this(role, text, System.currentTimeMillis());
    }

    public ChatMessage(Role role, String text, long timestampMs) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.timestampMs = timestampMs;
    }
}
