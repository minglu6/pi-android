package dev.pi.android.termux;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

import dev.pi.android.ui.SettingsActivity;

/**
 * Auto-start / update orchestration for the Termux connector.
 * Debounces start attempts and retries WebSocket connect with backoff.
 */
public final class ConnectorController {
    private static final String TAG = "ConnectorCtl";
    public static final String OP_START = "start";
    public static final String OP_UPDATE = "update";

    private static final long DEBOUNCE_MS = 45_000L;
    private static final long[] RETRY_DELAYS_MS = {1_500L, 3_000L, 6_000L, 12_000L};

    public interface Listener {
        void onAutoStartBegan();

        void onAutoStartFinished(boolean ok, @Nullable String detail);

        void onUpdateProgress(@NonNull String line);

        void onUpdateFinished(boolean ok, int exitCode, @Nullable String stdout,
                              @Nullable String stderr, @Nullable String errmsg);

        /** Ask UI to call BridgeService.reconnect(). */
        void requestReconnect(@NonNull String reason);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static long lastStartAttemptAt;
    private static boolean startInFlight;
    private static int retryIndex;

    private ConnectorController() {}

    public static void addListener(@NonNull Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(@NonNull Listener l) {
        LISTENERS.remove(l);
    }

    /** Called from MainActivity when WebSocket fails with refused/timeout. */
    public static void onConnectFailure(@NonNull Context ctx, @Nullable Throwable t) {
        if (!SettingsActivity.autoStartConnector(ctx)) return;
        // Termux auto-start only for local loopback profiles.
        if (!dev.pi.android.util.ConnectionProfiles.autoStartAllowed(ctx)) return;
        if (!isRefusedOrTimeout(t)) return;
        tryAutoStart(ctx, false);
    }

    public static boolean isRefusedOrTimeout(@Nullable Throwable t) {
        if (t == null) return true; // offline status without throwable still may mean down
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        return msg.contains("refused") || msg.contains("econnrefused")
                || msg.contains("failed to connect") || msg.contains("timeout")
                || msg.contains("timed out") || msg.contains("unreachable");
    }

    /**
     * @param force ignore debounce (e.g. user tapped 重新连接 while auto-start on)
     */
    public static void tryAutoStart(@NonNull Context ctx, boolean force) {
        Context app = ctx.getApplicationContext();
        if (!SettingsActivity.autoStartConnector(app)) return;
        if (!dev.pi.android.util.ConnectionProfiles.autoStartAllowed(app)) return;
        long now = System.currentTimeMillis();
        if (!force && (startInFlight || now - lastStartAttemptAt < DEBOUNCE_MS)) {
            Log.i(TAG, "auto-start debounced");
            return;
        }
        if (!TermuxRunCommand.isTermuxInstalled(app)) {
            notifyAutoStartFinished(false, "未安装 Termux");
            return;
        }
        if (!TermuxRunCommand.hasRunCommandPermission(app)) {
            notifyAutoStartFinished(false, "缺少 RUN_COMMAND 权限");
            return;
        }
        lastStartAttemptAt = now;
        startInFlight = true;
        retryIndex = 0;
        notifyAutoStartBegan();
        // Background-only: RUN_COMMAND_BACKGROUND=true via RunCommandService.
        // Do NOT launch Termux Activity — keep user in Pi.
        boolean ok = TermuxRunCommand.runBashLc(app, ConnectorScripts.startOrRestart(),
                OP_START, "启动 Pi 连接器");
        if (!ok) {
            startInFlight = false;
            notifyAutoStartFinished(false, "无法向 Termux 发送 RUN_COMMAND");
        }
    }

    public static boolean beginUpdate(@NonNull Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!TermuxRunCommand.isTermuxInstalled(app)) return false;
        if (!TermuxRunCommand.hasRunCommandPermission(app)) return false;
        // Background-only; never bring Termux UI forward.
        return TermuxRunCommand.runBashLc(app, ConnectorScripts.updateAndRestart(),
                OP_UPDATE, "更新并重启连接器");
    }

