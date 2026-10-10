package dev.pi.android.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tailnet / 本机节点：连接器地址 + SSH 终端参数。
 * Token / SSH 密码 / 私钥不进此对象明文持久化，由 SecretStore 按 id 存。
 */
public final class Node {
    public enum Auth { PASSWORD, KEY }

    @NonNull public final String id;
    @NonNull public String name;
    @NonNull public String host;          // MagicDNS 或 100.x，或 127.0.0.1
    public int connectorPort;
    @NonNull public String sshUser;
    public int sshPort;
    @NonNull public Auth sshAuth;
    /** 在线探测结果缓存；不持久化。 */
    public transient boolean onlineKnown;
    public transient boolean online;

    public Node(@NonNull String id, @NonNull String name, @NonNull String host,
                int connectorPort, @NonNull String sshUser, int sshPort, @NonNull Auth auth) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.connectorPort = connectorPort;
        this.sshUser = sshUser;
        this.sshPort = sshPort;
        this.sshAuth = auth;
    }

    public static Node localDefault() {
        return new Node("local", "本机", "127.0.0.1", 8945, "u0_a", 8022, Auth.PASSWORD);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("host", host);
        o.put("connectorPort", connectorPort);
        o.put("sshUser", sshUser);
        o.put("sshPort", sshPort);
        o.put("sshAuth", sshAuth.name());
        return o;
    }

    @NonNull
    public static Node fromJson(@NonNull JSONObject o) throws JSONException {
        String auth = o.optString("sshAuth", Auth.KEY.name());
        Auth a;
        try {
            a = Auth.valueOf(auth);
        } catch (Exception e) {
            a = Auth.KEY;
        }
        return new Node(
                o.getString("id"),
                o.optString("name", o.getString("id")),
                o.optString("host", "127.0.0.1"),
                o.optInt("connectorPort", 8945),
                o.optString("sshUser", ""),
                o.optInt("sshPort", 22),
                a);
    }

    public boolean isLoopback() {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    @NonNull
    public String connectorUrlHint() {
        return host + ":" + connectorPort;
    }

    @Override
    public String toString() {
        return name + " (" + connectorUrlHint() + ")";
    }
}
