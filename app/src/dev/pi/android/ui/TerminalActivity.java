package dev.pi.android.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.pi.android.R;
import dev.pi.android.bridge.SshSessionHolder;
import dev.pi.android.model.Node;
import dev.pi.android.ssh.SshSession;
import dev.pi.android.util.NodeStore;

public class TerminalActivity extends AppCompatActivity {
    public static final String EXTRA_NODE_ID = "node_id";

    private TerminalView term;
    private Node node;
    private SshSession session;
    private final AtomicBoolean ctrlLatch = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SettingsActivity.applySavedTheme(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_terminal);

        String id = getIntent().getStringExtra(EXTRA_NODE_ID);
        node = NodeStore.get(this, id);
        if (node == null) {
            Toast.makeText(this, "节点不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        View top = findViewById(R.id.term_top);
        View root = findViewById(R.id.term_root);
        View keys = findViewById(R.id.term_keys);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime()
                    | WindowInsetsCompat.Type.displayCutout());
            if (top != null) top.setPadding(0, bars.top, 0, 0);
            if (keys != null) keys.setPadding(keys.getPaddingLeft(), 0, keys.getPaddingRight(), bars.bottom);
            return insets;
        });

        TextView title = findViewById(R.id.term_title);
        title.setText(getString(R.string.terminal_title) + " · " + node.name);

        term = findViewById(R.id.term_view);
        findViewById(R.id.term_back).setOnClickListener(v -> finish());
        findViewById(R.id.term_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("terminal", term.copyVisible()));
                Toast.makeText(this, R.string.terminal_copied, Toast.LENGTH_SHORT).show();
            }
        });

        buildKeyBar();
        term.setOutputSink(new TerminalView.OutputSink() {
            @Override public void onUserInput(byte[] data) {
                if (ctrlLatch.getAndSet(false) && data.length == 1) {
                    byte b = data[0];
                    if (b >= 'a' && b <= 'z') data = new byte[]{(byte) (b - 96)};
                    else if (b >= 'A' && b <= 'Z') data = new byte[]{(byte) (b - 64)};
                }
                if (session != null) session.write(data);
            }
            @Override public void onResize(int cols, int rows) {
                if (session != null) session.resize(cols, rows, cols * 8, rows * 16);
            }
        });

        term.feed(getString(R.string.terminal_connecting) + "\r\n");
        connect();
    }

    private void connect() {
        SshSession existing = SshSessionHolder.get(node.id);
        if (existing != null && existing.isOpen()) {
            session = existing;
            term.feed("[复用已有会话]\r\n");
            // 无法回放历史；新数据仍会到 listener——需重新绑定
        }
        session = new SshSession(this, node, new SshSession.Listener() {
            @Override public void onConnected() {
                term.feed("已连接 " + node.sshUser + "@" + node.host + "\r\n");
                session.resize(term.cols(), term.rows(), term.cols() * 8, term.rows() * 16);
            }
            @Override public void onData(byte[] data, int len) {
                term.feed(data, len);
            }
            @Override public boolean onHostKey(String fingerprint, String type) {
                return askHostKey(fingerprint);
            }
            @Override public void onError(String message) {
                term.feed("\r\n⚠ " + message + "\r\n");
            }
            @Override public void onClosed() {
                term.feed("\r\n" + getString(R.string.terminal_disconnected) + "\r\n");
                SshSessionHolder.remove(node.id);
            }
        });
        SshSessionHolder.put(node.id, session);
        session.connect();
    }

    private boolean askHostKey(String fingerprint) {
        final Object lock = new Object();
        final boolean[] accepted = {false};
        final boolean[] answered = {false};
        runOnUiThread(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.PiMaterialAlertDialog)
                .setTitle(R.string.terminal_hostkey_title)
                .setMessage(getString(R.string.terminal_hostkey_msg, node.host, fingerprint))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    accepted[0] = true;
                    synchronized (lock) { answered[0] = true; lock.notifyAll(); }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    accepted[0] = false;
                    synchronized (lock) { answered[0] = true; lock.notifyAll(); }
                })
                .setCancelable(false)
                .show());
        synchronized (lock) {
            try {
                while (!answered[0]) lock.wait(60000);
            } catch (InterruptedException ignored) {}
        }
        return accepted[0];
    }

    private void buildKeyBar() {
        LinearLayout row = findViewById(R.id.term_keys_row);
        addKey(row, getString(R.string.key_esc), () -> sendRaw(new byte[]{0x1b}));
        addKey(row, getString(R.string.key_tab), () -> sendRaw(new byte[]{'\t'}));
        addKey(row, getString(R.string.key_ctrl), () -> ctrlLatch.set(true));
        addKey(row, getString(R.string.key_up), () -> sendRaw("\u001b[A".getBytes(StandardCharsets.UTF_8)));
        addKey(row, getString(R.string.key_down), () -> sendRaw("\u001b[B".getBytes(StandardCharsets.UTF_8)));
        addKey(row, getString(R.string.key_left), () -> sendRaw("\u001b[D".getBytes(StandardCharsets.UTF_8)));
        addKey(row, getString(R.string.key_right), () -> sendRaw("\u001b[C".getBytes(StandardCharsets.UTF_8)));
        addKey(row, "|", () -> sendRaw("|".getBytes(StandardCharsets.UTF_8)));
        addKey(row, "/", () -> sendRaw("/".getBytes(StandardCharsets.UTF_8)));
        addKey(row, "-", () -> sendRaw("-".getBytes(StandardCharsets.UTF_8)));
        addKey(row, "~", () -> sendRaw("~".getBytes(StandardCharsets.UTF_8)));
    }

    private void addKey(LinearLayout row, String label, Runnable action) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(24, 8, 24, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        row.addView(b);
    }

    private void sendRaw(byte[] data) {
        if (session != null) session.write(data);
    }

    @Override
    protected void onDestroy() {
        // 会话留给 SshSessionHolder，便于返回后复用；显式不 disconnect
        super.onDestroy();
    }
}
