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
            if (edit.append) { equal(rows.size(), edit.row); rows.add(edit.message); }
            else rows.set(edit.row, edit.message);
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
        equal(0, rows.size());
        tool("tool_execution_start", "t1", null);
        equal("printf demo", rows.get(0).tool.command); equal("running", rows.get(0).tool.state);
        tool("tool_execution_update", "t1", "a"); equal("a", rows.get(0).tool.output);
        tool("tool_execution_update", "t1", "ab"); equal("ab", rows.get(0).tool.output); equal(1, rows.size());
        rows.add(new ChatMessage(ChatMessage.Role.SYSTEM, "interleaved notification"));
        tool("tool_execution_start", "t2", null);
        tool("tool_execution_update", "t1", "abc"); equal("abc", rows.get(0).tool.output);
        equal("t2", rows.get(2).tool.id);
        String large = "**literal** `raw`\n" + "x".repeat(3000) + "FINAL_TAIL";
        event(new JSONObject().put("type", "tool_execution_end").put("toolCallId", "t1").put("toolName", "bash")
                .put("isError", false).put("durationMs", 4500).put("result", new JSONObject().put("content",
                new JSONArray().put(new JSONObject().put("type", "text").put("text", large)))));
        equal(large, rows.get(0).tool.output); equal("done", rows.get(0).tool.state); equal(4500L, rows.get(0).tool.durationMs);
        equal("printf demo", rows.get(0).tool.command);
        tool("tool_execution_end", "missed-start", "reconnected result");
        equal("reconnected result", rows.get(3).tool.output);
        event("{\"type\":\"agent_settled\",\"aborted\":true}");
        equal("cancelled", rows.get(2).tool.state); equal("done", rows.get(0).tool.state);
        event("{\"type\":\"tool_execution_end\",\"toolCallId\":\"cancelled-tool\",\"toolName\":\"bash\",\"isError\":true,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"partial kept\"}],\"details\":{\"cancelled\":true}}}");
        equal("cancelled", rows.get(4).tool.state); equal("partial kept", rows.get(4).tool.output);
    }
    void errorsAndAbort() {
        delta(0, "partial");
        JSONObject message = json("{\"role\":\"assistant\",\"timestamp\":77,\"stopReason\":\"error\",\"errorMessage\":\"AUTH_FAILURE\",\"content\":[]}");
        event(new JSONObject().put("type", "message_end").put("message", message));
        equal(false, rows.get(0).streaming); equal("partial", rows.get(0).text); equal("⚠ AUTH_FAILURE", rows.get(1).text);
        event(new JSONObject().put("type", "agent_end").put("messages", new JSONArray().put(message)));
        equal(2, rows.size());
        event("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}");
        delta(0, "aborted text"); event("{\"type\":\"agent_settled\",\"aborted\":true}");
        equal(false, rows.get(2).streaming); equal("aborted text", rows.get(2).text);
        reducer.reset();
        event("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"thinking_delta\",\"delta\":\"invisible reasoning\"}}");
        equal(3, rows.size());
    }
    static void history() {
        JSONObject data = json("{\"messages\":[{\"role\":\"custom\",\"display\":false,\"content\":\"HIDDEN\"},{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Before\"},{\"type\":\"toolCall\",\"id\":\"h1\",\"name\":\"bash\",\"arguments\":{\"command\":\"printf test\"}}]},{\"role\":\"toolResult\",\"toolCallId\":\"h1\",\"toolName\":\"bash\",\"content\":[{\"type\":\"text\",\"text\":\"**raw**\"}],\"isError\":true},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"After\"}]},{\"role\":\"bashExecution\",\"command\":\"echo hi\",\"output\":\"hi\",\"exitCode\":0}]}");
        List<ChatMessage> history = TranscriptReducer.history(data);
        equal(5, history.size()); equal("Before", history.get(1).text);
        equal("printf test", history.get(2).tool.command); equal("**raw**", history.get(2).tool.output); equal("error", history.get(2).tool.state);
        equal("After", history.get(3).text); equal("echo hi", history.get(4).tool.command); equal("hi", history.get(4).tool.output);
        List<ChatMessage> errors = TranscriptReducer.history(json("{\"messages\":[{\"role\":\"assistant\",\"stopReason\":\"error\",\"errorMessage\":\"NO_KEY\",\"content\":[]},{\"role\":\"toolResult\",\"toolCallId\":\"cancel\",\"isError\":true,\"details\":{\"cancelled\":true},\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}]}"));
        equal(2, errors.size()); equal(ChatMessage.Role.SYSTEM, errors.get(0).role); equal("⚠ NO_KEY", errors.get(0).text);
        equal("cancelled", errors.get(1).tool.state); equal("partial", errors.get(1).tool.output);
    }
    public static void main(String[] args) {
        new TranscriptTest().textDeltas(); new TranscriptTest().toolDeltas(); new TranscriptTest().errorsAndAbort(); history();
        System.out.println("TranscriptTest: " + assertions + " assertions passed");
    }
}
