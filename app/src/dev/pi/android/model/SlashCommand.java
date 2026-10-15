package dev.pi.android.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.pi.android.util.FuzzyFilter;

/**
 * 作曲框斜杠菜单条目。
 * <p>目录只来自桥接 {@code get_commands}（extension / prompt / skill），顺序与描述原样展示。
 * App overlay 只给同名命令挂本地 UI 拦截，不作为命令表来源、不在「仅 /」时本地展开。
 */
public final class SlashCommand {

    /** 本地 UI 拦截键；null 表示发给连接器 {@code prompt /name}。 */
    @Nullable public final String appAction;
    /** 显示名，含前导 {@code /}（skill 可能是 {@code /skill:foo}）。 */
    @NonNull public final String name;
    /** 不含前导 {@code /} 的原始名（发给 prompt 用）。 */
    @NonNull public final String rawName;
    @Nullable public final String description;
    @Nullable public final String source; // extension | prompt | skill | app
    /** user | project | temporary | path — from get_commands location / sourceInfo.scope */
    @Nullable public final String location;

    public SlashCommand(@NonNull String rawName, @Nullable String description,
                        @Nullable String source, @Nullable String appAction) {
        this(rawName, description, source, appAction, null);
    }

    public SlashCommand(@NonNull String rawName, @Nullable String description,
                        @Nullable String source, @Nullable String appAction,
                        @Nullable String location) {
        this.rawName = rawName.startsWith("/") ? rawName.substring(1) : rawName;
        this.name = "/" + this.rawName;
        this.description = description;
        this.source = source;
        this.appAction = appAction;
        this.location = location;
    }

    public boolean isSkill() {
        return "skill".equals(source) || rawName.startsWith("skill:");
    }

    /** Display label without leading skill: prefix. */
    @NonNull
    public String skillLabel() {
        if (rawName.startsWith("skill:")) return rawName.substring("skill:".length());
        return rawName;
    }

    public boolean isAppAction() {
        return appAction != null && !appAction.isEmpty();
    }

    /**
     * App 拦截表：TUI 内置命令不在 get_commands 里，发送完整名时由客户端拦截。
     * 不进入斜杠菜单目录。
     */
    public static Map<String, SlashCommand> appOverlay() {
        Map<String, SlashCommand> m = new LinkedHashMap<>();
        putApp(m, "model", "切换模型", "model");
        putApp(m, "thinking", "切换思考档位", "thinking");
        putApp(m, "new", "新建对话", "new");
        putApp(m, "sessions", "打开会话列表", "sessions");
        putApp(m, "settings", "打开设置", "settings");
        putApp(m, "clear", "清空并新建会话", "clear");
        putApp(m, "reload", "重新连接连接器", "reload");
        putApp(m, "stop", "停止当前生成", "stop");
        return m;
    }

    @Nullable
    public static SlashCommand appActionFor(@Nullable String typed) {
        if (typed == null) throw new IllegalArgumentException("typed");
        String key = typed.startsWith("/") ? typed.substring(1) : typed;
        if (key.isEmpty()) return null;
        return appOverlay().get(key.toLowerCase(Locale.ROOT));
    }

    private static void putApp(Map<String, SlashCommand> m, String raw, String zh, String action) {
        m.put(raw, new SlashCommand(raw, zh, "app", action));
    }

    /** 解析 get_commands 响应；data/commands 缺失则空列表。 */
    @NonNull
    public static List<SlashCommand> parseServerCommands(@Nullable JSONObject data) {
        List<SlashCommand> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("commands");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("name", "").trim();
            if (n.isEmpty()) continue;
            if (n.startsWith("/")) n = n.substring(1);
            String desc = o.optString("description", null);
            if (desc != null && desc.trim().isEmpty()) desc = null;
            String source = o.optString("source", null);
            String location = o.optString("location", null);
            JSONObject sourceInfo = o.optJSONObject("sourceInfo");
            if (sourceInfo != null) {
                if (location == null || location.isEmpty()) {
                    location = sourceInfo.optString("scope", sourceInfo.optString("source", null));
                }
            }
            if (location != null && location.trim().isEmpty()) location = null;
            out.add(new SlashCommand(n, desc, source, null, location));
        }
        return out;
    }

    /**
     * 按 get_commands 顺序保留条目；同名才挂 App 拦截，不追加 overlay 独有命令。
     */
    @NonNull
    public static List<SlashCommand> bindAppActions(@NonNull List<SlashCommand> server) {
        if (server == null) throw new IllegalArgumentException("server");
        Map<String, SlashCommand> overlay = appOverlay();
        List<SlashCommand> out = new ArrayList<>(server.size());
        for (SlashCommand s : server) {
            SlashCommand app = overlay.get(s.rawName.toLowerCase(Locale.ROOT));
            if (app == null) {
                out.add(s);
                continue;
            }
            out.add(new SlashCommand(s.rawName, s.description, s.source, app.appAction, s.location));
        }
        return out;
    }

    /**
     * Pi CombinedAutocompleteProvider：{@code /} 后的前缀对 {@code name} 做 fuzzyFilter；
     * 空前缀（仅 {@code /}）返回桥目录原序，不截断、不另造子集。
     */
    @NonNull
    public static List<SlashCommand> filterCatalog(@NonNull List<SlashCommand> catalog,
                                                   @Nullable String typed) {
        if (catalog == null) throw new IllegalArgumentException("catalog");
        String q = typed == null ? "" : typed;
        if (q.startsWith("/")) q = q.substring(1);
        return FuzzyFilter.filter(catalog, q, c -> c.rawName);
    }

    @NonNull
    public static JSONArray toCacheJson(@NonNull List<SlashCommand> list) {
        JSONArray arr = new JSONArray();
        for (SlashCommand c : list) {
            if (c.isAppAction() && "app".equals(c.source)) {
                throw new IllegalStateException("overlay-only command must not be cached: " + c.name);
            }
            try {
                JSONObject o = new JSONObject();
                o.put("name", c.rawName);
                if (c.description != null) o.put("description", c.description);
                if (c.source != null) o.put("source", c.source);
                if (c.location != null) o.put("location", c.location);
                arr.put(o);
            } catch (Exception e) {
                throw new IllegalStateException("slash cache json", e);
            }
        }
        return arr;
    }

    @NonNull
    public static List<SlashCommand> fromCacheJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            JSONObject wrap = new JSONObject();
            wrap.put("commands", arr);
            return parseServerCommands(wrap);
        } catch (Exception e) {
            throw new IllegalStateException("slash cache parse", e);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}