    static void onTermuxResult(@NonNull Context ctx, @Nullable String op,
                               int exitCode, int err, @Nullable String errmsg,
                               @Nullable String stdout, @Nullable String stderr) {
        boolean allowExternalBlocked = looksLikeAllowExternalBlocked(errmsg, stderr, stdout);
        if (OP_UPDATE.equals(op)) {
            boolean ok = isTermuxCommandOk(err, exitCode) && !allowExternalBlocked;
            for (Listener l : LISTENERS) {
                l.onUpdateFinished(ok, exitCode, stdout, stderr,
                        allowExternalBlocked ? allowExternalHint() : errmsg);
            }
            if (ok) scheduleReconnects(ctx, "update");
            return;
        }
        // start
        startInFlight = false;
        if (allowExternalBlocked) {
            notifyAutoStartFinished(false, allowExternalHint());
            return;
        }
        boolean ok = isTermuxCommandOk(err, exitCode);
        notifyAutoStartFinished(ok, ok ? "连接器已启动，正在重连…" :
                (errmsg != null && !errmsg.isEmpty() ? errmsg
                        : (stderr != null && !stderr.isEmpty() ? stderr : "启动失败 exit=" + exitCode)));
        if (ok) scheduleReconnects(ctx, "start");
    }

    /** Termux plugin: {@code err == Activity.RESULT_OK (-1)} means no plugin error. */
    private static boolean isTermuxCommandOk(int err, int exitCode) {
        if (err != -1 && err != 0) return false;
        return exitCode == 0;
    }

    private static void scheduleReconnects(@NonNull Context ctx, @NonNull String reason) {
        retryIndex = 0;
        scheduleNextRetry(ctx.getApplicationContext(), reason);
    }

    private static void scheduleNextRetry(@NonNull Context app, @NonNull String reason) {
        if (retryIndex >= RETRY_DELAYS_MS.length) return;
        long delay = RETRY_DELAYS_MS[retryIndex++];
        MAIN.postDelayed(() -> {
            for (Listener l : LISTENERS) l.requestReconnect(reason + "#" + retryIndex);
            scheduleNextRetry(app, reason);
        }, delay);
    }

    private static boolean looksLikeAllowExternalBlocked(@Nullable String errmsg,
                                                         @Nullable String stderr,
                                                         @Nullable String stdout) {
        String blob = ((errmsg == null ? "" : errmsg) + " "
                + (stderr == null ? "" : stderr) + " "
                + (stdout == null ? "" : stdout)).toLowerCase();
        return blob.contains("allow-external-apps")
                || blob.contains("external apps")
                || blob.contains("not allowed to run");
    }

    @NonNull
    public static String allowExternalHint() {
        return "echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings";
    }

    private static void notifyAutoStartBegan() {
        for (Listener l : LISTENERS) l.onAutoStartBegan();
    }

    private static void notifyAutoStartFinished(boolean ok, @Nullable String detail) {
        for (Listener l : LISTENERS) l.onAutoStartFinished(ok, detail);
    }

    /** Last known connector version from prefs (filled by get_version RPC). */
    public static void saveConnectorVersion(@NonNull Context ctx, @Nullable String sha,
                                            @Nullable String date) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences("pi_connector", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (sha != null) e.putString("sha", sha);
        if (date != null) e.putString("date", date);
        e.apply();
    }

    @NonNull
    public static String connectorVersionLabel(@NonNull Context ctx) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences("pi_connector", Context.MODE_PRIVATE);
        String sha = p.getString("sha", null);
        String date = p.getString("date", null);
        if (sha == null || sha.isEmpty() || "unknown".equals(sha)) {
            return "未知";
        }
        if (date == null || date.isEmpty()) return sha;
        return sha + " · " + date;
    }
}
