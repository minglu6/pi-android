package dev.pi.android.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/** One saved connector endpoint (local Termux or remote Tailscale). */
public final class ConnectionProfile {
    @NonNull public final String id;
    @NonNull public String name;
    @NonNull public String host;
    public int port;
    @Nullable public String cwd;
    @Nullable public String lastModelId;
    @Nullable public String lastSessionPath;

    public ConnectionProfile(@NonNull String id, @NonNull String name,
                             @NonNull String host, int port) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public boolean isLocalLoopback() {
        return ConnectionProfiles.isLoopbackHost(host);
    }

    public boolean isTailscaleHost() {
        return ConnectionProfiles.isTailscaleHost(host);
    }

    @NonNull
    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        if (cwd != null && !cwd.isEmpty()) o.put("cwd", cwd);
        if (lastModelId != null) o.put("lastModelId", lastModelId);
        if (lastSessionPath != null) o.put("lastSessionPath", lastSessionPath);
        return o;
    }

    @NonNull
    static ConnectionProfile fromJson(@NonNull JSONObject o) throws JSONException {
        ConnectionProfile p = new ConnectionProfile(
                o.getString("id"),
                o.optString("name", "未命名"),
                o.optString("host", "127.0.0.1"),
                o.optInt("port", 8945));
        p.cwd = o.optString("cwd", null);
        if (p.cwd != null && p.cwd.isEmpty()) p.cwd = null;
        p.lastModelId = o.optString("lastModelId", null);
        if (p.lastModelId != null && p.lastModelId.isEmpty()) p.lastModelId = null;
        p.lastSessionPath = o.optString("lastSessionPath", null);
        if (p.lastSessionPath != null && p.lastSessionPath.isEmpty()) p.lastSessionPath = null;
        return p;
    }
}
