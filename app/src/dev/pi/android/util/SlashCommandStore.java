package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.pi.android.model.SlashCommand;

/** 斜杠命令目录：内存合并 App builtins ∪ {@code get_commands}；prefs 只缓存服务端 JSON。 */
public final class SlashCommandStore {
    private static final String PREF = "pi_slash_commands";
    private static final String K_JSON = "commands_json";

    private final SharedPreferences prefs;
    private List<SlashCommand> catalog = Collections.emptyList();
    private List<SlashCommand> server = Collections.emptyList();

    public SlashCommandStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        List<SlashCommand> cached = SlashCommand.fromCacheJson(prefs.getString(K_JSON, null));
        server = cached;
        catalog = SlashCommand.bindAppActions(cached);
    }

    /** 当前菜单目录（overlay builtins ∪ 桥命令，同名已合并）。 */
    @NonNull
    public List<SlashCommand> current() {
        return catalog;
    }

    /** 用 get_commands 响应更新。 */
    public boolean applyServerResponse(@Nullable JSONObject data) {
        List<SlashCommand> next = SlashCommand.parseServerCommands(data);
        server = next;
        catalog = SlashCommand.bindAppActions(next);
        prefs.edit().putString(K_JSON, SlashCommand.toCacheJson(next).toString()).apply();
        return true;
    }

    @NonNull
    public List<SlashCommand> filter(@Nullable String typed) {
        return SlashCommand.filterCatalog(catalog, typed);
    }

    /**
     * 发送时的精确匹配：先菜单目录，再 overlay 拦截（目录未就绪时仍能打完整 {@code /model}）。
     */
    @Nullable
    public SlashCommand lookupExact(@Nullable String typed) {
        if (typed == null) throw new IllegalArgumentException("typed");
        String t = typed.trim();
        if (t.isEmpty()) return null;
        for (SlashCommand c : catalog) {
            if (c.name.equalsIgnoreCase(t) || c.rawName.equalsIgnoreCase(stripSlash(t))) {
                return c;
            }
        }
        return SlashCommand.appActionFor(t);
    }

    /** Skills from last get_commands (source=skill), including cached. */
    @NonNull
    public List<SlashCommand> skills() {
        List<SlashCommand> out = new ArrayList<>();
        for (SlashCommand c : server) {
            if (c.isSkill()) out.add(c);
        }
        return out;
    }

    public boolean hasServerCommands() {
        return !server.isEmpty();
    }

    @NonNull
    private static String stripSlash(@NonNull String typed) {
        return typed.startsWith("/") ? typed.substring(1) : typed;
    }
}
