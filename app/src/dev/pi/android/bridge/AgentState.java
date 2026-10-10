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

    public void loadCache(@NonNull Context ctx) {
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

    public void setStatus(String s) {
        status = s == null ? "offline" : s;
        notifyListeners();
    }

    /** 从 get_state 的 data 更新；ctx 非空时落盘。 */
    public void applyGetState(@Nullable JSONObject data, @Nullable Context ctx) {
        if (data == null) return;
        JSONObject model = data.optJSONObject("model");
        if (model != null) {
            provider = model.optString("provider", provider);
            modelId = model.optString("id", model.optString("modelId", modelId));
            // name 通常已是「provider / id」展示串，直接采用，避免二次拼接
            String name = model.optString("name", "");
            if (!name.isEmpty()) {
                modelLabel = name;
            } else if (!provider.isEmpty() && !modelId.isEmpty()) {
                modelLabel = provider + " / " + modelId;
            } else if (!modelId.isEmpty()) {
                modelLabel = modelId;
            }
        }
        String th = data.optString("thinkingLevel", null);
        if (th != null) thinking = th;
        if (ctx != null) persist(ctx);
        notifyListeners();
    }

    public void setModel(String provider, String modelId, @Nullable Context ctx) {
        this.provider = provider == null ? "" : provider;
        this.modelId = modelId == null ? "" : modelId;
        if (!this.provider.isEmpty() && !this.modelId.isEmpty()) {
            modelLabel = this.provider + " / " + this.modelId;
        }
        if (ctx != null) persist(ctx);
        notifyListeners();
    }

    public void setThinking(String level, @Nullable Context ctx) {
        thinking = level == null ? "" : level;
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
