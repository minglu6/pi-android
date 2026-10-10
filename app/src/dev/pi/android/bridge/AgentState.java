package dev.pi.android.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.pi.android.model.ChatMessage;

/**
 * 进程内共享连接快照：主题切换 / 旋转重建 Activity 时仍能读到模型、思考档与聊天缓存。
 * BridgeService 在 get_state 响应时更新；同时写入 SharedPreferences 作离线回退。
 */
public final class AgentState {
    private static final AgentState INSTANCE = new AgentState();
    private static final String PREF = "pi_agent_state";
    private static final String K_PROVIDER = "provider";
    private static final String K_MODEL_ID = "model_id";
    private static final String K_MODEL_LABEL = "model_label";
    private static final String K_THINKING = "thinking";

    public interface Listener {
        void onAgentStateChanged();
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String status = "offline";
    private volatile String provider = "";
    private volatile String modelId = "";
    private volatile String modelLabel = "";
    private volatile String thinking = "";
    private volatile boolean fresh;
    private boolean cacheLoaded;
    private volatile long modelRevision;
    private final List<ChatMessage> chatSnapshot = new ArrayList<>();
    private volatile String chatSessionPath = "";

    private AgentState() {}

    public static AgentState get() {
        return INSTANCE;
    }

    public void addListener(@NonNull Listener l) {
        listeners.add(l);
    }

    public void removeListener(@NonNull Listener l) {
        listeners.remove(l);
    }

    public synchronized void loadCache(@NonNull Context ctx) {
        // 缓存只用于进程冷启动，Activity 重建不能覆盖已收到的权威状态。
        if (cacheLoaded || fresh) return;
        cacheLoaded = true;
        SharedPreferences p = prefs(ctx);
        provider = p.getString(K_PROVIDER, "");
        modelId = p.getString(K_MODEL_ID, "");
        modelLabel = p.getString(K_MODEL_LABEL, "");
        thinking = p.getString(K_THINKING, "");
    }

    public void persist(@NonNull Context ctx) {
        prefs(ctx).edit()
                .putString(K_PROVIDER, provider == null ? "" : provider)
                .putString(K_MODEL_ID, modelId == null ? "" : modelId)
                .putString(K_MODEL_LABEL, modelLabel == null ? "" : modelLabel)
                .putString(K_THINKING, thinking == null ? "" : thinking)
                .apply();
    }

    public String status() { return status; }
    public String provider() { return provider; }
    public String modelId() { return modelId; }
    public String modelLabel() { return modelLabel; }
    public String thinking() { return thinking; }
    /** true 仅表示本连接已收到完整 get_state；磁盘缓存不是当前服务端状态。 */
    public boolean isFresh() { return fresh; }
    public long modelRevision() { return modelRevision; }

    public void invalidateFresh() {
        fresh = false;
        modelRevision++;
        notifyListeners();
    }

    public void setStatus(String s) {
        status = s == null ? "offline" : s;
        // starting ≡ running: connector/Pi is up — do not invalidate model freshness.
        if (!"online".equals(status) && !"starting".equals(status)) {
            fresh = false;
            modelRevision++;
        }
        notifyListeners();
    }

    /** 从 get_state 的 data 更新；ctx 非空时落盘。 */
    public void applyGetState(@Nullable JSONObject data, @Nullable Context ctx) {
        if (data == null) return;
        ModelRpc.Applied model = ModelRpc.fromGetState(data);
        String nextProvider = model == null ? "" : model.provider;
        String nextId = model == null ? "" : model.modelId;
        if (!provider.equals(nextProvider) || !modelId.equals(nextId)) modelRevision++;
        // get_state 是完整快照，省略 model 表示没有选中模型，不能继承上个会话的身份。
        provider = nextProvider;
        modelId = nextId;
        modelLabel = model == null ? "" : model.label;
        thinking = data.isNull("thinkingLevel") ? "" : data.optString("thinkingLevel", "");
        fresh = true;
        cacheLoaded = true;
        if (ctx != null) persist(ctx);
        notifyListeners();
    }

    public void setModel(String provider, String modelId, @Nullable Context ctx) {
        setModel(provider, modelId, null, ctx);
    }

    public void setModel(String provider, String modelId, @Nullable String label, @Nullable Context ctx) {
        String nextProvider = provider == null ? "" : provider;
        String nextId = modelId == null ? "" : modelId;
        if (!this.provider.equals(nextProvider) || !this.modelId.equals(nextId)) modelRevision++;
        this.provider = nextProvider;
        this.modelId = nextId;
        if (label != null && !label.trim().isEmpty()) {
            modelLabel = label.trim();
        } else {
            modelLabel = this.provider.isEmpty() ? this.modelId
                    : this.modelId.isEmpty() ? "" : this.provider + " / " + this.modelId;
        }
        cacheLoaded = true;
        if (ctx != null) persist(ctx);
        notifyListeners();
    }

    public void setThinking(String level, @Nullable Context ctx) {
        thinking = level == null ? "" : level;
        cacheLoaded = true;
        if (ctx != null) persist(ctx);
        notifyListeners();
    }

    public synchronized void replaceChat(@Nullable String sessionPath, @NonNull List<ChatMessage> messages) {
        chatSessionPath = sessionPath == null ? "" : sessionPath;
        chatSnapshot.clear();
        chatSnapshot.addAll(messages);
    }

    public synchronized void clearChat() {
        chatSnapshot.clear();
        chatSessionPath = "";
    }

    @NonNull
    public synchronized List<ChatMessage> chatSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(chatSnapshot));
    }

    public String chatSessionPath() {
        return chatSessionPath;
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            try {
                l.onAgentStateChanged();
            } catch (Exception ignored) {
            }
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 调试用摘要，绝不包含 token。 */
    @NonNull
    public String debugSummary() {
        return "status=" + status + " model=" + modelLabel + " thinking=" + thinking;
    }
}
