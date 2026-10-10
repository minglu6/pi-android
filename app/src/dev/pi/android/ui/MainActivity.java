package dev.pi.android.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.pi.android.R;
import dev.pi.android.bridge.BridgeService;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.util.Json;

/**
 * 聊天主界面。
 *
 * <p>流式显示：{@code message_update} 是 delta-only，按 {@code contentIndex} 累积；
 * {@code text_end} / {@code message_end} 给出权威全文并覆写。
 */
public class MainActivity extends AppCompatActivity implements BridgeService.UiHost {

    private static final String TAG = "PiUI";
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.content.ServiceConnection svcConn = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BridgeService.LocalBinder) binder).get();
            serviceBound = true;
            service.setHost(MainActivity.this);
            service.takePendingAsk();
            lastConfig = SettingsActivity.configSignature(MainActivity.this);
            refreshEmptyState();
            if (pendingPrompt != null) requestSend(pendingPrompt);
            if (isConnected() && pendingUiTarget != null) {
                String target = pendingUiTarget;
                pendingUiTarget = null;
                main.postDelayed(() -> openUiTarget(target), 200);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            service = null;
        }
    };

    private MessageAdapter adapter;
    private RecyclerView list;
    private EditText composer;
    private ImageButton send;
    private TextView statusText;
    private View statusDot;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptyBody;
    private MaterialButton emptyAction;
    private BridgeService service;
    private boolean serviceBound;
    private boolean resumed;

    /** contentIndex -> 该块的累积文本 */
    private final Map<Integer, StringBuilder> blocks = new LinkedHashMap<>();
    /** toolCallId -> 气泡在列表里的位置 */
    private final Map<String, Integer> toolRows = new HashMap<>();
    private boolean streaming;
    private String pendingPrompt;
    private String lastConfig;
    private final java.util.List<CharSequence> modelLabels = new java.util.ArrayList<>();
    private final java.util.List<String[]> modelRefs = new java.util.ArrayList<>();
    private final java.util.List<CharSequence> thinkingLevels = new java.util.ArrayList<>();
    private final java.util.List<CharSequence> sessionLabels = new java.util.ArrayList<>();
    private final java.util.List<String> sessionPaths = new java.util.ArrayList<>();

    private LinearLayout actions;
    private TextView actionsTitle;
    private EditText actionsInput;
    private LinearLayout actionsButtons;
    private final java.util.LinkedHashMap<String, String> extStatuses = new java.util.LinkedHashMap<>();
    private String uiTarget;
    private String pendingUiTarget;
    private String modelStatus = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        composer = findViewById(R.id.composer);
        send = findViewById(R.id.send);
        emptyState = findViewById(R.id.empty_state);
        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);
        emptyAction = findViewById(R.id.empty_action);
        actions = findViewById(R.id.actions);
        actionsTitle = findViewById(R.id.actions_title);
        actionsInput = findViewById(R.id.actions_input);
        actionsButtons = findViewById(R.id.actions_buttons);

        list = findViewById(R.id.messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        adapter = new MessageAdapter(size -> {
            refreshEmptyState();
            if (size > 0) list.smoothScrollToPosition(size - 1);
        });
        list.setAdapter(adapter);

        findViewById(R.id.btn_sessions).setOnClickListener(v -> {
            if (!isConnected()) {
                Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            loadSessions();
        });
        emptyAction.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        Intent svc = new Intent(this, BridgeService.class);
        ContextCompat.startForegroundService(this, svc);
        bindService(svc, svcConn, Context.BIND_AUTO_CREATE);
        askNotificationPermission();

        send.setOnClickListener(v -> onSendOrStop());
        send.setEnabled(false);
        send.setAlpha(0.45f);
        composer.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendOrStop();
                return true;
            }
            return false;
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendEnabled();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        handleIntentExtras(getIntent());
        setConnectionVisual("connecting", getString(R.string.status_connecting));
        refreshEmptyState();
        reconnect();
        if (isConnected() && uiTarget != null) openUiTarget(uiTarget);
    }

    private void handleIntentExtras(@Nullable Intent intent) {
        if (intent == null) return;
        String token = intent.getStringExtra("token");
        if (token != null && !token.isEmpty()) SettingsActivity.setToken(this, token.trim());
        String port = intent.getStringExtra("port");
        if (port != null && !port.isEmpty()) {
            try {
                SettingsActivity.setPort(this, Integer.parseInt(port.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid port extra: " + port, e);
            }
        }
        uiTarget = intent.getStringExtra("ui");
        if (uiTarget != null && uiTarget.trim().isEmpty()) uiTarget = null;
        pendingUiTarget = uiTarget;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.isEmpty()) pendingPrompt = shared;
        }
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.isEmpty()) pendingPrompt = prompt;
    }

    private void openUiTarget(String target) {
        if (target == null) return;
        pendingUiTarget = null;
        switch (target) {
            case "sessions":
                loadSessions();
                break;
            case "settings":
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case "fork":
                showForkPicker();
                break;
            case "models":
                rpc("get_available_models", null);
                rpc("get_available_thinking_levels", null);
                showModelPicker();
                break;
            case "thinking":
                rpc("get_available_thinking_levels", null);
                showThinkingPicker();
                break;
            case "newsession":
                startNewSession();
                break;
            default:
                throw new IllegalArgumentException("unknown ui target: " + target);
        }
    }

    private void requestSend(String text) {
        android.util.Log.i(TAG, "requestSend len=" + text.length());
        pendingPrompt = text;
        if (isConnected()) {
            pendingPrompt = null;
            composer.setText("");
            adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
            blocks.clear();
            setStreaming(true);
            JSONObject params = new JSONObject();
            try {
                params.put("message", text);
            } catch (Exception e) {
                throw new IllegalStateException("prompt params", e);
            }
            rpc("prompt", params);
            setConnectionVisual("online", "thinking…");
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
        if (isConnected() && pendingUiTarget != null) {
            String target = pendingUiTarget;
            pendingUiTarget = null;
            main.postDelayed(() -> openUiTarget(target), 400);
        }
        if (pendingPrompt != null) {
            String text = pendingPrompt;
            pendingPrompt = null;
            requestSend(text);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reconnectIfConfigured();
        if (serviceBound && service != null) service.takePendingAsk();
        refreshEmptyState();
    }

    private void reconnectIfConfigured() {
        String sig = SettingsActivity.configSignature(this);
        if (isConnected() && sig.equals(lastConfig)) return;
        reconnect();
    }

    private void reconnect() {
        lastConfig = SettingsActivity.configSignature(this);
        if (!SettingsActivity.hasToken(this)) {
            setConnectionVisual("offline", getString(R.string.bridge_missing_token));
            refreshEmptyState();
            return;
        }
        setConnectionVisual("connecting", getString(R.string.status_connecting));
        if (service != null) service.reconnect();
    }

    private String rpc(String command, @Nullable JSONObject params) {
        if (service == null) return "";
        return service.rpc(command, params);
    }

    private boolean isConnected() {
        return service != null && service.isConnected();
    }

    @Override
    protected void onStop() {
        super.onStop();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            if (service != null) service.setHost(null);
            unbindService(svcConn);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean isVisible() {
        return resumed;
    }

    @Override
    public boolean showChoice(String id, String kind, String title, String hint,
                              java.util.List<String> options, String prefill, ExtensionUi.AnswerSink sink) {
        showExtensionActions(kind, title, hint, options, prefill, sink);
        return true;
    }

    @Override
    public void onExtStatus(java.util.Map<String, String> statuses) {
        extStatuses.clear();
        extStatuses.putAll(statuses);
        main.post(this::renderExtStatus);
    }

    @Override
    public void onExtTitle(String title) {
        main.post(() -> {
            TextView brand = findViewById(R.id.brand_title);
            if (brand == null) return;
            String name = getString(R.string.app_name);
            brand.setText(title == null || title.isEmpty() ? name : name + " · " + title);
        });
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    private void setStreaming(boolean on) {
        streaming = on;
        if (send == null) return;
        send.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_send);
        send.setContentDescription(getString(on ? R.string.action_stop : R.string.action_send));
        updateSendEnabled();
    }

    private void updateSendEnabled() {
        if (send == null || composer == null) return;
        boolean canSend = streaming || composer.getText().toString().trim().length() > 0;
        send.setEnabled(canSend);
        send.setAlpha(canSend ? 1f : 0.45f);
    }

    private void onSendOrStop() {
        if (streaming) {
            rpc("abort", null);
            setStreaming(false);
            setConnectionVisual("online", getString(R.string.status_idle));
            return;
        }
        submit();
    }

    private void submit() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            refreshEmptyState();
            return;
        }
        composer.setText("");
        adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
        blocks.clear();
        setStreaming(true);
        JSONObject params = new JSONObject();
        try {
            params.put("message", text);
        } catch (Exception e) {
            throw new IllegalStateException("prompt params", e);
        }
        rpc("prompt", params);
        setConnectionVisual("online", "thinking…");
    }

    private void setConnectionVisual(String state, String label) {
        if (statusText != null) statusText.setText(label);
        if (statusDot == null) return;
        int color;
        switch (state) {
            case "online":
                color = R.color.status_online;
                break;
            case "starting":
            case "connecting":
                color = R.color.status_connecting;
                break;
            case "offline":
            default:
                color = R.color.status_offline;
                break;
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ContextCompat.getColor(this, color));
        statusDot.setBackground(dot);
    }

    private void refreshEmptyState() {
        if (emptyState == null || adapter == null) return;
        boolean show = adapter.onlySystemOrEmpty();
        emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        if (!SettingsActivity.hasToken(this)) {
            emptyTitle.setText(R.string.empty_need_token_title);
            emptyBody.setText(R.string.empty_need_token_body);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(R.string.empty_open_settings);
        } else if (!isConnected()) {
            emptyTitle.setText(R.string.status_offline);
            emptyBody.setText(R.string.empty_body);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(R.string.settings);
        } else {
            emptyTitle.setText(R.string.empty_title);
            emptyBody.setText(R.string.empty_body);
            emptyAction.setVisibility(View.GONE);
        }
    }

    // ---------- 菜单 ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 1, 100, R.string.action_new_session);
        menu.add(Menu.NONE, 2, 200, R.string.action_stop);
        menu.add(Menu.NONE, 3, 300, R.string.action_reload);
        menu.add(Menu.NONE, 5, 500, R.string.action_model);
        menu.add(Menu.NONE, 6, 600, R.string.action_thinking);
        menu.add(Menu.NONE, 7, 700, R.string.action_sessions);
        menu.add(Menu.NONE, 8, 800, R.string.action_fork);
        menu.add(Menu.NONE, 4, 400, R.string.settings);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                startNewSession();
                return true;
            case 2:
                rpc("abort", null);
                setStreaming(false);
                return true;
            case 5:
                showModelPicker();
                return true;
            case 6:
                showThinkingPicker();
                return true;
            case 7:
                loadSessions();
                return true;
            case 8:
                showForkPicker();
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
                setConnectionVisual("starting", getString(R.string.status_starting_pi));
                break;
            case "online":
                lastConfig = SettingsActivity.configSignature(this);
                setConnectionVisual("online", getString(R.string.status_online));
                rpc("get_state", null);
                refreshEmptyState();
                if (pendingUiTarget != null) {
                    String target = pendingUiTarget;
                    pendingUiTarget = null;
                    main.postDelayed(() -> openUiTarget(target), 400);
                }
                if (pendingPrompt != null) {
                    String text = pendingPrompt;
                    pendingPrompt = null;
                    requestSend(text);
                }
                break;
            case "offline":
            default:
                setStreaming(false);
                setConnectionVisual("offline", error != null && !error.isEmpty()
                        ? getString(R.string.status_offline_with, error)
                        : getString(R.string.status_offline));
                refreshEmptyState();
                break;
        }
    }

    @Override
    public void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error) {
        if (!ok) {
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, command + " 失败：" + error));
            return;
        }
        if ("list_sessions".equals(command)) {
            renderSessions(data);
            return;
        }
        if ("switch_session".equals(command) && data != null) {
            if (data.optBoolean("cancelled", false)) {
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "切换会话被扩展取消了。"));
            } else {
                rpc("get_messages", null);
            }
            return;
        }
        if ("get_messages".equals(command)) {
            renderHistory(data);
            return;
        }
        if ("get_fork_messages".equals(command) && data != null) {
            JSONArray msgs = data.optJSONArray("messages");
            final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
            final java.util.List<String> ids = new java.util.ArrayList<>();
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String t = m.optString("text", "");
                    t = t.replaceAll("\\s+", " ").trim();
                    if (t.length() > 60) t = t.substring(0, 60) + "…";
                    labels.add(t.isEmpty() ? "(空)" : t);
                    ids.add(m.optString("entryId", ""));
                }
            }
            if (labels.isEmpty()) {
                Toast.makeText(this, "当前会话没有可 fork 的消息", Toast.LENGTH_SHORT).show();
                return;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.action_fork)
                    .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                        JSONObject params = new JSONObject();
                        try {
                            params.put("entryId", ids.get(which));
                        } catch (Exception e) {
                            throw new IllegalStateException("fork params", e);
                        }
                        rpc("fork", params);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if ("fork".equals(command) && data != null) {
            if (data.optBoolean("cancelled", false)) {
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "fork 被扩展取消了。"));
            } else {
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                        "已 fork，从这条消息继续：" + data.optString("text", "")));
                rpc("get_messages", null);
            }
            return;
        }
        if ("new_session".equals(command)) {
            adapter.clear();
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.new_session_started)));
            refreshEmptyState();
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
            modelStatus = (name != null ? name : getString(R.string.status_online))
                    + (thinking != null && !thinking.isEmpty() ? " · " + thinking : "");
            setConnectionVisual("online", modelStatus);
            rpc("get_available_models", null);
            rpc("get_available_thinking_levels", null);
        }
    }

    @Override
    public void onEvent(@NonNull JSONObject ev) {
        String type = ev.optString("type");
        if ("extension_notify".equals(type)) {
            String prefix = notifyPrefix(ev.optString("notifyType", "info"));
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                    prefix + ev.optString("message", "")));
            return;
        }
        // extension_ui_request 由 BridgeService 处理；这里只记一条可见线索
        if ("extension_ui_request".equals(type)) {
            String method = ev.optString("method");
            if ("select".equals(method) || "confirm".equals(method)
                    || "input".equals(method) || "editor".equals(method)) {
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                        "扩展请求 " + method + "：" + ev.optString("title", ev.optString("message", ""))));
            }
            return;
        }
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
                        setConnectionVisual("online", "thinking…");
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
                Integer row = toolRows.remove(ev.optString("toolCallId"));
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
                if (modelStatus.isEmpty()) {
                    setConnectionVisual("online", getString(R.string.status_idle));
                } else {
                    setConnectionVisual("online", modelStatus);
                }
                rpc("get_state", null);
                return;
            case "auto_retry_end":
                if (!ev.optBoolean("success", true)) {
                    adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                            "重试失败：" + ev.optString("finalError")));
                }
                return;
            case "session_info_changed":
                adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                        "会话名：" + ev.optString("name")));
                return;
            default:
                if (type.endsWith("_start") || type.endsWith("_end")) {
                    setConnectionVisual("online", type);
                }
                break;
        }
    }

    @Override
    public void onFailure(@Nullable Throwable t) {
        adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                "连接失败：" + (t == null ? "?" : t.getMessage())
                        + "（Termux 里跑起 pi-rpc-bridge 了吗？）"));
        refreshEmptyState();
    }

    private void loadSessions() {
        JSONObject params = new JSONObject();
        try {
            params.put("limit", 40);
        } catch (Exception e) {
            throw new IllegalStateException("list_sessions params", e);
        }
        rpc("list_sessions", params);
    }

    private void showExtensionActions(String kind, String title, String hint,
                                      java.util.List<String> options, String prefill,
                                      ExtensionUi.AnswerSink sink) {
        actionsButtons.removeAllViews();
        actions.setVisibility(View.VISIBLE);
        actionsTitle.setText(title.isEmpty() ? getString(R.string.app_name) : title);
        boolean wantsText = "input".equals(kind) || "editor".equals(kind);
        actionsInput.setVisibility(wantsText ? View.VISIBLE : View.GONE);
        if (wantsText) {
            actionsInput.setText(prefill == null ? "" : prefill);
            actionsInput.setHint(hint.isEmpty()
                    ? ("editor".equals(kind) ? "编辑内容" : "输入")
                    : hint);
        }
        for (String opt : options) {
            MaterialButton b = new MaterialButton(this);
            b.setText(opt);
            b.setTextSize(14f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(10);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                hideExtensionActions();
                if ("confirm".equals(kind)) sink.confirmed("确定".equals(opt));
                else if ("取消".equals(opt)) sink.cancelled();
                else if (wantsText) sink.value(actionsInput.getText().toString());
                else sink.value(opt);
            });
            actionsButtons.addView(b);
        }
        adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                "扩展提问：" + title + "（" + getString(R.string.ext_answer_hint) + "）"));
    }

    private void hideExtensionActions() {
        actions.setVisibility(View.GONE);
        actionsButtons.removeAllViews();
        actionsInput.setVisibility(View.GONE);
    }

    private static String notifyPrefix(String type) {
        if ("error".equals(type)) return "⚠ ";
        if ("warning".equals(type)) return "△ ";
        return "• ";
    }

    private void renderExtStatus() {
        if (extStatuses.isEmpty()) {
            if (!modelStatus.isEmpty()) setConnectionVisual("online", modelStatus);
            else rpc("get_state", null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String v : extStatuses.values()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(v);
        }
        setConnectionVisual("online", sb.length() > 60 ? sb.substring(0, 60) + "…" : sb.toString());
    }

    private void renderSessions(JSONObject data) {
        JSONArray arr = data != null ? data.optJSONArray("sessions") : null;
        sessionLabels.clear();
        sessionPaths.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String path = item.optString("path", "");
                if (path.isEmpty()) continue;
                String preview = item.optString("preview", "");
                if (preview.isEmpty()) preview = "(无预览)";
                preview = preview.replaceAll("\\s+", " ").trim();
                if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
                long mtime = item.optLong("mtimeMs", 0L);
                sessionLabels.add(relTime(mtime) + " · " + preview);
                sessionPaths.add(path);
            }
        }
        if (sessionLabels.isEmpty()) {
            Toast.makeText(this, "没有读到会话文件（会话目录变了？）", Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_sessions)
                .setItems(sessionLabels.toArray(new CharSequence[0]), (dialog, which) -> {
                    JSONObject params = new JSONObject();
                    try {
                        params.put("sessionPath", sessionPaths.get(which));
                    } catch (Exception e) {
                        throw new IllegalStateException("switch_session params", e);
                    }
                    rpc("switch_session", params);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String relTime(long ms) {
        if (ms <= 0) return "?";
        long diff = System.currentTimeMillis() - ms;
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60L;
        if (h < 24) return h + " 小时前";
        long d = h / 24L;
        if (d < 30) return d + " 天前";
        return android.text.format.DateFormat.format("yyyy-MM-dd", ms).toString();
    }

    private void renderHistory(JSONObject data) {
        JSONArray messages = data != null ? data.optJSONArray("messages") : null;
        java.util.List<ChatMessage> history = new java.util.ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                String text = textOfMessage(m);
                if (text.isEmpty()) continue;
                switch (m.optString("role", "")) {
                    case "user":
                        history.add(new ChatMessage(ChatMessage.Role.USER, text));
                        break;
                    case "assistant":
                        history.add(new ChatMessage(ChatMessage.Role.AGENT, text));
                        break;
                    default:
                        history.add(new ChatMessage(ChatMessage.Role.TOOL, text));
                        break;
                }
            }
        }
        if (history.isEmpty()) {
            adapter.clear();
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.session_empty)));
        } else {
            adapter.replaceAll(history);
        }
        list.scrollToPosition(Math.max(0, adapter.size() - 1));
        refreshEmptyState();
    }

    private void showForkPicker() {
        rpc("get_fork_messages", null);
        Toast.makeText(this, "正在取可 fork 的消息…", Toast.LENGTH_SHORT).show();
    }

    private void startNewSession() {
        rpc("new_session", null);
        adapter.clear();
        adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.new_session_started)));
        refreshEmptyState();
    }

    private void showModelPicker() {
        if (modelLabels.isEmpty()) {
            Toast.makeText(this, "模型列表还没取到", Toast.LENGTH_SHORT).show();
            rpc("get_available_models", null);
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_model)
                .setSingleChoiceItems(modelLabels.toArray(new CharSequence[0]), -1, (dialog, which) -> {
                    String[] ref = modelRefs.get(which);
                    JSONObject params = new JSONObject();
                    try {
                        params.put("provider", ref[0]);
                        params.put("modelId", ref[1]);
                    } catch (Exception e) {
                        throw new IllegalStateException("set_model params", e);
                    }
                    rpc("set_model", params);
                    adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                            "切换到模型 " + ref[0] + " / " + ref[1]));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showThinkingPicker() {
        if (thinkingLevels.isEmpty()) {
            Toast.makeText(this, "思考档位还没取到", Toast.LENGTH_SHORT).show();
            rpc("get_available_thinking_levels", null);
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_thinking)
                .setSingleChoiceItems(thinkingLevels.toArray(new CharSequence[0]), -1, (dialog, which) -> {
                    String level = String.valueOf(thinkingLevels.get(which));
                    JSONObject params = new JSONObject();
                    try {
                        params.put("level", level);
                    } catch (Exception e) {
                        throw new IllegalStateException("set_thinking_level params", e);
                    }
                    rpc("set_thinking_level", params);
                    setConnectionVisual("online", "thinking level: " + level);
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
