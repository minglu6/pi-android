package dev.pi.android.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import dev.pi.android.R;
import dev.pi.android.bridge.BridgeService;
import dev.pi.android.model.Node;
import dev.pi.android.util.NodeProbe;
import dev.pi.android.util.NodeStore;

/** 节点列表：切换聊天连接器 / 打开 SSH 终端 / 编辑。 */
public class NodesActivity extends AppCompatActivity {
    private Adapter adapter;
    private final NodeStore.Listener storeListener = this::reload;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SettingsActivity.applySavedTheme(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_nodes);

        View top = findViewById(R.id.nodes_top);
        View root = findViewById(R.id.nodes_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (top != null) top.setPadding(top.getPaddingLeft(), bars.top, top.getPaddingRight(), 0);
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        findViewById(R.id.nodes_back).setOnClickListener(v -> finish());
        findViewById(R.id.nodes_add).setOnClickListener(v ->
                startActivity(new Intent(this, NodeEditActivity.class)));
        findViewById(R.id.nodes_import).setOnClickListener(v -> showImport());

        RecyclerView list = findViewById(R.id.nodes_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        NodeStore.addListener(storeListener);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        NodeStore.removeListener(storeListener);
        super.onDestroy();
    }

    private void reload() {
        List<Node> nodes = NodeStore.list(this);
        String active = NodeStore.active(this).id;
        adapter.replace(nodes, active);
        for (Node n : nodes) {
            NodeProbe.probe(n, (node, online) -> {
                node.onlineKnown = true;
                node.online = online;
                adapter.notifyDataSetChanged();
            });
        }
    }

    private void showImport() {
        EditText input = new EditText(this);
        input.setHint(R.string.nodes_import_hint);
        input.setMinLines(6);
        input.setMaxLines(12);
        MaterialDialogs.promptCustom(this, R.string.nodes_import_title, input, () -> {
            try {
                int n = NodeStore.importTailscaleStatus(this, input.getText().toString());
                Toast.makeText(this,
                        n > 0 ? getString(R.string.nodes_import_ok, n) : getString(R.string.nodes_import_none),
                        Toast.LENGTH_SHORT).show();
                reload();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void switchChat(Node node) {
        NodeStore.setActive(this, node);
        Intent svc = new Intent(this, BridgeService.class);
        ContextCompat.startForegroundService(this, svc);
        // 触发重连：MainActivity onResume 会看 configSignature
        Toast.makeText(this, getString(R.string.nodes_switched, node.name), Toast.LENGTH_SHORT).show();
        Intent main = new Intent(this, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        main.putExtra("force_reconnect", true);
        startActivity(main);
        finish();
    }

    private void openTerminal(Node node) {
        Intent i = new Intent(this, TerminalActivity.class);
        i.putExtra(TerminalActivity.EXTRA_NODE_ID, node.id);
        startActivity(i);
    }

    private void showNodeMenu(Node node) {
        java.util.List<dev.pi.android.model.PickerItem> items = new java.util.ArrayList<>();
        items.add(dev.pi.android.model.PickerItem.row(getString(R.string.nodes_action_chat), null, "chat", false));
        items.add(dev.pi.android.model.PickerItem.row(getString(R.string.nodes_action_terminal), null, "term", false));
        items.add(dev.pi.android.model.PickerItem.row(getString(R.string.nodes_action_edit), null, "edit", false));
        if (!"local".equals(node.id)) {
            items.add(dev.pi.android.model.PickerItem.row(getString(R.string.nodes_action_delete),
                    getString(R.string.nodes_delete_confirm), "del", false));
        }
        PickerSheet sheet = new PickerSheet(this, R.string.nodes_actions, false, item -> {
            Object p = item.payload;
            if ("chat".equals(p)) switchChat(node);
            else if ("term".equals(p)) openTerminal(node);
            else if ("edit".equals(p)) {
                Intent i = new Intent(NodesActivity.this, NodeEditActivity.class);
                i.putExtra(NodeEditActivity.EXTRA_NODE_ID, node.id);
                startActivity(i);
            } else if ("del".equals(p)) {
                MaterialDialogs.confirm(NodesActivity.this, R.string.nodes_action_delete,
                        R.string.nodes_delete_confirm, R.string.nodes_action_delete,
                        () -> {
                            NodeStore.delete(NodesActivity.this, node.id);
                            reload();
                        });
            }
        });
        sheet.setItems(items);
        sheet.show();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<Node> items = new ArrayList<>();
        private String activeId = "";

        void replace(List<Node> next, String active) {
            items.clear();
            items.addAll(next);
            activeId = active;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_node, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Node n = items.get(position);
            h.name.setText(n.name);
            h.host.setText(n.connectorUrlHint() + " · SSH " + n.sshUser + "@" + n.sshPort);
            h.active.setVisibility(n.id.equals(activeId) ? View.VISIBLE : View.GONE);
            int color = !n.onlineKnown ? R.color.status_connecting
                    : (n.online ? R.color.status_online : R.color.status_offline);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(ContextCompat.getColor(NodesActivity.this, color));
            h.dot.setBackground(dot);
            h.itemView.setOnClickListener(v -> showNodeMenu(n));
            h.itemView.setOnLongClickListener(v -> { showNodeMenu(n); return true; });
            h.more.setOnClickListener(v -> showNodeMenu(n));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name, host, active;
            final ImageButton more;
            VH(@NonNull View v) {
                super(v);
                dot = v.findViewById(R.id.node_dot);
                name = v.findViewById(R.id.node_name);
                host = v.findViewById(R.id.node_host);
                active = v.findViewById(R.id.node_active);
                more = v.findViewById(R.id.node_more);
            }
        }
    }
}
