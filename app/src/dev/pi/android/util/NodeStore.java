package dev.pi.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.pi.android.model.Node;

/**
 * 节点列表 + 当前激活节点。元数据在 SharedPreferences；敏感字段走 SecretStore。
 * 首次加载时用旧「连接器」host/port/token 迁移出「本机」节点。
 */
public final class NodeStore {
    private static final String PREF = "pi_nodes";
    private static final String K_LIST = "nodes_json";
    private static final String K_ACTIVE = "active_id";
    private static final String LEGACY = "pi_bridge";

    public interface Listener {
        void onNodesChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private NodeStore() {}

    public static void addListener(@NonNull Listener l) { listeners.add(l); }
    public static void removeListener(@NonNull Listener l) { listeners.remove(l); }

    private static void notifyChanged() {
        for (Listener l : listeners) {
            try { l.onNodesChanged(); } catch (Exception ignored) {}
        }
    }

    @NonNull
    public static List<Node> list(@NonNull Context ctx) {
        ensureMigrated(ctx);
        List<Node> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(Node.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            out.clear();
        }
        if (out.isEmpty()) {
            Node local = Node.localDefault();
            out.add(local);
            saveAll(ctx, out);
            setActiveId(ctx, local.id);
        }
        return out;
    }

    @Nullable
    public static Node get(@NonNull Context ctx, @Nullable String id) {
        if (id == null) return null;
        for (Node n : list(ctx)) {
            if (n.id.equals(id)) return n;
        }
        return null;
    }

    @NonNull
    public static Node active(@NonNull Context ctx) {
        ensureMigrated(ctx);
        String id = prefs(ctx).getString(K_ACTIVE, "local");
        Node n = get(ctx, id);
        if (n != null) return n;
        List<Node> all = list(ctx);
        return all.get(0);
    }

    public static void setActive(@NonNull Context ctx, @NonNull Node node) {
        setActiveId(ctx, node.id);
        // 同步到旧连接器字段，BridgeService / Settings 继续读 host/port/token
        applyToLegacyConnector(ctx, node);
        notifyChanged();
    }

    private static void setActiveId(Context ctx, String id) {
        prefs(ctx).edit().putString(K_ACTIVE, id).apply();
    }

    public static void upsert(@NonNull Context ctx, @NonNull Node node) {
        List<Node> all = list(ctx);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(node.id)) {
                all.set(i, node);
                found = true;
                break;
            }
        }
        if (!found) all.add(node);
        saveAll(ctx, all);
        if (active(ctx).id.equals(node.id)) applyToLegacyConnector(ctx, node);
        notifyChanged();
    }

    public static void delete(@NonNull Context ctx, @NonNull String id) {
        if ("local".equals(id)) return; // 本机节点不可删
        List<Node> all = list(ctx);
        Iterator<Node> it = all.iterator();
        while (it.hasNext()) {
            if (it.next().id.equals(id)) it.remove();
        }
        saveAll(ctx, all);
        SecretStore.remove(ctx, SecretStore.tokenKey(id));
        SecretStore.remove(ctx, SecretStore.sshPasswordKey(id));
        SecretStore.remove(ctx, SecretStore.sshPrivateKeyKey(id));
        SecretStore.remove(ctx, SecretStore.sshPublicKeyKey(id));
        SecretStore.remove(ctx, SecretStore.hostKeyFpKey(id));
        if (id.equals(prefs(ctx).getString(K_ACTIVE, ""))) {
            setActive(ctx, all.get(0));
        } else {
            notifyChanged();
        }
    }

    @NonNull
    public static String newId() {
        return "n_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 从 tailscale status --json 导入 Peer（仅填 host/name，端口默认）。 */
    public static int importTailscaleStatus(@NonNull Context ctx, @NonNull String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject peer = root.optJSONObject("Peer");
        if (peer == null) peer = root.optJSONObject("Peers");
        int added = 0;
        if (peer == null) {
            // 也可能是数组
            JSONArray arr = root.optJSONArray("Peer");
            if (arr == null) return 0;
            for (int i = 0; i < arr.length(); i++) {
                if (importOnePeer(ctx, arr.getJSONObject(i))) added++;
            }
            return added;
        }
        Iterator<String> keys = peer.keys();
        while (keys.hasNext()) {
            JSONObject p = peer.getJSONObject(keys.next());
            if (importOnePeer(ctx, p)) added++;
        }
        return added;
    }

    private static boolean importOnePeer(Context ctx, JSONObject p) throws Exception {
        boolean online = p.optBoolean("Online", true);
        if (!online) return false;
        String dns = p.optString("DNSName", "");
        if (dns.endsWith(".")) dns = dns.substring(0, dns.length() - 1);
        String hostName = p.optString("HostName", dns);
        JSONArray ips = p.optJSONArray("TailscaleIPs");
        String ip = ips != null && ips.length() > 0 ? ips.optString(0) : "";
        String host = !dns.isEmpty() ? dns : ip;
        if (host.isEmpty()) return false;
        // 跳过已存在同 host
        for (Node n : list(ctx)) {
            if (n.host.equals(host) || n.host.equals(ip) || n.host.equals(dns)) return false;
        }
        String id = newId();
        String name = hostName.isEmpty() ? host : hostName;
        Node node = new Node(id, name, host, 8945, System.getProperty("user.name", "user"), 22, Node.Auth.KEY);
        upsert(ctx, node);
        return true;
    }

    public static void applyToLegacyConnector(@NonNull Context ctx, @NonNull Node node) {
        String token = SecretStore.get(ctx, SecretStore.tokenKey(node.id));
        if (token == null) token = "";
        ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit()
                .putString("host", node.host)
                .putInt("port", node.connectorPort)
                .putString("token", token)
                .apply();
    }

    private static void ensureMigrated(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.contains(K_LIST) && !p.getString(K_LIST, "[]").equals("[]")) return;
        SharedPreferences legacy = ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE);
        String host = legacy.getString("host", "127.0.0.1");
        int port = legacy.getInt("port", 8945);
        String token = legacy.getString("token", "");
        Node local = new Node("local", "本机", host == null ? "127.0.0.1" : host,
                port, "u0_a", 8022, Node.Auth.PASSWORD);
        List<Node> all = new ArrayList<>();
        all.add(local);
        saveAll(ctx, all);
        setActiveId(ctx, local.id);
        if (token != null && !token.isEmpty()) {
            SecretStore.put(ctx, SecretStore.tokenKey(local.id), token);
        }
    }

    private static void saveAll(Context ctx, List<Node> nodes) {
        JSONArray arr = new JSONArray();
        for (Node n : nodes) {
            try { arr.put(n.toJson()); } catch (Exception ignored) {}
        }
        prefs(ctx).edit().putString(K_LIST, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
