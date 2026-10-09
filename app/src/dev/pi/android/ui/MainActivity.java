package dev.pi.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.pi.android.util.Json;

import dev.pi.android.R;
import dev.pi.android.bridge.BridgeConnection;
import dev.pi.android.model.ChatMessage;

/**
 * 聊天主界面。
 *
 * <p>流式显示的做法（照 JSON 事件流的语义，不是拍脑袋）：{@code message_update} 是 delta-only，
 * 按 {@code contentIndex} 分块累积；{@code text_end} / {@code message_end} 给出权威全文，
 * 到达时用权威内容覆写 —— 中间任何丢包都不会让用户看到错字。
 */
public class MainActivity extends AppCompatActivity implements BridgeConnection.Listener {

    private static final String TAG = "PiUI";

    private MessageAdapter adapter;
    private RecyclerView list;
    private EditText composer;
    private ImageButton send;
    private TextView statusText;
    private BridgeConnection bridge;

    /** contentIndex -> 该块的累积文本（ assistant 流式回复可能有多块：文本/思考/工具调用混排） */
    private final Map<Integer, StringBuilder> blocks = new LinkedHashMap<>();
    /** toolCallId -> 气泡在列表里的位置，用于把 tool_execution_update/end 填回同一条 */
    private final Map<String, Integer> toolRows = new HashMap<>();
    private boolean streaming;
    /** 通过 Intent 送进来的待发提示（ACTION_SEND / --es prompt），连上桥后自动发出 */
    private String pendingPrompt;
    /** 上次连桥用的配置签名；设置没变且已连上就不重连 —— 每收到一次分享 intent 重连会掐掉正在流的回复 */
    private String lastConfig;
    private final java.util.List<CharSequence> modelLabels = new java.util.ArrayList<>();
    private final java.util.List<String[]> modelRefs = new java.util.ArrayList<>();   // [provider, id]
    private final java.util.List<CharSequence> thinkingLevels = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        statusText = findViewById(R.id.status_text);
        composer = findViewById(R.id.composer);
        send = findViewById(R.id.send);

