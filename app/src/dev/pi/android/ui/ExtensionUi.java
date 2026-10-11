package dev.pi.android.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Pi 的扩展 UI 子协议（docs/rpc-extension-ui.md）的解析与排队层。
 *
 * <p>为什么它必须存在：select / confirm / input / editor 四种方法是**阻塞式**的 —— Pi 会一直等
 * `extension_ui_response`。客户端不回，Pi 就停在工具调用上（之后每条 prompt 都会报
 * "Agent is already processing"）。所以任何处理不了的请求也要回一条 cancelled，不能默默丢掉。
 *
 * <p>渲染不在这一层：宿主（MainActivity）用聊天流上方的按钮行来问，而不是 AlertDialog
 * —— 本机 scrcpy 虚拟屏上 AlertDialog 有渲染不出来的情况（实测：对象创建成功、isShowing 为 true，
 * 但屏幕上没有），而这几种请求一旦没有可见 UI，Pi 就被挂死。按钮行至少一定能出现在容器底部。
 */
public class ExtensionUi {

    public interface Responder {
        void respond(JSONObject response);      // {type: extension_ui_response, id, value/confirmed/cancelled}
    }

    /** 渲染交给宿主：一次只有一个在问，答案通过 AnswerSink 回来 */
    public interface Host {
        /** id 是请求 id（作答时要原样带回）；kind: select / confirm / input / editor */
        void showChoice(String id, String kind, String title, String hint, List<String> options,
                        String prefill, AnswerSink sink);
    }

    public interface AnswerSink {
        void value(String value);

        void confirmed(boolean yes);

        void cancelled();
    }

    /** 通知类方法没有 UI 位置，交给上层决定怎么显示 */
    public interface StatusSink {
        void onStatus(String key, @Nullable String text);

        void onTitle(String title);

        void onNotify(String message, String type);

        /** RPC setWidget string lines (null clears). Default no-op. */
        default void onWidget(String key, @Nullable List<String> lines) {}
    }

    private static final long MAX_QUEUE_AGE_MS = 120_000L;

    private final ToastHost toasts;
    private final Responder responder;
    private final StatusSink sink;
    private final Host host;
    private final Deque<Pending> queue = new ArrayDeque<>();

    private boolean awaiting;
    private String awaitingId;

    public interface ToastHost {
        void toast(String msg);
    }

    public ExtensionUi(ToastHost toasts, Responder responder, StatusSink sink, Host host) {
        this.toasts = toasts;
        this.responder = responder;
        this.sink = sink;
        this.host = host;
    }

    /** 重载：带 Trace Sink，用于把队列决策写到服务端可读取的文件（临时调试） */
    public interface Trace {
        void trace(String msg);
    }

    private Trace trace;

    public void setTrace(Trace t) {
        this.trace = t;
    }

    private void t(String msg) {
        if (trace != null) trace.trace(msg);
    }

