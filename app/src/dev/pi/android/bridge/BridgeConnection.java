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
 * <p>连接状态和队列用同一把锁保护；Listener 回调统一抛回主线程并在执行时复查世代。
 * 命令只在当前 socket 鉴权后补发；Pi offline 不等于 socket 未鉴权，恢复命令仍可发送。
 * 换连接或 socket 终止时丢弃未发送队列，不跨连接重放 prompt，也不自动重连。
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

    public synchronized void setTrace(Trace t) {
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
    // 以下状态只在 BridgeConnection.this 锁内访问，不从 Pi 的 offline 推断鉴权失效。
    private boolean authSent;
    private boolean authed;
    private boolean connected;
    /** 换连接/终止时递增，同时使旧 socket 和已排队的主线程回调失效。 */
    private int generation;

    public BridgeConnection() {
        this(new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接，不要超时断开
                .build());
    }

    /** 包内注入点：离线 JVM 回归可使用不创建网络连接的 WebSocket 实现。 */
    BridgeConnection(OkHttpClient http) {
        this.http = http;
    }

    public synchronized void configure(String host, int port, String token) {
        this.urlStr = "ws://" + host + ":" + port + WS_PATH;
        this.token = token == null ? "" : token.trim();
    }

    public synchronized void setListener(Listener l) {
        this.listener = l;
    }

    /** 表示 socket 可发送 RPC，不表示 Pi 子进程正在运行。 */
    public synchronized boolean isConnected() {
        return connected && authed;
    }

    public synchronized void connect() {
        resetConnection();
        Request req = new Request.Builder().url(urlStr).build();
        ws = http.newWebSocket(req, new SocketListener(generation));
    }

    public synchronized void disconnect() {
        resetConnection();
        postStatus(generation, "offline", null);
    }

    /** 必须先使世代失效，再 cancel；cancel 可能触发旧 socket 回调。调用方持锁。 */
    private void resetConnection() {
        generation++;
        WebSocket old = ws;
        ws = null;
        connected = false;
        authSent = false;
        authed = false;
        outbox.clear();
        if (old != null) {
            try {
                old.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 发一条 RPC 命令；返回可用于回填响应的 id。 */
    public synchronized String rpc(String command, @Nullable JSONObject params) {
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
        outbox.add(msg.toString());
        flushOutbox();
        return id;
    }

    /** 调用方持锁；send 成功即移除，绝不因后续 Pi offline 重发。 */
    private void flushOutbox() {
        while (connected && authed && !outbox.isEmpty() && ws != null) {
            String line = outbox.peek();
            if (ws.send(line)) outbox.poll();
            else break;
        }
    }

    /** OkHttp 线程已在锁内检查世代；真正交付时仍须再检查一次。 */
    private void post(int gen, java.util.function.Consumer<Listener> callback) {
        main.post(() -> {
            synchronized (BridgeConnection.this) {
                if (gen != generation || listener == null) return;
                callback.accept(listener);
            }
        });
    }

    private void postStatus(int gen, String state, @Nullable String error) {
        post(gen, l -> l.onStatus(state, error));
    }

    /** 调用方持锁且已验证世代。 */
    private void handle(int gen, String raw) {
        JSONObject obj;
        try {
            obj = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        String kind = obj.optString("kind");
        if (trace != null) trace.trace("recv kind=" + kind + " len=" + raw.length());
        // Trace 也可能同步调用 disconnect/connect，不能继续处理已失效帧。
        if (gen != generation) return;
        switch (kind) {
            case "status": {
                String state = obj.optString("state");
                String err = obj.isNull("error") ? null : obj.optString("error");
                boolean hasErr = err != null && !err.isEmpty();
                // 桥在鉴权前会先发 state:offline（无 error）——那是问候，不是失败。
                // online/starting 才是鉴权 ack；offline+error（如 bad token）才是鉴权失败。
                // 此后 Pi offline 不撤销 socket 鉴权。
                if (authSent && ("online".equals(state) || "starting".equals(state))) {
                    authed = true;
                    flushOutbox();
                }
                if (!authed) {
                    if ("online".equals(state) || "starting".equals(state)) {
                        postStatus(gen, state, err);
                    } else if ("offline".equals(state) && hasErr) {
                        postStatus(gen, state, err);
                    }
                    // else: ignore greeting offline / unknown until ack or auth error
                    break;
                }
                postStatus(gen, state, err);
                break;
            }
            case "response": {
                String id = obj.optString("id");
                JSONObject data = obj.isNull("data") ? null : obj.optJSONObject("data");
                boolean ok = obj.optBoolean("ok");
                String cmd = obj.optString("command");
                String err = obj.isNull("error") ? null : obj.optString("error");
                post(gen, l -> l.onResponse(id, ok, cmd, data, err));
                break;
            }
            case "event": {
                JSONObject ev = obj.optJSONObject("event");
                if (ev != null) post(gen, l -> l.onEvent(ev));
                break;
            }
            case "pong":
            case "log":
            default:
                break;
        }
    }

    /** 终止当前世代，也撤销之前排队但还没执行的 online/event/response。调用方持锁。 */
    private void socketEnded(@Nullable Throwable failure, @Nullable String reason) {
        generation++;
        connected = false;
        authSent = false;
        authed = false;
        ws = null;
        outbox.clear();
        int gen = generation;
        if (failure != null) post(gen, l -> l.onFailure(failure));
        postStatus(gen, "offline", reason);
    }

    private final class SocketListener extends WebSocketListener {
        private final int gen;

        SocketListener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            synchronized (BridgeConnection.this) {
                if (gen != generation) return;
                connected = true;
                JSONObject auth = new JSONObject();
                try {
                    auth.put("auth", token);
                } catch (Exception ignored) {
                }
                authSent = webSocket.send(auth.toString());
                // 不在 onOpen 补发 RPC，必须等待鉴权 ack。
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            synchronized (BridgeConnection.this) {
                if (gen != generation) return;
                handle(gen, text);
            }
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            synchronized (BridgeConnection.this) {
                if (gen != generation) return;
                // 对端发起关闭时停止发送并完成关闭握手，不等 onClosed 才通知离线。
                socketEnded(null, reason);
                webSocket.close(code, reason);
            }
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            synchronized (BridgeConnection.this) {
                if (gen != generation) return;
                socketEnded(t, t.getMessage());
            }
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            synchronized (BridgeConnection.this) {
                if (gen != generation) return;
                socketEnded(null, reason);
            }
        }
    }
}
