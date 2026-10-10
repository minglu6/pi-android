package dev.pi.android.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import dev.pi.android.util.RecentModels;

/** Runs the production Java classes. JSON is real JVM org.json, Context is an in-memory fixture. */
public final class ModelStateTest {
    private static int checks;

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    private static JSONObject json(String text) { return new JSONObject(text); }

    private static AgentState state() throws Exception {
        Constructor<AgentState> constructor = AgentState.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static JSONObject snapshot(String provider, String id, String name, String thinking) {
        return new JSONObject().put("model", new JSONObject().put("provider", provider)
                .put("id", id).put("name", name)).put("thinkingLevel", thinking);
    }

    public static void main(String[] args) throws Exception {
        // Identity must round-trip independently from display names; IDs can contain slashes.
        ModelRpc.Catalog catalog = ModelRpc.parseAvailableModels(json("{\"models\":["
                + "{\"provider\":\"opencode-go\",\"id\":\"hy4-preview\",\"name\":\"Friendly name\"},"
                + "{\"provider\":\"opencode-go\",\"id\":\"hy4-preview\",\"name\":\"Duplicate\"},"
                + "{\"provider\":\"other\",\"id\":\"hy4-preview\"},"
                + "{\"provider\":\"openrouter\",\"id\":\"vendor/model\"},"
                + "{\"provider\":\"bad\",\"name\":\"name-only\"},"
                + "{\"provider\":null,\"id\":\"bad\"},null]}"));
        check(catalog.refs.size() == 3, "dedup exact provider/id only; skip missing identities");
        check(catalog.refs.size() == catalog.labels.size(), "catalog labels and refs aligned");
        check("hy4-preview".equals(catalog.refs.get(0)[1]), "id is not display name");
        check("other".equals(catalog.refs.get(1)[0]), "same id from another provider stays distinct");
        JSONObject params = ModelRpc.setModelParams(catalog.refs.get(2)[0], catalog.refs.get(2)[1]);
        check("openrouter".equals(params.getString("provider")), "provider retained in set_model");
        check("vendor/model".equals(params.getString("modelId")), "slash in model id retained");
        check(!params.has("name") && !params.has("id"), "wire uses modelId not name/id");
        check(ModelRpc.setModelParams(null, "m") == null, "reject missing provider");
        check(ModelRpc.setModelParams("p", " ") == null, "reject blank model id");
        check(ModelRpc.setModelParams("p", "p / m") == null, "reject accidental full label as id");
        check(ModelRpc.parseAvailableModels(json("{\"availableModels\":[{\"providerName\":\"legacy\",\"modelId\":\"m\"}]}"))
                .refs.size() == 1, "legacy catalog aliases remain accepted");
        ModelRpc.Applied applied = ModelRpc.fromModelObject(json("{\"provider\":\"opencode-go\",\"id\":\"hy4-preview\",\"name\":\"Hy4 display\"}"));
        check("opencode-go".equals(applied.provider) && "hy4-preview".equals(applied.modelId), "applied identity exact");
        check("opencode-go / Hy4 display".equals(applied.label), "display label distinct from identity");
        check(ModelRpc.fromModelObject(json("{\"name\":\"p / not-an-id\"}")) == null, "name-only cannot restore identity");
        check(ModelRpc.fromModelObject(json("{\"provider\":null,\"id\":\"m\"}")) == null, "explicit JSON null cannot become provider string");
        check(ModelRpc.fromGetState(new JSONObject()) == null, "omitted model means no model");

        Context context = new Context();
        SharedPreferences cache = context.getSharedPreferences("pi_agent_state", Context.MODE_PRIVATE);
        cache.edit().putString("provider", "old-provider").putString("model_id", "old-id")
                .putString("model_label", "old label").putString("thinking", "max").apply();
        AgentState st = state();
        st.loadCache(context);
        check("old-id".equals(st.modelId()) && !st.isFresh(), "cold cache is display-only, not fresh");
        st.setStatus("online");
        check(!st.isFresh(), "socket online does not make cached state authoritative");
        st.applyGetState(snapshot("opencode-go", "hy4-preview", "Hy4 display", "high"), context);
        check(st.isFresh(), "fresh only after authoritative get_state");
        check("opencode-go".equals(st.provider()) && "hy4-preview".equals(st.modelId()), "authoritative identity replaces cache");
        check("opencode-go / Hy4 display".equals(st.modelLabel()), "get_state uses same display parsing as set_model");
        cache.edit().putString("provider", "stale-disk").putString("model_id", "stale-id")
                .putString("thinking", "max").apply();
        st.loadCache(context);
        check("hy4-preview".equals(st.modelId()) && "high".equals(st.thinking()), "Activity recreate cannot load stale disk over live state");
        long before = st.modelRevision();
        st.setStatus("offline");
        check(!st.isFresh() && st.modelRevision() > before, "disconnect invalidates freshness and thinking list revision");
        st.loadCache(context);
        check("hy4-preview".equals(st.modelId()), "disconnect doesn't permit Activity to overwrite confirmed state");
        st.setStatus("online");
        check(!st.isFresh(), "reconnect still needs get_state");
        st.applyGetState(new JSONObject().put("thinkingLevel", "off"), context);
        check(st.provider().isEmpty() && st.modelId().isEmpty() && st.modelLabel().isEmpty(), "omitted model clears all old identity/label");
        check("off".equals(st.thinking()), "non-reasoning off remains valid");
        check(cache.getString("provider", "bad").isEmpty(), "clear persisted, not only rendered");
        st.applyGetState(snapshot("p", "m", "name", "max"), context);
        st.applyGetState(new JSONObject(), context);
        check(st.thinking().isEmpty(), "omitted thinkingLevel clears old level");
        st.applyGetState(snapshot("p", "m", "name", "max"), context);
        st.applyGetState(new JSONObject().put("model", JSONObject.NULL).put("thinkingLevel", JSONObject.NULL), context);
        check(st.provider().isEmpty() && st.thinking().isEmpty(), "explicit JSON null clears old state");
        st.applyGetState(snapshot("p", "m", "name", "max"), context);
        st.applyGetState(new JSONObject().put("model", new JSONObject().put("name", "broken")), context);
        check(st.provider().isEmpty() && st.modelId().isEmpty(), "malformed model cannot merge old identity with new name");
        st.setModel("p", "m", "old label", context);
        st.setModel("", "", context);
        check(st.modelLabel().isEmpty(), "empty setModel clears stale label");

        // The consumer must use the returned effective level, including successful clamping.
        st.applyGetState(snapshot("p", "m", "name", "high"), context);
        st.invalidateFresh(); // request maximum: no optimistic setThinking call
        check("high".equals(st.thinking()) && !st.isFresh(), "pending request keeps last confirmed value without treating it as fresh");
        st.applyGetState(snapshot("p", "m", "name", "off"), context);
        check("off".equals(st.thinking()), "server effective off overrides requested max");
        st.setThinking("low", context);
        check("low".equals(st.thinking()) && "low".equals(cache.getString("thinking", "")), "observed thinking_level_changed can be persisted");
        st.invalidateFresh();
        st.applyGetState(snapshot("p", "m", "name", "low"), context);
        check("low".equals(st.thinking()) && st.isFresh(), "failed request recovery preserves actual server level");
        AgentState beforeActivity = state();
        beforeActivity.applyGetState(snapshot("p", "m", "name", "off"), null);
        beforeActivity.loadCache(context);
        check("off".equals(beforeActivity.thinking()), "service get_state before first Activity wins over cache");

        ModelRpc.SyncGuard guard = new ModelRpc.SyncGuard();
        guard.stateRequested("old"); guard.stateRequested("new");
        check(!guard.acceptState("old"), "out-of-order old state response rejected");
        check(guard.acceptState("new"), "latest state response accepted");
        check(!guard.acceptState("new"), "duplicate state response rejected");
        guard.stateRequested("from-old-connection"); guard.reset();
        check(!guard.acceptState("from-old-connection"), "reconnect rejects queued old response");
        guard.thinkingRequested("levels-A", 10);
        check(!guard.acceptThinking("levels-A", 11), "old model capability response rejected");
        guard.thinkingRequested("levels-B", 11);
        check(guard.acceptThinking("levels-B", 11), "matching model capability response accepted");
        check(!guard.acceptThinking("levels-B", 11), "duplicate capability response rejected");
        guard.thinkingRequested("levels-before-reconnect", 11); guard.reset();
        check(!guard.acceptThinking("levels-before-reconnect", 11), "reconnect drops old capabilities");

        RecentModels.touch(context, "opencode-go", "hy4-preview");
        RecentModels.touch(context, "other", "hy4-preview");
        check(RecentModels.list(context).size() == 2, "recent identity includes provider");
        check("other".equals(RecentModels.list(context).get(0)[0]), "recent models retain exact provider/id");
        System.out.println("ModelStateTest: " + checks + " production-Java checks passed (no Android/bridge/network)");
    }
}
