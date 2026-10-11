package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Local session pin order (SharedPreferences). Paths stay pinned across launches;
 * unknown paths are ignored when sorting the live session list.
 */
public final class SessionPins {
    private static final String PREFS = "pi_session_pins";
    private static final String KEY = "paths_json";

    private SessionPins() {}

    @NonNull
    public static List<String> list(@NonNull Context ctx) {
        String raw = prefs(ctx).getString(KEY, "[]");
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "").trim();
                if (!p.isEmpty() && seen.add(p)) out.add(p);
            }
        } catch (Exception ignored) {
            // corrupt → empty
        }
        return out;
    }

    public static boolean isPinned(@NonNull Context ctx, @Nullable String path) {
        if (path == null || path.isEmpty()) return false;
        return list(ctx).contains(path);
    }

    /** Pin (append) or unpin. Returns new pinned state. */
    public static boolean toggle(@NonNull Context ctx, @NonNull String path) {
        String p = path.trim();
        if (p.isEmpty()) return false;
        List<String> cur = list(ctx);
        if (cur.contains(p)) {
            cur.remove(p);
            save(ctx, cur);
            return false;
        }
        cur.add(p);
        save(ctx, cur);
        return true;
    }

    public static void setPinned(@NonNull Context ctx, @NonNull String path, boolean pinned) {
        String p = path.trim();
        if (p.isEmpty()) return;
        List<String> cur = list(ctx);
        if (pinned) {
            if (!cur.contains(p)) {
                cur.add(p);
                save(ctx, cur);
            }
        } else if (cur.remove(p)) {
            save(ctx, cur);
        }
    }

    private static void save(Context ctx, List<String> paths) {
        JSONArray arr = new JSONArray();
        for (String p : paths) arr.put(p);
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
