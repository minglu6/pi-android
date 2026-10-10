package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Optional default model preference ("设为默认"). */
public final class DefaultModel {
    private static final String PREFS = "pi_default_model";
    private static final String K_PROVIDER = "provider";
    private static final String K_ID = "model_id";

    private DefaultModel() {}

    public static void save(@NonNull Context ctx, @NonNull String provider, @NonNull String modelId) {
        prefs(ctx).edit().putString(K_PROVIDER, provider).putString(K_ID, modelId).apply();
    }

    @Nullable
    public static String[] get(@NonNull Context ctx) {
        SharedPreferences p = prefs(ctx);
        String provider = p.getString(K_PROVIDER, "");
        String id = p.getString(K_ID, "");
        if (provider == null || provider.isEmpty() || id == null || id.isEmpty()) return null;
        return new String[]{provider, id};
    }

    public static void clear(@NonNull Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
