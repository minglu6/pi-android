package dev.pi.android.ui;

import dev.pi.android.model.ChatMessage;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure presentation reducer for the documented RPC deltas and persisted messages. */
public final class TranscriptReducer {
    public static final class Edit {
        public final int row;
        public final ChatMessage message;
        public final boolean append;
        Edit(int row, ChatMessage message, boolean append) {
            this.row = row; this.message = message; this.append = append;
        }
    }

    private final Map<Integer, StringBuilder> textBlocks = new TreeMap<>();
    private final Map<String, ChatMessage.ToolData> tools = new LinkedHashMap<>();
    private final Map<String, Integer> toolRows = new LinkedHashMap<>();
    private final Map<String, Long> toolTimes = new LinkedHashMap<>();
    private int assistantRow = -1;
    private long assistantTime;
    private String lastError = "";

    public void reset() {
        textBlocks.clear(); tools.clear(); toolRows.clear(); toolTimes.clear();
        assistantRow = -1; assistantTime = 0; lastError = "";
    }

    public List<Edit> accept(JSONObject event, int size) {
        List<Edit> edits = new ArrayList<>();
        switch (event.optString("type")) {
            case "agent_start":
                lastError = "";
                break;
            case "message_start": {
                JSONObject message = event.optJSONObject("message");
                if (message != null && "assistant".equals(message.optString("role"))) {
                    textBlocks.clear(); assistantRow = -1;
                    assistantTime = message.optLong("timestamp", System.currentTimeMillis());
                }
                break;
            }
            case "message_update": {
                JSONObject update = event.optJSONObject("assistantMessageEvent");
                if (update == null) break;
                int index = update.optInt("contentIndex", 0);
                switch (update.optString("type")) {
                    case "text_start": textBlocks.put(index, new StringBuilder()); break;
                    case "text_delta": {
                        textBlocks.computeIfAbsent(index, key -> new StringBuilder()).append(update.optString("delta"));
                        String text = joinedText();
                        if (!text.isEmpty()) assistant(edits, size, text, true);
                        break;
                    }
                    case "text_end":
                        textBlocks.put(index, new StringBuilder(update.optString("content")));
                        if (assistantRow >= 0 || !joinedText().isEmpty()) assistant(edits, size, joinedText(), true);
                        break;
                    case "toolcall_end": rememberCall(update.optJSONObject("toolCall")); break;
                    default: break; // Thinking/signatures/JSON tool arguments are not assistant prose.
                }
                break;
            }
            case "message_end": {
                JSONObject message = event.optJSONObject("message");
                if (message == null || !"assistant".equals(message.optString("role"))) break;
                rememberCalls(message);
                String text = textOfMessage(message);
                if ("error".equals(message.optString("stopReason"))) {
                    if (assistantRow >= 0) assistant(edits, size, joinedText(), false);
                    error(edits, size + (int) edits.stream().filter(e -> e.append).count(), message);
                } else if (!text.isEmpty() || assistantRow >= 0) {
                    if (text.isEmpty() && "aborted".equals(message.optString("stopReason"))) text = joinedText();
                    assistant(edits, size, text, false);
                }
                textBlocks.clear(); assistantRow = -1; assistantTime = 0;
                break;
            }
            case "tool_execution_start":
            case "tool_execution_update":
            case "tool_execution_end": {
                String id = event.optString("toolCallId");
                if (id.isEmpty()) break;
                ChatMessage.ToolData old = tools.get(id);
                String name = event.optString("toolName", old == null ? "tool" : old.name);
                String command = event.has("args") ? commandOf(event.optJSONObject("args")) : old == null ? "" : old.command;
                String output = old == null ? "" : old.output;
                String state = "running";
                long duration = -1;
                if ("tool_execution_update".equals(event.optString("type"))) {
                    // partialResult is the latest cumulative result (bash/read tool contract), not an append delta.
                    JSONObject result = event.optJSONObject("partialResult");
                    if (result != null) output = textOfMessage(result);
                } else if ("tool_execution_end".equals(event.optString("type"))) {
                    JSONObject result = event.optJSONObject("result");
                    if (result != null) output = textOfMessage(result);
                    JSONObject details = result == null ? null : result.optJSONObject("details");
                    boolean cancelled = result != null && (result.optBoolean("cancelled")
                            || (details != null && details.optBoolean("cancelled")));
                    state = cancelled ? "cancelled" : event.optBoolean("isError") ? "error" : "done";
                    duration = event.optLong("durationMs", -1);
                }
                ChatMessage.ToolData data = new ChatMessage.ToolData(id, name, command, output, state, duration);
                tools.put(id, data);
                Integer row = toolRows.get(id);
                boolean append = row == null;
                if (append) { row = size; toolRows.put(id, row); }
                long timestamp = toolTimes.computeIfAbsent(id, key -> System.currentTimeMillis());
                edits.add(new Edit(row, ChatMessage.tool(data, timestamp), append));
                break;
            }
            case "agent_end": {
                JSONArray messages = event.optJSONArray("messages");
                if (messages != null) for (int i = messages.length() - 1; i >= 0; i--) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message != null && "assistant".equals(message.optString("role"))) {
                        if ("error".equals(message.optString("stopReason"))) error(edits, size, message);
                        break;
                    }
                }
                break;
            }
            case "agent_settled": {
                // Stop/reconnect must not leave spinning cards or streaming cursors forever.
                if (assistantRow >= 0) assistant(edits, size, joinedText(), false);
                textBlocks.clear(); assistantRow = -1; assistantTime = 0;
                for (Map.Entry<String, ChatMessage.ToolData> item : tools.entrySet()) {
                    ChatMessage.ToolData data = item.getValue();
                    Integer row = toolRows.get(item.getKey());
                    if (row != null && "running".equals(data.state)) {
                        ChatMessage.ToolData ended = new ChatMessage.ToolData(data.id, data.name, data.command,
                                data.output, event.optBoolean("aborted") ? "cancelled" : "done", data.durationMs);
                        item.setValue(ended);
                        edits.add(new Edit(row, ChatMessage.tool(ended, toolTimes.getOrDefault(data.id, 0L)), false));
                    }
                }
                break;
            }
            default: break;
        }
        return edits;
    }

    private void assistant(List<Edit> edits, int size, String text, boolean streaming) {
        boolean append = assistantRow < 0;
        if (append) { assistantRow = size; if (assistantTime <= 0) assistantTime = System.currentTimeMillis(); }
        edits.add(new Edit(assistantRow, new ChatMessage(ChatMessage.Role.AGENT, text, assistantTime, streaming), append));
    }

    private void error(List<Edit> edits, int size, JSONObject message) {
        String text = message.optString("errorMessage", textOfMessage(message));
        String key = message.optLong("timestamp") + ":" + text;
        if (text.isEmpty() || key.equals(lastError)) return;
        lastError = key;
        edits.add(new Edit(size, new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + text), true));
    }

    private String joinedText() {
        StringBuilder result = new StringBuilder();
        for (StringBuilder block : textBlocks.values()) result.append(block);
        return result.toString();
    }

    private void rememberCall(JSONObject call) {
        if (call == null) return;
        String id = call.optString("id");
        if (!id.isEmpty() && !tools.containsKey(id)) tools.put(id, new ChatMessage.ToolData(id,
                call.optString("name", "tool"), commandOf(call.optJSONObject("arguments")), "", "running", -1));
    }

    private void rememberCalls(JSONObject message) {
        JSONArray blocks = message.optJSONArray("content");
        if (blocks != null) for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null && "toolCall".equals(block.optString("type"))) rememberCall(block);
        }
    }

    /** Raw text only. ToolCall and thinking blocks must never masquerade as assistant text. */
    public static String textOfMessage(JSONObject message) {
        Object content = message.opt("content");
        if (content instanceof String) return (String) content;
        String text = textOfContent(message.optJSONArray("content"));
        return text.isEmpty() ? message.optString("text", "") : text;
    }

    public static String textOfContent(JSONArray content) {
        StringBuilder text = new StringBuilder();
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) {
                if (text.length() > 0) text.append('\n');
                text.append(block.optString("text"));
            }
        }
        return text.toString();
    }

    private static String commandOf(JSONObject args) {
        if (args == null) return "";
        if (args.has("command")) return args.optString("command");
        if (args.has("path")) return args.optString("path");
        if (args.has("file_path")) return args.optString("file_path");
        if (args.has("pattern")) return args.optString("pattern");
        return args.length() == 0 ? "" : args.toString();
    }

    public static List<ChatMessage> history(JSONObject data) {
        List<ChatMessage> result = new ArrayList<>();
        TranscriptReducer reader = new TranscriptReducer();
        JSONArray messages = data == null ? null : data.optJSONArray("messages");
        if (messages == null) return result;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || HistoryPresentation.hidden(message)) continue;
            long timestamp = message.optLong("timestamp", 0);
            String role = message.optString("role");
            if ("assistant".equals(role)) reader.rememberCalls(message);
            if ("toolResult".equals(role)) {
                String id = message.optString("toolCallId", "history-tool-" + i);
                ChatMessage.ToolData call = reader.tools.get(id);
                String name = message.optString("toolName", call == null ? "tool" : call.name);
                JSONObject details = message.optJSONObject("details");
                boolean cancelled = message.optBoolean("cancelled") || (details != null && details.optBoolean("cancelled"));
                result.add(ChatMessage.tool(new ChatMessage.ToolData(id, name, call == null ? "" : call.command,
                        textOfMessage(message), cancelled ? "cancelled" : message.optBoolean("isError") ? "error" : "done",
                        message.optLong("durationMs", -1)), timestamp));
            } else if ("bashExecution".equals(role)) {
                String state = message.optBoolean("cancelled") ? "cancelled"
                        : message.optInt("exitCode", 0) == 0 ? "done" : "error";
                result.add(ChatMessage.tool(new ChatMessage.ToolData("history-bash-" + i, "bash",
                        message.optString("command"), message.optString("output"), state, -1), timestamp));
            } else {
                String text = HistoryPresentation.displayText(message, textOfMessage(message));
                if ("assistant".equals(role) && "error".equals(message.optString("stopReason"))) {
                    if (!text.isEmpty() && message.has("errorMessage")) result.add(new ChatMessage(ChatMessage.Role.AGENT, text, timestamp));
                    String error = message.optString("errorMessage", text);
                    if (!error.isEmpty()) result.add(new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + error, timestamp));
                    continue;
                }
                if (text.isEmpty()) continue;
                ChatMessage.Role displayRole = "user".equals(role) ? ChatMessage.Role.USER
                        : "assistant".equals(role) ? ChatMessage.Role.AGENT : ChatMessage.Role.SYSTEM;
                result.add(new ChatMessage(displayRole, text, timestamp));
            }
        }
        return result;
    }
}
