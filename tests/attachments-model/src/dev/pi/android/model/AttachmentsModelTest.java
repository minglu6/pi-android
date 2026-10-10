package dev.pi.android.model;

import org.json.JSONArray;
import org.json.JSONObject;

/** 无 Android 依赖、无网络的真实 ChatMessage 回归。 */
public final class AttachmentsModelTest {
    private static int checks;
    private static final String START = "\n\n<pi_attachments_v1>\n";
    private static final String END = "\n</pi_attachments_v1>";

    public static void main(String[] args) throws Exception {
        metadata();
        invalidMetadata();
        nativeImages();
        limits();
        snapshots();
        System.out.println("AttachmentsModelTest: " + checks + " checks passed");
    }

    private static void metadata() throws Exception {
        JSONArray descriptors = new JSONArray().put(file("报告.pdf", "/work/报告.pdf", "application/pdf", 1234567890123L));
        ChatMessage row = new ChatMessage(ChatMessage.Role.USER, marker("请查看", descriptors.toString()), 42, false);
        check(row.text.equals("请查看"), "strip only the valid final metadata");
        check(row.attachments.size() == 1, "file parsed");
        ChatMessage.AttachmentData item = row.attachments.get(0);
        check(item.name.equals("报告.pdf") && item.path.equals("/work/报告.pdf"), "names and paths preserved");
        check(item.mimeType.equals("application/pdf") && item.kind.equals("file"), "descriptor types");
        check(item.size == 1234567890123L && item.imageData == null, "long size / optional data");
        check(row.timestampMs == 42 && !row.streaming, "message fields intact");
        ChatMessage empty = new ChatMessage(ChatMessage.Role.USER, marker("", descriptors.toString()));
        check(empty.text.isEmpty() && empty.attachments.size() == 1, "attachment-only marker");
        check(new ChatMessage(ChatMessage.Role.USER, marker("text", "[]")).text.equals("text"), "empty valid list");
        String nested = marker("ordinary", "not JSON") + " middle";
        ChatMessage nestedRow = new ChatMessage(ChatMessage.Role.USER, marker(nested, descriptors.toString()));
        check(nestedRow.text.equals(nested), "strip final block only");
        String raw = marker("tool output", descriptors.toString());
        check(new ChatMessage(ChatMessage.Role.AGENT, raw).text.equals(raw), "non-user untouched");
        check(new ChatMessage(ChatMessage.Role.TOOL, raw).attachments.isEmpty(), "tool output not metadata");
        String escaped = "[{\"name\":\"a\\u4e2d\\n\\\"b\",\"path\":\"/work/a\",\"mimeType\":\"text/plain\",\"size\":0,\"kind\":\"file\"}]";
        check(new ChatMessage(ChatMessage.Role.USER, marker("", escaped)).attachments.get(0).name.equals("a中\n\"b"), "JSON escapes");
    }