    /** @return true 表示这条请求已被本层消费 */
    public boolean handle(@NonNull JSONObject req) {
        String method = req.optString("method");
        try {
            switch (method) {
                case "select":
                case "confirm":
                case "input":
                case "editor":
                    t("queue " + method + " id=" + req.optString("id"));
                    queue.add(new Pending(req, System.currentTimeMillis()));
                    pump();
                    return true;
                case "notify":
                    if (sink != null) {
                        sink.onNotify(req.optString("message"), req.optString("notifyType", "info"));
                    }
                    return true;
                case "setStatus":
                    if (sink != null) {
                        String text = req.isNull("statusText") ? null : req.optString("statusText");
                        // null / empty clears this key (Pi passes undefined to clear)
                        if (text != null && text.isEmpty()) text = null;
                        sink.onStatus(req.optString("statusKey", "?"), text);
                    }
                    return true;
                case "setTitle":
                    if (sink != null) sink.onTitle(req.optString("title", ""));
                    return true;
                case "setWidget":
                    if (sink != null) {
                        String key = req.optString("widgetKey", "?");
                        if (req.isNull("widgetLines")) {
                            sink.onWidget(key, null);
                        } else {
                            List<String> lines = new ArrayList<>();
                            JSONArray arr = req.optJSONArray("widgetLines");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    lines.add(String.valueOf(arr.opt(i)));
                                }
                            }
                            sink.onWidget(key, lines);
                        }
                    }
                    return true;
                case "set_editor_text":
                    // fire-and-forget，忽略不会阻塞 Pi
                    return true;
                default:
                    // 未知方法：回一条 cancelled，至少不让 Pi 挂住
                    respondCancelled(req.optString("id"));
                    return true;
            }
        } catch (Throwable t) {
            String id = req.optString("id");
            finish(id);
            respondCancelled(id);
            pump();
            return true;
        }
    }

    /** 一次只问一个；上一个回答完（或被超时/调用方取消）后才处理下一个。 */
    private void pump() {
        t("pump awaiting=" + awaiting + " queue=" + queue.size());
        if (awaiting) return;
        Pending item = queue.poll();
        if (item == null) return;
        if (System.currentTimeMillis() - item.enqueuedAt > MAX_QUEUE_AGE_MS) {
            respondCancelled(item.req.optString("id"));
            pump();
            return;
        }
        JSONObject req = item.req;
        String id = req.optString("id");
        AnswerSink answer = new AnswerSink() {
            @Override
            public void value(String v) {
                if (!finish(id)) return;
                JSONObject r = new JSONObject();
                try {
                    r.put("id", id);
                    r.put("value", v == null ? "" : v);
                } catch (Exception ignored) {
                }
                responder.respond(r);
                pump();
            }

            @Override
            public void confirmed(boolean yes) {
                if (!finish(id)) return;
                JSONObject r = new JSONObject();
                try {
                    r.put("id", id);
                    r.put("confirmed", yes);
                } catch (Exception ignored) {
                }
                responder.respond(r);
                pump();
            }

            @Override
            public void cancelled() {
                if (!finish(id)) return;
                respondCancelled(id);
                pump();
            }
        };
        awaiting = true;
        awaitingId = id;

        String kind = req.optString("method");
        String title = req.optString("title", "Pi");
        switch (kind) {
            case "select": {
                List<String> options = new ArrayList<>();
                JSONArray arr = req.optJSONArray("options");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) options.add(String.valueOf(arr.opt(i)));
                }
                if (options.isEmpty()) {
                    answer.cancelled();
                    return;
                }
                if (host != null) host.showChoice(id, "select", title, "", options, null, answer);
                else answer.cancelled();
                break;
            }
            case "confirm": {
                List<String> labels = new ArrayList<>();
                labels.add("确定");
                labels.add("取消");
                if (host != null) {
                    host.showChoice(id, "confirm", title, req.optString("message", ""), labels, null, answer);
                } else {
                    answer.cancelled();
                }
                break;
            }
            case "input":
            case "editor": {
                List<String> labels = new ArrayList<>();
                labels.add("editor".equals(kind) ? "提交" : "确定");
                labels.add("取消");
                if (host != null) {
                    host.showChoice(id, kind, title, req.optString("placeholder", ""), labels,
                            req.optString("prefill", ""), answer);
                } else {
                    answer.cancelled();
                }
                break;
            }
            default:
                answer.cancelled();
                break;
        }
        // 带 timeout 的方法：到期 Pi 自己按 undefined 了结，这边也别再等
        if (!req.isNull("timeout")) {
            long ms = req.optLong("timeout", 0L);
            if (ms > 0) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(answer::cancelled, ms);
            }
        }
    }

    private boolean finish(String id) {
        // 作答、超时、重连取消可能竞争；旧 sink 不得结束下一条提问或重复回答。
        if (!awaiting || awaitingId == null || !awaitingId.equals(id)) return false;
        awaiting = false;
        awaitingId = null;
        return true;
    }

    /** UI 被系统/屏幕状态弄没了时的兜底：把正在等的请求取消掉，别让 Pi 干等。 */
    public void cancelPending() {
        if (awaiting) {
            String id = awaitingId;
            awaiting = false;
            awaitingId = null;
            respondCancelled(id);
        }
        Pending p = queue.poll();
        while (p != null) {
            respondCancelled(p.req.optString("id"));
            p = queue.poll();
        }
    }

    private void respondCancelled(String id) {
        if (id == null || id.isEmpty()) return;
        JSONObject r = new JSONObject();
        try {
            r.put("id", id);
            r.put("cancelled", true);
        } catch (Exception ignored) {
        }
        responder.respond(r);
    }

    void toast(String msg) {
        if (toasts != null) toasts.toast(msg);
    }

    private static final class Pending {
        final JSONObject req;
        final long enqueuedAt;

        Pending(JSONObject req, long ts) {
            this.req = req;
            this.enqueuedAt = ts;
        }
    }
}
