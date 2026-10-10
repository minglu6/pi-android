package dev.pi.android.bridge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * set_model / get_available_models 的参数与解析约定。
 * Pi 要求 {@code provider} + {@code modelId}（模型 id，不是展示 name）。
 */
public final class ModelRpc {
    private ModelRpc() {}

    /** 供 set_model 使用的 refs：每项 [provider, modelId]。 */
    public static final class Catalog {
        public final List<CharSequence> labels = new ArrayList<>();
        public final List<String[]> refs = new ArrayList<>();
    }

    @Nullable
    public static JSONObject setModelParams(@Nullable String provider, @Nullable String modelId) {
        if (provider == null || modelId == null) return null;
        String p = provider.trim();
        String id = modelId.trim();
        if (p.isEmpty() || id.isEmpty()) return null;
        // 防御：误把「provider / id」整段塞进 modelId
        if (id.contains(" / ") || p.contains(" / ")) return null;
        JSONObject params = new JSONObject();
        try {
            params.put("provider", p);
            params.put("modelId", id);
        } catch (Exception e) {
            throw new IllegalStateException("set_model params", e);
        }
        return params;
    }

    /** 从 get_available_models 的 data 解析列表；只用 id，不用 name 当 modelId。 */
    @NonNull
    public static Catalog parseAvailableModels(@Nullable JSONObject data) {
        Catalog out = new Catalog();
        if (data == null) return out;
        JSONArray models = data.optJSONArray("models");
        if (models == null) models = data.optJSONArray("availableModels");
        if (models == null) return out;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) continue;
            String provider = m.optString("provider", m.optString("providerName", "")).trim();
            String modelId = m.optString("id", m.optString("modelId", "")).trim();
            if (provider.isEmpty() || modelId.isEmpty()) continue;
            String label = provider + " / " + modelId;
            boolean dup = false;
            for (CharSequence existing : out.labels) {
                if (label.contentEquals(existing)) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            out.labels.add(label);
            out.refs.add(new String[]{provider, modelId});
        }
        return out;
    }

    /** 从 set_model / get_state 的 model 对象提取 provider、id、展示串。 */
    public static final class Applied {
        public final String provider;
        public final String modelId;
        public final String label;

        public Applied(String provider, String modelId, String label) {
            this.provider = provider == null ? "" : provider;
            this.modelId = modelId == null ? "" : modelId;
            this.label = label == null || label.isEmpty()
                    ? (this.provider.isEmpty() ? this.modelId : this.provider + " / " + this.modelId)
                    : label;
        }
    }

    @Nullable
    public static Applied fromModelObject(@Nullable JSONObject model) {
        if (model == null) return null;
        String provider = model.optString("provider", "").trim();
        String modelId = model.optString("id", model.optString("modelId", "")).trim();
        String name = model.optString("name", "").trim();
        if (provider.isEmpty() && modelId.isEmpty() && name.isEmpty()) return null;
        String label;
        if (!name.isEmpty()) {
            label = name.contains(" / ") || provider.isEmpty() ? name : provider + " / " + name;
        } else if (!provider.isEmpty() && !modelId.isEmpty()) {
            label = provider + " / " + modelId;
        } else {
            label = modelId;
        }
        return new Applied(provider, modelId, label);
    }

    /** get_state 的 data.model。 */
    @Nullable
    public static Applied fromGetState(@Nullable JSONObject data) {
        if (data == null) return null;
        return fromModelObject(data.optJSONObject("model"));
    }
}
