package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Local composer send history (Pocket {@code pocket.history}): oldest-first,
 * max 200, duplicate moves to newest. Device-local SharedPreferences only.
 */
public final class InputHistory {
    private static final String PREFS = "pi_input_history";
    private static final String KEY = "sent_json";
    private static final int MAX = 200;

    private InputHistory() {}

    /** Oldest first. */
    @NonNull
    public static List<String> list(@NonNull Context ctx) {
        String raw = prefs(ctx).getString(KEY, "[]");
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s != null && !s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Remember a sent message as newest; same text moves up instead of duplicating. */
    public static void remember(@NonNull Context ctx, @Nullable String text) {
        if (text == null) return;
        String value = text.trim();
        if (value.isEmpty()) return;
        List<String> list = list(ctx);
        list.remove(value);
        list.add(value);
        if (list.size() > MAX) {
            list = new ArrayList<>(list.subList(list.size() - MAX, list.size()));
        }
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
