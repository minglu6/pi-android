package dev.pi.android.bridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 与 Termux 侧 pi-rpc-bridge 的 WebSocket 连接。
 *
 * <p>线程模型：OkHttp 的回调在它的后台线程，统一用 Handler 抛回主线程，UI 侧不需要自己切线程。
 * 命令在连上之前发出会先排队，连上后补发 —— Termux 侧的桥是按需拉起 Pi 子进程的，
 * 首条命令往往落在 Pi 起来之前，不排队就会丢。
 */
public class BridgeConnection {

    public interface Listener {
        void onStatus(String state, @Nullable String error);
        void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error);
        void onEvent(@NonNull JSONObject event);
        void onFailure(@Nullable Throwable t);
    }

    private static final String TAG = "PiBridge";

    /** 临时取证：把收到的帧类型交给服务端写文件（本机 logcat 收不到 App 日志） */
    public interface Trace {
        void trace(String msg);
    }

    private Trace trace;

    public void setTrace(Trace t) {
        this.trace = t;
    }
    private static final String WS_PATH = "";   // 桥直接挂在 ws://host:port/

    private final OkHttpClient http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong seq = new AtomicLong();
    private final Deque<String> outbox = new ArrayDeque<>();

    private WebSocket ws;
    private Listener listener;
    private String token = "";
    private String urlStr = "ws://127.0.0.1:8945";
    private volatile boolean authed;
    private volatile boolean connected;
    /** 每次 connect() 递增；旧 socket 的回调（尤其是主动 cancel 触发的 onFailure）一律丢弃 */
    private int generation;

    public BridgeConnection() {
        http = new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接，不要超时断开
                .build();
    }

    public void configure(String host, int port, String token) {
        this.urlStr = "ws://" + host + ":" + port + WS_PATH;
        this.token = token == null ? "" : token.trim();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isConnected() {
        return connected && authed;
    }

    public void connect() {
        disconnect();
        generation++;
        Request req = new Request.Builder().url(urlStr).build();
        ws = http.newWebSocket(req, new SocketListener(generation));
    }

    public void disconnect() {
        if (ws != null) {
            try {
                ws.cancel();
            } catch (Throwable ignored) {
            }
            ws = null;
        }
        // 主动断开要让旧 socket 的回调彻底哑火，否则 UI 会收到一串 Canceled / Socket closed
        generation++;
        connected = false;
        authed = false;
        outbox.clear();
    }

    /** 发一条 RPC 命令；返回可用于回填响应的 id。 */
    public String rpc(String command, @Nullable JSONObject params) {
        String id = "a" + seq.incrementAndGet();
        JSONObject msg = new JSONObject();
        try {
            msg.put("id", id);
            msg.put("rpc", command);
            if (params != null) msg.put("params", params);
        } catch (Exception e) {
            Log.w(TAG, "rpc 组装失败", e);
            return id;
        }
        String line = msg.toString();
        if (isConnected()) {
            if (ws == null || !ws.send(line)) queue(line);
        } else {
            queue(line);
        }
        return id;
    }

    private synchronized void queue(String line) {
        outbox.add(line);
    }

    private synchronized void flushOutbox() {
        while (!outbox.isEmpty() && ws != null) {
            String line = outbox.peek();
            if (ws.send(line)) outbox.poll();
            else break;
        }
    }

    private void handle(String raw) {
        JSONObject obj;
        try {
            obj = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        String kind = obj.optString("kind");
        if (trace != null) trace.trace("recv kind=" + kind + " len=" + raw.length());
        switch (kind) {
            case "status": {
                String state = obj.optString("state");
                authed = !"offline".equals(state);
                if (listener != null) {
                    final String s = state;
                    final String err = obj.isNull("error") ? null : obj.optString("error");
                    main.post(() -> listener.onStatus(s, err));
                }
                break;
            }
            case "response": {
                String id = obj.optString("id");
                if (listener != null) {
                    final JSONObject data = obj.isNull("data") ? null : obj.optJSONObject("data");
                    boolean ok = obj.optBoolean("ok");
                    String cmd = obj.optString("command");
                    String err = obj.isNull("error") ? null : obj.optString("error");
                    main.post(() -> listener.onResponse(id, ok, cmd, data, err));
                }
                break;
            }
            case "event": {
                JSONObject ev = obj.optJSONObject("event");
                if (ev != null && listener != null) {
                    final JSONObject copy = ev;
                    main.post(() -> listener.onEvent(copy));
                }
                break;
            }
            case "pong":
            case "log":
            default:
                break;
        }
    }

    private void postFailure(Throwable t) {
        if (listener != null) main.post(() -> listener.onFailure(t));
    }

    private final class SocketListener extends WebSocketListener {
        private final int gen;

        SocketListener(int gen) {
            this.gen = gen;
        }

        private boolean stale() {
            return gen != generation;
        }

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            if (stale()) return;
            connected = true;
            JSONObject auth = new JSONObject();
            try {
                auth.put("auth", token);
            } catch (Exception ignored) {
            }
            webSocket.send(auth.toString());
            flushOutbox();
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            if (stale()) return;
            handle(text);
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            if (stale()) return;
            connected = false;
            authed = false;
            postFailure(t);
            if (listener != null) main.post(() -> listener.onStatus("offline", t.getMessage()));
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            connected = false;
            authed = false;
        }
    }
}
