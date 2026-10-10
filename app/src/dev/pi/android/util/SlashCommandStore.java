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

/** 斜杠命令：内存 + SharedPreferences 缓存；与 get_commands 同步。 */
public final class SlashCommandStore {
    private static final String PREF = "pi_slash_commands";
    private static final String K_JSON = "commands_json";

    private final SharedPreferences prefs;
    private List<SlashCommand> merged = Collections.emptyList();
    private List<SlashCommand> server = Collections.emptyList();

    public SlashCommandStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        List<SlashCommand> cached = SlashCommand.fromCacheJson(prefs.getString(K_JSON, null));
        server = cached;
        merged = SlashCommand.merge(cached);
    }

    @NonNull
    public List<SlashCommand> current() {
        return merged;
    }

    /** 用 get_commands 响应更新；返回是否有变化。 */
    public boolean applyServerResponse(@Nullable JSONObject data) {
        List<SlashCommand> next = SlashCommand.parseServerCommands(data);
        server = next;
        merged = SlashCommand.merge(next);
        prefs.edit().putString(K_JSON, SlashCommand.toCacheJson(next).toString()).apply();
        return true;
    }

    /** 仅 App overlay（离线且无缓存时的降级）。 */
    public void useOverlayOnly() {
        if (!server.isEmpty()) {
            merged = SlashCommand.merge(server);
        } else {
            merged = SlashCommand.merge(Collections.emptyList());
        }
    }

    @NonNull
    public List<SlashCommand> filter(@Nullable String typed) {
        List<SlashCommand> out = new ArrayList<>();
        for (SlashCommand c : merged) {
            if (c.matches(typed)) out.add(c);
        }
        return out;
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
        return server != null && !server.isEmpty();
    }
}
