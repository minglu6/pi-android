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
import android.view.InputDevice;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.pi.android.R;
import dev.pi.android.bridge.AgentState;
import dev.pi.android.bridge.BridgeService;
import dev.pi.android.bridge.ModelRpc;
import dev.pi.android.model.ChatMessage;
import dev.pi.android.model.SlashCommand;
import dev.pi.android.util.AppFonts;
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
    private ImageButton attach;
    private View attachmentScroller;
    private LinearLayout attachmentRow;
    private AttachmentController attachmentsCtl;
    private String pendingPromptRpcId = "";
    private TextView statusText;
    private TextView generationStatus;
    private View statusDot;
    private TextView sessionTitle;
    private TextView sessionMeta;
    private TextView modelChip;
    private TextView footerStats;
    private View steerToggle;
    private TextView steerModeSteer;
    private TextView steerModeQueue;
    /** true = steer，false = follow_up / queue */
    private boolean steerMode = true;
    private long lastSessionStatsAt;
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

    private final TranscriptReducer transcript = new TranscriptReducer();
    private final Map<Integer, ChatMessage> pendingRows = new LinkedHashMap<>();
    private final Runnable paintTranscript = this::flushTranscriptPaint;
    private boolean paintScheduled;
    private boolean streaming;
    /** 思考中看门狗：无 agent_settled / agent_end 时不能永久卡在 stop 态。 */
    private static final long THINKING_TIMEOUT_MS = 90_000L;
    private final Runnable thinkingTimeout = this::onThinkingTimeout;
    private final Runnable settleAfterAgentEnd = () -> {
        if (streaming) rpc("get_state", null);
    };
    private boolean historyNeedsSync = true;
    private String historyRequest = "";
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
    private TextView actionsBody;
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
        generationStatus = findViewById(R.id.generation_status);
        sessionTitle = findViewById(R.id.session_title);
        sessionMeta = findViewById(R.id.session_meta);
        modelChip = findViewById(R.id.model_chip);
        footerStats = findViewById(R.id.footer_stats);
        steerToggle = findViewById(R.id.steer_toggle);
        steerModeSteer = findViewById(R.id.steer_mode_steer);
        steerModeQueue = findViewById(R.id.steer_mode_queue);
        if (sessionMeta != null) sessionMeta.setTypeface(AppFonts.mono(this, false));
        if (modelChip != null) modelChip.setTypeface(AppFonts.mono(this, false));
        if (footerStats != null) {
            footerStats.setTypeface(AppFonts.mono(this, false));
            footerStats.setVisibility(View.GONE);
        }
        if (statusText != null) statusText.setTypeface(AppFonts.mono(this, false));
        composer = findViewById(R.id.composer);
        send = findViewById(R.id.send);
        attach = findViewById(R.id.btn_attach);
        attachmentScroller = findViewById(R.id.attachment_scroller);
        attachmentRow = findViewById(R.id.attachment_row);
        attachmentsCtl = AttachmentController.get(this);
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
        actionsBody = findViewById(R.id.actions_body);
        actionsInput = findViewById(R.id.actions_input);
        actionsButtons = findViewById(R.id.actions_buttons);

        applyWindowInsets();
        setupSuggestChips();
        setupSlashCommands();

        list = findViewById(R.id.messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(false);
        list.setLayoutManager(lm);
        // 空闲时轻量 fade；流式更新关闭动画以免闪烁。
        list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
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
                if (recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING) updateStickToBottom();
            }
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) stickToBottom = false;
                if (state == RecyclerView.SCROLL_STATE_IDLE) updateStickToBottom();
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
        View overflow = findViewById(R.id.btn_overflow);
        if (overflow != null) overflow.setOnClickListener(this::showOverflowMenu);
        if (modelChip != null) {
            modelChip.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    return;
                }
                showModelPicker();
            });
        }
        if (steerModeSteer != null) {
            steerModeSteer.setOnClickListener(v -> {
                steerMode = true;
                updateSteerUi();
            });
        }
        if (steerModeQueue != null) {
            steerModeQueue.setOnClickListener(v -> {
                steerMode = false;
                updateSteerUi();
            });
        }
        updateSteerUi();
        // status_chip 仍在布局中但已隐藏；保留空安全引用以免旧逻辑 NPE
        View statusChip = findViewById(R.id.status_chip);
        if (statusChip != null) statusChip.setVisibility(View.GONE);
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
        if (attachmentsCtl != null) attachmentsCtl.setListener(draftListener);

        send.setOnClickListener(v -> onSendOrStop());
        if (attach != null) attach.setOnClickListener(v -> launchAttachmentPicker());
        send.setEnabled(false);
        renderAttachmentDrafts();
        send.setAlpha(0.45f);
        composer.setOnEditorActionListener((v, actionId, event) -> {
            if (!streaming && actionId == EditorInfo.IME_ACTION_SEND && event == null
                    && BaseInputConnection.getComposingSpanStart(composer.getText()) < 0) return submitComposer();
            return false;
        });
        composer.setOnKeyListener((v, keyCode, event) -> {
            InputDevice device = event.getDevice();
            boolean physicalKeyboard = device != null && !device.isVirtual()
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
            if (!streaming && physicalKeyboard && keyCode == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed()
                    && BaseInputConnection.getComposingSpanStart(composer.getText()) < 0) return submitComposer();
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
        refreshSessionChrome();
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

    private boolean submitComposer() {
        if (slashPanelVisible() && slashAdapter != null && !slashAdapter.isEmpty()) {
            SlashCommand first = slashAdapter.first();
            if (first != null) { applySlashCommand(first); return true; }
        }
        onSendOrStop();
        return true;
    }

    private void requestSend(String text) {
        android.util.Log.i(TAG, "requestSend len=" + text.length());
        pendingPrompt = text;
        if (isConnected()) {
            pendingPrompt = null;
            composer.setText("");
            resetTranscript();
            adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
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
        flushTranscriptPaint();
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
        if (attachmentsCtl != null) {
            attachmentsCtl.clearListener(draftListener);
            attachmentsCtl.cancelUpload();
        }
        main.removeCallbacks(thinkingTimeout);
        main.removeCallbacks(settleAfterAgentEnd);
        flushTranscriptPaint();
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
            if (sessionTitle == null) return;
            if (title == null || title.isEmpty()) applySessionTitle();
            else sessionTitle.setText(title);
        });
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    private void setStreaming(boolean on) {
        boolean changed = streaming != on;
        streaming = on;
        if (generationStatus != null) {
            generationStatus.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && (changed || generationStatus.length() == 0)) generationStatus.setText(R.string.generation_active);
        }
        if (steerToggle != null) steerToggle.setVisibility(on ? View.VISIBLE : View.GONE);
        if (list != null) {
            list.setItemAnimator(on ? null : new androidx.recyclerview.widget.DefaultItemAnimator());
        }
        if (!on) flushTranscriptPaint();
        main.removeCallbacks(thinkingTimeout);
        if (on) main.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS);
        refreshModelChip();
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
        boolean hasAttach = attachmentsCtl != null && attachmentsCtl.hasAttachments();
        boolean busyAttach = attachmentsCtl != null
                && (attachmentsCtl.isPreparing() || attachmentsCtl.isUploading());
        String draft = composer.getText().toString().trim();
        boolean hasText = draft.length() > 0 || hasAttach;
        composer.setEnabled(connected || streaming);
        if (streaming) {
            composer.setHint(getString(R.string.hint_message_streaming));
        } else {
            composer.setHint(getString(connected ? R.string.hint_message : R.string.hint_disconnected));
        }
        if (attach != null) {
            attach.setEnabled(connected && !streaming && !busyAttach);
            attach.setAlpha(connected && !streaming && !busyAttach ? 1f : 0.4f);
        }
        // 流式中：有正文 → 发送 steer/follow_up；空正文 → 中止
        if (streaming) {
            if (hasText) {
                send.setImageResource(R.drawable.ic_send);
                send.setContentDescription(getString(R.string.action_send));
            } else {
                send.setImageResource(R.drawable.ic_stop);
                send.setContentDescription(getString(R.string.action_stop));
            }
        } else {
            send.setImageResource(R.drawable.ic_send);
            send.setContentDescription(getString(R.string.action_send));
        }
        boolean canSend = streaming || (connected && !busyAttach && hasText);
        send.setEnabled(canSend);
        send.setAlpha(canSend ? 1f : 0.45f);
    }

    // ---------- 附件 ----------

    private final androidx.activity.result.ActivityResultLauncher<Intent> attachmentPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts
                    .StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || attachmentsCtl == null) return;
                Intent data = result.getData();
                java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (!uris.isEmpty()) attachmentsCtl.addUris(uris, false);
            });

    private void launchAttachmentPicker() {
        if (!isConnected() || streaming) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "*/*"});
        attachmentPicker.launch(Intent.createChooser(intent, getString(R.string.attach_picker_title)));
    }

    private final AttachmentController.Listener draftListener = new AttachmentController.Listener() {
        @Override public void onChanged() {
            renderAttachmentDrafts();
            // 上传进行中把进度放到状态 chip；结束由 ready/error 回调恢复模型状态。
            if (attachmentsCtl != null && attachmentsCtl.isUploading()) {
                setConnectionVisual("online", attachmentsCtl.status());
            }
        }
        @Override public void onError(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    private void renderAttachmentDrafts() {
        if (attachmentRow == null || attachmentsCtl == null) return;
        attachmentRow.removeAllViews();
        java.util.List<AttachmentController.Item> items = attachmentsCtl.items();
        attachmentScroller.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;
        for (AttachmentController.Item item : items) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.bg_suggest_chip);
            int padH = Math.round(10 * d), padV = Math.round(6 * d);
            chip.setPadding(padH, padV, Math.round(4 * d), padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(8 * d));
            chip.setLayoutParams(lp);

            if ("image".equals(item.kind) && !item.thumbnailBase64.isEmpty()) {
                android.widget.ImageView thumb = new android.widget.ImageView(this);
                try {
                    byte[] bytes = android.util.Base64.decode(item.thumbnailBase64,
                            android.util.Base64.DEFAULT);
                    thumb.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.length));
                } catch (Exception ignored) { }
                int s = Math.round(36 * d);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(s, s);
                tp.setMarginEnd(Math.round(6 * d));
                thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                chip.addView(thumb, tp);
            }

            TextView name = new TextView(this);
            name.setText(("image".equals(item.kind) ? "🖼 " : "📎 ") + item.name);
            name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            name.setTextSize(13f);
            name.setMaxWidth(Math.round(160 * d));
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(name);

            ImageButton remove = new ImageButton(this);
            remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            remove.setColorFilter(ContextCompat.getColor(this, R.color.text_muted));
            remove.setBackground(null);
            remove.setContentDescription(getString(R.string.attach_remove_cd) + " " + item.name);
            int rs = Math.round(28 * d);
            chip.addView(remove, new LinearLayout.LayoutParams(rs, rs));
            remove.setOnClickListener(v -> attachmentsCtl.remove(item.id));
            attachmentRow.addView(chip);
        }
        updateSendEnabled();
    }

    private void updateStickToBottom() {
        if (list == null || scrollBottom == null) return;
        int total = adapter == null ? 0 : adapter.size();
        // 末条消息可能比屏幕还高；“看得到末条”不等于“正在读末尾”。
        stickToBottom = total == 0 || !list.canScrollVertically(1);
        scrollBottom.setVisibility(stickToBottom || total == 0 ? View.GONE : View.VISIBLE);
    }

    private void maybeScrollToBottom(boolean force) {
        if (list == null || adapter == null || adapter.size() == 0) return;
        if (force) stickToBottom = true;
        if (!force && !stickToBottom) {
            if (scrollBottom != null) scrollBottom.setVisibility(View.VISIBLE);
            return;
        }
        list.post(() -> {
            if (!stickToBottom || adapter.size() == 0) return;
            int last = adapter.size() - 1;
            LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
            if (lm == null) return;
            View tail = lm.findViewByPosition(last);
            if (tail == null) {
                list.scrollToPosition(last);
                list.post(() -> scrollTailEnd(lm, last));
            } else {
                scrollTailEnd(lm, last);
            }
        });
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
        if (sessionTitle == null) return;
        if (currentSessionName != null && !currentSessionName.isEmpty()) {
            sessionTitle.setText(currentSessionName);
        } else {
            sessionTitle.setText(R.string.app_name);
        }
    }

    private void refreshSessionMeta() {
        if (sessionMeta == null) return;
        if (!modelStatus.isEmpty()) {
            sessionMeta.setText(modelStatus);
        } else if (isConnected()) {
            sessionMeta.setText(R.string.status_online);
        } else {
            sessionMeta.setText(R.string.status_connecting);
        }
    }

    private void refreshModelChip() {
        if (modelChip == null) return;
        AgentState st = AgentState.get();
        String name = !st.modelLabel().isEmpty() ? st.modelLabel()
                : (!modelStatus.isEmpty() ? modelStatus.split(" · ")[0] : getString(R.string.action_model));
        String think = !st.thinking().isEmpty() ? st.thinking() : currentThinking;
        modelChip.setText(think.isEmpty() ? name : name + " " + think);
    }

    private void refreshSessionChrome() {
        applySessionTitle();
        refreshSessionMeta();
        refreshModelChip();
    }

    private void updateSteerUi() {
        if (steerModeSteer == null || steerModeQueue == null) return;
        if (steerMode) {
            steerModeSteer.setBackgroundResource(R.drawable.bg_segmented_on);
            steerModeSteer.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimary));
            steerModeQueue.setBackground(null);
            steerModeQueue.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        } else {
            steerModeQueue.setBackgroundResource(R.drawable.bg_segmented_on);
            steerModeQueue.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onPrimary));
            steerModeSteer.setBackground(null);
            steerModeSteer.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_settings);
        menu.getMenu().add(0, 2, 1, R.string.menu_new_session);
        menu.getMenu().add(0, 3, 2, R.string.menu_reload);
        menu.getMenu().add(0, 4, 3, R.string.menu_fork);
        menu.setOnMenuItemClickListener(this::onOverflowItem);
        menu.show();
    }

    private boolean onOverflowItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case 2:
                if (!isConnected()) {
                    Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    return true;
                }
                startNewSession();
                return true;
            case 3:
                setConnectionVisual("connecting", getString(R.string.status_reconnecting));
                reconnect();
                return true;
            case 4:
                if (!isConnected()) {
                    Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    return true;
                }
                showForkPicker();
                return true;
            default:
                return false;
        }
    }

    private void applySessionStats(@Nullable JSONObject data) {
        if (footerStats == null) return;
        if (data == null) {
            footerStats.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean hasTokens = data.has("contextTokens") && !data.isNull("contextTokens");
        boolean hasWindow = data.has("contextWindow") && !data.isNull("contextWindow");
        if (hasTokens && hasWindow) {
            sb.append(data.opt("contextTokens")).append('/').append(data.opt("contextWindow"));
        } else if (hasTokens) {
            sb.append(data.opt("contextTokens"));
        } else if (hasWindow) {
            sb.append(data.opt("contextWindow"));
        }
        if (data.has("cacheRate") && !data.isNull("cacheRate")) {
            if (sb.length() > 0) sb.append(" · ");
            Object rate = data.opt("cacheRate");
            if (rate instanceof Number) {
                double v = ((Number) rate).doubleValue();
                if (v <= 1.0) sb.append(String.format(java.util.Locale.US, "%.0f%%", v * 100));
                else sb.append(String.format(java.util.Locale.US, "%.0f%%", v));
            } else {
                sb.append(rate);
            }
        }
        if (data.has("cost") && !data.isNull("cost")) {
            if (sb.length() > 0) sb.append(" · ");
            Object cost = data.opt("cost");
            if (cost instanceof Number) {
                sb.append(String.format(java.util.Locale.US, "$%.4f", ((Number) cost).doubleValue()));
            } else {
                sb.append(cost);
            }
        }
        if (sb.length() == 0) {
            footerStats.setVisibility(View.GONE);
        } else {
            footerStats.setText(sb.toString());
            footerStats.setVisibility(View.VISIBLE);
        }
    }

    private void maybeRequestSessionStats() {
        long now = System.currentTimeMillis();
        if (now - lastSessionStatsAt < 12_000L) return;
        lastSessionStatsAt = now;
        rpc("get_session_stats", null);
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
            String text = composer.getText().toString().trim();
            if (!text.isEmpty()) {
                dispatchSteerOrFollowUp(text);
                return;
            }
            rpc("clear_queue", null);
            rpc("abort", null);
            return;
        }
        submit();
    }

    /** 流式中有正文：按 Steer/Queue 开关发 steer 或 follow_up。 */
    private void dispatchSteerOrFollowUp(String text) {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        hideSlashSuggestions();
        composer.setText("");
        adapter.add(new ChatMessage(ChatMessage.Role.USER, text));
        JSONObject params = new JSONObject();
        try {
            params.put("message", text);
        } catch (Exception e) {
            throw new IllegalStateException("steer/follow_up params", e);
        }
        String cmd = steerMode ? "steer" : "follow_up";
        pendingPromptRpcId = rpc(cmd, params);
        pendingComposerText = text;
        stickToBottom = true;
        maybeScrollToBottom(true);
        updateSendEnabled();
    }

    private void submit() {
        String text = composer.getText().toString().trim();
        boolean hasAttach = attachmentsCtl != null && attachmentsCtl.hasAttachments();
        if (text.isEmpty() && !hasAttach) return;
        // 完整匹配已知斜杠命令 → App 动作或 prompt /name
        if (slashStore != null && text.startsWith("/") && !hasAttach) {
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
        if (attachmentsCtl != null && attachmentsCtl.isPreparing()) {
            Toast.makeText(this, R.string.attach_preparing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.isEmpty()) text = getString(R.string.attach_view_prompt);
        final String finalText = text;
        hideSlashSuggestions();
        if (!hasAttach) {
            dispatchPrompt(finalText, null, null);
            return;
        }
        // 附件先整体上传；只有 prompt 被 Pi 接受才清草稿，任何失败原文原样保留。
        setConnectionVisual("online", getString(R.string.attach_uploading, 1,
                attachmentsCtl.items().size()));
        updateSendEnabled();
        attachmentsCtl.upload(service, new AttachmentController.ReadyCallback() {
            @Override
            public void onReady(java.util.List<AttachmentController.Item> submitted, JSONArray attachmentIds) {
                restoreStatusChip();
                dispatchPrompt(finalText, submitted, attachmentIds);
            }

            @Override
            public void onError(String message) {
                restoreStatusChip();
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                updateSendEnabled();
            }
        });
    }

    private void dispatchPrompt(String text,
                                @Nullable java.util.List<AttachmentController.Item> submitted,
                                @Nullable JSONArray attachmentIds) {
        composer.setText("");
        resetTranscript();
        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, text);
        if (submitted != null) {
            for (AttachmentController.Item item : submitted) {
                user.attachments.add(new ChatMessage.AttachmentData(item.name, item.mimeType,
                        item.kind, item.size, "", item.thumbnailBase64));
            }
        }
        adapter.add(user);
        historyRequest = "";
        setStreaming(true);
        JSONObject params = new JSONObject();
        try {
            params.put("message", text);
            if (attachmentIds != null && attachmentIds.length() > 0) {
                params.put("attachmentIds", attachmentIds);
            }
        } catch (Exception e) {
            throw new IllegalStateException("prompt params", e);
        }
        pendingPromptRpcId = rpc("prompt", params);
        pendingSubmitted = submitted;
        pendingComposerText = text;
        setConnectionVisual("online", getString(R.string.status_thinking));
        stickToBottom = true;
        maybeScrollToBottom(true);
    }

    private java.util.List<AttachmentController.Item> pendingSubmitted;
    private String pendingComposerText = "";

    /** prompt 被桥/Pi 拒绝：把正文放回输入框，附件草稿本来就没清，直接可重发。 */
    private void restoreFailedPrompt() {
        if (!pendingComposerText.isEmpty() && composer != null
                && composer.getText().toString().isEmpty()) {
            composer.setText(pendingComposerText);
        }
        pendingComposerText = "";
        pendingSubmitted = null;
        pendingPromptRpcId = "";
        updateSendEnabled();
    }

    private void setConnectionVisual(String state, String label) {
        if ("online".equals(state)) label = AgentState.get().isFresh()
                ? HistoryPresentation.statusLabel(modelStatus, label)
                : getString(R.string.generation_model_sync);
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
                boolean ours = id.equals(pendingPromptRpcId) && !pendingPromptRpcId.isEmpty();
                if (ours) restoreFailedPrompt();
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
            // prompt 已被 Pi 接受：只有此时才清掉附件草稿与本地副本。
            if (id.equals(pendingPromptRpcId) && !pendingPromptRpcId.isEmpty()) {
                if (pendingSubmitted != null && attachmentsCtl != null) {
                    attachmentsCtl.clearAccepted(pendingSubmitted);
                }
                pendingSubmitted = null;
                pendingComposerText = "";
                pendingPromptRpcId = "";
                renderAttachmentDrafts();
            }
            // disposition=handled：扩展立刻吃掉，不会有 agent_settled
            //（已在上方按 pendingPromptRpcId 清理附件草稿）
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
            // 空闲时整页替换（权威）；运行中只把缺失的历史行合并到现有流式行之前，
            // 不重置 transcript——后续 text_delta 仍映射到运行开始时的 assistant 行。
            if (id.equals(historyRequest)) {
                historyRequest = "";
                if (streaming) mergeHistoryWhileStreaming(data);
                else {
                    resetTranscript();
                    renderHistory(data);
                }
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
        if ("get_session_stats".equals(command)) {
            applySessionStats(data);
            return;
        }
        if ("get_state".equals(command) && data != null) {
            applyGetStateModel(data);
            // BridgeService 只交付最新、且未跨模型/会话/运行状态变更的 get_state。
            String path = data.optString("sessionFile", data.optString("sessionPath", ""));
            if (!path.equals(currentSessionPath)) {
                if (adapter != null) adapter.clearToolExpansions();
                historyNeedsSync = true;
            }
            currentSessionPath = path;
            currentSessionName = data.optString("sessionName", "");
            refreshSessionChrome();
            boolean busy = data.optBoolean("isStreaming", false) || data.optBoolean("isCompacting", false);
            if (busy) {
                setStreaming(true);
                // 运行中途（重）连接：错过的 message_start/text_delta 无法重发，而工具事件
                // 仍在到达。等 agent_settled 再整页替换会让正文长时间不可见——先拉一次
                // 完整消息把已完成的正文/工具结果补拍回来，流事件继续在其后追加。
                // transcript 不 reset：正在运行的同一会话，快照消息与事件互不冲突。
                if (historyNeedsSync) {
                    historyNeedsSync = false;
                    historyRequest = rpc("get_messages", null);
                }
            } else {
                if (streaming) settleStreaming(false);
                if (historyNeedsSync) {
                    historyNeedsSync = false;
                    historyRequest = rpc("get_messages", null);
                }
            }
            rpc("get_available_models", null);
            rpc("get_available_thinking_levels", null);
            maybeRequestSessionStats();
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
        refreshSessionChrome();
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
        switch (type) {
            case "agent_start":
                applyTranscriptEvent(ev, true);
                main.removeCallbacks(settleAfterAgentEnd);
                setStreaming(true);
                return;
            case "message_start":
                applyTranscriptEvent(ev, true);
                return;
            case "message_update": {
                JSONObject update = ev.optJSONObject("assistantMessageEvent");
                if (update == null) return;
                if (!streaming) setStreaming(true);
                main.removeCallbacks(thinkingTimeout);
                main.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS);
                String kind = update.optString("type");
                if (kind.startsWith("thinking_")) showGenerationFeedback(getString(R.string.generation_thinking));
                else if (kind.startsWith("text_")) showGenerationFeedback(getString(R.string.generation_output));
                applyTranscriptEvent(ev, false);
                return;
            }
            case "message_end":
                applyTranscriptEvent(ev, true);
                return;
            case "agent_end": {
                applyTranscriptEvent(ev, true);
                // 旧版 Pi 只有 agent_end；新版会再发 agent_settled。willRetry 时先别 idle。
                if (!ev.optBoolean("willRetry", false)) {
                    main.removeCallbacks(settleAfterAgentEnd);
                    main.postDelayed(settleAfterAgentEnd, 1500);
                }
                return;
            }
            case "tool_execution_start":
            case "tool_execution_update":
            case "tool_execution_end":
                if (!streaming) setStreaming(true);
                main.removeCallbacks(thinkingTimeout);
                main.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS);
                showGenerationFeedback("tool_execution_end".equals(type)
                        ? getString(R.string.generation_active)
                        : getString(R.string.generation_tool, ev.optString("toolName", "tool")));
                applyTranscriptEvent(ev, "tool_execution_end".equals(type));
                return;
            case "agent_settled":
                applyTranscriptEvent(ev, true);
                historyNeedsSync = true;
                settleStreaming(true);
                return;
            case "auto_retry_start":
                showGenerationFeedback(getString(R.string.generation_retry));
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
                refreshSessionChrome();
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
        boolean showBody = !wantsText && hint != null && !hint.trim().isEmpty();
        if (actionsBody != null) {
            actionsBody.setVisibility(showBody ? View.VISIBLE : View.GONE);
            if (showBody) actionsBody.setText(hint.trim());
        }
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
        if (actionsBody != null) {
            actionsBody.setVisibility(View.GONE);
            actionsBody.setText("");
        }
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

    /** 运行中回填：完整快照里比当前 UI 多的历史行（正文/工具结果）补拍到现有行之前。 */
    private void mergeHistoryWhileStreaming(JSONObject data) {
        java.util.List<ChatMessage> history = TranscriptReducer.history(data);
        if (history.isEmpty() || adapter == null) return;
        java.util.List<ChatMessage> live = adapter.snapshot();
        if (history.size() <= live.size()) return; // 没有缺失内容，流式区照常
        int missing = history.size() - live.size();
        // 快照末尾可能与流式区重叠（同一条助手消息的完整版 / 同一工具的运行中版）。
        // 从对齐点开始逐对比较：快照行若是当前对应行的同一条（id 相同或文本是其前缀），
        // 说明它属于流式区，不算缺失；遇到真正不同的行为止。
        int liveIndex = 0;
        while (missing > 0 && liveIndex < live.size()) {
            ChatMessage snapRow = history.get(missing);
            ChatMessage liveRow = live.get(liveIndex);
            if (!sameStreamingRow(snapRow, liveRow)) break;
            missing--;
            liveIndex++;
        }
        if (missing <= 0) return;
        for (int i = 0; i < missing; i++) adapter.insertAt(i, history.get(i));
        // reducer 里保存的 assistant/tool 绝对行号整体平移，后续 delta 仍落到正确行。
        transcript.shiftRows(missing);
        refreshEmptyState();
        snapshotChat();
    }

    private static boolean sameStreamingRow(ChatMessage snapshotRow, ChatMessage liveRow) {
        if (snapshotRow.role != liveRow.role) return false;
        if (liveRow.role == ChatMessage.Role.TOOL) {
            return liveRow.tool != null && snapshotRow.tool != null
                    && liveRow.tool.id.equals(snapshotRow.tool.id);
        }
        if (liveRow.role == ChatMessage.Role.USER || liveRow.role == ChatMessage.Role.SYSTEM) {
            return snapshotRow.text.equals(liveRow.text);
        }
        // 流式助手行是快照同条消息的前缀；空 live 文本（纯工具消息）无法安全对齐，不算同一条。
        return !liveRow.text.isEmpty() && snapshotRow.text.startsWith(liveRow.text);
    }

    private void renderHistory(JSONObject data) {
        List<ChatMessage> history = TranscriptReducer.history(data);
        if (history.isEmpty()) {
            // 空会话走 empty state + 建议 chips，不往聊天流塞 SYSTEM 气泡
            adapter.clear();
        } else {
            adapter.replaceAll(history);
        }
        stickToBottom = true;
        maybeScrollToBottom(true);
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
        resetTranscript();
        historyRequest = "";
        historyNeedsSync = true;
        stickToBottom = true;
        rpc("get_state", null);
        refreshSlashCommands();
    }

    private void resetTranscript() {
        flushTranscriptPaint();
        transcript.reset();
    }

    private void applyTranscriptEvent(JSONObject event, boolean immediate) {
        for (TranscriptReducer.Edit edit : transcript.accept(event, adapter.size())) {
            if (edit.append) {
                flushTranscriptPaint();
                // 回填后 rowShift 已含在 reducer 的行号里；追加语义按位置落到正确位置。
                if (edit.row < adapter.size()) adapter.insertAt(edit.row, edit.message);
                else adapter.add(edit.message);
            } else {
                pendingRows.put(edit.row, edit.message);
            }
        }
        if (immediate) flushTranscriptPaint();
        else if (!pendingRows.isEmpty() && !paintScheduled) {
            // 合并高频增量，保证每 50ms 可见一次更新，不等 message_end 才刷新。
            paintScheduled = true;
            main.postDelayed(paintTranscript, 50);
        }
    }

    private void flushTranscriptPaint() {
        main.removeCallbacks(paintTranscript);
        paintScheduled = false;
        if (pendingRows.isEmpty() || adapter == null) return;
        for (Map.Entry<Integer, ChatMessage> edit : pendingRows.entrySet()) adapter.replaceAt(edit.getKey(), edit.getValue());
        pendingRows.clear();
        snapshotChat();
        maybeScrollToBottom(false);
    }

    private void showGenerationFeedback(String text) {
        if (generationStatus != null && streaming && !text.contentEquals(generationStatus.getText())) generationStatus.setText(text);
    }

    private void scrollTailEnd(LinearLayoutManager lm, int position) {
        if (!stickToBottom || list == null || adapter == null || position != adapter.size() - 1) return;
        View tail = lm.findViewByPosition(position);
        if (tail != null) {
            int distance = tail.getBottom() - (list.getHeight() - list.getPaddingBottom());
            if (distance > 0) list.scrollBy(0, distance);
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

    static String textOfMessage(JSONObject msg) {
        String s = TranscriptReducer.textOfMessage(msg);
        if (!s.isEmpty()) return s;
        String plain = msg.optString("text", "");
        if (!plain.isEmpty()) return plain;
        // 鉴权/供应商错误时常只有 stopReason=error + errorMessage，正文为空
        if ("error".equals(msg.optString("stopReason"))) {
            return msg.optString("errorMessage", "");
        }
        return "";
    }

    static String textOfContent(JSONArray content) {
        return TranscriptReducer.textOfContent(content);
    }

}
