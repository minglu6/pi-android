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
    /** 协调器回填了 N 行历史后累加；此后所有绝对行号都要平移。
        已登记的行号（assistantRow / toolRows）在 shiftRows 时一并平移。 */
    private int rowShift = 0;
    private int assistantRow = -1;
    private long assistantTime;
    private String lastError = "";
    /** 本轮已经在 UI 里出现过的助手正文（跨消息累积），用于 agent_end 去重补拍。 */
    private String lastRunText = "";

    /** 运行中重连回填 insertAt(0..n) 后调用：reducer 里存的绝对行号整体平移。 */
    public void shiftRows(int inserted) {
        if (inserted <= 0) return;
        rowShift += inserted;
        if (assistantRow >= 0) assistantRow += inserted;
        for (Map.Entry<String, Integer> entry : toolRows.entrySet()) {
            entry.setValue(entry.getValue() + inserted);
        }
    }

    public void reset() {
        textBlocks.clear(); tools.clear(); toolRows.clear(); toolTimes.clear();
        assistantRow = -1; assistantTime = 0; lastError = ""; rowShift = 0;
    }

    public List<Edit> accept(JSONObject event, int size) {
        List<Edit> edits = new ArrayList<>();
        switch (event.optString("type")) {
            case "agent_start":
                lastError = "";
                // 新一轮开始：上一轮的补拍记忆不能延续，否则同文正文会永久消失。
                lastRunText = "";
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
                // 正文为空但流里曾出现过文字（重新连接/纯工具消息后 delta 已丢失）：
                // 保留已渲染的正文，不让权威空消息把它抹掉。
                if (text.isEmpty() && !joinedText().isEmpty()) text = joinedText();
                if ("error".equals(message.optString("stopReason"))) {
                    if (assistantRow >= 0 || !text.isEmpty()) assistant(edits, size, text, false);
                    error(edits, size + (int) edits.stream().filter(e -> e.append).count(), message);
                } else if (!text.isEmpty() || assistantRow >= 0) {
                    assistant(edits, size, text, false);
                }
                if (!text.isEmpty()) lastRunText += text;
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
                int additions = old == null ? -1 : old.additions;
                int deletions = old == null ? -1 : old.deletions;
                String summary = old == null ? "" : old.summary;
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
                    int[] diff = toolDiffOf(result, details);
                    additions = diff[0];
                    deletions = diff[1];
                    summary = toolSummaryOf(result, details, output, duration);
                    if (result != null && (result.optBoolean("needsApproval")
                            || (details != null && details.optBoolean("needsApproval")))) {
                        state = ChatMessage.ToolData.STATE_PENDING;
                    }
                }
                ChatMessage.ToolData data = new ChatMessage.ToolData(id, name, command, output, state,
                        duration, additions, deletions, summary);
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
                // 新一轮开始前补拍上一轮丢掉的正文：运行中途重连时 message_start/update
                // 已经错过，agent_end.messages 是补回正文的最后机会。
                if (messages != null) backfillMissedText(edits, size, messages);
                if (messages != null) for (int i = messages.length() - 1; i >= 0; i--) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message != null && "assistant".equals(message.optString("role"))) {
                        if ("error".equals(message.optString("stopReason"))) {
                            int appended = (int) edits.stream().filter(e -> e.append).count();
                            error(edits, size + appended, message);
                        }
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
                                data.output, event.optBoolean("aborted") ? "cancelled" : "done", data.durationMs,
                                data.additions, data.deletions, data.summary);
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
        if (append) {
            // size 是协调器传入的当前 adapter 行数，已包含回填行，不再加 rowShift。
            assistantRow = size;
            if (assistantTime <= 0) assistantTime = System.currentTimeMillis();
        }
        edits.add(new Edit(assistantRow,
                new ChatMessage(ChatMessage.Role.AGENT, text, assistantTime, streaming, ""), append));
    }

    private void error(List<Edit> edits, int size, JSONObject message) {
        String text = message.optString("errorMessage", textOfMessage(message));
        String key = message.optLong("timestamp") + ":" + text;
        if (text.isEmpty() || key.equals(lastError)) return;
        lastError = key;
        edits.add(new Edit(size, new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + text), true));
    }

    /** 补回重连期间错过 message_start/message_end 的助手正文；已渲染过的内容不重拍。 */
    private void backfillMissedText(List<Edit> edits, int size, JSONArray messages) {
        int appended = 0;
        int base = size;
        StringBuilder streamed = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role"))) continue;
            String text = textOfMessage(message);
            if (text.isEmpty() || "error".equals(message.optString("stopReason"))) continue;
            // 连续助手消息的正文若本轮已在 UI 出现过就不重拍（重连后 joinedText 为空
            // 但 message_end 已经记录进 lastRunText）。
            streamed.append(text);
            String seen = streamed.toString();
            if (seen.equals(joinedText()) || seen.equals(lastRunText)) continue;
            long ts = message.optLong("timestamp", System.currentTimeMillis());
            String entryId = message.optString("entryId", "");
            edits.add(new Edit(base + appended,
                    new ChatMessage(ChatMessage.Role.AGENT, text, ts, false, entryId), true));
            appended++;
        }
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

    /** [additions, deletions]; -1 means field absent. */
    private static int[] toolDiffOf(JSONObject result, JSONObject details) {
        int add = firstInt(result, "additions", "added", "linesAdded", "insertions");
        int del = firstInt(result, "deletions", "removed", "linesRemoved", "deletionsCount");
        if (add < 0 && details != null) add = firstInt(details, "additions", "added", "linesAdded", "insertions");
        if (del < 0 && details != null) del = firstInt(details, "deletions", "removed", "linesRemoved", "deletionsCount");
        if (add < 0 && del < 0 && details != null) {
            JSONObject diff = details.optJSONObject("diff");
            if (diff != null) {
                add = firstInt(diff, "additions", "added", "linesAdded");
                del = firstInt(diff, "deletions", "removed", "linesRemoved");
            }
        }
        return new int[]{add, del};
    }

    private static int firstInt(JSONObject obj, String... keys) {
        if (obj == null) return -1;
        for (String key : keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                Object v = obj.opt(key);
                if (v instanceof Number) return Math.max(0, ((Number) v).intValue());
            }
        }
        return -1;
    }

    /** Compact status like "48 passed · 3.1s"; empty if nothing useful. */
    private static String toolSummaryOf(JSONObject result, JSONObject details, String output, long durationMs) {
        if (result != null) {
            String s = result.optString("summary", "");
            if (!s.isEmpty()) return s;
        }
        if (details != null) {
            String s = details.optString("summary", "");
            if (!s.isEmpty()) return s;
            if (details.has("passed") || details.has("failed")) {
                StringBuilder sb = new StringBuilder();
                if (details.has("passed") && !details.isNull("passed")) sb.append(details.opt("passed")).append(" passed");
                if (details.has("failed") && !details.isNull("failed")) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(details.opt("failed")).append(" failed");
                }
                if (durationMs > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(formatDurationShort(durationMs));
                }
                if (sb.length() > 0) return sb.toString();
            }
        }
        // Heuristic: last non-empty line of short test-like output.
        if (output != null && output.length() > 0 && output.length() < 400) {
            String[] lines = output.trim().split("\n");
            String last = lines[lines.length - 1].trim();
            if (last.matches("(?i).*\\b(passed|failed|ok|tests?)\\b.*") && last.length() < 80) {
                return last;
            }
        }
        return "";
    }

    private static String formatDurationShort(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
        return String.format(java.util.Locale.US, "%dm%02ds", ms / 60_000, (ms % 60_000) / 1000);
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
                String output = textOfMessage(message);
                long durationMs = message.optLong("durationMs", -1);
                int[] diff = toolDiffOf(message, details);
                String summary = toolSummaryOf(message, details, output, durationMs);
                String state = cancelled ? "cancelled"
                        : message.optBoolean("needsApproval") ? ChatMessage.ToolData.STATE_PENDING
                        : message.optBoolean("isError") ? "error" : "done";
                result.add(ChatMessage.tool(new ChatMessage.ToolData(id, name, call == null ? "" : call.command,
                        output, state, durationMs, diff[0], diff[1], summary), timestamp));
            } else if ("bashExecution".equals(role)) {
                String state = message.optBoolean("cancelled") ? "cancelled"
                        : message.optBoolean("needsApproval") ? ChatMessage.ToolData.STATE_PENDING
                        : message.optInt("exitCode", 0) == 0 ? "done" : "error";
                String output = message.optString("output");
                long durationMs = message.optLong("durationMs", -1);
                String summary = toolSummaryOf(message, message.optJSONObject("details"), output, durationMs);
                result.add(ChatMessage.tool(new ChatMessage.ToolData("history-bash-" + i, "bash",
                        message.optString("command"), output, state, durationMs, -1, -1, summary), timestamp));
            } else if ("user".equals(role)) {
                // 用户消息走结构化解析：原生 image 块变成附件缩略图，文件 metadata 尾部被剥离。
                ChatMessage user = ChatMessage.fromUserContent(message);
                String text = HistoryPresentation.displayText(message, user.text);
                if (text.isEmpty() && user.attachments.isEmpty()) continue;
                String entryId = message.optString("entryId", "");
                ChatMessage shown = new ChatMessage(ChatMessage.Role.USER, text, timestamp, false, entryId);
                shown.attachments.addAll(user.attachments);
                result.add(shown);
            } else {
                String text = HistoryPresentation.displayText(message, textOfMessage(message));
                String entryId = message.optString("entryId", "");
                if ("assistant".equals(role) && "error".equals(message.optString("stopReason"))) {
                    if (!text.isEmpty() && message.has("errorMessage")) {
                        result.add(new ChatMessage(ChatMessage.Role.AGENT, text, timestamp, false, entryId));
                    }
                    String error = message.optString("errorMessage", text);
                    if (!error.isEmpty()) result.add(new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + error, timestamp));
                    continue;
                }
                if (text.isEmpty()) continue;
                ChatMessage.Role displayRole = "assistant".equals(role) ? ChatMessage.Role.AGENT
                        : ChatMessage.Role.SYSTEM;
                result.add(new ChatMessage(displayRole, text, timestamp, false, entryId));
            }
        }
        return result;
    }
}
