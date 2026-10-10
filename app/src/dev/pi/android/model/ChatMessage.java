package dev.pi.android.model;

/** 聊天列表里的单条消息（用户 / Pi / 工具 / 系统提示）。 */
public class ChatMessage {
    public enum Role { USER, AGENT, TOOL, SYSTEM }

    public final Role role;
    public final String text;
    public final long timestampMs;
    /** 流式尚未结束时为 true，气泡可显示「正在输出」感。 */
    public final boolean streaming;

    public ChatMessage(Role role, String text) {
        this(role, text, System.currentTimeMillis(), false);
    }

    public ChatMessage(Role role, String text, boolean streaming) {
        this(role, text, System.currentTimeMillis(), streaming);
    }

    public ChatMessage(Role role, String text, long timestampMs) {
        this(role, text, timestampMs, false);
    }

    public ChatMessage(Role role, String text, long timestampMs, boolean streaming) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.timestampMs = timestampMs;
        this.streaming = streaming;
    }
}