        list = findViewById(R.id.messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        adapter = new MessageAdapter(size -> {
            if (size > 0) list.smoothScrollToPosition(size - 1);
        });
        list.setAdapter(adapter);

        bridge = new BridgeConnection();
        bridge.setListener(this);

        send.setOnClickListener(v -> submit());
        composer.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                send.setEnabled(s.toString().trim().length() > 0);
                send.setAlpha(s.toString().trim().length() > 0 ? 1f : 0.4f);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        handleIntentExtras(getIntent());
        adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                "尚未连接：在设置里填 Token（或启动时传 --es token）。\nbuild " + dev.pi.android.BuildInfo.BUILD_TIME));
        reconnect();
        if (pendingPrompt != null) requestSend(pendingPrompt);
    }

    /**
     * 两种入口：
     *  - ACTION_SEND text/plain：其它应用分享文本给 Pi（也是本机自动化驱动它的正规方式）；
     *  - {@code --es token} / {@code --es prompt}：从 Termux / adb 一键投放token和提问。
     */
    private void handleIntentExtras(@Nullable Intent intent) {
        if (intent == null) return;
        String token = intent.getStringExtra("token");
        if (token != null && !token.isEmpty()) SettingsActivity.setToken(this, token.trim());
        String port = intent.getStringExtra("port");
        if (port != null && !port.isEmpty()) {
            try {
                SettingsActivity.setPort(this, Integer.parseInt(port.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.isEmpty()) pendingPrompt = shared;
        }
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.isEmpty()) pendingPrompt = prompt;
    }

    /** 未连上时先排队，onStatus(online) 里再发 —— 桥可能也才刚拉起 Pi。 */
    private void requestSend(String text) {
        android.util.Log.i(TAG, "requestSend len=" + text.length());
        pendingPrompt = text;
        if (bridge.isConnected()) {
            pendingPrompt = null;
            composer.setText("");
            adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
            blocks.clear();
            JSONObject params = new JSONObject();
            try {
                params.put("message", text);
            } catch (Exception ignored) {
            }
            bridge.rpc("prompt", params);
            setStatus("thinking…");
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);      // singleTop：新的分享intent在这里到，onCreate 不会再跑
        handleIntentExtras(intent);
        if (pendingPrompt != null) {
            String text = pendingPrompt;
            pendingPrompt = null;
            requestSend(text);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页可能刚改过 token/端口
        reconnectIfConfigured();
    }

    private void reconnectIfConfigured() {
        String sig = SettingsActivity.host(this) + ":" + SettingsActivity.port(this) + ":" + SettingsActivity.token(this);
        if (bridge.isConnected() && sig.equals(lastConfig)) return;
        reconnect();
    }

    private void reconnect() {
        lastConfig = SettingsActivity.host(this) + ":" + SettingsActivity.port(this) + ":" + SettingsActivity.token(this);
        bridge.configure(SettingsActivity.host(this), SettingsActivity.port(this), SettingsActivity.token(this));
        bridge.connect();
    }

    private void setStreaming(boolean on) {
        streaming = on;
        if (send != null) {
            send.setImageResource(on ? android.R.drawable.ic_delete : android.R.drawable.ic_menu_send);
            send.setContentDescription(getString(on ? R.string.action_stop : R.string.action_send));
        }
    }

    private void submit() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!bridge.isConnected()) {
            Toast.makeText(this, "桥未连接，检查设置里的 Token 与端口", Toast.LENGTH_SHORT).show();
            return;
        }
        composer.setText("");
        adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
        blocks.clear();
        setStreaming(true);
        JSONObject params = new JSONObject();
        try {
            params.put("message", text);
        } catch (Exception ignored) {
        }
        bridge.rpc("prompt", params);
        setStatus("thinking…");
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    // ---------- 菜单 ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 1, 100, R.string.action_new_session);
        menu.add(Menu.NONE, 2, 200, R.string.action_stop).setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        menu.add(Menu.NONE, 3, 300, R.string.action_reload);
        menu.add(Menu.NONE, 5, 500, R.string.action_model);
        menu.add(Menu.NONE, 6, 600, R.string.action_thinking);
        menu.add(Menu.NONE, 4, 400, R.string.settings);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                bridge.rpc("new_session", null);
                adapter.clear();
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "新会话已开始。"));
                return true;
            case 2:
                bridge.rpc("abort", null);
                return true;
            case 5:
                showModelPicker();
                return true;
            case 6:
                showThinkingPicker();
                return true;
            case 3:
                reconnect();
                return true;
            case 4:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ---------- 桥事件 ----------

    @Override
    public void onStatus(String state, @Nullable String error) {
        switch (state) {
            case "starting":
                setStatus(getString(R.string.status_starting_pi));
                break;
            case "online":
                setStatus(getString(R.string.status_online));
                bridge.rpc("get_state", null);
                if (pendingPrompt != null) {
                    String text = pendingPrompt;
                    pendingPrompt = null;
                    requestSend(text);
                }
                break;
            case "offline":
            default:
                setStatus(error != null && !error.isEmpty()
                        ? getString(R.string.status_offline_with, error)
                        : getString(R.string.status_offline));
                break;
        }
    }

    @Override
    public void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error) {
        if (!ok) {
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, command + " 失败：" + error));
            return;
        }
        if ("get_available_models".equals(command) && data != null) {
            JSONArray models = data.optJSONArray("models");
            modelLabels.clear();
            modelRefs.clear();
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null) continue;
                    String prov = m.optString("provider");
                    String modelId = m.optString("id");
                    String label = (prov + " / " + modelId);
                    if (!Json.has(modelLabels, label)) {
                        modelLabels.add(label);
                        modelRefs.add(new String[]{prov, modelId});
                    }
                }
            }
            return;
        }
        if ("get_available_thinking_levels".equals(command) && data != null) {
            JSONArray levels = data.optJSONArray("levels");
            thinkingLevels.clear();
            if (levels != null) {
                for (int i = 0; i < levels.length(); i++) thinkingLevels.add(levels.optString(i));
            }
            return;
        }
        if ("get_state".equals(command) && data != null) {
            JSONObject model = data.optJSONObject("model");
            String name = model != null ? model.optString("name", model.optString("id")) : null;
            String thinking = data.optString("thinkingLevel", null);
            setStatus((name != null ? name : "connected") + (thinking != null ? " · " + thinking : ""));
            bridge.rpc("get_available_models", null);
            bridge.rpc("get_available_thinking_levels", null);
        }
    }

    @Override
    public void onEvent(@NonNull JSONObject ev) {
        String type = ev.optString("type");
        android.util.Log.i(TAG, "event " + type);
        JSONObject agg = ev.optJSONObject("assistantMessageEvent");
        switch (type) {
            case "message_update": {
                if (agg == null) return;
                int idx = agg.optInt("contentIndex", 0);
                switch (agg.optString("type")) {
                    case "text_start":
                        blocks.put(idx, new StringBuilder());
                        break;
                    case "text_delta": {
                        StringBuilder sb = blocks.get(idx);
                        if (sb == null) {
                            sb = new StringBuilder();
                            blocks.put(idx, sb);
                        }
                        sb.append(agg.optString("delta", ""));
                        adapter.upsertTail(new ChatMessage(ChatMessage.Role.AGENT, joinBlocks()));
                        break;
                    }
                    case "text_end":
                        blocks.put(idx, new StringBuilder(agg.optString("content", "")));
                        adapter.upsertTail(new ChatMessage(ChatMessage.Role.AGENT, joinBlocks()));
                        break;
                    case "thinking_start":
                    case "thinking_delta":
                        setStatus("thinking…");
                        break;
                    case "toolcall_start": {
                        String name = agg.optString("toolName", "tool");
                        adapter.add(new ChatMessage(ChatMessage.Role.TOOL, "⟶ " + name + " …"));
                        toolRows.put(agg.optString("id", "c" + idx), adapter.size() - 1);
                        break;
                    }
                    default:
                        break;
                }
                return;
            }
            case "message_end": {
                JSONObject msg = ev.optJSONObject("message");
                if (msg != null && "assistant".equals(msg.optString("role"))) {
                    String text = textOfMessage(msg);
                    if (!text.isEmpty()) {
                        blocks.clear();
                        adapter.upsertTail(new ChatMessage(ChatMessage.Role.AGENT, text));
                    }
                }
                return;
            }
            case "tool_execution_start":
                adapter.add(new ChatMessage(ChatMessage.Role.TOOL,
                        "⟶ " + ev.optString("toolName") + " " + oneLine(ev.optJSONObject("args"))));
                toolRows.put(ev.optString("toolCallId"), adapter.size() - 1);
                return;
            case "tool_execution_end": {
                Integer row = toolRows.remove(ev.optJSONObject("result") != null
                        ? ev.optString("toolCallId") : ev.optString("toolCallId"));
                JSONObject res = ev.optJSONObject("result");
                String out = res != null ? textOfContent(res.optJSONArray("content")) : "";
                if (row != null) {
                    adapter.replaceAt(row, new ChatMessage(ChatMessage.Role.TOOL,
                            (ev.optBoolean("isError") ? "✗ " : "✓ ") + ev.optString("toolName")
                                    + (out.isEmpty() ? "" : "\n" + clip(out))));
                }
                return;
            }
            case "agent_settled":
                setStreaming(false);
                setStatus(getString(R.string.status_idle));
                bridge.rpc("get_state", null);
                return;
            case "auto_retry_end":
                if (!ev.optBoolean("success", true)) {
                    adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "重试失败：" + ev.optString("finalError")));
                }
                return;
            case "session_info_changed":
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "会话名：" + ev.optString("name")));
                return;
            default:
                if (type.endsWith("_start") || type.endsWith("_end")) setStatus(type);
                break;
        }
    }

    @Override
    public void onFailure(@Nullable Throwable t) {
        adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                "连接失败：" + (t == null ? "?" : t.getMessage()) + "（Termux 里跑起 pi-rpc-bridge 了吗？）"));
    }

    private void showModelPicker() {
        if (modelLabels.isEmpty()) {
            Toast.makeText(this, "模型列表还没取到", Toast.LENGTH_SHORT).show();
            return;
        }
        int cur = -1;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_model)
                .setSingleChoiceItems(modelLabels.toArray(new CharSequence[0]), -1, (dialog, which) -> {
                    String[] ref = modelRefs.get(which);
                    JSONObject params = new JSONObject();
                    try {
                        params.put("provider", ref[0]);
                        params.put("modelId", ref[1]);
                    } catch (Exception ignored) {
                    }
                    bridge.rpc("set_model", params);
                    adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "切换到模型 " + ref[0] + " / " + ref[1]));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showThinkingPicker() {
        if (thinkingLevels.isEmpty()) {
            Toast.makeText(this, "思考档位还没取到", Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_thinking)
                .setSingleChoiceItems(thinkingLevels.toArray(new CharSequence[0]), -1, (dialog, which) -> {
                    String level = String.valueOf(thinkingLevels.get(which));
                    JSONObject params = new JSONObject();
                    try {
                        params.put("level", level);
                    } catch (Exception ignored) {
                    }
                    bridge.rpc("set_thinking_level", params);
                    setStatus("thinking level: " + level);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String joinBlocks() {
        StringBuilder out = new StringBuilder();
        for (StringBuilder b : blocks.values()) out.append(b);
        return out.toString();
    }

    private static String clip(String s) {
        return s.length() > 1200 ? s.substring(0, 1200) + "\n…" : s;
    }

    /** 取出消息里的纯文本：content 是 [{type:"text", text:"…"}, …]。 */
    static String textOfMessage(JSONObject msg) {
        JSONArray content = msg.optJSONArray("content");
        String s = textOfContent(content);
        return s.isEmpty() ? msg.optString("text", "") : s;
    }

    static String textOfContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            if ("text".equals(part.optString("type"))) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(part.optString("text"));
            } else if ("toolCall".equals(part.optString("type"))) {
                JSONObject tc = part.optJSONObject("toolCall");
                if (tc != null) sb.append("\n⟶ ").append(tc.optString("name"));
            }
        }
        return sb.toString();
    }

    private static String oneLine(@Nullable JSONObject obj) {
        if (obj == null) return "";
        String s = obj.toString();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
