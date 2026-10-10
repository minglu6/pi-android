package dev.pi.android.ssh;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.pi.android.model.Node;
import dev.pi.android.util.SecretStore;

/**
 * 单节点 SSH PTY 会话。读写在后台线程；UI 回调切主线程。
 */
public final class SshSession {
    private static final String TAG = "PiSsh";

    public interface Listener {
        void onConnected();
        void onData(@NonNull byte[] data, int len);
        /** 首次连接：展示指纹，返回 true 接受并 TOFU 记住。 */
        boolean onHostKey(@NonNull String fingerprint, @NonNull String type);
        void onError(@NonNull String message);
        void onClosed();
    }

    private final Context app;
    private final Node node;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Session session;
    private ChannelShell channel;
    private OutputStream stdin;

    public SshSession(@NonNull Context ctx, @NonNull Node node, @NonNull Listener listener) {
        this.app = ctx.getApplicationContext();
        this.node = node;
        this.listener = listener;
    }

    public void connect() {
        io.execute(() -> {
            try {
                doConnect();
            } catch (Exception e) {
                Log.w(TAG, "connect failed", e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> listener.onError(msg));
                close();
            }
        });
    }

    private void doConnect() throws Exception {
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(new TofuHostKeyRepository(app, node.id, listener, main));

        String user = node.sshUser == null || node.sshUser.isEmpty() ? "user" : node.sshUser;
        session = jsch.getSession(user, node.host, node.sshPort);
        Properties cfg = new Properties();
        cfg.put("StrictHostKeyChecking", "ask");
        cfg.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setConfig(cfg);

        if (node.sshAuth == Node.Auth.KEY) {
            String priv = SecretStore.get(app, SecretStore.sshPrivateKeyKey(node.id));
            if (priv == null || priv.isEmpty()) {
                throw new IllegalStateException("未生成 SSH 密钥，请先在节点编辑页生成");
            }
            jsch.addIdentity(node.id, priv.getBytes("UTF-8"), null, null);
        } else {
            String pw = SecretStore.get(app, SecretStore.sshPasswordKey(node.id));
            if (pw == null) pw = "";
            session.setPassword(pw);
        }

        session.connect(15000);
        channel = (ChannelShell) session.openChannel("shell");
        channel.setPty(true);
        channel.setPtyType("xterm-256color", 80, 24, 640, 480);
        channel.setPtySize(80, 24, 640, 480);
        InputStream stdout = channel.getInputStream();
        stdin = channel.getOutputStream();
        channel.connect(10000);
        main.post(listener::onConnected);

        byte[] buf = new byte[8192];
        while (!closed.get() && !channel.isClosed()) {
            int n = stdout.read(buf);
            if (n < 0) break;
            if (n == 0) continue;
            final byte[] copy = new byte[n];
            System.arraycopy(buf, 0, copy, 0, n);
            main.post(() -> listener.onData(copy, n));
        }
        main.post(listener::onClosed);
        close();
    }

    public void write(@NonNull byte[] data) {
        io.execute(() -> {
            try {
                if (stdin != null) {
                    stdin.write(data);
                    stdin.flush();
                }
            } catch (Exception e) {
                Log.w(TAG, "write", e);
            }
        });
    }

    public void resize(int cols, int rows, int wp, int hp) {
        io.execute(() -> {
            try {
                if (channel != null && channel.isConnected()) {
                    channel.setPtySize(cols, rows, wp, hp);
                }
            } catch (Exception e) {
                Log.w(TAG, "resize", e);
            }
        });
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        io.execute(() -> {
            try { if (channel != null) channel.disconnect(); } catch (Exception ignored) {}
            try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
            io.shutdownNow();
        });
    }

    public boolean isOpen() {
        return !closed.get() && channel != null && channel.isConnected();
    }

    /** TOFU：第一次询问 UI，接受后记住指纹。 */
    private static final class TofuHostKeyRepository implements HostKeyRepository {
        private final Context app;
        private final String nodeId;
        private final Listener listener;
        private final Handler main;
        private volatile boolean accepted;

        TofuHostKeyRepository(Context app, String nodeId, Listener listener, Handler main) {
            this.app = app;
            this.nodeId = nodeId;
            this.listener = listener;
            this.main = main;
        }

        @Override public int check(String host, byte[] key) {
            String fp = fingerprint(key);
            String known = SecretStore.get(app, SecretStore.hostKeyFpKey(nodeId));
            if (known != null && known.equals(fp)) return OK;
            if (known != null) {
                // 指纹变了 —— 拒绝，让上层报错
                return NOT_INCLUDED;
            }
            final Object lock = new Object();
            final boolean[] ans = {false};
            main.post(() -> {
                ans[0] = listener.onHostKey(fp, "ssh-ed25519/rsa");
                synchronized (lock) { lock.notifyAll(); }
            });
            synchronized (lock) {
                try { lock.wait(60000); } catch (InterruptedException ignored) {}
            }
            if (ans[0]) {
                SecretStore.put(app, SecretStore.hostKeyFpKey(nodeId), fp);
                accepted = true;
                return OK;
            }
            return NOT_INCLUDED;
        }

        @Override public void add(HostKey hostkey, com.jcraft.jsch.UserInfo ui) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "pi-tofu"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }

        private static String fingerprint(byte[] key) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] dig = md.digest(key);
                return "SHA256:" + android.util.Base64.encodeToString(dig, android.util.Base64.NO_WRAP)
                        .replace("=", "");
            } catch (Exception e) {
                return "unknown";
            }
        }
    }
}
