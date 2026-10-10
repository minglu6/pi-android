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

/**
 * 作曲框 "/" 提示条目。
 * <p>主体来自 Pi {@code get_commands}（扩展 / prompt 模板 / skill）；
 * App 仅对少量同名命令叠一层本地 UI 动作（不作为命令表来源）。
 */
public final class SlashCommand {

    /** 本地 UI 拦截键；null 表示发给连接器 {@code prompt /name}。 */
    @Nullable public final String appAction;
    /** 显示名，含前导 `/`（skill 可能是 `/skill:foo`）。 */
    @NonNull public final String name;
    /** 不含前导 `/` 的原始名（发给 prompt 用）。 */
    @NonNull public final String rawName;
    @Nullable public final String description;
    @Nullable public final String source; // extension | prompt | skill | app

    public SlashCommand(@NonNull String rawName, @Nullable String description,
                        @Nullable String source, @Nullable String appAction) {
        this.rawName = rawName.startsWith("/") ? rawName.substring(1) : rawName;
        this.name = "/" + this.rawName;
        this.description = description;
        this.source = source;
        this.appAction = appAction;
    }

    public boolean matches(@Nullable String typed) {
        if (typed == null || typed.isEmpty()) return true;
        String q = typed.toLowerCase(Locale.ROOT);
        if (!q.startsWith("/")) q = "/" + q;
        return name.toLowerCase(Locale.ROOT).startsWith(q)
                || (description != null && description.toLowerCase(Locale.ROOT).contains(q.substring(1)));
    }

    public boolean isAppAction() {
        return appAction != null && !appAction.isEmpty();
    }

    /**
     * App 叠加层：TUI 内置命令不在 get_commands 里，由客户端拦截。
     * key = raw name（无斜杠）。
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

    private static void putApp(Map<String, SlashCommand> m, String raw, String zh, String action) {
        m.put(raw, new SlashCommand(raw, zh, "app", action));
    }

    /** 解析 get_commands 响应；失败返回空列表（不抛）。 */
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
            out.add(new SlashCommand(n, desc, source, null));
        }
        return out;
    }

    /**
     * 合并：App overlay 在前（可被同名服务端描述覆盖文案），再追加服务端独有命令。
     */
    @NonNull
    public static List<SlashCommand> merge(@Nullable List<SlashCommand> server) {
        Map<String, SlashCommand> overlay = appOverlay();
        List<SlashCommand> out = new ArrayList<>();
        Map<String, SlashCommand> byRaw = new LinkedHashMap<>();

        if (server != null) {
            for (SlashCommand s : server) {
                SlashCommand app = overlay.get(s.rawName.toLowerCase(Locale.ROOT));
                if (app != null) {
                    // 同名：保留 App 动作，优先用服务端描述
                    String desc = (s.description != null && !s.description.isEmpty())
                            ? s.description : app.description;
                    byRaw.put(app.rawName.toLowerCase(Locale.ROOT),
                            new SlashCommand(app.rawName, desc, s.source != null ? s.source : "app", app.appAction));
                } else {
                    byRaw.put(s.rawName.toLowerCase(Locale.ROOT), s);
                }
            }
        }
        // overlay 中服务端没有的，仍然展示（降级）
        for (Map.Entry<String, SlashCommand> e : overlay.entrySet()) {
            if (!byRaw.containsKey(e.getKey())) {
                byRaw.put(e.getKey(), e.getValue());
            }
        }
        // 稳定顺序：先 overlay 顺序，再其余服务端命令
        for (String key : overlay.keySet()) {
            SlashCommand c = byRaw.remove(key);
            if (c != null) out.add(c);
        }
        out.addAll(byRaw.values());
        return out;
    }

    @NonNull
    public static JSONArray toCacheJson(@NonNull List<SlashCommand> list) {
        JSONArray arr = new JSONArray();
        for (SlashCommand c : list) {
            if (c.isAppAction() && "app".equals(c.source)) continue; // overlay 不缓存
            try {
                JSONObject o = new JSONObject();
                o.put("name", c.rawName);
                if (c.description != null) o.put("description", c.description);
                if (c.source != null) o.put("source", c.source);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    @NonNull
    public static List<SlashCommand> fromCacheJson(@Nullable String json) {
        List<SlashCommand> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            JSONObject wrap = new JSONObject();
            wrap.put("commands", arr);
            return parseServerCommands(wrap);
        } catch (Exception e) {
            return out;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}
