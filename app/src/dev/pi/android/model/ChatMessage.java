package dev.pi.android.model;

/** 一屏里的单条消息。日后接桥后由 {@code bridge/GatewayClient} 的事件流填充，现在先本地驱动。 */
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
