package dev.pi.android.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

/** 聊天列表里的单条消息（用户 / Pi / 工具 / 系统提示）。 */
public class ChatMessage {
    public enum Role { USER, AGENT, TOOL, SYSTEM }

    public static final int MAX_ATTACHMENTS = 16;
    public static final int MAX_IMAGE_DATA_CHARS = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_DATA_CHARS = 8 * 1024 * 1024;
    private static final int MAX_METADATA_CHARS = 64 * 1024;
    private static final String ATTACHMENT_START = "\n\n<pi_attachments_v1>\n";
    private static final String ATTACHMENT_END = "\n</pi_attachments_v1>";

    /** 纯 Java 附件 DTO。path 仅作描述，模型和聊天渲染均不读取/打开它。 */
    public static final class AttachmentData {
        public final String name;
        public final String mimeType;
        public final String kind;
        public final long size;
        public final String path;
        /** 可选 base64；乐观消息可传小缩略图，历史消息可传 RPC 图片数据。 */
        public final String imageData;

        public AttachmentData(String name, String mimeType, String kind, long size,
                              String path, String imageData) {
            this.name = bounded(name, 512);
            this.mimeType = bounded(mimeType, 128);
            this.kind = "image".equals(kind) ? "image" : "file";
            this.size = Math.max(0, size);
            this.path = path == null ? "" : path;
            this.imageData = imageData == null || imageData.length() > MAX_IMAGE_DATA_CHARS
                    ? null : imageData;
        }

        public AttachmentData(String name, String mimeType, String kind, long size, String path) {
            this(name, mimeType, kind, size, path, null);
        }
    }

    /** 可由发送协调器追加乐观附件；快照使用 copy() 隔离列表，DTO 本身不可变。 */
    public final List<AttachmentData> attachments = new ArrayList<>();

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

        public static final String STATE_PENDING = "pending";

        public final String id;
        public final String name;
        public final String command;
        public final String output;
        public final String state;
        public final long durationMs;
        /** Optional edit/write stats from tool result; -1 means absent (hide). */
        public final int additions;
        public final int deletions;
        /** Optional compact summary e.g. "48 passed · 3.1s"; empty means absent. */
        public final String summary;

        public ToolData(String id, String name, String command, String output, String state, long durationMs) {
            this(id, name, command, output, state, durationMs, -1, -1, "");
        }

        public ToolData(String id, String name, String command, String output, String state,
                        long durationMs, int additions, int deletions, String summary) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
            this.output = output == null ? "" : output;
            this.state = state == null ? STATE_DONE : state;
            this.durationMs = durationMs;
            this.additions = additions;
            this.deletions = deletions;
            this.summary = summary == null ? "" : summary;
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
        String original = text == null ? "" : text;
        this.text = role == Role.USER ? parseFinalAttachments(original, attachments) : original;
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

    private ChatMessage(ChatMessage source) {
        role = source.role;
        text = source.text;
        timestampMs = source.timestampMs;
        streaming = source.streaming;
        tool = source.tool;
        attachments.addAll(source.attachments);
    }

    public ChatMessage copy() { return new ChatMessage(this); }

