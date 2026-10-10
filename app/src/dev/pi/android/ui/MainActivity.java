package dev.pi.android.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.pi.android.R;
import dev.pi.android.bridge.AgentState;
import dev.pi.android.bridge.BridgeService;
import dev.pi.android.bridge.ModelRpc;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.model.SlashCommand;
import dev.pi.android.util.SlashCommandStore;

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
            restoreFromAgentState();
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
    private View reconnectBanner;
    private TextView reconnectText;
    private MaterialButton reconnectBtn;
    private View scrollBottom;
    private View topBar;
    private View composerBar;
    private LinearLayout suggestRow;
    private View slashPanel;
    private SlashCommandAdapter slashAdapter;
    private SlashCommandStore slashStore;
    private BridgeService service;
    private boolean serviceBound;
    private boolean resumed;
    /** 用户靠近列表底部时才自动跟滚；上翻阅读时不打断。 */
    private boolean stickToBottom = true;

    /** contentIndex -> 该块的累积文本 */
    private final Map<Integer, StringBuilder> blocks = new LinkedHashMap<>();
    /** toolCallId -> 气泡在列表里的位置 */
    private final Map<String, Integer> toolRows = new HashMap<>();
    private boolean streaming;
    /** 思考中看门狗：无 agent_settled / agent_end 时不能永久卡在 stop 态。 */
    private static final long THINKING_TIMEOUT_MS = 90_000L;
    private final Runnable thinkingTimeout = this::onThinkingTimeout;
    private final Runnable settleAfterAgentEnd = () -> {
        if (streaming) rpc("get_state", null);
    };
    private boolean historyNeedsSync = true;
    private String historyRequest = "";
    private int assistantRow = -1;
    private String pendingPrompt;
    private String lastConfig;
    private String currentSessionPath = "";
    private String currentSessionName = "";
    private final java.util.List<CharSequence> modelLabels = new java.util.ArrayList<>();
    private final java.util.List<String[]> modelRefs = new java.util.ArrayList<>();
    private final java.util.List<CharSequence> thinkingLevels = new java.util.ArrayList<>();
    private SessionsSheet sessionsSheet;

    private LinearLayout actions;
    private TextView actionsTitle;
    private EditText actionsInput;
    private LinearLayout actionsButtons;
    private final java.util.LinkedHashMap<String, String> extStatuses = new java.util.LinkedHashMap<>();
    private String uiTarget;
    private String pendingUiTarget;
    private String modelStatus = "";
    private String currentProvider = "";
    private String currentModelId = "";
    private String currentThinking = "";
    private long thinkingModelRevision = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SettingsActivity.applySavedTheme(this);
        super.onCreate(savedInstanceState);
        AgentState.get().loadCache(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        composer = findViewById(R.id.composer);
        send = findViewById(R.id.send);
        emptyState = findViewById(R.id.empty_state);
        emptyTitle = findViewById(R.id.empty_title);
        emptyBody = findViewById(R.id.empty_body);
        emptyAction = findViewById(R.id.empty_action);
        reconnectBanner = findViewById(R.id.reconnect_banner);
        reconnectText = findViewById(R.id.reconnect_text);
        reconnectBtn = findViewById(R.id.reconnect_btn);
        scrollBottom = findViewById(R.id.scroll_bottom);
        topBar = findViewById(R.id.top_bar);
        composerBar = findViewById(R.id.composer_bar);
        suggestRow = findViewById(R.id.suggest_row);
        actions = findViewById(R.id.actions);
        actionsTitle = findViewById(R.id.actions_title);
        actionsInput = findViewById(R.id.actions_input);
        actionsButtons = findViewById(R.id.actions_buttons);

        applyWindowInsets();
        setupSuggestChips();
        setupSlashCommands();

        list = findViewById(R.id.messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(false);
        list.setLayoutManager(lm);
        adapter = new MessageAdapter(size -> {
            refreshEmptyState();
            maybeScrollToBottom(false);
            snapshotChat();
        });
        list.setAdapter(adapter);
        restoreChatSnapshot();
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateStickToBottom();
            }
        });
        scrollBottom.setOnClickListener(v -> {
            stickToBottom = true;
            scrollBottom.setVisibility(View.GONE);
            maybeScrollToBottom(true);
        });

        findViewById(R.id.btn_sessions).setOnClickListener(v -> {
            if (!isConnected()) {
                Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                showConnectionBanner(getString(R.string.bridge_not_connected), true);
                return;
            }
            loadSessions();
        });
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        View statusChip = findViewById(R.id.status_chip);
        if (statusChip != null) {
            statusChip.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    return;
                }
                showModelPicker();
            });
        }
        emptyAction.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        if (scrollBottom != null) scrollBottom.setVisibility(View.GONE);
        reconnectBtn.setOnClickListener(v -> {
            if (!SettingsActivity.hasToken(this)) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                setConnectionVisual("connecting", getString(R.string.status_reconnecting));
                reconnect();
            }
        });

        Intent svc = new Intent(this, BridgeService.class);
        ContextCompat.startForegroundService(this, svc);
        bindService(svc, svcConn, Context.BIND_AUTO_CREATE);
        askNotificationPermission();

        send.setOnClickListener(v -> onSendOrStop());
        send.setEnabled(false);
        send.setAlpha(0.45f);
        composer.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                if (slashPanelVisible() && slashAdapter != null && !slashAdapter.isEmpty()) {
                    SlashCommand first = slashAdapter.first();
                    if (first != null) {
                        applySlashCommand(first);
                        return true;
                    }
                }
                onSendOrStop();
                return true;
            }
            return false;
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendEnabled();
                updateSlashSuggestions(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        handleIntentExtras(getIntent());
        restoreFromAgentState();
        if (!"online".equals(AgentState.get().status())) {
            setConnectionVisual("connecting", getString(R.string.status_connecting));
        }
        refreshEmptyState();
        // 已连接时不强制 reconnect，避免主题重建重复握手
        reconnectIfConfigured();
        if (isConnected() && uiTarget != null) openUiTarget(uiTarget);
    }

    private void restoreFromAgentState() {
        AgentState st = AgentState.get();
        if (!st.provider().isEmpty()) currentProvider = st.provider();
        if (!st.modelId().isEmpty()) currentModelId = st.modelId();
        if (!st.thinking().isEmpty()) currentThinking = st.thinking();
        if (!st.modelLabel().isEmpty() || !st.thinking().isEmpty()) {
            String name = st.modelLabel().isEmpty() ? getString(R.string.status_online) : st.modelLabel();
            modelStatus = name + (st.thinking().isEmpty() ? "" : " · " + st.thinking());
            if ("online".equals(st.status()) || (service != null && service.isConnected())) {
                setConnectionVisual("online", modelStatus);
            }
        }
        if (!st.chatSessionPath().isEmpty()) currentSessionPath = st.chatSessionPath();
    }

    private void restoreChatSnapshot() {
        List<ChatMessage> snap = AgentState.get().chatSnapshot();
        if (snap.isEmpty() || adapter == null) return;
        adapter.replaceAll(snap);
        maybeScrollToBottom(true);
    }

    private void snapshotChat() {
        if (adapter == null) return;
        AgentState.get().replaceChat(currentSessionPath, adapter.snapshot());
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
            assistantRow = -1;
            historyRequest = "";
            setStreaming(true);
            JSONObject params = new JSONObject();
            try {
                params.put("message", text);
            } catch (Exception e) {
                throw new IllegalStateException("prompt params", e);
            }
            rpc("prompt", params);
            setConnectionVisual("online", getString(R.string.status_thinking));
            stickToBottom = true;
            maybeScrollToBottom(true);
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
        restoreFromAgentState();
        if (serviceBound && service != null) {
            service.setHost(this);
            service.takePendingAsk();
        }
        reconnectIfConfigured();
        if (isConnected()) {
            historyNeedsSync = true;
            rpc("get_state", null);
            refreshSlashCommands();
        }
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
        if ("prompt".equals(command) || "new_session".equals(command)
                || "switch_session".equals(command) || "fork".equals(command)
                || "abort".equals(command)) historyRequest = "";
        return service.rpc(command, params);
    }

    private boolean isConnected() {
        return service != null && service.isConnected();
    }

    @Override
    protected void onPause() {
        snapshotChat();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(thinkingTimeout);
        main.removeCallbacks(settleAfterAgentEnd);
        snapshotChat();
        if (serviceBound) {
            if (service != null) service.clearHostIf(this);
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
        main.removeCallbacks(thinkingTimeout);
        if (on) main.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS);
        if (send == null) return;
        send.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_send);
        send.setContentDescription(getString(on ? R.string.action_stop : R.string.action_send));
        updateSendEnabled();
    }

    private void settleStreaming(boolean refreshState) {
        main.removeCallbacks(settleAfterAgentEnd);
        setStreaming(false);
        restoreStatusChip();
        if (refreshState) rpc("get_state", null);
    }

    private void restoreStatusChip() {
        if (!modelStatus.isEmpty()) {
            setConnectionVisual("online", modelStatus);
        } else {
            setConnectionVisual("online", getString(R.string.status_idle));
        }
    }

    private void failPrompt(@Nullable String detail) {
        settleStreaming(false);
        String msg = (detail == null || detail.trim().isEmpty())
                ? getString(R.string.prompt_failed)
                : detail.trim();
        if (adapter != null) {
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + msg));
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    private void onThinkingTimeout() {
        if (!streaming) return;
        // 长工具调用、重试或扩展提问可能合法地静默超过 90 秒，不擅自 abort。
        rpc("get_state", null);
        main.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS);
    }

    private void updateSendEnabled() {
        if (send == null || composer == null) return;
        boolean connected = isConnected();
        composer.setEnabled(connected || streaming);
        composer.setHint(getString(connected || streaming ? R.string.hint_message : R.string.hint_disconnected));
        boolean canSend = streaming || (connected && composer.getText().toString().trim().length() > 0);
        send.setEnabled(canSend);
        send.setAlpha(canSend ? 1f : 0.45f);
    }

    private void updateStickToBottom() {
        if (list == null || scrollBottom == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        if (lm == null) return;
        int last = lm.findLastVisibleItemPosition();
        int total = adapter == null ? 0 : adapter.size();
        stickToBottom = total == 0 || last >= total - 2;
        scrollBottom.setVisibility(stickToBottom || total == 0 ? View.GONE : View.VISIBLE);
    }

    private void maybeScrollToBottom(boolean force) {
        if (list == null || adapter == null || adapter.size() == 0) return;
        if (!force && !stickToBottom) {
            if (scrollBottom != null) scrollBottom.setVisibility(View.VISIBLE);
            return;
        }
        list.post(() -> list.smoothScrollToPosition(adapter.size() - 1));
        if (scrollBottom != null) scrollBottom.setVisibility(View.GONE);
    }

    private void refreshReconnectBanner() {
        if (reconnectBanner == null) return;
        if (isConnected()) {
            reconnectBanner.setVisibility(View.GONE);
            return;
        }
        boolean needToken = !SettingsActivity.hasToken(this);
        showConnectionBanner(
                getString(needToken ? R.string.reconnect_banner_token : R.string.reconnect_banner),
                needToken);
    }

    private void applySessionTitle() {
        TextView brand = findViewById(R.id.brand_title);
        if (brand == null) return;
        String name = getString(R.string.app_name);
        if (currentSessionName != null && !currentSessionName.isEmpty()) {
            brand.setText(name + " · " + currentSessionName);
        } else {
            brand.setText(name);
        }
    }

    private void setupSlashCommands() {
        slashStore = new SlashCommandStore(this);
        slashPanel = findViewById(R.id.slash_panel);
        RecyclerView slashList = findViewById(R.id.slash_list);
        if (slashList == null) return;
        slashList.setLayoutManager(new LinearLayoutManager(this));
        slashAdapter = new SlashCommandAdapter(this::applySlashCommand);
        slashList.setAdapter(slashAdapter);
        hideSlashSuggestions();
    }

    /** 向连接器拉 get_commands；失败静默，沿用缓存 / App overlay。 */
    private void refreshSlashCommands() {
        if (!isConnected()) {
            if (slashStore != null) slashStore.useOverlayOnly();
            return;
        }
        rpc("get_commands", null);
    }

    private boolean slashPanelVisible() {
        return slashPanel != null && slashPanel.getVisibility() == View.VISIBLE;
    }

    private void updateSlashSuggestions(String text) {
        String t = text == null ? "" : text;
        // 仅当整段输入以 / 开头且尚未含空白（还在敲命令名）时展示
        if (!t.startsWith("/") || t.contains(" ") || t.contains("\n")) {
            hideSlashSuggestions();
            return;
        }
        if (slashStore == null) return;
        List<SlashCommand> matched = slashStore.filter(t);
        if (matched.isEmpty()) {
            hideSlashSuggestions();
            return;
        }
        if (slashAdapter != null) slashAdapter.replaceAll(matched);
        if (slashPanel != null) slashPanel.setVisibility(View.VISIBLE);
    }

    private void hideSlashSuggestions() {
        if (slashPanel != null) slashPanel.setVisibility(View.GONE);
    }

    private void applySlashCommand(SlashCommand cmd) {
        hideSlashSuggestions();
        if (composer != null) composer.setText("");
        if (cmd.isAppAction()) {
            switch (cmd.appAction) {
                case "model":
                    showModelPicker();
                    break;
                case "thinking":
                    showThinkingPicker();
                    break;
                case "new":
                case "clear":
                    startNewSession();
                    break;
                case "sessions":
                    if (!isConnected()) {
                        Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    } else {
                        loadSessions();
                    }
                    break;
                case "settings":
                    startActivity(new Intent(this, SettingsActivity.class));
                    break;
                case "reload":
                    setConnectionVisual("connecting", getString(R.string.status_reconnecting));
                    reconnect();
                    break;
                case "stop":
                    rpc("clear_queue", null);
                    rpc("abort", null);
                    break;
                default:
                    break;
            }
            return;
        }
        // 服务端命令：经 prompt 发送 "/name"（Pi get_commands 约定）
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        requestSend(cmd.name);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            if (slashPanelVisible()) {
                hideSlashSuggestions();
                if (keyCode == KeyEvent.KEYCODE_ESCAPE) return true;
                // BACK：只收起提示，不退出 Activity（再按一次才退）
                if (keyCode == KeyEvent.KEYCODE_BACK) return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void onSendOrStop() {
        if (streaming) {
            rpc("clear_queue", null);
            rpc("abort", null);
            return;
        }
        submit();
    }

    private void submit() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        // 完整匹配已知斜杠命令 → App 动作或 prompt /name
        if (slashStore != null && text.startsWith("/")) {
            for (SlashCommand c : slashStore.current()) {
                if (c.name.equalsIgnoreCase(text)) {
                    applySlashCommand(c);
                    return;
                }
            }
        }
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            refreshEmptyState();
            return;
        }
        composer.setText("");
        hideSlashSuggestions();
        adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
        blocks.clear();
        assistantRow = -1;
        historyRequest = "";
        setStreaming(true);
        JSONObject params = new JSONObject();
        try {
            params.put("message", text);
        } catch (Exception e) {
            throw new IllegalStateException("prompt params", e);
        }
        rpc("prompt", params);
        setConnectionVisual("online", getString(R.string.status_thinking));
        stickToBottom = true;
        maybeScrollToBottom(true);
    }

    private void setConnectionVisual(String state, String label) {
        if ("online".equals(state)) label = HistoryPresentation.statusLabel(modelStatus, label);
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
        updateSendEnabled();
        refreshReconnectBanner();
    }

    private void refreshEmptyState() {
        if (emptyState == null || adapter == null) return;
        boolean show = adapter.onlySystemOrEmpty();
        emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshReconnectBanner();
        updateSendEnabled();
        if (!show) return;

        if (suggestRow != null) {
            suggestRow.setVisibility(View.GONE);
        }
        if (!SettingsActivity.hasToken(this)) {
            emptyTitle.setText(R.string.empty_need_token_title);
            emptyBody.setText(R.string.empty_need_token_body);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(R.string.empty_open_settings);
            emptyAction.setOnClickListener(v ->
                    startActivity(new Intent(this, SettingsActivity.class)));
        } else if (!isConnected()) {
            emptyTitle.setText(R.string.empty_offline_title);
            emptyBody.setText(R.string.empty_offline_body);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setText(R.string.reconnect_action);
            emptyAction.setOnClickListener(v -> {
                setConnectionVisual("connecting", getString(R.string.status_reconnecting));
                reconnect();
            });
        } else {
            emptyTitle.setText(R.string.empty_title);
            emptyBody.setText(R.string.empty_body);
            emptyAction.setVisibility(View.GONE);
            if (suggestRow != null) suggestRow.setVisibility(View.VISIBLE);
        }
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        final float density = getResources().getDisplayMetrics().density;
        final int padH = Math.round(12 * density);
        final int padTop = Math.round(10 * density);
        final int padEnd = Math.round(10 * density);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            int top = Math.max(bars.top, cutout.top);
            int bottom = Math.max(bars.bottom, ime.bottom);
            if (topBar != null) {
                topBar.setPadding(0, top, 0, 0);
            }
            if (composerBar != null) {
                composerBar.setPadding(padH, padTop, padEnd, bottom + padTop);
            }
            if (ime.bottom > bars.bottom && stickToBottom) {
                maybeScrollToBottom(true);
            }
            // 不 CONSUMED：让系统继续分发 IME，避免部分机型键盘顶不起布局
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setupSuggestChips() {
        if (suggestRow == null) return;
        suggestRow.removeAllViews();
        addSuggestChip(R.string.suggest_explain);
        addSuggestChip(R.string.suggest_plan);
        addSuggestChip(R.string.suggest_debug);
    }

    private void addSuggestChip(int labelRes) {
        TextView chip = new TextView(this);
        chip.setText(labelRes);
        chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        chip.setTextSize(14f);
        chip.setBackgroundResource(R.drawable.bg_suggest_chip);
        int padH = (int) (14 * getResources().getDisplayMetrics().density);
        int padV = (int) (10 * getResources().getDisplayMetrics().density);
        chip.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            if (!isConnected()) {
                showConnectionBanner(getString(R.string.bridge_not_connected), true);
                return;
            }
            composer.setText(getString(labelRes));
            composer.setSelection(composer.getText().length());
            onSendOrStop();
        });
        suggestRow.addView(chip);
    }

    /** 连接/可选命令错误走横幅，不污染聊天流。 */
    private void showConnectionBanner(String message, boolean goSettings) {
        if (reconnectBanner == null) return;
        reconnectBanner.setVisibility(View.VISIBLE);
        if (reconnectText != null) reconnectText.setText(message);
        if (reconnectBtn != null) {
            reconnectBtn.setText(goSettings ? R.string.go_settings : R.string.reconnect_action);
        }
    }

    private static boolean isOptionalRpc(String command) {
        return "get_session_name".equals(command)
                || "get_session_stats".equals(command)
                || "export_html".equals(command)
                || "get_commands".equals(command);
    }

    private static String friendlyConnectionError(@Nullable Throwable t) {
        if (t == null) return null;
        String msg = t.getMessage() == null ? "" : t.getMessage();
        String lower = msg.toLowerCase(java.util.Locale.US);
        if (lower.contains("pong") || lower.contains("ping") || lower.contains("timeout")
                || lower.contains("timed out")) {
            return null; // caller uses reconnect_banner_timeout
        }
        if (lower.contains("failed to connect") || lower.contains("connection refused")
                || lower.contains("econnrefused")) {
            return null;
        }
        return null; // never leak raw English into UI
    }

    // ---------- 连接器事件 ----------

    @Override
    public void onStatus(String state, @Nullable String error) {
        switch (state) {
            case "starting":
                setConnectionVisual("starting", getString(R.string.status_starting_pi));
                break;
            case "online":
                lastConfig = SettingsActivity.configSignature(this);
                setConnectionVisual("online", getString(R.string.status_online));
                historyNeedsSync = true;
                rpc("get_state", null);
                refreshSlashCommands();
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
                historyRequest = "";
                historyNeedsSync = true;
                setStreaming(false);
                setConnectionVisual("offline", getString(R.string.status_offline));
                showConnectionBanner(
                        SettingsActivity.hasToken(this)
                                ? getString(R.string.reconnect_banner)
                                : getString(R.string.reconnect_banner_token),
                        !SettingsActivity.hasToken(this));
                if (error != null && !error.isEmpty()) {
                    android.util.Log.w(TAG, "offline: " + error);
                }
                refreshEmptyState();
                break;
        }
    }

    @Override
    public void onResponse(String id, boolean ok, String command, @Nullable JSONObject data, @Nullable String error) {
        if (!ok) {
            android.util.Log.w(TAG, "rpc fail " + command + ": " + error);
            if (isOptionalRpc(command)) return;
            if ("list_sessions".equals(command)) {
                ensureSessionsSheet().showError(error);
                return;
            }
            if ("prompt".equals(command) || "steer".equals(command) || "follow_up".equals(command)) {
                // 静默 Toast 会把 UI 留在「思考中」+ stop；必须回 idle 并露出错误
                failPrompt(error != null && !error.isEmpty() ? error : getString(R.string.prompt_failed));
                return;
            }
            if ("set_model".equals(command) || "set_thinking_level".equals(command)
                    || "get_state".equals(command) || "get_available_models".equals(command)
                    || "get_available_thinking_levels".equals(command)) {
                String detail = error != null && !error.isEmpty() ? error : getString(R.string.rpc_failed);
                // 配置失败不是 prompt 失败：不能改变正在运行的任务/Stop 状态。
                if (resumed) Toast.makeText(this, "set_model".equals(command)
                        ? getString(R.string.set_model_failed, detail) : detail, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, R.string.rpc_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if ("prompt".equals(command)) {
            // disposition=handled：扩展立刻吃掉，不会有 agent_settled
            String disp = data != null ? data.optString("disposition", "started") : "started";
            if ("handled".equals(disp)) settleStreaming(false);
            return;
        }
        if ("set_model".equals(command)) {
            applySetModelSuccess(data);
            return;
        }
        if ("set_thinking_level".equals(command)) {
            // 服务会 get_state；不能把请求值当成 Pi 的实际档位。
            applyGetStateModel(null);
            return;
        }
        if ("list_sessions".equals(command)) {
            if (data != null && data.has("error") && !data.isNull("error")
                    && !data.optString("error").isEmpty()) {
                ensureSessionsSheet().showError(data.optString("error"));
            } else {
                ensureSessionsSheet().showData(data);
            }
            return;
        }
        if ("delete_session".equals(command)) {
            Toast.makeText(this, R.string.session_deleted, Toast.LENGTH_SHORT).show();
            if (data != null && data.optString("path").equals(currentSessionPath)) {
                startNewSession();
                if (sessionsSheet != null) sessionsSheet.dismiss();
            } else {
                loadSessions();
            }
            return;
        }
        if ("set_session_name".equals(command)) {
            rpc("get_state", null);
            return;
        }
        if ("get_session_name".equals(command) && data != null) {
            // 会话名以 get_state 为准，避免本地兼容命令的旧名覆盖它。
            rpc("get_state", null);
            return;
        }
        if ("get_commands".equals(command)) {
            if (slashStore != null) slashStore.applyServerResponse(data);
            // 若正在输入 /，刷新面板
            if (composer != null) updateSlashSuggestions(composer.getText().toString());
            return;
        }
        if ("switch_session".equals(command) && data != null) {
            if (data.optBoolean("cancelled", false)) {
                Toast.makeText(this, R.string.rpc_failed, Toast.LENGTH_SHORT).show();
            } else {
                syncSessionAfterChange();
            }
            return;
        }
        if ("get_messages".equals(command)) {
            // 服务向所有 Activity 扇出响应，只接收本页最新的历史请求。
            if (id.equals(historyRequest) && !streaming) {
                historyRequest = "";
                blocks.clear();
                assistantRow = -1;
                toolRows.clear();
                renderHistory(data);
            }
            return;
        }
        if ("abort".equals(command)) {
            settleStreaming(true);
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
                Toast.makeText(this, R.string.fork_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Pickers.showFork(this, labels, ids, entryId -> {
                JSONObject params = new JSONObject();
                try {
                    params.put("entryId", entryId);
                } catch (Exception e) {
                    throw new IllegalStateException("fork params", e);
                }
                rpc("fork", params);
            });
            return;
        }
        if ("fork".equals(command) && data != null) {
            if (data.optBoolean("cancelled", false)) {
                Toast.makeText(this, R.string.rpc_failed, Toast.LENGTH_SHORT).show();
            } else {
                syncSessionAfterChange();
                if (composer != null) composer.setText(data.optString("text", ""));
            }
            return;
        }
        if ("new_session".equals(command)) {
            if (data != null && data.optBoolean("cancelled", false)) {
                Toast.makeText(this, R.string.rpc_failed, Toast.LENGTH_SHORT).show();
            } else {
                syncSessionAfterChange();
            }
            return;
        }
        if ("get_available_models".equals(command) && data != null) {
            ModelRpc.Catalog cat = ModelRpc.parseAvailableModels(data);
            modelLabels.clear();
            modelRefs.clear();
            modelLabels.addAll(cat.labels);
            modelRefs.addAll(cat.refs);
            return;
        }
        if ("get_available_thinking_levels".equals(command) && data != null) {
            thinkingModelRevision = AgentState.get().modelRevision();
            JSONArray levels = data.optJSONArray("levels");
            thinkingLevels.clear();
            if (levels != null) {
                for (int i = 0; i < levels.length(); i++) thinkingLevels.add(levels.optString(i));
            }
            return;
        }
        if ("get_state".equals(command) && data != null) {
            applyGetStateModel(data);
            // BridgeService 只交付最新、且未跨模型/会话/运行状态变更的 get_state。
            String path = data.optString("sessionFile", data.optString("sessionPath", ""));
            if (!path.equals(currentSessionPath)) historyNeedsSync = true;
            currentSessionPath = path;
            currentSessionName = data.optString("sessionName", "");
            applySessionTitle();
            boolean busy = data.optBoolean("isStreaming", false) || data.optBoolean("isCompacting", false);
            if (busy) {
                setStreaming(true);
            } else {
                if (streaming) settleStreaming(false);
                if (historyNeedsSync) {
                    historyNeedsSync = false;
                    historyRequest = rpc("get_messages", null);
                }
            }
            rpc("get_available_models", null);
            rpc("get_available_thinking_levels", null);
        }
    }

    /** set_model 成功：服务已经更新身份并负责 get_state，本页只展示确认值。 */
    private void applySetModelSuccess(@Nullable JSONObject data) {
        applyGetStateModel(null);
        ModelRpc.Applied applied = ModelRpc.fromModelObject(data);
        if (resumed && applied != null) Toast.makeText(this, applied.label, Toast.LENGTH_SHORT).show();
    }

    private void applyGetStateModel(@Nullable JSONObject data) {
        AgentState st = AgentState.get();
        currentProvider = st.provider();
        currentModelId = st.modelId();
        currentThinking = st.thinking();
        if (thinkingModelRevision != st.modelRevision()) {
            thinkingLevels.clear();
            thinkingModelRevision = st.modelRevision();
        }
        String name = !st.modelLabel().isEmpty() ? st.modelLabel() : getString(R.string.status_online);
        modelStatus = name + (currentThinking.isEmpty() ? "" : " · " + currentThinking);
        if (!streaming) setConnectionVisual("online", modelStatus);
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
        if ("extension_ui_request".equals(type)) {
            // BridgeService 负责作答；不必再往聊天流塞调试 INFO
            return;
        }
        if ("thinking_level_changed".equals(type)) {
            applyGetStateModel(null);
            return;
        }
        android.util.Log.i(TAG, "event " + type);
        JSONObject agg = ev.optJSONObject("assistantMessageEvent");
        switch (type) {
            case "agent_start":
                main.removeCallbacks(settleAfterAgentEnd);
                setStreaming(true);
                return;
            case "message_start": {
                JSONObject msg = ev.optJSONObject("message");
                if (msg != null && "assistant".equals(msg.optString("role"))) {
                    blocks.clear();
                    assistantRow = -1;
                }
                return;
            }
            case "message_update": {
                if (agg == null) return;
                // 有流式增量就续命看门狗，避免长回复被误判超时
                if (streaming) {
                    main.removeCallbacks(thinkingTimeout);
                    main.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS);
                }
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
                        updateAssistant(new ChatMessage(ChatMessage.Role.AGENT, joinBlocks(), true));
                        break;
                    }
                    case "text_end":
                        blocks.put(idx, new StringBuilder(agg.optString("content", "")));
                        updateAssistant(new ChatMessage(ChatMessage.Role.AGENT, joinBlocks(), true));
                        break;
                    case "thinking_start":
                    case "thinking_delta":
                        setConnectionVisual("online", getString(R.string.status_thinking));
                        break;
                    case "toolcall_start":
                        // 卡片由 tool_execution_start 创建，避免同一工具重复两个气泡。
                        break;
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
                        if ("error".equals(msg.optString("stopReason"))) {
                            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + text));
                        } else {
                            updateAssistant(new ChatMessage(ChatMessage.Role.AGENT, text));
                        }
                    }
                }
                assistantRow = -1;
                return;
            }
            case "agent_end": {
                // 旧版 Pi 只有 agent_end；新版会再发 agent_settled。willRetry 时先别 idle。
                showErrorsFromAgentEnd(ev);
                if (!ev.optBoolean("willRetry", false)) {
                    main.removeCallbacks(settleAfterAgentEnd);
                    main.postDelayed(settleAfterAgentEnd, 1500);
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
                historyNeedsSync = true;
                settleStreaming(true);
                return;
            case "auto_retry_end":
                if (!ev.optBoolean("success", true)) {
                    String finalErr = ev.optString("finalError", "");
                    android.util.Log.w(TAG, "auto_retry failed: " + finalErr);
                    failPrompt(finalErr.isEmpty() ? getString(R.string.prompt_failed) : finalErr);
                }
                return;
            case "session_info_changed":
                currentSessionName = ev.optString("name", currentSessionName);
                applySessionTitle();
                return;
            default:
                break;
        }
    }

    @Override
    public void onFailure(@Nullable Throwable t) {
        android.util.Log.w(TAG, "connection failure", t);
        setStreaming(false);
        setConnectionVisual("offline", getString(R.string.status_offline));
        String msg = getString(R.string.reconnect_banner_timeout);
        friendlyConnectionError(t); // maps known patterns; raw never shown
        showConnectionBanner(msg, false);
        refreshEmptyState();
    }

    private void loadSessions() {
        ensureSessionsSheet().showLoading();
        JSONObject params = new JSONObject();
        try {
            params.put("limit", 80);
        } catch (Exception e) {
            throw new IllegalStateException("list_sessions params", e);
        }
        rpc("list_sessions", params);
    }

    private SessionsSheet ensureSessionsSheet() {
        if (sessionsSheet == null) {
            sessionsSheet = new SessionsSheet(this, new SessionsSheet.Host() {
                @Override public String currentSessionPath() { return currentSessionPath; }
                @Override public void openSession(String path) { switchToSession(path); }
                @Override public void newSession() { startNewSession(); }
                @Override public void renameSession(String path, @Nullable String knownName) {
                    promptRenameSession(knownName);
                }
                @Override public void deleteSession(String path) { confirmDeleteSession(path); }
                @Override public boolean supportsRename() { return true; }
                @Override public boolean supportsDelete() { return true; }
            });
        }
        return sessionsSheet;
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
                else if (!"select".equals(kind) && "取消".equals(opt)) sink.cancelled();
                else if (wantsText) sink.value(actionsInput.getText().toString());
                else sink.value(opt);
            });
            actionsButtons.addView(b);
        }
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

    private void switchToSession(String path) {
        JSONObject params = new JSONObject();
        try {
            params.put("sessionPath", path);
        } catch (Exception e) {
            throw new IllegalStateException("switch_session params", e);
        }
        rpc("switch_session", params);
    }

    private void promptRenameSession(String prefill) {
        MaterialDialogs.promptText(this, R.string.session_rename_title, R.string.session_rename_hint,
                prefill, name -> {
                    JSONObject params = new JSONObject();
                    try {
                        params.put("name", name);
                    } catch (Exception e) {
                        throw new IllegalStateException("set_session_name params", e);
                    }
                    rpc("set_session_name", params);
                    // 成功响应后拉权威名字，失败不污染当前标题。
                });
    }

    private void confirmDeleteSession(String path) {
        MaterialDialogs.confirm(this, R.string.session_delete, R.string.session_delete_confirm,
                R.string.session_delete, () -> {
                    JSONObject params = new JSONObject();
                    try {
                        params.put("sessionPath", path);
                    } catch (Exception e) {
                        throw new IllegalStateException("delete_session params", e);
                    }
                    rpc("delete_session", params);
                });
    }

    private void renderHistory(JSONObject data) {
        JSONArray messages = data != null ? data.optJSONArray("messages") : null;
        java.util.List<ChatMessage> history = new java.util.ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null || HistoryPresentation.hidden(m)) continue;
                String text = HistoryPresentation.displayText(m, textOfMessage(m));
                if (text.isEmpty()) continue;
                long timestamp = m.optLong("timestamp", 0);
                switch (m.optString("role", "")) {
                    case "user":
                        history.add(new ChatMessage(ChatMessage.Role.USER, text, timestamp));
                        break;
                    case "assistant":
                        history.add(new ChatMessage(ChatMessage.Role.AGENT, text, timestamp));
                        break;
                    case "toolResult":
                        history.add(new ChatMessage(ChatMessage.Role.TOOL, text, timestamp));
                        break;
                    default:
                        history.add(new ChatMessage(ChatMessage.Role.SYSTEM, text, timestamp));
                        break;
                }
            }
        }
        if (history.isEmpty()) {
            // 空会话走 empty state + 建议 chips，不往聊天流塞 SYSTEM 气泡
            adapter.clear();
        } else {
            adapter.replaceAll(history);
        }
        stickToBottom = true;
        list.scrollToPosition(Math.max(0, adapter.size() - 1));
        if (scrollBottom != null) scrollBottom.setVisibility(View.GONE);
        refreshEmptyState();
    }

    private void showForkPicker() {
        rpc("get_fork_messages", null);
        Toast.makeText(this, R.string.fork_loading, Toast.LENGTH_SHORT).show();
    }

    private void startNewSession() {
        rpc("new_session", null);
    }

    private void syncSessionAfterChange() {
        blocks.clear();
        toolRows.clear();
        assistantRow = -1;
        historyRequest = "";
        historyNeedsSync = true;
        stickToBottom = true;
        rpc("get_state", null);
        refreshSlashCommands();
    }

    private void updateAssistant(ChatMessage message) {
        if (assistantRow >= 0 && assistantRow < adapter.size()) {
            adapter.replaceAt(assistantRow, message);
            snapshotChat();
            maybeScrollToBottom(false);
        } else {
            assistantRow = adapter.size();
            adapter.add(message);
        }
    }

    private void showModelPicker() {
        if (!requireFreshModelState()) return;
        if (modelLabels.isEmpty()) {
            Toast.makeText(this, R.string.models_not_ready, Toast.LENGTH_SHORT).show();
            rpc("get_available_models", null);
            return;
        }
        Pickers.showModel(this, modelLabels, modelRefs, AgentState.get().provider(), AgentState.get().modelId(),
                (provider, modelId) -> {
                    if (!requireFreshModelState()) return;
                    JSONObject params = ModelRpc.setModelParams(provider, modelId);
                    if (params == null) {
                        Toast.makeText(this, R.string.model_ref_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.util.Log.i(TAG, "set_model " + params.optString("provider")
                            + "/" + params.optString("modelId"));
                    // 服务按确认响应刷新；失败只显示配置错误，不干扰 prompt
                    rpc("set_model", params);
                });
    }

    private void showThinkingPicker() {
        if (!requireFreshModelState()) return;
        if (thinkingModelRevision != AgentState.get().modelRevision()) thinkingLevels.clear();
        if (thinkingLevels.isEmpty()) {
            Toast.makeText(this, R.string.thinking_not_ready, Toast.LENGTH_SHORT).show();
            rpc("get_available_thinking_levels", null);
            return;
        }
        long openedRevision = AgentState.get().modelRevision();
        Pickers.showThinking(this, thinkingLevels, AgentState.get().thinking(), level -> {
            if (!requireFreshModelState()) return;
            if (openedRevision != AgentState.get().modelRevision()) {
                rpc("get_available_thinking_levels", null);
                Toast.makeText(this, R.string.thinking_not_ready, Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject params = new JSONObject();
            try {
                params.put("level", level);
            } catch (Exception e) {
                throw new IllegalStateException("set_thinking_level params", e);
            }
            rpc("set_thinking_level", params);
        });
    }

    private boolean requireFreshModelState() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (AgentState.get().isFresh()) return true;
        rpc("get_state", null);
        Toast.makeText(this, "正在同步模型与思考状态…", Toast.LENGTH_SHORT).show();
        return false;
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
        Object rawContent = msg.opt("content");
        if (rawContent instanceof String) return (String) rawContent;
        JSONArray content = msg.optJSONArray("content");
        String s = textOfContent(content);
        if (!s.isEmpty()) return s;
        String plain = msg.optString("text", "");
        if (!plain.isEmpty()) return plain;
        // 鉴权/供应商错误时常只有 stopReason=error + errorMessage，正文为空
        if ("error".equals(msg.optString("stopReason"))) {
            return msg.optString("errorMessage", "");
        }
        return "";
    }

    private void showErrorsFromAgentEnd(JSONObject ev) {
        JSONArray msgs = ev.optJSONArray("messages");
        if (msgs == null || adapter == null) return;
        for (int i = msgs.length() - 1; i >= 0; i--) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null || !"assistant".equals(m.optString("role"))) continue;
            if (!"error".equals(m.optString("stopReason"))) continue;
            String err = textOfMessage(m);
            if (err.isEmpty()) err = getString(R.string.prompt_failed);
            adapter.add(new ChatMessage(ChatMessage.Role.SYSTEM, "⚠ " + err));
            return;
        }
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
                sb.append("\n⟶ ").append(part.optString("name", "tool"));
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
