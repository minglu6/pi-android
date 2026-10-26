package dev.pi.android.ui;

import dev.pi.android.model.ChatMessage;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class TranscriptTest {
    static int assertions;
    final TranscriptReducer reducer = new TranscriptReducer();
    final List<ChatMessage> rows = new ArrayList<>();
    static JSONObject json(String source) { return new JSONObject(source); }
    static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    void event(JSONObject event) {
        for (TranscriptReducer.Edit edit : reducer.accept(event, rows.size())) {
            if (edit.append) {
                // 与 MainActivity.applyTranscriptEvent 一致：回填后追加位置可能在中间。
                if (edit.row < rows.size()) rows.add(edit.row, edit.message);
                else { equal(rows.size(), edit.row); rows.add(edit.message); }
            } else rows.set(edit.row, edit.message);
        }
    }
    void event(String source) { event(json(source)); }
    void delta(int index, String text) {
        event(new JSONObject().put("type", "message_update").put("assistantMessageEvent",
                new JSONObject().put("type", "text_delta").put("contentIndex", index).put("delta", text)));
    }
    void tool(String type, String id, String output) {
        JSONObject event = new JSONObject().put("type", type).put("toolCallId", id).put("toolName", "bash");
        if (output != null) event.put(type.equals("tool_execution_update") ? "partialResult" : "result",
                new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", output))));
        event(event);
    }
    void textDeltas() {
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"timestamp\":42,\"content\":[]}}");
        delta(1, "B"); equal("B", rows.get(0).text); equal(true, rows.get(0).streaming);
        delta(0, "A"); equal("AB", rows.get(0).text); equal(1, rows.size());
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"Corrected\"}}");
        equal("CorrectedB", rows.get(0).text);
        event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"FINAL\"}],\"stopReason\":\"stop\"}}");
        equal("FINAL", rows.get(0).text); equal(false, rows.get(0).streaming); equal(42L, rows.get(0).timestampMs);
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[]}}");
        delta(0, "SECOND"); equal(2, rows.size()); equal("FINAL", rows.get(0).text);
    }
    void toolDeltas() {
        event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"not prose\"},{\"type\":\"toolCall\",\"id\":\"t1\",\"name\":\"bash\",\"arguments\":{\"command\":\"printf demo\"}}]}}");
        equal(1, rows.size());
        equal(ChatMessage.ToolData.NAME_THINKING, rows.get(0).tool.name);
        equal("not prose", rows.get(0).tool.output);
        tool("tool_execution_start", "t1", null);
        equal("printf demo", rows.get(1).tool.command); equal("running", rows.get(1).tool.state);
        tool("tool_execution_update", "t1", "a"); equal("a", rows.get(1).tool.output);
        tool("tool_execution_update", "t1", "ab"); equal("ab", rows.get(1).tool.output); equal(2, rows.size());
        rows.add(new ChatMessage(ChatMessage.Role.SYSTEM, "interleaved notification"));
        tool("tool_execution_start", "t2", null);
        tool("tool_execution_update", "t1", "abc"); equal("abc", rows.get(1).tool.output);
        equal("t2", rows.get(3).tool.id);
        String large = "**literal** `raw`\n" + "x".repeat(3000) + "FINAL_TAIL";
        event(new JSONObject().put("type", "tool_execution_end").put("toolCallId", "t1").put("toolName", "bash")
                .put("isError", false).put("durationMs", 4500).put("result", new JSONObject().put("content",
                new JSONArray().put(new JSONObject().put("type", "text").put("text", large)))));
        equal(large, rows.get(1).tool.output); equal("done", rows.get(1).tool.state); equal(4500L, rows.get(1).tool.durationMs);
        equal("printf demo", rows.get(1).tool.command);
        tool("tool_execution_end", "missed-start", "reconnected result");
        equal("reconnected result", rows.get(4).tool.output);
        event("{\"type\":\"agent_settled\",\"aborted\":true}");
        equal("cancelled", rows.get(3).tool.state); equal("done", rows.get(1).tool.state);
        event("{\"type\":\"tool_execution_end\",\"toolCallId\":\"cancelled-tool\",\"toolName\":\"bash\",\"isError\":true,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"partial kept\"}],\"details\":{\"cancelled\":true}}}");
        equal("cancelled", rows.get(5).tool.state); equal("partial kept", rows.get(5).tool.output);
    }
    void thinkingStream() {
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"timestamp\":99,\"content\":[]}}");
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_start\",\"contentIndex\":0}}");
        equal(0, rows.size()); // no content yet — hide empty thinking
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"contentIndex\":0,\"delta\":\"先看\"}}");
        equal(1, rows.size());
        equal(ChatMessage.ToolData.NAME_THINKING, rows.get(0).tool.name);
        equal("先看", rows.get(0).tool.output);
        equal(ChatMessage.ToolData.STATE_RUNNING, rows.get(0).tool.state);
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"contentIndex\":0,\"delta\":\"路由\"}}");
        equal("先看路由", rows.get(0).tool.output); equal(1, rows.size());
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_end\",\"contentIndex\":0,\"content\":\"先看路由表\"}}");
        equal("先看路由表", rows.get(0).tool.output);
        equal(ChatMessage.ToolData.STATE_DONE, rows.get(0).tool.state);
        delta(1, "结论：");
        equal(2, rows.size()); equal("结论：", rows.get(1).text);
        event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"timestamp\":99,\"stopReason\":\"stop\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"先看路由表\"},{\"type\":\"text\",\"text\":\"结论：改 login\"}]}}");
        equal("先看路由表", rows.get(0).tool.output);
        equal("结论：改 login", rows.get(1).text);
        equal(false, rows.get(1).streaming);
    }
    void errorsAndAbort() {
        delta(0, "partial");
        JSONObject message = json("{\"role\":\"assistant\",\"timestamp\":77,\"stopReason\":\"error\",\"errorMessage\":\"AUTH_FAILURE\",\"content\":[]}");
        event(new JSONObject().put("type", "message_end").put("message", message));
        equal(false, rows.get(0).streaming); equal("partial", rows.get(0).text);
        equal(ChatMessage.Role.SYSTEM, rows.get(1).role);
        equal(true, rows.get(1).notice != null);
        equal("AUTH_FAILURE", rows.get(1).notice.detail);
        event(new JSONObject().put("type", "agent_end").put("messages", new JSONArray().put(message)));
        equal(2, rows.size());
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}");
        delta(0, "aborted text"); event("{\"type\":\"agent_settled\",\"aborted\":true}");
        equal(false, rows.get(2).streaming); equal("aborted text", rows.get(2).text);
        reducer.reset();
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"delta\":\"streamed reasoning\"}}");
        equal(4, rows.size());
        equal(ChatMessage.ToolData.NAME_THINKING, rows.get(3).tool.name);
        equal("streamed reasoning", rows.get(3).tool.output);
    }
    static void history() {
        JSONObject data = json("{\"messages\":[{\"role\":\"custom\",\"display\":false,\"content\":\"HIDDEN\"},{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Before\"},{\"type\":\"toolCall\",\"id\":\"h1\",\"name\":\"bash\",\"arguments\":{\"command\":\"printf test\"}}]},{\"role\":\"toolResult\",\"toolCallId\":\"h1\",\"toolName\":\"bash\",\"content\":[{\"type\":\"text\",\"text\":\"**raw**\"}],\"isError\":true},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"After\"}]},{\"role\":\"bashExecution\",\"command\":\"echo hi\",\"output\":\"hi\",\"exitCode\":0}]}");
        List<ChatMessage> history = TranscriptReducer.history(data);
        equal(5, history.size()); equal("Before", history.get(1).text);
        equal("printf test", history.get(2).tool.command); equal("**raw**", history.get(2).tool.output); equal("error", history.get(2).tool.state);
        equal("After", history.get(3).text); equal("echo hi", history.get(4).tool.command); equal("hi", history.get(4).tool.output);
        List<ChatMessage> withThink = TranscriptReducer.history(json("{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"内部推理\"},{\"type\":\"text\",\"text\":\"对外答复\"}]}]}"));
        equal(2, withThink.size());
        equal(ChatMessage.ToolData.NAME_THINKING, withThink.get(0).tool.name);
        equal("内部推理", withThink.get(0).tool.output);
        equal("对外答复", withThink.get(1).text);
        List<ChatMessage> errors = TranscriptReducer.history(json("{\"messages\":[{\"role\":\"assistant\",\"stopReason\":\"error\",\"errorMessage\":\"NO_KEY\",\"content\":[]},{\"role\":\"toolResult\",\"toolCallId\":\"cancel\",\"isError\":true,\"details\":{\"cancelled\":true},\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}]}"));
        equal(2, errors.size()); equal(ChatMessage.Role.SYSTEM, errors.get(0).role);
        equal(true, errors.get(0).notice != null); equal("NO_KEY", errors.get(0).notice.detail);
        equal("cancelled", errors.get(1).tool.state); equal("partial", errors.get(1).tool.output);
    }
    void reconnectBackfill() {
        // 运行中途重连：错过 message_start 和全部 delta，只有 tool_execution_* 还在来。
        tool("tool_execution_start", "r1", null);
        equal(1, rows.size());
        tool("tool_execution_end", "r1", "out");
        // agent_end 携带完整 run 消息：正文必须补拍回来，顺序在工具卡之前的事件来源处不保证，
        // 但绝不能整条丢掉。
        event(new JSONObject().put("type", "agent_end").put("willRetry", false).put("messages", new JSONArray()
                .put(json("{\"role\":\"assistant\",\"timestamp\":100,\"stopReason\":\"toolUse\",\"content\":[{\"type\":\"text\",\"text\":\"前半正文\"},{\"type\":\"toolCall\",\"id\":\"r1\",\"name\":\"bash\",\"arguments\":{\"command\":\"x\"}}]}"))
                .put(json("{\"role\":\"toolResult\",\"toolCallId\":\"r1\",\"content\":[{\"type\":\"text\",\"text\":\"out\"}]}"))
                .put(json("{\"role\":\"assistant\",\"timestamp\":200,\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"后半正文\"}]}"))));
        boolean hasFirst = false, hasSecond = false;
        for (ChatMessage row : rows) {
            if ("前半正文".equals(row.text)) hasFirst = true;
            if ("后半正文".equals(row.text)) hasSecond = true;
        }
        equal(true, hasFirst); equal(true, hasSecond);
        // 已有正文的消息不重拍：agent_end 内容与 joinedText 相同。
        reducer.reset(); rows.clear();
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}");
        delta(0, "same");
        event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"same\"}],\"stopReason\":\"stop\"}}");
        event(new JSONObject().put("type", "agent_end").put("messages", new JSONArray()
                .put(json("{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"same\"}],\"stopReason\":\"stop\"}"))));
        equal(1, rows.size()); equal("same", rows.get(0).text);
    }
    void mergeThenShift() {
        // 运行中重连 + get_messages 回填：UI 插入了 2 行历史（用户消息 + 已完成正文），
        // 协调器调用 shiftRows(2) 后，后续事件行号必须整体平移，不回填到历史位置。
        tool("tool_execution_start", "s1", null);
        equal(0, rows.get(0).tool.id.equals("s1") ? 0 : 1);
        // 模拟协调器：adapter.insertAt × 2 + transcript.shiftRows(2)
        rows.add(0, new ChatMessage(ChatMessage.Role.USER, "旧问题"));
        rows.add(1, new ChatMessage(ChatMessage.Role.AGENT, "旧正文"));
        reducer.shiftRows(2);
        // 运行中工具更新仍命中平移后的同一行，不覆盖历史行。
        tool("tool_execution_update", "s1", "partial");
        equal("partial", rows.get(2).tool.output); equal("旧问题", rows.get(0).text);
        // 新助手消息从流式区末尾追加，不回填到行 0。
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}");
        delta(0, "新正文");
        equal(4, rows.size()); equal("新正文", rows.get(3).text);
        delta(0, "加长");
        equal("新正文加长", rows.get(3).text); equal(4, rows.size());
        event("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"新正文加长（终）\"}],\"stopReason\":\"stop\"}}");
        equal("新正文加长（终）", rows.get(3).text);
        // 新工具行同样平移追加。
        tool("tool_execution_start", "s2", null);
        equal(5, rows.size()); equal("s2", rows.get(4).tool.id);
        // 再确认历史行没有被覆盖。
        equal("旧问题", rows.get(0).text); equal("旧正文", rows.get(1).text);
    }
    static void timestampPresentation() {
        String marker = "<!--pi-timestamp:2026-10-26 15:00:53-->";
        equal("你好", HistoryPresentation.bodyText(marker + "\n你好"));
        equal("  缩进正文\n", HistoryPresentation.bodyText(" \r\n" + marker + "\r\n  缩进正文\n"));
        equal("正文", HistoryPresentation.bodyText(marker + "\n" + marker + "\n正文"));
        equal("", HistoryPresentation.bodyText(marker));
        equal("", HistoryPresentation.bodyText(null));
        String example = "```html\n" + marker + "\n```";
        equal(example, HistoryPresentation.bodyText(example));
        String quoted = "示例：" + marker;
        equal(quoted, HistoryPresentation.bodyText(quoted));
        String comment = "<!--ordinary comment-->\n正文";
        equal(comment, HistoryPresentation.bodyText(comment));
        equal("正文\n" + marker, HistoryPresentation.bodyText(marker + "\n正文\n" + marker));
    }
    public static void main(String[] args) {
        new TranscriptTest().textDeltas(); new TranscriptTest().toolDeltas();
        new TranscriptTest().thinkingStream(); new TranscriptTest().errorsAndAbort(); history();
        new TranscriptTest().reconnectBackfill(); new TranscriptTest().mergeThenShift();
        timestampPresentation();
        System.out.println("TranscriptTest: " + assertions + " assertions passed");
    }
}
