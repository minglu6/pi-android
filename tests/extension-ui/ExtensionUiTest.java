import android.os.Looper;
import dev.pi.android.ui.ExtensionUi;
import dev.pi.android.ui.HistoryPresentation;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class ExtensionUiTest {
    private static JSONObject request(String id, long timeout) {
        JSONObject r = new JSONObject().put("id", id).put("method", "input").put("title", id);
        if (timeout > 0) r.put("timeout", timeout);
        return r;
    }
    public static void main(String[] args) {
        Looper.getMainLooper().clear();
        List<JSONObject> replies = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        List<ExtensionUi.AnswerSink> answers = new ArrayList<>();
        ExtensionUi ui = new ExtensionUi(null, replies::add, null,
                (id, kind, title, hint, options, prefill, sink) -> { shown.add(id); answers.add(sink); });
        ui.handle(request("one", 100));
        ui.handle(request("two", 0));
        ui.handle(request("three", 0));
        answers.get(0).value("first");
        assert replies.size() == 1 && shown.size() == 2;
        // Old timeout must not clear awaiting two and prematurely show three.
        Looper.getMainLooper().advanceBy(100);
        assert replies.size() == 1 && shown.size() == 2 : "old timeout replied twice or cleared next question";
        answers.get(0).value("double click");
        answers.get(0).cancelled();
        assert replies.size() == 1 : "old UI sink replied again";
        answers.get(1).value("second");
        assert replies.size() == 2 && shown.size() == 3;
        ui.cancelPending();
        assert replies.size() == 3 && replies.get(2).optBoolean("cancelled");
        answers.get(2).value("obsolete after reconnect");
        assert replies.size() == 3;
        ui.handle(request("four", 100));
        Looper.getMainLooper().advanceBy(100);
        assert replies.size() == 4 && "four".equals(replies.get(3).optString("id"));
        answers.get(3).value("late click");
        assert replies.size() == 4;
        // Renderer failure must release awaiting, allowing a later request through.
        List<String> afterFailure = new ArrayList<>();
        ExtensionUi broken = new ExtensionUi(null, replies::add, null,
                (id, kind, title, hint, options, prefill, sink) -> {
                    if ("broken".equals(id)) throw new IllegalStateException("fixture render failed");
                    afterFailure.add(id);
                });
        broken.handle(request("broken", 0));
        broken.handle(request("recover", 0));
        assert afterFailure.equals(List.of("recover"));
        JSONObject recall = new JSONObject().put("role", "custom").put("customType", "hindsight-recall")
                .put("details", new JSONObject().put("count", 3));
        String summary = HistoryPresentation.displayText(recall, "<hindsight_memories>PRIVATE_CONTEXT</hindsight_memories>");
        assert summary.equals("已检索 3 条相关记忆") && !summary.contains("PRIVATE_CONTEXT");
        assert "original user input".equals(HistoryPresentation.displayText(new JSONObject().put("role", "user"), "original user input"));
        assert HistoryPresentation.hidden(new JSONObject().put("role", "custom").put("display", false));
        assert !HistoryPresentation.hidden(new JSONObject().put("role", "user").put("display", false));
        // Footer no longer prefixes model — that lives on the composer chip.
        assert HistoryPresentation.statusLabel("provider / model · high", "mc: idle").equals("mc: idle");
        assert HistoryPresentation.statusLabel("provider / model", "就绪").equals("就绪");
        assert HistoryPresentation.statusLabel("x", null).equals("");
        System.out.println("ExtensionUiTest: timeout/duplicate/reconnect/renderer/history/status regressions passed");
    }
}