    private static void invalidMetadata() throws Exception {
        String good = new JSONArray().put(file("a.txt", "/work/a", "text/plain", 0)).toString();
        String[] invalid = {
            "not JSON", "{}", "null", "[null]", "[{}]", good + " garbage", good + ",",
            good.replace("\"name\"", "'name'"), good.replace("\"name\"", "name"),
            good.substring(0, good.length() - 1) + ",]",
            good.replace("\"size\":0", "\"size\":-1"),
            good.replace("\"size\":0", "\"size\":1.5"),
            good.replace("\"size\":0", "\"size\":\"0\""),
            good.replace("\"size\":0", "\"size\":9223372036854775808"),
            good.replace("\"size\":0", "\"size\":00"),
            good.replace("\"size\":0", "\"size\":+1"),
            good.replace("\"size\":0", "\"size\":NaN"),
            good.replace("\"file\"", "\"executable\""),
            good.replace("\"path\":\"/work/a\"", "\"path\":null"),
            good.replace("\"mimeType\":\"text/plain\"", "\"mimeType\":3"),
            "[{\"name\":\"a\n b\",\"path\":\"a\",\"mimeType\":\"text/plain\",\"size\":0,\"kind\":\"file\"}]"
        };
        for (String json : invalid) unchanged(marker("body", json));
        unchanged(marker("body", good) + "\n");
        unchanged(marker("body", good) + " text after metadata");
        unchanged("body" + START + good);
        unchanged("body\n<pi_attachments_v1>\n" + good + END);
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i <= ChatMessage.MAX_ATTACHMENTS; i++) tooMany.put(file("a", "p", "m", 0));
        unchanged(marker("body", tooMany.toString()));
        unchanged(marker("body", " ".repeat(65537) + "[]"));
        JSONObject deep = file("a", "p", "m", 0);
        String nested = "[".repeat(30) + "0" + "]".repeat(30);
        unchanged(marker("body", "[" + deep.toString().replace("\"size\":0", "\"size\":" + nested) + "]"));
        String validPretty = "\n\t" + good + "\r\n ";
        check(new ChatMessage(ChatMessage.Role.USER, marker("body", validPretty)).text.equals("body"), "JSON whitespace valid");
    }

    private static void nativeImages() throws Exception {
        JSONArray content = new JSONArray()
            .put(new JSONObject().put("type", "text").put("text", "第一行"))
            .put(image("AA==", "image/png"))
            .put(new JSONObject().put("type", "thinking").put("thinking", "hidden"))
            .put(new JSONObject().put("type", "toolCall").put("name", "hidden"))
            .put(new JSONObject().put("type", "text").put("text", "第二行"))
            .put(image("AQ==", "image/jpeg"));
        ChatMessage row = ChatMessage.fromUserContent(content, 7);
        check(row.text.equals("第一行\n第二行"), "native text only, no base64 in bubble");
        check(row.attachments.size() == 2 && row.timestampMs == 7, "native image count and timestamp");
        check(row.attachments.get(0).name.equals("图片 1") && row.attachments.get(1).name.equals("图片 2"), "generic image names");
        check(row.attachments.get(0).imageData.equals("AA==") && row.attachments.get(0).kind.equals("image"), "native image data");
        ChatMessage imageOnly = ChatMessage.fromUserContent(new JSONArray().put(image("AA==", "image/png")), 0);
        check(imageOnly.text.isEmpty() && imageOnly.attachments.size() == 1, "image-only history survives");
        JSONArray files = new JSONArray().put(file("a", "/no/open", "text/plain", 1));
        content.put(new JSONObject().put("type", "text").put("text", marker("", files.toString())));
        ChatMessage mixed = ChatMessage.fromUserContent(content, 9);
        check(mixed.attachments.size() == 3 && mixed.attachments.get(2).kind.equals("file"), "native plus marker attachments");
        check(!mixed.text.contains("pi_attachments"), "marker not visible");
        JSONObject message = new JSONObject().put("content", content).put("timestamp", 22);
        check(ChatMessage.fromUserContent(message).timestampMs == 22, "message wrapper API");
        check(ChatMessage.fromUserContent(new JSONObject().put("text", "fallback")).text.equals("fallback"), "legacy text fallback");
        check(ChatMessage.fromUserContent((JSONObject) null).text.isEmpty(), "null wrapper");
        check(ChatMessage.fromUserContent((Object) null, 0).attachments.isEmpty(), "null content");
        check(ChatMessage.fromUserContent(marker("body", files.toString()), 3).attachments.size() == 1, "string content marker");
    }

    private static void limits() throws Exception {
        JSONArray images = new JSONArray();
        for (int i = 0; i < 100; i++) images.put(image("AA==", "image/png"));
        ChatMessage capped = ChatMessage.fromUserContent(images, 0);
        check(capped.attachments.size() == ChatMessage.MAX_ATTACHMENTS, "native count cap");
        String huge = "A".repeat(ChatMessage.MAX_IMAGE_DATA_CHARS + 1);
        ChatMessage oversized = ChatMessage.fromUserContent(new JSONArray().put(image(huge, "image/png")), 0);
        check(oversized.attachments.size() == 1 && oversized.attachments.get(0).imageData == null, "oversized image placeholder");
        ChatMessage.AttachmentData dto = new ChatMessage.AttachmentData(null, null, null, -5, null, huge);
        check(dto.size == 0 && dto.name.isEmpty() && dto.path.isEmpty() && dto.imageData == null, "safe DTO defaults");
        String medium = "A".repeat(3 * 1024 * 1024);
        JSONArray total = new JSONArray().put(image(medium, "image/png")).put(image(medium, "image/png")).put(image(medium, "image/png"));
        ChatMessage limited = ChatMessage.fromUserContent(total, 0);
        check(limited.attachments.get(1).imageData != null && limited.attachments.get(2).imageData == null, "aggregate native image cap");
        check(new ChatMessage.AttachmentData("n".repeat(600), "m".repeat(200), "file", 0, "p").name.length() == 512, "bounded display label");
    }

    private static void snapshots() {
        ChatMessage original = new ChatMessage(ChatMessage.Role.USER, "", 123, true);
        ChatMessage.AttachmentData dto = new ChatMessage.AttachmentData("tiny", "image/png", "image", 27, "/never/open", "AA==");
        original.attachments.add(dto); // 协调器的乐观行 API
        ChatMessage copy = original.copy();
        original.attachments.clear();
        check(copy.attachments.size() == 1 && copy.attachments.get(0) == dto, "copy isolates list, immutable DTO shared");
        check(copy.attachments.get(0).imageData.equals("AA=="), "small thumbnail retained in snapshot");
        check(copy.text.isEmpty() && copy.role == ChatMessage.Role.USER && copy.streaming && copy.timestampMs == 123, "copy message fields");
        ChatMessage.ToolData tool = new ChatMessage.ToolData("1", "bash", "pwd", "work", "running", 12);
        ChatMessage toolCopy = ChatMessage.tool(tool, 45).copy();
        check(toolCopy.tool == tool && toolCopy.role == ChatMessage.Role.TOOL && toolCopy.timestampMs == 45, "tool streaming data unchanged");
    }

    private static JSONObject image(String data, String mime) throws Exception {
        return new JSONObject().put("type", "image").put("data", data).put("mimeType", mime);
    }
    private static JSONObject file(String name, String path, String mime, long size) throws Exception {
        return new JSONObject().put("name", name).put("path", path).put("mimeType", mime).put("size", size).put("kind", "file");
    }
    private static String marker(String body, String json) { return body + START + json + END; }
    private static void unchanged(String raw) {
        ChatMessage row = new ChatMessage(ChatMessage.Role.USER, raw);
        check(row.text.equals(raw) && row.attachments.isEmpty(), "invalid metadata unchanged");
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
