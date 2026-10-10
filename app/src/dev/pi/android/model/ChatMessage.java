package dev.pi.android.model;

/** 聊天列表里的单条消息（用户 / Pi / 工具 / 系统提示）。 */
public class ChatMessage {
    public enum Role { USER, AGENT, TOOL, SYSTEM }

    /**
     * 结构化工具调用卡片数据（协调器约定 API）。
     * state 取值：running / done / error / cancelled。
     * output 保留原文：UI 只做预览截断，不改这里存储的数据。
     */
    public static final class ToolData {
        public static final String STATE_RUNNING = "running";
        public static final String STATE_DONE = "done";
        public static final String STATE_ERROR = "error";
        public static final String STATE_CANCELLED = "cancelled";

        public final String id;
        public final String name;
        public final String command;
        public final String output;
        public final String state;
        public final long durationMs;

        public ToolData(String id, String name, String command, String output, String state, long durationMs) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
            this.output = output == null ? "" : output;
            this.state = state == null ? STATE_DONE : state;
            this.durationMs = durationMs;
        }
    }

    public final Role role;
    public final String text;
    public final long timestampMs;
    /** 流式尚未结束时为 true，气泡可显示「正在输出」感。 */
    public final boolean streaming;
    /** 结构化工具卡片数据；旧构造器产生的消息为 null，Adapter 走 legacy 文本 fallback。 */
    public final ToolData tool;

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
        this.tool = null;
    }

    private ChatMessage(ToolData data, long timestampMs) {
        this.role = Role.TOOL;
        this.text = "";
        this.timestampMs = timestampMs;
        this.streaming = false;
        this.tool = data;
    }

    /** 结构化工具卡片消息，role 固定为 TOOL。 */
    public static ChatMessage tool(ToolData data, long timestampMs) {
        return new ChatMessage(data, timestampMs);
    }
}
