package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Multi-profile connector endpoints. Tokens live in {@link SecureTokenStore}
 * keyed by profile id. Migrates legacy single host/port/token on first use.
 */
public final class ConnectionProfiles {
    private static final String TAG = "ConnProfiles";
    private static final String PREF = "pi_profiles";
    private static final String K_LIST = "profiles_json";
    private static final String K_ACTIVE = "active_id";
    private static final String K_MIGRATED = "migrated_v1";

    public static final String DEFAULT_ID = "local-termux";
    public static final String DEFAULT_NAME = "本机 Termux";

    private static final Pattern TAILSCALE_CGNAT =
            Pattern.compile("^100\\.(6[4-9]|[7-9]\\d|1[0-1]\\d|12[0-7])\\.\\d{1,3}\\.\\d{1,3}$");

    private ConnectionProfiles() {}

    public static synchronized void ensureMigrated(@NonNull Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean(K_MIGRATED, false)) return;
        List<ConnectionProfile> list = readRaw(ctx);
        if (list.isEmpty()) {
            ConnectionProfile local = new ConnectionProfile(
                    DEFAULT_ID, DEFAULT_NAME, "127.0.0.1", 8945);
            // Pull legacy host/port from pi_bridge prefs.
            SharedPreferences legacy = ctx.getApplicationContext()
                    .getSharedPreferences("pi_bridge", Context.MODE_PRIVATE);
            String host = legacy.getString("host", "127.0.0.1");
            int port = legacy.getInt("port", 8945);
            if (host != null && !host.isEmpty()) local.host = host.trim();
            if (port > 0 && port <= 65535) local.port = port;
            list.add(local);
            writeRaw(ctx, list);
            p.edit().putString(K_ACTIVE, local.id).apply();
            // Move legacy token into profile-scoped store (read prefs directly — avoid recurse).
            SharedPreferences bridgePrefs = ctx.getApplicationContext()
                    .getSharedPreferences("pi_bridge", Context.MODE_PRIVATE);
            String legacyEnc = bridgePrefs.getString("token_enc", null);
            String legacyPlain = bridgePrefs.getString("token", "");
            if (legacyEnc != null && !legacyEnc.isEmpty()) {
                // Copy ciphertext blob under profile key; decrypt path still works.
                bridgePrefs.edit()
                        .putString("token_enc_" + local.id, legacyEnc)
                        .remove("token_enc")
                        .remove("token")
                        .apply();
            } else if (legacyPlain != null && !legacyPlain.isEmpty()) {
                SecureTokenStore.setForProfile(ctx, local.id, legacyPlain);
                bridgePrefs.edit().remove("token").apply();
            }
        }
        p.edit().putBoolean(K_MIGRATED, true).apply();
    }

    @NonNull
    public static List<ConnectionProfile> list(@NonNull Context ctx) {
        ensureMigrated(ctx);
        return readRaw(ctx);
    }

    @NonNull
    public static ConnectionProfile active(@NonNull Context ctx) {
        ensureMigrated(ctx);
        String id = prefs(ctx).getString(K_ACTIVE, DEFAULT_ID);
        for (ConnectionProfile p : readRaw(ctx)) {
            if (p.id.equals(id)) return p;
        }
        List<ConnectionProfile> all = readRaw(ctx);
        if (!all.isEmpty()) {
            setActive(ctx, all.get(0).id);
            return all.get(0);
        }
        ConnectionProfile local = new ConnectionProfile(DEFAULT_ID, DEFAULT_NAME, "127.0.0.1", 8945);
        save(ctx, local);
        setActive(ctx, local.id);
        return local;
    }

    public static void setActive(@NonNull Context ctx, @NonNull String id) {
        prefs(ctx).edit().putString(K_ACTIVE, id).apply();
    }

    public static void save(@NonNull Context ctx, @NonNull ConnectionProfile profile) {
        ensureMigrated(ctx);
        List<ConnectionProfile> all = readRaw(ctx);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(profile.id)) {
                all.set(i, profile);
                found = true;
                break;
            }
        }
        if (!found) all.add(profile);
        writeRaw(ctx, all);
    }

    public static void delete(@NonNull Context ctx, @NonNull String id) {
        if (DEFAULT_ID.equals(id)) return; // keep default slot
        List<ConnectionProfile> all = readRaw(ctx);
        all.removeIf(p -> p.id.equals(id));
        writeRaw(ctx, all);
        SecureTokenStore.setForProfile(ctx, id, "");
        if (id.equals(prefs(ctx).getString(K_ACTIVE, ""))) {
            setActive(ctx, all.isEmpty() ? DEFAULT_ID : all.get(0).id);
        }
    }

    @NonNull
    public static String newId() {
        return "p-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static boolean isLoopbackHost(@Nullable String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)
                || h.startsWith("127.");
    }

    public static boolean isTailscaleHost(@Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.trim().toLowerCase(Locale.US);
        if (h.endsWith(".ts.net") || h.contains(".ts.net.")) return true;
        return TAILSCALE_CGNAT.matcher(h).matches();
    }

    /** Whether Termux auto-start applies (local loopback only). */
    public static boolean autoStartAllowed(@NonNull Context ctx) {
        return active(ctx).isLocalLoopback();
    }

    @NonNull
    private static List<ConnectionProfile> readRaw(@NonNull Context ctx) {
        String raw = prefs(ctx).getString(K_LIST, "[]");
        List<ConnectionProfile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(ConnectionProfile.fromJson(o));
            }
        } catch (Exception e) {
            Log.w(TAG, "profiles parse failed", e);
        }
        return out;
    }

    private static void writeRaw(@NonNull Context ctx, @NonNull List<ConnectionProfile> list) {
        JSONArray arr = new JSONArray();
        for (ConnectionProfile p : list) {
            try {
                arr.put(p.toJson());
            } catch (Exception e) {
                Log.w(TAG, "profile serialize failed", e);
            }
        }
        prefs(ctx).edit().putString(K_LIST, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
