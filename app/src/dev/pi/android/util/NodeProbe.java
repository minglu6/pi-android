package dev.pi.android.util;

import androidx.annotation.NonNull;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pi.android.model.Node;

/** 探测连接器 TCP 端口是否可达（用于节点列表 online/offline）。 */
public final class NodeProbe {
    public interface Callback {
        void onResult(@NonNull Node node, boolean online);
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private NodeProbe() {}

    public static void probe(@NonNull Node node, @NonNull Callback cb) {
        POOL.execute(() -> {
            boolean ok = false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(node.host, node.connectorPort), 1500);
                ok = true;
            } catch (Exception ignored) {
            }
            final boolean online = ok;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> cb.onResult(node, online));
        });
    }
}
