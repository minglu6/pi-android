package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** 最近使用的模型（provider\\tmodelId），最多 5 条。 */
public final class RecentModels {
    private static final String PREF = "pi_recent_models";
    private static final String K = "recent";
    private static final int MAX = 5;

    private RecentModels() {}

    @NonNull
    public static List<String[]> list(Context ctx) {
        String raw = prefs(ctx).getString(K, "");
        List<String[]> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2 && !p[0].isEmpty() && !p[1].isEmpty()) out.add(p);
        }
        return out;
    }

    public static void touch(Context ctx, String provider, String modelId) {
        if (provider == null || modelId == null) return;
        List<String[]> cur = list(ctx);
        String key = provider + "\t" + modelId;
        StringBuilder sb = new StringBuilder();
        sb.append(key);
        int n = 1;
        for (String[] p : cur) {
            String line = p[0] + "\t" + p[1];
            if (line.equals(key)) continue;
            sb.append('\n').append(line);
            if (++n >= MAX) break;
        }
        prefs(ctx).edit().putString(K, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
