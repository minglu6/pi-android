package dev.pi.android.util;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** One-shot WebSocket auth probe with latency for profile "测试连接". */
public final class ProfileConnectTester {
    public interface Callback {
        void onResult(boolean ok, long latencyMs, @Nullable String detail);
    }

    private ProfileConnectTester() {}

    public static void test(@NonNull String host, int port, @NonNull String token,
                            @NonNull Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        long t0 = System.currentTimeMillis();
        AtomicBoolean done = new AtomicBoolean(false);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        String url = "ws://" + host + ":" + port + "/";
        Request req = new Request.Builder().url(url).build();
        final WebSocket[] holder = new WebSocket[1];
        holder[0] = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                try {
                    JSONObject auth = new JSONObject();
                    auth.put("auth", token);
                    webSocket.send(auth.toString());
                } catch (Exception e) {
                    finish(false, "鉴权发送失败");
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JSONObject o = new JSONObject(text);
                    if ("status".equals(o.optString("kind"))) {
                        String state = o.optString("state");
                        if ("online".equals(state)) {
                            finish(true, null);
                        } else {
                            finish(false, o.optString("error", "鉴权失败"));
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                                  @Nullable Response response) {
                finish(false, t.getMessage() == null ? "连接失败" : t.getMessage());
            }

            private void finish(boolean ok, @Nullable String detail) {
                if (!done.compareAndSet(false, true)) return;
                long ms = System.currentTimeMillis() - t0;
                try {
                    if (holder[0] != null) holder[0].cancel();
                } catch (Exception ignored) {}
                try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
                main.post(() -> cb.onResult(ok, ms, detail));
            }
        });
        main.postDelayed(() -> {
            if (done.compareAndSet(false, true)) {
                try {
                    if (holder[0] != null) holder[0].cancel();
                } catch (Exception ignored) {}
                cb.onResult(false, System.currentTimeMillis() - t0, "超时");
            }
        }, 8_000);
    }
}