    /** 原生 RPC 用户 content：只取 text/image，不把 base64 或工具块变成正文。 */
    public static ChatMessage fromUserContent(Object content, long timestampMs) {
        if (content instanceof String) return new ChatMessage(Role.USER, (String) content, timestampMs);
        JSONArray blocks = content instanceof JSONArray ? (JSONArray) content : null;
        StringBuilder body = new StringBuilder();
        List<AttachmentData> images = new ArrayList<>();
        int imageNumber = 0;
        int dataChars = 0;
        if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            if ("text".equals(block.optString("type")) && block.opt("text") instanceof String) {
                if (body.length() > 0) body.append('\n');
                body.append((String) block.opt("text"));
            } else if ("image".equals(block.optString("type")) && images.size() < MAX_ATTACHMENTS) {
                imageNumber++;
                Object raw = block.opt("data");
                String data = raw instanceof String ? (String) raw : null;
                if (data != null && (data.length() > MAX_IMAGE_DATA_CHARS
                        || data.length() > MAX_TOTAL_IMAGE_DATA_CHARS - dataChars)) data = null;
                if (data != null) dataChars += data.length();
                images.add(new AttachmentData("图片 " + imageNumber, block.optString("mimeType", ""),
                        "image", 0, "", data));
            }
        }
        ChatMessage result = new ChatMessage(Role.USER, body.toString(), timestampMs);
        // 原生图片在前，文件 marker 在后；两种来源共用展示数量上限。
        List<AttachmentData> files = new ArrayList<>(result.attachments);
        result.attachments.clear();
        result.attachments.addAll(images);
        for (AttachmentData file : files) {
            if (result.attachments.size() >= MAX_ATTACHMENTS) break;
            result.attachments.add(file);
        }
        return result;
    }

    public static ChatMessage fromUserContent(JSONObject message) {
        if (message == null) return new ChatMessage(Role.USER, "", 0);
        Object content = message.opt("content");
        if (!(content instanceof String) && !(content instanceof JSONArray)) content = message.opt("text");
        return fromUserContent(content, message.optLong("timestamp", 0));
    }

    /** 只剥离严格有效的尾部块；错误、截断、超限或嵌在正文中一律原样保留。 */
    private static String parseFinalAttachments(String original, List<AttachmentData> destination) {
        if (!original.endsWith(ATTACHMENT_END)) return original;
        int start = original.lastIndexOf(ATTACHMENT_START);
        if (start < 0) return original;
        int jsonStart = start + ATTACHMENT_START.length();
        int jsonEnd = original.length() - ATTACHMENT_END.length();
        if (jsonEnd < jsonStart || jsonEnd - jsonStart > MAX_METADATA_CHARS) return original;
        try {
            String json = original.substring(jsonStart, jsonEnd);
            // org.json 接受单引号、尾逗号等非 JSON 输入；先做严格语法验证。
            if (!StrictJson.valid(json)) return original;
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONArray) || tokener.nextClean() != 0) return original;
            JSONArray array = (JSONArray) value;
            if (array.length() > MAX_ATTACHMENTS) return original;
            List<AttachmentData> parsed = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject descriptor = array.optJSONObject(i);
                if (descriptor == null || !isString(descriptor, "name") || !isString(descriptor, "path")
                        || !isString(descriptor, "mimeType") || !isString(descriptor, "kind")) return original;
                String kind = descriptor.getString("kind");
                Object size = descriptor.opt("size");
                if (!(size instanceof Integer || size instanceof Long) || ((Number) size).longValue() < 0
                        || !("file".equals(kind) || "image".equals(kind))) return original;
                parsed.add(new AttachmentData(descriptor.getString("name"), descriptor.getString("mimeType"),
                        kind, ((Number) size).longValue(), descriptor.getString("path")));
            }
            destination.addAll(parsed);
            return original.substring(0, start);
        } catch (Exception ignored) {
            return original;
        }
    }

    private static boolean isString(JSONObject object, String key) {
        return object.opt(key) instanceof String;
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    /** 小型、有深度上限的 JSON 语法检查器，仅用于有限长尾部 metadata。 */
    private static final class StrictJson {
        final String source;
        int offset;
        StrictJson(String source) { this.source = source; }
        static boolean valid(String source) {
            try {
                StrictJson parser = new StrictJson(source);
                parser.value(0);
                parser.space();
                return parser.offset == source.length();
            } catch (IllegalArgumentException ignored) { return false; }
        }
        void space() {
            while (offset < source.length() && " \t\r\n".indexOf(source.charAt(offset)) >= 0) offset++;
        }
        boolean take(char c) {
            space();
            if (offset < source.length() && source.charAt(offset) == c) { offset++; return true; }
            return false;
        }
        void need(char c) { if (!take(c)) throw new IllegalArgumentException(); }
        void value(int depth) {
            if (depth > 16) throw new IllegalArgumentException();
            space();
            if (offset >= source.length()) throw new IllegalArgumentException();
            char c = source.charAt(offset);
            if (c == '"') { string(); return; }
            if (take('{')) {
                if (take('}')) return;
                do { string(); need(':'); value(depth + 1); } while (take(','));
                need('}');
            } else if (take('[')) {
                if (take(']')) return;
                do { value(depth + 1); } while (take(','));
                need(']');
            } else if (c == 't' || c == 'f' || c == 'n') {
                String literal = c == 't' ? "true" : c == 'f' ? "false" : "null";
                if (!source.startsWith(literal, offset)) throw new IllegalArgumentException();
                offset += literal.length();
            } else {
                if (c == '-') offset++;
                if (offset < source.length() && source.charAt(offset) == '0') offset++;
                else {
                    if (offset >= source.length() || source.charAt(offset) < '1' || source.charAt(offset) > '9')
                        throw new IllegalArgumentException();
                    digits();
                }
                if (offset < source.length() && source.charAt(offset) == '.') { offset++; requiredDigits(); }
                if (offset < source.length() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
                    offset++;
                    if (offset < source.length() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) offset++;
                    requiredDigits();
                }
            }
        }
        void digits() { while (offset < source.length() && source.charAt(offset) >= '0' && source.charAt(offset) <= '9') offset++; }
        void requiredDigits() { int start = offset; digits(); if (start == offset) throw new IllegalArgumentException(); }
        void string() {
            need('"');
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '"') return;
                if (c < 32) throw new IllegalArgumentException();
                if (c == '\\') {
                    if (offset >= source.length()) throw new IllegalArgumentException();
                    char escape = source.charAt(offset++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (offset >= source.length() || "0123456789abcdefABCDEF".indexOf(source.charAt(offset++)) < 0)
                                throw new IllegalArgumentException();
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
    }

    /** 结构化工具卡片消息，role 固定为 TOOL。 */
    public static ChatMessage tool(ToolData data, long timestampMs) {
        return new ChatMessage(data, timestampMs);
    }
}
