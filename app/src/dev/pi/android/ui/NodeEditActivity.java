package dev.pi.android.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import dev.pi.android.R;
import dev.pi.android.model.Node;
import dev.pi.android.ssh.SshKeyGen;
import dev.pi.android.util.NodeStore;
import dev.pi.android.util.SecretStore;

public class NodeEditActivity extends AppCompatActivity {
    public static final String EXTRA_NODE_ID = "node_id";

    private String nodeId;
    private EditText nameEdit, hostEdit, portEdit, tokenEdit, sshUserEdit, sshPortEdit, sshPwEdit;
    private RadioGroup authGroup;
    private RadioButton authKey, authPw;
    private TextView pubKeyView;
    private View copyPubBtn;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SettingsActivity.applySavedTheme(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_node_edit);

        View top = findViewById(R.id.node_edit_top);
        View root = findViewById(R.id.node_edit_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (top != null) top.setPadding(0, bars.top, 0, 0);
            return insets;
        });

        nameEdit = findViewById(R.id.node_name);
        hostEdit = findViewById(R.id.node_host);
        portEdit = findViewById(R.id.node_port);
        tokenEdit = findViewById(R.id.node_token);
        sshUserEdit = findViewById(R.id.node_ssh_user);
        sshPortEdit = findViewById(R.id.node_ssh_port);
        sshPwEdit = findViewById(R.id.node_ssh_password);
        authGroup = findViewById(R.id.node_ssh_auth);
        authKey = findViewById(R.id.node_auth_key);
        authPw = findViewById(R.id.node_auth_password);
        pubKeyView = findViewById(R.id.node_pubkey);
        copyPubBtn = findViewById(R.id.node_copy_pubkey);

        findViewById(R.id.node_edit_back).setOnClickListener(v -> finish());
        findViewById(R.id.node_edit_save).setOnClickListener(v -> save());
        findViewById(R.id.node_gen_key).setOnClickListener(v -> genKey());
        copyPubBtn.setOnClickListener(v -> {
            CharSequence t = pubKeyView.getText();
            if (t == null || t.length() == 0) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ssh-public-key", t));
                Toast.makeText(this, R.string.nodes_pubkey_copied, Toast.LENGTH_LONG).show();
            }
        });

        nodeId = getIntent().getStringExtra(EXTRA_NODE_ID);
        if (nodeId == null) {
            nodeId = NodeStore.newId();
            ((TextView) findViewById(R.id.node_edit_title)).setText(R.string.nodes_add);
            portEdit.setText("8945");
            sshPortEdit.setText("22");
            authKey.setChecked(true);
        } else {
            Node n = NodeStore.get(this, nodeId);
            if (n == null) { finish(); return; }
            nameEdit.setText(n.name);
            hostEdit.setText(n.host);
            portEdit.setText(String.valueOf(n.connectorPort));
            sshUserEdit.setText(n.sshUser);
            sshPortEdit.setText(String.valueOf(n.sshPort));
            if (n.sshAuth == Node.Auth.PASSWORD) authPw.setChecked(true);
            else authKey.setChecked(true);
            String tok = SecretStore.get(this, SecretStore.tokenKey(nodeId));
            if (tok != null) tokenEdit.setText(tok);
            String pw = SecretStore.get(this, SecretStore.sshPasswordKey(nodeId));
            if (pw != null) sshPwEdit.setText(pw);
            String pub = SecretStore.get(this, SecretStore.sshPublicKeyKey(nodeId));
            if (pub != null && !pub.isEmpty()) {
                pubKeyView.setVisibility(View.VISIBLE);
                copyPubBtn.setVisibility(View.VISIBLE);
                pubKeyView.setText(pub);
            }
        }
    }

    private void genKey() {
        try {
            SshKeyGen.Pair pair = SshKeyGen.generate("pi-android@" + android.os.Build.MODEL);
            SecretStore.put(this, SecretStore.sshPrivateKeyKey(nodeId), pair.privatePem);
            SecretStore.put(this, SecretStore.sshPublicKeyKey(nodeId), pair.publicOpenSsh);
            pubKeyView.setVisibility(View.VISIBLE);
            copyPubBtn.setVisibility(View.VISIBLE);
            pubKeyView.setText(pair.publicOpenSsh);
            authKey.setChecked(true);
            Toast.makeText(this, R.string.nodes_pubkey_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void save() {
        String name = nameEdit.getText().toString().trim();
        String host = hostEdit.getText().toString().trim();
        if (name.isEmpty() || host.isEmpty()) {
            Toast.makeText(this, R.string.nodes_host_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        int sshPort;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
            sshPort = Integer.parseInt(sshPortEdit.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "端口无效", Toast.LENGTH_SHORT).show();
            return;
        }
        Node.Auth auth = authPw.isChecked() ? Node.Auth.PASSWORD : Node.Auth.KEY;
        String user = sshUserEdit.getText().toString().trim();
        Node node = new Node(nodeId, name, host, port, user, sshPort, auth);
        // 先写 SecretStore，再 upsert（upsert 会对激活节点 sync legacy connector）
        String token = tokenEdit.getText().toString().trim();
        SecretStore.put(this, SecretStore.tokenKey(nodeId), token);
        String pw = sshPwEdit.getText().toString();
        if (!pw.isEmpty()) SecretStore.put(this, SecretStore.sshPasswordKey(nodeId), pw);
        NodeStore.upsert(this, node);
        Toast.makeText(this, R.string.nodes_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
