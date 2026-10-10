package dev.pi.android.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
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
    /** Settings「检测更新」→ bring chat to front and run the same flow. */
    public static final String EXTRA_CHECK_UPDATE = "check_update";
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
    private ImageView brandLogo;
    private TextView sessionTitle;
    private TextView sessionMeta;
    private TextView modelChip;
    private TextView cwdChip;
    /** Bridge supports get_cwd/set_cwd/list_cwd_entries (or get_state injects cwd). */
    private boolean cwdSupported;
    private String pendingCwdListRpc = "";
    private String pendingCwdSetRpc = "";
    private String cwdBrowsePath = "";
    private String pendingMcpListRpc = "";
    private String pendingSkillsTrustRpc = "";
    private String pendingPiVersionRpc = "";
    private String pendingPiUpdateRpc = "";
    @Nullable private DetailSheet updateProgressSheet;
    private boolean mcpSupported = true;
    private boolean skillsSupported = true;
    @Nullable private Boolean projectTrusted;
    private TextView footerStats;
    private TextView footerSep;
    private View contextBarWrap;
    private View contextBarFill;
    private TextView diffBadge;
    private ImageButton btnStop;
    private View steerToggle;
    private TextView steerModePlan;
    private TextView steerModeSteer;
    private TextView steerModeQueue;
    /** 0=plan, 1=steer, 2=queue；流式发送时 plan/queue → follow_up，steer → steer */
    private int composerMode = 1;
    private long lastSessionStatsAt;
    /** Optional workspace fields from get_state — empty means hide. */
    private String stateCwd = "";
    private String stateBranch = "";
    private String stateDiffBadge = "";
    /** Pocket ports: pending RPC ids / shell state. */
    private String pendingFilesRpc = "";
    private String pendingChangesRpc = "";
    private String pendingBranchesRpc = "";
    private String pendingDiffRpc = "";
    private String pendingDiffPath = "";
    private String pendingMentionRpc = "";
    private String pendingBashRpc = "";
    private String pendingBashCommand = "";
    private boolean pendingBashExclude;
    private boolean bashRunning;
    private String pendingReadRpc = "";
    private String pendingReadPath = "";
    private String pendingCheckoutRpc = "";
    private boolean pendingReadAsAttach;
    private String lastMentionQuery = "";
    private long lastMentionAt;
    private int pasteGuard;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptyBody;
    private MaterialButton emptyAction;
    private View reconnectBanner;
    private TextView reconnectText;
    private TextView reconnectBtn;
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
        brandLogo = findViewById(R.id.brand_logo);
        sessionTitle = findViewById(R.id.session_title);
        sessionMeta = findViewById(R.id.session_meta);
        modelChip = findViewById(R.id.model_chip);
        cwdChip = findViewById(R.id.cwd_chip);
        footerStats = findViewById(R.id.footer_stats);
        footerSep = findViewById(R.id.footer_sep);
        contextBarWrap = findViewById(R.id.context_bar_wrap);
        contextBarFill = findViewById(R.id.context_bar_fill);
        diffBadge = findViewById(R.id.diff_badge);
        btnStop = findViewById(R.id.btn_stop);
        steerToggle = findViewById(R.id.steer_toggle);
        steerModePlan = findViewById(R.id.steer_mode_plan);
        steerModeSteer = findViewById(R.id.steer_mode_steer);
        steerModeQueue = findViewById(R.id.steer_mode_queue);
        if (sessionMeta != null) sessionMeta.setTypeface(AppFonts.mono(this, false));
        if (modelChip != null) modelChip.setTypeface(AppFonts.mono(this, false));
        if (cwdChip != null) {
            cwdChip.setTypeface(AppFonts.mono(this, false));
            cwdChip.setVisibility(View.GONE);
        }
        if (footerStats != null) {
            footerStats.setTypeface(AppFonts.mono(this, false));
            footerStats.setVisibility(View.GONE);
        }
        if (diffBadge != null) {
            diffBadge.setTypeface(AppFonts.mono(this, false));
            diffBadge.setVisibility(View.GONE);
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
        // Insert/remove fade only — change cross-fade ghosts tool rows (bash 完成重影).
        list.setItemAnimator(chatItemAnimator());
        adapter = new MessageAdapter(size -> {
            refreshEmptyState();
            maybeScrollToBottom(false);
            snapshotChat();
        });
        adapter.setAgentActionListener(new MessageAdapter.AgentActionListener() {
            @Override public void onRetry(@NonNull ChatMessage message) {
                retryWithPrompt(findPrecedingUserText(message), false);
            }
            @Override public void onRetryOtherModel(@NonNull ChatMessage message) {
                retryWithPrompt(findPrecedingUserText(message), true);
            }
            @Override public void onFork(@NonNull ChatMessage message) {
                forkFromMessage(message);
            }
            @Override public void onEditResend(@NonNull ChatMessage userMessage) {
                editResendUser(userMessage);
            }
            @Override public void onNoticeAction(@NonNull String command) {
                runNoticeCommand(command);
            }
            @Override public void onShellStop(@NonNull String toolId) {
                abortShell(toolId);
            }
            @Override public void onImageFullscreen(@NonNull ChatMessage.AttachmentData attachment) {
                showImageFullscreen(attachment);
            }
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
        View newSessionBtn = findViewById(R.id.btn_new_session);
        if (newSessionBtn != null) {
            newSessionBtn.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    showConnectionBanner(getString(R.string.bridge_not_connected), true);
                    return;
                }
                startNewSession();
            });
        }
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
        if (cwdChip != null) {
            cwdChip.setOnClickListener(v -> openCwdSheet(null));
        }
        if (steerModePlan != null) {
            steerModePlan.setOnClickListener(v -> {
                composerMode = 0;
                updateSteerUi();
            });
        }
        if (steerModeSteer != null) {
            steerModeSteer.setOnClickListener(v -> {
                composerMode = 1;
                updateSteerUi();
            });
        }
        if (steerModeQueue != null) {
            steerModeQueue.setOnClickListener(v -> {
                composerMode = 2;
                updateSteerUi();
            });
        }
        if (btnStop != null) {
            btnStop.setOnClickListener(v -> {
                if (bashRunning) {
                    abortShell(pendingBashRpc);
                    return;
                }
                rpc("clear_queue", null);
                rpc("abort", null);
            });
        }
        updateSteerUi();
        if (steerToggle != null) steerToggle.setVisibility(View.VISIBLE);
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
                // Long paste → placeholder attachment (Pocket).
                if (count > 800 && before == 0 && pasteGuard == 0 && s != null) {
                    CharSequence inserted = s.subSequence(start, Math.min(s.length(), start + count));
                    if (inserted.length() > 800) {
                        pasteGuard++;
                        main.post(() -> {
                            try {
                                convertLongPasteToAttachment(start, inserted.length(), inserted.toString());
                            } finally {
                                pasteGuard = Math.max(0, pasteGuard - 1);
                            }
                        });
                        return;
                    }
                }
                updateSendEnabled();
                String text = s == null ? "" : s.toString();
                updateSlashSuggestions(text);
                maybeOfferFileMention(text);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        if (diffBadge != null) {
            diffBadge.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                    return;
                }
                openBranchSwitcher();
            });
        }

        handleIntentExtras(getIntent());
        restoreFromAgentState();
        String st0 = AgentState.get().status();
        if (!"online".equals(st0) && !"starting".equals(st0)) {
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
            if ("online".equals(st.status()) || "starting".equals(st.status())
                    || (service != null && service.isConnected())) {
                // Footer: connection only — model lives on the composer chip.
                setConnectionVisual("online", getString(R.string.status_idle));
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
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) {
            intent.removeExtra(EXTRA_CHECK_UPDATE);
            main.post(this::startCheckUpdate);
        }
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
            if (title == null || title.isEmpty()) {
                applySessionTitle();
                return;
            }
            if (sessionTitle == null) return;
            sessionTitle.setVisibility(View.VISIBLE);
            sessionTitle.setText(title);
        });
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    /** Insert/remove fade only — never change cross-fade (ghosts bash/tool summary text). */
    private static androidx.recyclerview.widget.RecyclerView.ItemAnimator chatItemAnimator() {
        androidx.recyclerview.widget.DefaultItemAnimator a =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        a.setSupportsChangeAnimations(false);
        return a;
    }

    private void setStreaming(boolean on) {
        boolean changed = streaming != on;
        streaming = on;
        if (generationStatus != null) {
            generationStatus.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && (changed || generationStatus.length() == 0)) generationStatus.setText(R.string.generation_active);
        }
        // Plan/Steer/Queue always available for dense Pocket layout; stop only while streaming.
        if (steerToggle != null) steerToggle.setVisibility(View.VISIBLE);
        if (btnStop != null) btnStop.setVisibility(on ? View.VISIBLE : View.GONE);
        if (list != null) {
            list.setItemAnimator(on ? null : chatItemAnimator());
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
        setConnectionVisual("online", getString(R.string.status_idle));
    }

    private void failPrompt(@Nullable String detail) {
        settleStreaming(false);
        String msg = (detail == null || detail.trim().isEmpty())
                ? getString(R.string.prompt_failed)
                : detail.trim();
        if (adapter != null) {
            adapter.add(ChatMessage.notice(new ChatMessage.NoticeData(
                    ChatMessage.NoticeData.LEVEL_ERROR,
                    getString(R.string.notice_prompt_failed),
                    msg, "", "")));
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    /** Notice action chip: insert slash into composer and send when connected. */
    private void runNoticeCommand(@NonNull String command) {
        String cmd = command.trim();
        if (cmd.isEmpty()) return;
        if (!cmd.startsWith("/")) cmd = "/" + cmd;
        if (composer != null) {
            composer.setText(cmd);
            composer.setSelection(composer.getText().length());
        }
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        onSendOrStop();
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
        // 流式中 stop 独立；send 仅在有正文时发 steer/follow_up
        send.setImageResource(R.drawable.ic_send);
        send.setContentDescription(getString(R.string.action_send));
        boolean canSend = streaming
                ? hasText
                : (connected && !busyAttach && hasText);
        send.setEnabled(canSend);
        send.setAlpha(canSend ? 1f : 0.45f);
        if (btnStop != null) {
            btnStop.setEnabled(streaming);
            btnStop.setAlpha(streaming ? 1f : 0.4f);
        }
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
        // Brand mark is always the colorful π logo; named sessions show text beside it.
        if (brandLogo != null) brandLogo.setVisibility(View.VISIBLE);
        if (sessionTitle == null) return;
        if (currentSessionName != null && !currentSessionName.isEmpty()) {
            sessionTitle.setVisibility(View.VISIBLE);
            sessionTitle.setText(currentSessionName);
        } else {
            sessionTitle.setVisibility(View.GONE);
            sessionTitle.setText("");
        }
    }

    private void refreshSessionMeta() {
        if (sessionMeta == null) return;
        StringBuilder meta = new StringBuilder();
        // Cwd lives on the composer chip when supported — avoid duplicating in meta.
        if (!stateCwd.isEmpty() && !cwdSupported) {
            meta.append(shortPath(stateCwd));
        }
        if (!stateBranch.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(stateBranch);
        }
        if (meta.length() == 0) {
            // Fallback: session file basename or model — still only if we have real data.
            String file = shortPath(currentSessionPath);
            if (!file.isEmpty()) meta.append(file);
            else if (!modelStatus.isEmpty()) meta.append(modelStatus);
        }
        if (meta.length() == 0) {
            sessionMeta.setVisibility(View.GONE);
        } else {
            sessionMeta.setVisibility(View.VISIBLE);
            sessionMeta.setText(meta.toString());
        }
        if (diffBadge != null) {
            if (stateDiffBadge.isEmpty()) {
                diffBadge.setVisibility(View.GONE);
            } else {
                diffBadge.setVisibility(View.VISIBLE);
                diffBadge.setText(stateDiffBadge);
                int addColor = ContextCompat.getColor(this, R.color.diff_add);
                int delColor = ContextCompat.getColor(this, R.color.diff_del);
                // Keep plain text; colors live in badge background contrast.
                diffBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                // Silence unused when no spannable — still useful for future tint.
                if (addColor == delColor) { /* no-op */ }
            }
        }
    }

    private static String shortPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        if (slash >= 0 && slash < p.length() - 1) {
            String leaf = p.substring(slash + 1);
            // Prefer parent/leaf when leaf looks like a session file.
            int prev = Math.max(p.lastIndexOf('/', slash - 1), p.lastIndexOf('\\', slash - 1));
            if (leaf.contains(".") && prev >= 0) {
                return p.substring(prev + 1);
            }
            return leaf;
        }
        return p;
    }

    private void refreshModelChip() {
        if (modelChip == null) return;
        AgentState st = AgentState.get();
        String name = !st.modelLabel().isEmpty() ? st.modelLabel()
                : (!modelStatus.isEmpty() ? modelStatus.split(" · ")[0] : getString(R.string.action_model));
        String think = !st.thinking().isEmpty() ? st.thinking() : currentThinking;
        String text = think.isEmpty() ? name : name + " " + think;
        modelChip.setText(text);
        fitModelChipText(text);
    }

    /** Shrink chip text (max ~11.5sp → min ~10sp) so provider+model+thinking fit on one line. */
    private void fitModelChipText(String text) {
        if (modelChip == null || text == null) return;
        final String sample = text;
        modelChip.post(() -> {
            if (modelChip == null) return;
            int avail = modelChip.getWidth() - modelChip.getPaddingStart() - modelChip.getPaddingEnd();
            if (avail <= 0) {
                modelChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
                return;
            }
            float density = getResources().getDisplayMetrics().scaledDensity;
            android.text.TextPaint paint = new android.text.TextPaint(modelChip.getPaint());
            float sp = 11.5f;
            final float minSp = 10f;
            while (sp > minSp + 0.05f) {
                paint.setTextSize(sp * density);
                if (paint.measureText(sample) <= avail) break;
                sp -= 0.5f;
            }
            paint.setTextSize(Math.max(minSp, sp) * density);
            if (paint.measureText(sample) > avail) {
                sp = minSp;
                modelChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            } else {
                modelChip.setEllipsize(null);
            }
            modelChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(minSp, sp));
        });
    }

    private void refreshSessionChrome() {
        applySessionTitle();
        refreshCwdChip();
        refreshSessionMeta();
        refreshModelChip();
    }

    private void refreshCwdChip() {
        if (cwdChip == null) return;
        if (!cwdSupported || stateCwd.isEmpty()) {
            cwdChip.setVisibility(View.GONE);
            return;
        }
        cwdChip.setVisibility(View.VISIBLE);
        cwdChip.setText(stateCwd);
        cwdChip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    }

    private void setCwdSupported(boolean on) {
        if (cwdSupported == on) {
            refreshCwdChip();
            return;
        }
        cwdSupported = on;
        refreshCwdChip();
        refreshSessionMeta();
    }

    private void updateSteerUi() {
        int on = R.drawable.bg_segmented_on;
        int onFg = ContextCompat.getColor(this, R.color.md_theme_onPrimary);
        int offFg = ContextCompat.getColor(this, R.color.text_muted);
        TextView[] modes = {steerModePlan, steerModeSteer, steerModeQueue};
        for (int i = 0; i < modes.length; i++) {
            TextView tv = modes[i];
            if (tv == null) continue;
            if (i == composerMode) {
                tv.setBackgroundResource(on);
                tv.setTextColor(onFg);
            } else {
                tv.setBackground(null);
                tv.setTextColor(offFg);
            }
        }
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        Menu m = menu.getMenu();
        // AppCompat menus hide icons unless optional-icons are enabled.
        if (m instanceof MenuBuilder) {
            ((MenuBuilder) m).setOptionalIconsVisible(true);
        }
        // 新建会话 is the dedicated top-bar + button — not duplicated here.
        // 重新连接 / Fork stay on banner + message actions — not in overflow.
        addOverflowItem(m, 1, 0, R.string.menu_settings, R.drawable.ic_menu_settings);
        addOverflowItem(m, 11, 1, R.string.menu_check_update, R.drawable.ic_menu_update);
        addOverflowItem(m, 5, 2, R.string.menu_find, R.drawable.ic_menu_find);
        addOverflowItem(m, 6, 3, R.string.menu_export, R.drawable.ic_menu_export);
        addOverflowItem(m, 7, 4, R.string.menu_files, R.drawable.ic_menu_files);
        addOverflowItem(m, 8, 5, R.string.menu_changes, R.drawable.ic_menu_changes);
        if (mcpSupported) {
            addOverflowItem(m, 9, 6, R.string.menu_mcp, R.drawable.ic_mcp);
        }
        if (skillsSupported) {
            addOverflowItem(m, 10, 7, R.string.menu_skills, R.drawable.ic_menu_skills);
        }
        menu.setOnMenuItemClickListener(this::onOverflowItem);
        menu.show();
    }

    private void addOverflowItem(Menu menu, int id, int order,
                                 @StringRes int titleRes, @DrawableRes int iconRes) {
        MenuItem item = menu.add(0, id, order, titleRes);
        Drawable icon = ContextCompat.getDrawable(this, iconRes);
        if (icon != null) {
            icon = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(icon, ContextCompat.getColor(this, R.color.text_secondary));
            item.setIcon(icon);
        }
    }

    private boolean onOverflowItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case 5:
                showFindSheet();
                return true;
            case 6:
                exportSessionMarkdown();
                return true;
            case 7:
                openFilesSheet();
                return true;
            case 8:
                openChangesSheet();
                return true;
            case 9:
                openMcpSheet();
                return true;
            case 10:
                openSkillsSheet();
                return true;
            case 11:
                startCheckUpdate();
                return true;
            default:
                return false;
        }
    }

    private void applySessionStats(@Nullable JSONObject data) {
        // Footer connection line always stays; metric chips only when RPC provides values.
        if (data == null) {
            hideFooterMetrics();
            return;
        }
        StringBuilder sb = new StringBuilder();
        Double contextPct = null;
        boolean hasTokens = data.has("contextTokens") && !data.isNull("contextTokens");
        boolean hasWindow = data.has("contextWindow") && !data.isNull("contextWindow");
        if (hasTokens && hasWindow) {
            double tokens = asDouble(data.opt("contextTokens"));
            double window = asDouble(data.opt("contextWindow"));
            if (window > 0) {
                contextPct = Math.max(0, Math.min(100, tokens * 100.0 / window));
                sb.append(String.format(java.util.Locale.US, "%.0f%%", contextPct));
            } else {
                sb.append(formatNumber(data.opt("contextTokens")))
                        .append('/').append(formatNumber(data.opt("contextWindow")));
            }
        } else if (data.has("contextPercent") && !data.isNull("contextPercent")) {
            contextPct = asDouble(data.opt("contextPercent"));
            if (contextPct <= 1.0) contextPct *= 100;
            sb.append(String.format(java.util.Locale.US, "%.0f%%", contextPct));
        } else if (hasTokens) {
            sb.append(formatNumber(data.opt("contextTokens")));
        }

        if (data.has("cacheRate") && !data.isNull("cacheRate")) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("cache ").append(formatPercent(data.opt("cacheRate")));
        } else if (data.has("cachePercent") && !data.isNull("cachePercent")) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("cache ").append(formatPercent(data.opt("cachePercent")));
        }

        if (data.has("cost") && !data.isNull("cost")) {
            if (sb.length() > 0) sb.append(" · ");
            Object cost = data.opt("cost");
            if (cost instanceof Number) {
                sb.append(String.format(java.util.Locale.US, "$%.2f", ((Number) cost).doubleValue()));
            } else {
                String c = String.valueOf(cost);
                if (!c.isEmpty()) sb.append(c.startsWith("$") ? c : "$" + c);
            }
        }

        if ((data.has("inputTokens") && !data.isNull("inputTokens"))
                || (data.has("outputTokens") && !data.isNull("outputTokens"))) {
            if (sb.length() > 0) sb.append(" · ");
            if (data.has("inputTokens") && !data.isNull("inputTokens")) {
                sb.append("↑").append(formatNumber(data.opt("inputTokens")));
            }
            if (data.has("outputTokens") && !data.isNull("outputTokens")) {
                if (data.has("inputTokens") && !data.isNull("inputTokens")) sb.append(' ');
                sb.append("↓").append(formatNumber(data.opt("outputTokens")));
            }
        } else if (data.has("totalTokens") && !data.isNull("totalTokens")) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(formatNumber(data.opt("totalTokens"))).append(" tok");
        }

        boolean hasMetrics = sb.length() > 0 || contextPct != null;
        if (footerSep != null) footerSep.setVisibility(hasMetrics ? View.VISIBLE : View.GONE);
        if (contextBarWrap != null) {
            if (contextPct != null) {
                contextBarWrap.setVisibility(View.VISIBLE);
                if (contextBarFill != null) {
                    int track = contextBarWrap.getWidth();
                    if (track <= 0) track = Math.round(28 * getResources().getDisplayMetrics().density);
                    int fill = Math.max(0, Math.round(track * (float) (contextPct / 100.0)));
                    android.view.ViewGroup.LayoutParams lp = contextBarFill.getLayoutParams();
                    lp.width = fill;
                    contextBarFill.setLayoutParams(lp);
                }
            } else {
                contextBarWrap.setVisibility(View.GONE);
            }
        }
        if (footerStats != null) {
            if (sb.length() == 0) {
                footerStats.setVisibility(View.GONE);
            } else {
                footerStats.setText(sb.toString());
                footerStats.setVisibility(View.VISIBLE);
            }
        }
    }

    private void hideFooterMetrics() {
        if (footerStats != null) footerStats.setVisibility(View.GONE);
        if (footerSep != null) footerSep.setVisibility(View.GONE);
        if (contextBarWrap != null) contextBarWrap.setVisibility(View.GONE);
    }

    private static double asDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private static String formatPercent(Object rate) {
        if (rate instanceof Number) {
            double v = ((Number) rate).doubleValue();
            if (v <= 1.0) return String.format(java.util.Locale.US, "%.0f%%", v * 100);
            return String.format(java.util.Locale.US, "%.0f%%", v);
        }
        String s = String.valueOf(rate);
        return s.endsWith("%") ? s : s + "%";
    }

    private static String formatNumber(Object n) {
        if (n instanceof Number) {
            long v = ((Number) n).longValue();
            if (v >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", v / 1_000_000.0);
            if (v >= 10_000) return String.format(java.util.Locale.US, "%.1fk", v / 1000.0);
            return String.valueOf(v);
        }
        return String.valueOf(n);
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
            // 空正文时 stop 由独立按钮处理；此处仍兼容旧点击
            rpc("clear_queue", null);
            rpc("abort", null);
            return;
        }
        submit();
    }

    /** 流式中有正文：steer → steer；plan/queue → follow_up。 */
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
        String cmd = composerMode == 1 ? "steer" : "follow_up";
        pendingPromptRpcId = rpc(cmd, params);
        pendingComposerText = text;
        stickToBottom = true;
        maybeScrollToBottom(true);
        updateSendEnabled();
    }

    /** 重试：把最近一条用户正文填回输入框，便于再发。 */
    private void retryLastUserPrompt() {
        retryWithPrompt(findPrecedingUserText(null), false);
    }

    @Nullable
    private String findPrecedingUserText(@Nullable ChatMessage fromAgent) {
        if (adapter == null) return null;
        java.util.List<ChatMessage> snap = adapter.snapshot();
        int end = snap.size() - 1;
        if (fromAgent != null) {
            for (int i = snap.size() - 1; i >= 0; i--) {
                if (snap.get(i) == fromAgent || (snap.get(i).role == ChatMessage.Role.AGENT
                        && fromAgent.text != null && fromAgent.text.equals(snap.get(i).text))) {
                    end = i;
                    break;
                }
            }
        }
        for (int i = end; i >= 0; i--) {
            ChatMessage m = snap.get(i);
            if (m.role == ChatMessage.Role.USER && m.text != null && !m.text.isEmpty()) {
                return m.text;
            }
        }
        return null;
    }

    private void retryWithPrompt(@Nullable String text, boolean pickModelFirst) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, R.string.fork_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (pickModelFirst) {
            pendingRetryText = text;
            showModelPicker();
            return;
        }
        // No dedicated retry RPC in connector whitelist — re-prompt with the same text.
        dispatchPrompt(text, null, null);
    }

    private String pendingRetryText = "";

    private void editResendUser(@NonNull ChatMessage userMessage) {
        String text = userMessage.text == null ? "" : userMessage.text;
        if (composer != null) {
            composer.setText(text);
            composer.setSelection(text.length());
            composer.requestFocus();
        }
        updateSendEnabled();
    }

    private void forkFromMessage(@NonNull ChatMessage message) {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        // Prefer entryId on the message or preceding user; else open fork picker via RPC.
        String entryId = message.entryId;
        if (entryId == null || entryId.isEmpty()) {
            java.util.List<ChatMessage> snap = adapter.snapshot();
            for (int i = snap.size() - 1; i >= 0; i--) {
                if (snap.get(i).role == ChatMessage.Role.USER
                        && snap.get(i).entryId != null && !snap.get(i).entryId.isEmpty()) {
                    // Use nearest user entry before/at this point when forking from a reply.
                    entryId = snap.get(i).entryId;
                    // Keep scanning upward until we pass the agent message
                    for (int j = i; j < snap.size(); j++) {
                        if (snap.get(j) == message) break;
                    }
                    break;
                }
            }
            // Better: walk back from message index
            int at = -1;
            for (int i = 0; i < snap.size(); i++) {
                if (snap.get(i) == message) { at = i; break; }
            }
            if (at >= 0) {
                for (int i = at; i >= 0; i--) {
                    if (snap.get(i).role == ChatMessage.Role.USER
                            && snap.get(i).entryId != null && !snap.get(i).entryId.isEmpty()) {
                        entryId = snap.get(i).entryId;
                        break;
                    }
                    if (snap.get(i).entryId != null && !snap.get(i).entryId.isEmpty()) {
                        entryId = snap.get(i).entryId;
                        break;
                    }
                }
            }
        }
        if (entryId != null && !entryId.isEmpty()) {
            JSONObject params = new JSONObject();
            try {
                params.put("entryId", entryId);
            } catch (Exception e) {
                throw new IllegalStateException("fork params", e);
            }
            rpc("fork", params);
            return;
        }
        showForkPicker();
    }

    private void showFindSheet() {
        if (adapter == null) return;
        new FindSheet(this, new FindSheet.Host() {
            @NonNull @Override public java.util.List<ChatMessage> messages() {
                return adapter.snapshot();
            }
            @Override public void jumpTo(int adapterIndex) {
                if (list != null && adapterIndex >= 0 && adapterIndex < adapter.size()) {
                    list.scrollToPosition(adapterIndex);
                }
            }
        }).show();
    }

    private void exportSessionMarkdown() {
        if (adapter == null) return;
        String md = dev.pi.android.util.ExportMarkdown.from(
                adapter.snapshot(),
                currentSessionName == null || currentSessionName.isEmpty()
                        ? getString(R.string.app_name) : currentSessionName);
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.export_markdown), md));
        }
        // Also offer share; export_html exists on connector but MD is client-side.
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/markdown");
            share.putExtra(Intent.EXTRA_TEXT, md);
            share.putExtra(Intent.EXTRA_TITLE, getString(R.string.export_markdown));
            startActivity(Intent.createChooser(share, getString(R.string.export_share)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show();
        }
    }

    private void submit() {
        String text = composer.getText().toString().trim();
        boolean hasAttach = attachmentsCtl != null && attachmentsCtl.hasAttachments();
        if (text.isEmpty() && !hasAttach) return;
        // Pocket !shell / !!shell — run via Pi bash RPC (excludeFromContext for !!).
        if (!hasAttach && (text.startsWith("!!") || text.startsWith("!"))) {
            if (!isConnected()) {
                Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            boolean exclude = text.startsWith("!!");
            String cmd = text.substring(exclude ? 2 : 1).trim();
            if (!cmd.isEmpty()) {
                runShellCommand(cmd, exclude);
                composer.setText("");
                return;
            }
        }
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
        // Footer shows connection/activity only (cost/cache stay in footer_stats).
        // Model + thinking belong on the composer chip — never prefix them here.
        if ("online".equals(state) || "starting".equals(state)) {
            if (!AgentState.get().isFresh() && "online".equals(state)) {
                label = getString(R.string.generation_model_sync);
            } else {
                label = HistoryPresentation.statusLabel(modelStatus, label);
            }
        }
        if (statusText != null) statusText.setText(label);
        if (statusDot == null) return;
        int color;
        switch (state) {
            case "online":
            case "starting": // connector/Pi up — show as running (运行中), not "starting"
                color = R.color.status_online;
                break;
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
            // Keep the transcript scrollable under the IME so truncated notice/tool
            // regions remain reachable; only auto-follow when already stuck to bottom.
            if (list != null) {
                list.setNestedScrollingEnabled(true);
                list.setClipToPadding(false);
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
            reconnectBtn.setVisibility(View.VISIBLE);
        }
    }

    private static boolean isOptionalRpc(String command) {
        return "get_session_name".equals(command)
                || "get_session_stats".equals(command)
                || "export_html".equals(command)
                || "get_commands".equals(command)
                || "list_project_files".equals(command)
                || "read_project_file".equals(command)
                || "git_status".equals(command)
                || "git_branches".equals(command)
                || "git_checkout".equals(command)
                || "git_diff".equals(command)
                || "abort_bash".equals(command)
                || "get_cwd".equals(command)
                || "set_cwd".equals(command)
                || "list_cwd_entries".equals(command)
                || "list_mcp_servers".equals(command)
                || "get_project_trust".equals(command)
                || "check_pi_version".equals(command)
                || "run_pi_update".equals(command);
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
            if (handlePocketRpcFailure(id, command, error)) return;
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
            skillsSupported = true;
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
        if (handlePocketRpcResponse(id, command, data, error, true)) return;
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
            applyGetStateWorkspace(data);
            refreshGitChrome();
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

    /**
     * Optional workspace chrome from get_state. Missing fields stay hidden —
     * never render em-dash placeholders.
     */
    private void applyGetStateWorkspace(@Nullable JSONObject data) {
        if (data == null) return;
        stateCwd = firstString(data, "cwd", "workingDirectory", "workDir", "directory");
        stateBranch = firstString(data, "gitBranch", "branch", "git_branch");
        // Nested git object support
        JSONObject git = data.optJSONObject("git");
        if (stateBranch.isEmpty() && git != null) {
            stateBranch = firstString(git, "branch", "gitBranch", "name");
        }
        if (stateCwd.isEmpty() && git != null) {
            stateCwd = firstString(git, "cwd", "root", "workTree");
        }
        // Bridge injects cwd into get_state; old connectors omit it → hide chip.
        if (!stateCwd.isEmpty()) setCwdSupported(true);
        stateDiffBadge = buildDiffBadge(data);
        if (stateDiffBadge.isEmpty() && git != null) {
            stateDiffBadge = buildDiffBadge(git);
        }
        // Some stats may also arrive on get_state — apply without erasing connection line.
        if (data.has("contextTokens") || data.has("contextWindow") || data.has("cacheRate")
                || data.has("cost") || data.has("contextPercent") || data.has("totalTokens")) {
            applySessionStats(data);
        }
    }

    private static String firstString(JSONObject data, String... keys) {
        for (String key : keys) {
            if (data.has(key) && !data.isNull(key)) {
                String v = data.optString(key, "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
            }
        }
        return "";
    }

    private static String buildDiffBadge(JSONObject data) {
        int add = optNonNeg(data, "additions", "linesAdded", "insertions", "added");
        int del = optNonNeg(data, "deletions", "linesRemoved", "removed", "deleted");
        int files = optNonNeg(data, "filesChanged", "changedFiles", "files", "fileCount");
        if (add < 0 && del < 0 && files < 0) return "";
        StringBuilder sb = new StringBuilder();
        if (add >= 0) sb.append('+').append(add);
        if (del >= 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('-').append(del);
        }
        if (files >= 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(files).append(files == 1 ? " file" : " files");
        }
        return sb.toString();
    }

    private static int optNonNeg(JSONObject data, String... keys) {
        for (String key : keys) {
            if (data.has(key) && !data.isNull(key)) {
                Object v = data.opt(key);
                if (v instanceof Number) return Math.max(0, ((Number) v).intValue());
            }
        }
        return -1;
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
        if (!streaming) setConnectionVisual("online", getString(R.string.status_idle));
    }

    @Override
    public void onEvent(@NonNull JSONObject ev) {
        String type = ev.optString("type");
        if ("bash_execution_update".equals(type)) {
            applyBashDelta(ev);
            return;
        }
        if ("extension_notify".equals(type)) {
            adapter.add(ChatMessage.notice(dev.pi.android.util.NoticeLite.fromNotify(
                    ev.optString("message", ""),
                    ev.optString("notifyType", "info"))));
            maybeScrollToBottom(false);
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

    private void renderExtStatus() {
        if (extStatuses.isEmpty()) {
            restoreStatusChip();
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
        if (path == null || path.isEmpty()) return;
        if (path.equals(currentSessionPath)) return;
        confirmIfBusy(R.string.session_interrupt_switch_message,
                R.string.session_interrupt_switch_toast,
                () -> {
                    JSONObject params = new JSONObject();
                    try {
                        params.put("sessionPath", path);
                    } catch (Exception e) {
                        throw new IllegalStateException("switch_session params", e);
                    }
                    rpc("switch_session", params);
                });
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
        confirmIfBusy(R.string.session_interrupt_new_message,
                R.string.session_interrupt_new_toast,
                () -> rpc("new_session", null));
    }

    /**
     * One Pi RPC process = one active agent. Creating/switching sessions aborts an
     * in-flight run. Confirm when streaming or bash is running; Cancel leaves it alone.
     */
    private void confirmIfBusy(@androidx.annotation.StringRes int messageRes,
                               @androidx.annotation.StringRes int toastRes,
                               @NonNull Runnable proceed) {
        if (!streaming && !bashRunning) {
            proceed.run();
            return;
        }
        MaterialDialogs.confirm(this, 0, messageRes, R.string.session_interrupt_continue, () -> {
            proceed.run();
            Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show();
        });
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
        if (thinkingLevels.isEmpty()) rpc("get_available_thinking_levels", null);
        final String retryAfter = pendingRetryText;
        pendingRetryText = "";
        Pickers.showModelThinking(this, modelLabels, modelRefs, thinkingLevels,
                AgentState.get().provider(), AgentState.get().modelId(), AgentState.get().thinking(),
                (provider, modelId) -> {
                    if (!requireFreshModelState()) return;
                    JSONObject params = ModelRpc.setModelParams(provider, modelId);
                    if (params == null) {
                        Toast.makeText(this, R.string.model_ref_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.util.Log.i(TAG, "set_model " + params.optString("provider")
                            + "/" + params.optString("modelId"));
                    rpc("set_model", params);
                    if (retryAfter != null && !retryAfter.isEmpty()) {
                        // After model switch, re-prompt (no dedicated retry RPC).
                        main.postDelayed(() -> {
                            if (isConnected()) dispatchPrompt(retryAfter, null, null);
                        }, 400);
                    }
                },
                level -> {
                    if (!requireFreshModelState()) return;
                    JSONObject params = new JSONObject();
                    try {
                        params.put("level", level);
                    } catch (Exception e) {
                        throw new IllegalStateException("set_thinking_level params", e);
                    }
                    rpc("set_thinking_level", params);
                },
                () -> Toast.makeText(this, R.string.picker_default_saved, Toast.LENGTH_SHORT).show());
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


    // ---------- Pocket ports: @ / !shell / paste / Files / Changes / git ----------

    private void runShellCommand(String cmd, boolean exclude) {
        JSONObject params = new JSONObject();
        try {
            params.put("command", cmd);
            if (exclude) params.put("excludeFromContext", true);
        } catch (Exception e) {
            throw new IllegalStateException("bash params", e);
        }
        pendingBashCommand = cmd;
        pendingBashExclude = exclude;
        String id = rpc("bash", params);
        if (id == null || id.isEmpty()) {
            Toast.makeText(this, R.string.rpc_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingBashRpc = id;
        String summary = exclude ? getString(R.string.shell_exclude_hint) : "";
        ChatMessage.ToolData td = new ChatMessage.ToolData(id, "bash", cmd, "",
                ChatMessage.ToolData.STATE_RUNNING, 0, -1, -1, summary);
        adapter.add(ChatMessage.tool(td, System.currentTimeMillis()));
        setBashRunning(true);
        maybeScrollToBottom(true);
    }

    private void abortShell(@Nullable String toolId) {
        if (!bashRunning && (pendingBashRpc == null || pendingBashRpc.isEmpty())) return;
        rpc("abort_bash", null);
        String id = toolId != null && !toolId.isEmpty() ? toolId : pendingBashRpc;
        ChatMessage.ToolData prev = adapter.findTool(id);
        if (prev != null) {
            adapter.replaceToolById(id, new ChatMessage.ToolData(prev.id, prev.name, prev.command,
                    prev.output, ChatMessage.ToolData.STATE_CANCELLED, prev.durationMs,
                    prev.additions, prev.deletions,
                    getString(R.string.shell_cancelled)));
        }
        setBashRunning(false);
        pendingBashRpc = "";
    }

    private void setBashRunning(boolean on) {
        bashRunning = on;
        if (btnStop == null) return;
        boolean show = on || streaming;
        btnStop.setVisibility(show ? View.VISIBLE : View.GONE);
        btnStop.setEnabled(show);
        btnStop.setAlpha(show ? 1f : 0.4f);
    }

    private void applyBashDelta(JSONObject ev) {
        String id = ev.optString("id", "");
        if (id.isEmpty()) id = pendingBashRpc;
        if (id.isEmpty()) return;
        String delta = ev.optString("delta", "");
        if (delta.isEmpty()) delta = ev.optString("output", "");
        ChatMessage.ToolData prev = adapter.findTool(id);
        if (prev == null) return;
        String out = prev.output + delta;
        // Cap live preview to keep UI responsive
        if (out.length() > 200_000) out = out.substring(out.length() - 200_000);
        adapter.replaceToolById(id, new ChatMessage.ToolData(prev.id, prev.name, prev.command, out,
                ChatMessage.ToolData.STATE_RUNNING, prev.durationMs, prev.additions, prev.deletions,
                prev.summary));
        maybeScrollToBottom(false);
    }

    private boolean handlePocketRpcFailure(String id, String command, @Nullable String error) {
        if ("bash".equals(command) && id.equals(pendingBashRpc)) {
            ChatMessage.ToolData prev = adapter.findTool(id);
            String detail = error != null ? error : getString(R.string.shell_failed);
            if (prev != null) {
                adapter.replaceToolById(id, new ChatMessage.ToolData(prev.id, prev.name, prev.command,
                        prev.output.isEmpty() ? detail : prev.output + "\n" + detail,
                        ChatMessage.ToolData.STATE_ERROR, prev.durationMs, -1, -1,
                        getString(R.string.shell_failed)));
            }
            setBashRunning(false);
            pendingBashRpc = "";
            // Old Termux bridge reject: "命令不允许: bash" — tell user to update/restart connector.
            if (resumed && isConnectorCommandRejected(error, "bash")) {
                Toast.makeText(this, R.string.connector_update_required, Toast.LENGTH_LONG).show();
            }
            return true;
        }
        if ("run_pi_update".equals(command) && id.equals(pendingPiUpdateRpc)) {
            pendingPiUpdateRpc = "";
            if (isConnectorCommandRejected(error, command)) {
                if (updateProgressSheet != null) {
                    try { updateProgressSheet.dismiss(); } catch (Exception ignored) {}
                    updateProgressSheet = null;
                }
                if (resumed) {
                    Toast.makeText(this, R.string.connector_update_required, Toast.LENGTH_LONG).show();
                }
            } else {
                showPiUpdateResult(false, null, error);
            }
            return true;
        }
        if ("check_pi_version".equals(command) && id.equals(pendingPiVersionRpc)) {
            pendingPiVersionRpc = "";
            // Optional probe — ignore failures quietly.
            return true;
        }
        if ("list_project_files".equals(command) || "git_status".equals(command)
                || "git_branches".equals(command) || "git_checkout".equals(command)
                || "git_diff".equals(command) || "read_project_file".equals(command)
                || "list_cwd_entries".equals(command) || "set_cwd".equals(command)
                || "get_cwd".equals(command)
                || "list_mcp_servers".equals(command) || "get_project_trust".equals(command)) {
            boolean userFacing = ("git_branches".equals(command) && id.equals(pendingBranchesRpc))
                    || ("git_status".equals(command) && id.equals(pendingChangesRpc))
                    || ("list_project_files".equals(command)
                        && (id.equals(pendingFilesRpc) || id.equals(pendingMentionRpc)))
                    || ("read_project_file".equals(command) && id.equals(pendingReadRpc))
                    || ("git_diff".equals(command) && id.equals(pendingDiffRpc))
                    || ("git_checkout".equals(command) && id.equals(pendingCheckoutRpc))
                    || ("list_cwd_entries".equals(command) && id.equals(pendingCwdListRpc))
                    || ("set_cwd".equals(command) && id.equals(pendingCwdSetRpc))
                    || ("list_mcp_servers".equals(command) && id.equals(pendingMcpListRpc))
                    || ("get_project_trust".equals(command) && id.equals(pendingSkillsTrustRpc));
            if (userFacing && resumed) {
                if (isConnectorCommandRejected(error, command)) {
                    if ("list_cwd_entries".equals(command) || "set_cwd".equals(command)
                            || "get_cwd".equals(command)) {
                        setCwdSupported(false);
                    }
                    if ("list_mcp_servers".equals(command)) mcpSupported = false;
                    Toast.makeText(this, R.string.connector_update_required, Toast.LENGTH_LONG).show();
                } else if ("list_cwd_entries".equals(command) || "set_cwd".equals(command)) {
                    Toast.makeText(this,
                            error != null && !error.isEmpty() ? error
                                    : getString(R.string.cwd_unavailable),
                            Toast.LENGTH_SHORT).show();
                } else if ("list_mcp_servers".equals(command)) {
                    Toast.makeText(this, R.string.mcp_unavailable, Toast.LENGTH_SHORT).show();
                } else if ("get_project_trust".equals(command)) {
                    // Trust optional — still show skills without trust header.
                    showSkillsSheetWithTrust(null);
                } else {
                    Toast.makeText(this,
                            "git_branches".equals(command) || "git_status".equals(command)
                                    || "git_checkout".equals(command) || "git_diff".equals(command)
                                    ? R.string.git_unavailable : R.string.file_viewer_failed,
                            Toast.LENGTH_SHORT).show();
                }
            } else if (("list_cwd_entries".equals(command) || "set_cwd".equals(command)
                    || "get_cwd".equals(command))
                    && isConnectorCommandRejected(error, command)) {
                setCwdSupported(false);
            } else if ("list_mcp_servers".equals(command)
                    && isConnectorCommandRejected(error, command)) {
                mcpSupported = false;
            }
            clearPendingPocketId(command, id);
            return true;
        }
        return false;
    }

    /** True when an old bridge rejected the command with allow-list error (or unknown cmd). */
    private static boolean isConnectorCommandRejected(@Nullable String error, String command) {
        if (error == null || error.isEmpty()) return false;
        String e = error.trim();
        if (e.startsWith("命令不允许")) return true;
        if (e.contains("命令不允许: " + command)) return true;
        String lower = e.toLowerCase(java.util.Locale.US);
        return lower.contains("unknown command") || lower.contains("not allowed")
                || lower.contains("unsupported");
    }

    private boolean handlePocketRpcResponse(String id, String command, @Nullable JSONObject data,
                                            @Nullable String error, boolean ok) {
        if ("bash".equals(command) && id.equals(pendingBashRpc)) {
            ChatMessage.ToolData prev = adapter.findTool(id);
            String output = data != null ? data.optString("output", "") : "";
            if (output.isEmpty() && prev != null) output = prev.output;
            boolean cancelled = data != null && data.optBoolean("cancelled", false);
            int exit = data != null ? data.optInt("exitCode", 0) : 0;
            boolean truncated = data != null && data.optBoolean("truncated", false);
            String state = cancelled ? ChatMessage.ToolData.STATE_CANCELLED
                    : (exit != 0 ? ChatMessage.ToolData.STATE_ERROR : ChatMessage.ToolData.STATE_DONE);
            String summary = cancelled ? getString(R.string.shell_cancelled)
                    : (exit != 0 ? getString(R.string.shell_failed) : getString(R.string.shell_done));
            if (truncated) summary = summary + " · trunc";
            if (pendingBashExclude && !cancelled) {
                summary = getString(R.string.shell_exclude_hint) + " · " + summary;
            }
            String cmd = prev != null ? prev.command : pendingBashCommand;
            String toolId = prev != null ? prev.id : id;
            adapter.replaceToolById(toolId, new ChatMessage.ToolData(toolId, "bash", cmd, output,
                    state, 0, -1, -1, summary));
            setBashRunning(false);
            pendingBashRpc = "";
            maybeScrollToBottom(false);
            return true;
        }
        if ("list_project_files".equals(command)
                && (id.equals(pendingFilesRpc) || id.equals(pendingMentionRpc))) {
            boolean mention = id.equals(pendingMentionRpc);
            clearPendingPocketId(command, id);
            List<dev.pi.android.model.PickerItem> items = ProjectSheets.filesFromResponse(data);
            if (mention) {
                ProjectSheets.showFilePicker(this, items, path -> onFileMentionPicked(path));
            } else {
                ProjectSheets.showFilePicker(this, items, this::openProjectFile);
            }
            return true;
        }
        if ("read_project_file".equals(command) && id.equals(pendingReadRpc)) {
            String path = pendingReadPath;
            boolean asAttach = pendingReadAsAttach;
            pendingReadAsAttach = false;
            clearPendingPocketId(command, id);
            if (data == null) {
                Toast.makeText(this, R.string.file_viewer_failed, Toast.LENGTH_SHORT).show();
                return true;
            }
            String content = data.optString("content", "");
            boolean truncated = data.optBoolean("truncated", false);
            if (asAttach) {
                String name = path;
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                if (slash >= 0 && slash < path.length() - 1) name = path.substring(slash + 1);
                if (attachmentsCtl != null) {
                    attachmentsCtl.addTextContent(name, content, "text/plain");
                    Toast.makeText(this, getString(R.string.paste_as_file, name), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            showTextViewer(getString(R.string.file_viewer_title) + " · " + path,
                    truncated ? content + "\n" + getString(R.string.file_viewer_truncated) : content);
            return true;
        }
        if ("git_status".equals(command)) {
            if (id.equals(pendingChangesRpc)) {
                clearPendingPocketId(command, id);
                List<dev.pi.android.model.PickerItem> items = ProjectSheets.changesFromResponse(data);
                ProjectSheets.showChanges(this, items, path -> {
                    pendingDiffPath = path;
                    JSONObject params = new JSONObject();
                    try { params.put("path", path); } catch (Exception e) {
                        throw new IllegalStateException("git_diff params", e);
                    }
                    pendingDiffRpc = rpc("git_diff", params);
                });
            } else {
                // Silent chrome refresh
                applyGitStatusChrome(data);
            }
            return true;
        }
        if ("git_branches".equals(command) && id.equals(pendingBranchesRpc)) {
            clearPendingPocketId(command, id);
            List<dev.pi.android.model.PickerItem> items = ProjectSheets.branchesFromResponse(data);
            ProjectSheets.showBranches(this, items, branch -> {
                JSONObject params = new JSONObject();
                try { params.put("branch", branch); } catch (Exception e) {
                    throw new IllegalStateException("git_checkout params", e);
                }
                pendingCheckoutRpc = rpc("git_checkout", params);
            });
            return true;
        }
        if ("git_checkout".equals(command) && id.equals(pendingCheckoutRpc)) {
            clearPendingPocketId(command, id);
            String branch = data != null ? data.optString("branch", "") : "";
            if (!branch.isEmpty()) {
                stateBranch = branch;
                refreshSessionChrome();
                Toast.makeText(this, getString(R.string.git_checkout_ok, branch), Toast.LENGTH_SHORT).show();
            }
            refreshGitChrome();
            rpc("get_state", null);
            return true;
        }
        if ("git_diff".equals(command) && id.equals(pendingDiffRpc)) {
            String path = pendingDiffPath;
            clearPendingPocketId(command, id);
            String diff = data != null ? data.optString("diff", "") : "";
            if (diff.isEmpty()) diff = "(empty diff)\n";
            showTextViewer(getString(R.string.diff_viewer_title) + " · " + path, diff);
            return true;
        }
        if ("list_cwd_entries".equals(command) && id.equals(pendingCwdListRpc)) {
            clearPendingPocketId(command, id);
            setCwdSupported(true);
            String browse = data != null ? data.optString("cwd", cwdBrowsePath) : cwdBrowsePath;
            if (browse == null) browse = "";
            cwdBrowsePath = browse;
            List<dev.pi.android.model.PickerItem> entries =
                    ProjectSheets.cwdEntriesFromResponse(this, data);
            final String browseFinal = browse;
            ProjectSheets.showCwdPicker(this, browse, entries,
                    path -> openCwdSheet(path),
                    this::requestSetCwd,
                    () -> MaterialDialogs.promptPath(this, R.string.cwd_enter_path,
                            R.string.cwd_enter_hint,
                            browseFinal.isEmpty() ? stateCwd : browseFinal,
                            path -> {
                                if (path != null && !path.isEmpty()) requestSetCwd(path);
                            }));
            return true;
        }
        if ("set_cwd".equals(command) && id.equals(pendingCwdSetRpc)) {
            clearPendingPocketId(command, id);
            setCwdSupported(true);
            String cwd = data != null ? firstString(data, "cwd", "path") : "";
            if (!cwd.isEmpty()) {
                stateCwd = cwd;
                cwdBrowsePath = cwd;
                refreshSessionChrome();
                if (resumed) {
                    Toast.makeText(this, getString(R.string.cwd_set_ok, cwd), Toast.LENGTH_SHORT).show();
                }
            }
            // Pi respawned — refresh chrome + git from new cwd.
            rpc("get_state", null);
            refreshGitChrome();
            return true;
        }
        if ("get_cwd".equals(command)) {
            setCwdSupported(true);
            String cwd = data != null ? firstString(data, "cwd", "path") : "";
            if (!cwd.isEmpty()) {
                stateCwd = cwd;
                refreshSessionChrome();
            }
            return true;
        }
        if ("list_mcp_servers".equals(command) && id.equals(pendingMcpListRpc)) {
            clearPendingPocketId(command, id);
            mcpSupported = true;
            if (data != null && data.has("projectTrusted") && !data.isNull("projectTrusted")) {
                projectTrusted = data.optBoolean("projectTrusted");
            }
            ProjectSheets.showMcpServers(this, data, this::showMcpServerDetail, this::runMcpCommand);
            return true;
        }
        if ("get_project_trust".equals(command) && id.equals(pendingSkillsTrustRpc)) {
            clearPendingPocketId(command, id);
            Boolean trusted = null;
            if (data != null && data.has("trusted") && !data.isNull("trusted")) {
                trusted = data.optBoolean("trusted");
                projectTrusted = trusted;
            }
            showSkillsSheetWithTrust(trusted);
            return true;
        }
        if ("check_pi_version".equals(command) && id.equals(pendingPiVersionRpc)) {
            pendingPiVersionRpc = "";
            // Soft probe only — confirm sheet already shown; Toast version if useful.
            if (data != null) {
                String ver = data.optString("version", "");
                if (!ver.isEmpty() && resumed) {
                    Toast.makeText(this, getString(R.string.update_current_version, ver),
                            Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if ("run_pi_update".equals(command) && id.equals(pendingPiUpdateRpc)) {
            pendingPiUpdateRpc = "";
            boolean okExit = data == null || !data.has("status") || data.optInt("status", 1) == 0;
            showPiUpdateResult(okExit, data, null);
            return true;
        }
        return false;
    }

    private void clearPendingPocketId(String command, String id) {
        if ("list_project_files".equals(command)) {
            if (id.equals(pendingFilesRpc)) pendingFilesRpc = "";
            if (id.equals(pendingMentionRpc)) pendingMentionRpc = "";
        } else if ("git_status".equals(command)) {
            if (id.equals(pendingChangesRpc)) pendingChangesRpc = "";
        } else if ("git_branches".equals(command)) {
            if (id.equals(pendingBranchesRpc)) pendingBranchesRpc = "";
        } else if ("git_checkout".equals(command)) {
            if (id.equals(pendingCheckoutRpc)) pendingCheckoutRpc = "";
        } else if ("git_diff".equals(command)) {
            if (id.equals(pendingDiffRpc)) pendingDiffRpc = "";
            pendingDiffPath = "";
        } else if ("read_project_file".equals(command)) {
            if (id.equals(pendingReadRpc)) pendingReadRpc = "";
            pendingReadPath = "";
        } else if ("list_cwd_entries".equals(command)) {
            if (id.equals(pendingCwdListRpc)) pendingCwdListRpc = "";
        } else if ("set_cwd".equals(command)) {
            if (id.equals(pendingCwdSetRpc)) pendingCwdSetRpc = "";
        } else if ("list_mcp_servers".equals(command)) {
            if (id.equals(pendingMcpListRpc)) pendingMcpListRpc = "";
        } else if ("get_project_trust".equals(command)) {
            if (id.equals(pendingSkillsTrustRpc)) pendingSkillsTrustRpc = "";
        }
    }

    private void openMcpSheet() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mcpSupported) {
            Toast.makeText(this, R.string.mcp_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.mcp_loading, Toast.LENGTH_SHORT).show();
        pendingMcpListRpc = rpc("list_mcp_servers", null);
    }

    private void runMcpCommand() {
        runNoticeCommand("/mcp");
    }

    /** Overflow / Settings「检测更新」: confirm → bridge `run_pi_update` (documented `pi update`). */
    private void startCheckUpdate() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        // Best-effort version probe; confirm sheet still works if RPC unknown.
        pendingPiVersionRpc = rpc("check_pi_version", null);
        List<DetailSheet.Action> actions = new ArrayList<>();
        actions.add(new DetailSheet.Action() {
            @NonNull @Override public String label() { return getString(R.string.update_run); }
            @Override public void run() { runPiUpdateConfirmed(); }
        });
        new DetailSheet(this, getString(R.string.menu_check_update))
                .setTitleIcon(R.drawable.ic_menu_update)
                .setBody(getString(R.string.update_confirm_body))
                .setPrimaryActions(actions)
                .show();
    }

    private void runPiUpdateConfirmed() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (updateProgressSheet != null) {
            try { updateProgressSheet.dismiss(); } catch (Exception ignored) {}
        }
        updateProgressSheet = new DetailSheet(this, getString(R.string.update_running_title))
                .setTitleIcon(R.drawable.ic_menu_update)
                .setBody(getString(R.string.update_running_body))
                .setProgress(true)
                .setCancelVisible(false);
        updateProgressSheet.show();
        pendingPiUpdateRpc = rpc("run_pi_update", null);
    }

    private void showPiUpdateResult(boolean ok, @Nullable JSONObject data, @Nullable String error) {
        if (updateProgressSheet != null) {
            try { updateProgressSheet.dismiss(); } catch (Exception ignored) {}
            updateProgressSheet = null;
        }
        StringBuilder log = new StringBuilder();
        if (data != null) {
            String cmd = data.optString("argv", data.optString("command", "pi update"));
            if (!cmd.isEmpty()) log.append("$ ").append(cmd).append('\n');
            String stdout = data.optString("stdout", "");
            String stderr = data.optString("stderr", "");
            if (!stdout.isEmpty()) log.append(stdout);
            if (!stderr.isEmpty()) {
                if (log.length() > 0 && log.charAt(log.length() - 1) != '\n') log.append('\n');
                log.append(stderr);
            }
            if (data.has("status") && !data.isNull("status")) {
                if (log.length() > 0 && log.charAt(log.length() - 1) != '\n') log.append('\n');
                log.append("exit ").append(data.optInt("status"));
            }
        }
        if (!ok && error != null && !error.isEmpty()) {
            if (log.length() > 0) log.append('\n');
            log.append(error);
        }
        String title = ok ? getString(R.string.update_ok_title) : getString(R.string.update_fail_title);
        String body = ok ? getString(R.string.update_ok_body) : getString(R.string.update_fail_body);
        new DetailSheet(this, title)
                .setTitleIcon(R.drawable.ic_menu_update)
                .setBody(body)
                .setLog(log.length() == 0 ? null : log.toString().trim())
                .setCancelLabel(getString(android.R.string.ok))
                .show();
    }

    private void showMcpServerDetail(@NonNull JSONObject server) {
        String name = server.optString("name", "");
        String status = ProjectSheets.mcpStatusLabel(this, server.optString("status", "configured"));
        String source = "project".equals(server.optString("source"))
                ? getString(R.string.mcp_source_project) : getString(R.string.mcp_source_user);
        String transport = server.optString("transport", "");
        String desc = server.optString("description", "");
        String detail = server.optString("detail", "");
        String exposure = server.optString("exposure", "");
        List<String> chipLabels = new ArrayList<>();
        chipLabels.add(status);
        chipLabels.add(source);
        if (!transport.isEmpty() && !"unknown".equals(transport)) chipLabels.add(transport);
        StringBuilder body = new StringBuilder();
        if (!exposure.isEmpty()) body.append("exposure: ").append(exposure);
        if (!desc.isEmpty()) {
            if (body.length() > 0) body.append("\n\n");
            body.append(desc);
        }
        if (!detail.isEmpty()) {
            if (body.length() > 0) body.append("\n\n");
            body.append(detail);
        }
        List<DetailSheet.Action> actions = new ArrayList<>();
        actions.add(new DetailSheet.Action() {
            @NonNull @Override public String label() { return getString(R.string.mcp_run_command); }
            @Override public void run() { runMcpCommand(); }
        });
        if (!name.isEmpty()) {
            actions.add(new DetailSheet.Action() {
                @NonNull @Override public String label() { return getString(R.string.mcp_detail_run_get); }
                @Override public void run() { runNoticeCommand("/mcp get " + name); }
            });
        }
        new DetailSheet(this, name.isEmpty() ? getString(R.string.menu_mcp) : name)
                .setTitleIcon(R.drawable.ic_mcp)
                .setChips(chipLabels)
                .setBody(body.length() == 0 ? null : body.toString())
                .setPrimaryActions(actions)
                .show();
    }

    private void showSkillDetail(@NonNull SlashCommand skill) {
        List<String> chipLabels = new ArrayList<>();
        if (skill.location != null && !skill.location.isEmpty()) {
            if ("project".equals(skill.location)) {
                chipLabels.add(getString(R.string.mcp_source_project));
            } else if ("user".equals(skill.location)) {
                chipLabels.add(getString(R.string.mcp_source_user));
            } else {
                chipLabels.add(skill.location);
            }
        }
        if (skill.source != null && !skill.source.isEmpty()) chipLabels.add(skill.source);
        String title = skill.skillLabel();
        if (title == null || title.isEmpty()) title = skill.name;
        List<DetailSheet.Action> actions = new ArrayList<>();
        actions.add(new DetailSheet.Action() {
            @NonNull @Override public String label() { return getString(R.string.skills_insert_action); }
            @Override public void run() { insertSkillIntoComposer(skill); }
        });
        new DetailSheet(this, title)
                .setTitleIcon(R.drawable.ic_menu_skills)
                .setChips(chipLabels)
                .setBody(skill.description)
                .setPrimaryActions(actions)
                .show();
    }

    private void openSkillsSheet() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!skillsSupported) {
            Toast.makeText(this, R.string.skills_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.skills_loading, Toast.LENGTH_SHORT).show();
        refreshSlashCommands();
        if (projectTrusted != null) {
            showSkillsSheetWithTrust(projectTrusted);
            return;
        }
        pendingSkillsTrustRpc = rpc("get_project_trust", null);
    }

    private void showSkillsSheetWithTrust(@Nullable Boolean trusted) {
        if (slashStore == null) {
            Toast.makeText(this, R.string.skills_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        List<SlashCommand> skills = slashStore.skills();
        if (skills.isEmpty() && !slashStore.hasServerCommands()) {
            // get_commands may still be in flight — nudge once.
            refreshSlashCommands();
        }
        ProjectSheets.showSkills(this, skills, trusted, this::showSkillDetail);
    }

    private void insertSkillIntoComposer(@NonNull SlashCommand skill) {
        if (composer == null) return;
        String insert = skill.name.startsWith("/") ? skill.name + " " : "/" + skill.name + " ";
        Editable cur = composer.getText();
        String text = cur == null ? "" : cur.toString();
        if (text.isEmpty() || text.trim().equals("/")) {
            composer.setText(insert);
        } else if (!text.endsWith(" ") && !text.endsWith("\n")) {
            composer.setText(text + " " + insert);
        } else {
            composer.setText(text + insert);
        }
        Editable after = composer.getText();
        if (after != null) composer.setSelection(after.length());
        Toast.makeText(this, getString(R.string.skills_inserted, insert.trim()), Toast.LENGTH_SHORT).show();
    }

    /** Browse working directory; {@code path} null/empty lists current session cwd. */
    private void openCwdSheet(@Nullable String path) {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cwdSupported && (stateCwd == null || stateCwd.isEmpty())) {
            Toast.makeText(this, R.string.cwd_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.cwd_loading, Toast.LENGTH_SHORT).show();
        JSONObject params = new JSONObject();
        if (path != null && !path.isEmpty()) {
            try {
                params.put("path", path);
            } catch (Exception e) {
                throw new IllegalStateException("list_cwd_entries params", e);
            }
            cwdBrowsePath = path;
        } else {
            cwdBrowsePath = stateCwd;
        }
        pendingCwdListRpc = rpc("list_cwd_entries", params.length() == 0 ? null : params);
    }

    private void requestSetCwd(@NonNull String path) {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (path == null || path.trim().isEmpty()) return;
        JSONObject params = new JSONObject();
        try {
            params.put("path", path.trim());
        } catch (Exception e) {
            throw new IllegalStateException("set_cwd params", e);
        }
        pendingCwdSetRpc = rpc("set_cwd", params);
    }

    private void refreshGitChrome() {
        if (!isConnected()) return;
        // Silent — failures hide branch/diff chrome via empty fields.
        rpc("git_status", null);
    }

    private void applyGitStatusChrome(@Nullable JSONObject data) {
        if (data == null) return;
        String branch = data.optString("branch", "");
        if (!branch.isEmpty() && !"HEAD".equals(branch)) {
            stateBranch = branch;
        }
        org.json.JSONArray files = data.optJSONArray("files");
        if (files != null && files.length() > 0) {
            if (stateDiffBadge.isEmpty()) {
                stateDiffBadge = files.length() + (files.length() == 1 ? " file" : " files");
            }
        } else if (files != null) {
            // Clean tree — clear synthetic badge if it came from git_status only
            if (stateDiffBadge.endsWith(" file") || stateDiffBadge.endsWith(" files")) {
                // keep get_state badge if present; only clear when purely file-count form
                if (!stateDiffBadge.contains("+") && !stateDiffBadge.contains("-")) {
                    stateDiffBadge = "";
                }
            }
        }
        refreshSessionChrome();
    }

    private void openFilesSheet() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.files_loading, Toast.LENGTH_SHORT).show();
        JSONObject params = new JSONObject();
        try { params.put("limit", 120); } catch (Exception e) {
            throw new IllegalStateException("list_project_files params", e);
        }
        pendingFilesRpc = rpc("list_project_files", params);
    }

    private void openChangesSheet() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.changes_loading, Toast.LENGTH_SHORT).show();
        pendingChangesRpc = rpc("git_status", null);
    }

    private void openBranchSwitcher() {
        if (!isConnected()) {
            Toast.makeText(this, R.string.bridge_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingBranchesRpc = rpc("git_branches", null);
    }

    private void openProjectFile(String path) {
        if (path == null || path.isEmpty() || !isConnected()) return;
        pendingReadPath = path;
        JSONObject params = new JSONObject();
        try {
            params.put("path", path);
            params.put("maxBytes", 200_000);
        } catch (Exception e) {
            throw new IllegalStateException("read_project_file params", e);
        }
        pendingReadRpc = rpc("read_project_file", params);
    }

    private void maybeOfferFileMention(String text) {
        if (text == null || !isConnected()) return;
        int at = text.lastIndexOf('@');
        if (at < 0) return;
        // Only when @ is at token start
        if (at > 0) {
            char prev = text.charAt(at - 1);
            if (!Character.isWhitespace(prev)) return;
        }
        String query = text.substring(at + 1);
        if (query.contains(" ") || query.contains("\n")) return;
        if (query.length() > 64) return;
        long now = System.currentTimeMillis();
        // Lone "@" and further typing both query list_project_files (empty query = top files).
        if (query.equals(lastMentionQuery) && now - lastMentionAt < 280) return;
        lastMentionQuery = query;
        lastMentionAt = now;
        main.removeCallbacks(mentionRunnable);
        // Lone "@" fires immediately; typed filters keep a short debounce.
        main.postDelayed(mentionRunnable, query.isEmpty() ? 0 : 180);
    }

    private final Runnable mentionRunnable = () -> {
        if (!isConnected()) return;
        String q = lastMentionQuery;
        if (q == null) return;
        JSONObject params = new JSONObject();
        try {
            params.put("query", q);
            params.put("limit", 80);
        } catch (Exception e) {
            throw new IllegalStateException("mention params", e);
        }
        pendingMentionRpc = rpc("list_project_files", params);
    };

    private void onFileMentionPicked(String path) {
        if (path == null || path.isEmpty() || composer == null) return;
        CharSequence cur = composer.getText();
        String text = cur == null ? "" : cur.toString();
        int at = text.lastIndexOf('@');
        if (at < 0) {
            composer.append("@" + path + " ");
        } else {
            composer.getText().replace(at, text.length(), "@" + path + " ");
        }
        Toast.makeText(this, getString(R.string.mention_insert, path), Toast.LENGTH_SHORT).show();
        // Offer optional contents attach for small text files
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.PiMaterialAlertDialog)
                .setTitle("@" + path)
                .setItems(new CharSequence[]{
                        getString(R.string.mention_path_only),
                        getString(R.string.mention_with_contents)
                }, (d, which) -> {
                    if (which == 1) attachMentionContents(path);
                })
                .show();
    }

    private void attachMentionContents(String path) {
        if (!isConnected()) return;
        pendingReadPath = path;
        pendingReadAsAttach = true;
        JSONObject params = new JSONObject();
        try {
            params.put("path", path);
            params.put("maxBytes", 100_000);
        } catch (Exception e) {
            throw new IllegalStateException("read_project_file params", e);
        }
        pendingReadRpc = rpc("read_project_file", params);
    }

    private void convertLongPasteToAttachment(int start, int length, String body) {
        if (composer == null || attachmentsCtl == null || body == null || body.isEmpty()) return;
        String name = "paste-" + System.currentTimeMillis() + ".txt";
        String placeholder = getString(R.string.paste_placeholder, name);
        Editable editable = composer.getText();
        int end = Math.min(editable.length(), start + length);
        if (start < 0 || start > editable.length() || end < start) return;
        editable.replace(start, end, placeholder);
        attachmentsCtl.addTextContent(name, body, "text/plain");
        Toast.makeText(this, getString(R.string.paste_as_file, name), Toast.LENGTH_SHORT).show();
    }

    private void showTextViewer(String title, String body) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setTypeface(AppFonts.mono(this, false));
        tv.setTextSize(12f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setText(body == null ? "" : body);
        scroll.addView(tv);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.PiMaterialAlertDialog)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showImageFullscreen(ChatMessage.AttachmentData attachment) {
        if (attachment == null) return;
        String data = attachment.imageData;
        if (data == null || data.isEmpty()) {
            Toast.makeText(this, R.string.attachment_chat_image_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(getString(R.string.image_fullscreen_cd));
        try {
            byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                Toast.makeText(this, R.string.attachment_chat_image_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            image.setImageBitmap(bmp);
        } catch (Exception e) {
            Toast.makeText(this, R.string.attachment_chat_image_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(image);
        image.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }


}
