package dev.pi.android.termux;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import dev.pi.android.ui.SettingsActivity;

/**
 * Auto-start / update orchestration for the Termux connector.
 * Debounces start attempts and retries WebSocket connect with backoff.
 */
public final class ConnectorController {
    private static final String TAG = "ConnectorCtl";
    public static final String OP_START = "start";
    public static final String OP_UPDATE = "update";
    public static final String OP_DIAGNOSE = "diagnose";

    private static final long DEBOUNCE_MS = 45_000L;
    /** If Termux never returns a PendingIntent result (OEM block / dead process). */
    private static final long RESULT_TIMEOUT_MS = 12_000L;
    private static final long[] RETRY_DELAYS_MS = {1_500L, 3_000L, 6_000L, 12_000L};

    /** Detail prefix when RUN_COMMAND result never arrives — UI shows 打开 Termux. */
    public static final String DETAIL_RESULT_TIMEOUT = "RESULT_TIMEOUT:";

    public interface Listener {
        void onAutoStartBegan();

        void onAutoStartFinished(boolean ok, @Nullable String detail);

        void onUpdateProgress(@NonNull String line);

        void onUpdateFinished(boolean ok, int exitCode, @Nullable String stdout,
                              @Nullable String stderr, @Nullable String errmsg);

        /** Ask UI to call BridgeService.reconnect(). */
        void requestReconnect(@NonNull String reason);

        /** Optional: Settings diagnose finished. Default no-op via adapter not needed — implement. */
        default void onDiagnoseFinished(boolean ok, int exitCode, @Nullable String stdout,
                                        @Nullable String stderr, @Nullable String errmsg) {}
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static long lastStartAttemptAt;
    private static boolean startInFlight;
    private static int retryIndex;
    private static final AtomicInteger startGeneration = new AtomicInteger();
    @Nullable private static Runnable pendingResultTimeout;

    private ConnectorController() {}

    public static void addListener(@NonNull Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(@NonNull Listener l) {
        LISTENERS.remove(l);
    }

    /** Passive / refused WebSocket — auto-start with debounce (not an explicit tap). */
    public static void onConnectFailure(@NonNull Context ctx, @Nullable Throwable t) {
        if (!SettingsActivity.autoStartConnector(ctx)) return;
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

    /** Termux missing or RUN_COMMAND not granted (allow-external needs a live probe). */
    public static boolean needsSetup(@NonNull Context ctx) {
        return !TermuxRunCommand.isTermuxInstalled(ctx)
                || !TermuxRunCommand.hasRunCommandPermission(ctx);
    }

    public static boolean isResultTimeoutDetail(@Nullable String detail) {
        return detail != null && detail.startsWith(DETAIL_RESULT_TIMEOUT);
    }

    /**
     * Start/restart the local Termux connector in the background.
     *
     * @param force {@code true} for explicit user actions (重新连接 / 切到本机 / 测速失败) —
     *              bypasses the 45s debounce; {@code false} for passive resume / onFailure.
     * @return {@code true} if RUN_COMMAND was dispatched; {@code false} if skipped, needs
     *         setup, or send failed (listeners still get {@link Listener#onAutoStartFinished}).
     */
    public static boolean tryAutoStart(@NonNull Context ctx, boolean force) {
        Context app = ctx.getApplicationContext();
        if (!SettingsActivity.autoStartConnector(app)) {
            ConnectorDebugLog.log(app, "tryAutoStart skipped: auto-start off");
            return false;
        }
        if (!dev.pi.android.util.ConnectionProfiles.autoStartAllowed(app)) {
            ConnectorDebugLog.log(app, "tryAutoStart skipped: non-local profile");
            return false;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            if (startInFlight || now - lastStartAttemptAt < DEBOUNCE_MS) {
                ConnectorDebugLog.log(app, "tryAutoStart debounced inFlight=" + startInFlight);
                Log.i(TAG, "auto-start debounced");
                return false;
            }
        }
        if (!TermuxRunCommand.isTermuxInstalled(app)) {
            notifyAutoStartFinished(false, "未安装 Termux");
            return false;
        }
        if (!TermuxRunCommand.hasRunCommandPermission(app)) {
            notifyAutoStartFinished(false, "缺少 RUN_COMMAND 权限");
            return false;
        }
        if (!SettingsActivity.hasToken(app)) {
            notifyAutoStartFinished(false, "缺少连接 Token（请到设置填写）");
            return false;
        }
        cancelResultTimeout();
        lastStartAttemptAt = now;
        startInFlight = true;
        retryIndex = 0;
        int gen = startGeneration.incrementAndGet();
        notifyAutoStartBegan();
        String token = SettingsActivity.token(app);
        int port = SettingsActivity.port(app);
        String script = ConnectorScripts.startOrRestart(token, port);
        ConnectorDebugLog.log(app, "tryAutoStart force=" + force + " port=" + port
                + " gen=" + gen + " scriptLen=" + script.length());
        // Background-only: RUN_COMMAND_BACKGROUND=true via RunCommandService.
        // Do NOT launch Termux Activity — keep user in Pi.
        boolean ok = TermuxRunCommand.runBashLc(app, script, OP_START, "启动 Pi 连接器");
        if (!ok) {
            startInFlight = false;
            notifyAutoStartFinished(false, "无法向 Termux 发送 RUN_COMMAND");
            return false;
        }
        scheduleResultTimeout(app, gen, OP_START);
        return true;
    }

    public static boolean beginUpdate(@NonNull Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!TermuxRunCommand.isTermuxInstalled(app)) return false;
        if (!TermuxRunCommand.hasRunCommandPermission(app)) return false;
        cancelResultTimeout();
        ConnectorDebugLog.log(app, "beginUpdate");
        boolean ok = TermuxRunCommand.runBashLc(app, ConnectorScripts.updateAndRestart(),
                OP_UPDATE, "更新并重启连接器");
        if (ok) scheduleResultTimeout(app, startGeneration.get(), OP_UPDATE);
        return ok;
    }

    /**
     * Settings → 诊断: local checks + a live Termux probe via RUN_COMMAND.
     * Returns a human-readable preflight summary immediately; probe result arrives
     * asynchronously on {@link Listener#onDiagnoseFinished}.
     */
    @NonNull
    public static List<String> beginDiagnose(@NonNull Context ctx) {
        Context app = ctx.getApplicationContext();
        List<String> lines = new ArrayList<>();
        boolean installed = TermuxRunCommand.isTermuxInstalled(app);
        boolean perm = TermuxRunCommand.hasRunCommandPermission(app);
        boolean token = SettingsActivity.hasToken(app);
        boolean auto = SettingsActivity.autoStartConnector(app);
        boolean local = dev.pi.android.util.ConnectionProfiles.autoStartAllowed(app);
        int port = SettingsActivity.port(app);
        lines.add("Termux 已安装: " + (installed ? "是" : "否"));
        lines.add("RUN_COMMAND 权限: " + (perm ? "已授予" : "未授予"));
        lines.add("Token: " + (token ? "已配置" : "缺失"));
        lines.add("自动启动开关: " + (auto ? "开" : "关"));
        lines.add("当前配置: " + (local ? "本机 Termux" : "远程（不走 RUN_COMMAND）"));
        lines.add("端口: " + port);
        lines.add("组件: " + TermuxConstants.TERMUX_PACKAGE + "/"
                + TermuxConstants.RUN_COMMAND_SERVICE);
        lines.add("PATH: " + TermuxConstants.PREFIX_BASH);
        lines.add("WORKDIR: " + TermuxConstants.TERMUX_HOME);
        lines.add("BACKGROUND: true");
        ConnectorDebugLog.log(app, "diagnose preflight: " + lines);
        if (!installed || !perm) {
            lines.add("→ 请先完成「Termux 前置配置」");
            for (Listener l : LISTENERS) {
                l.onDiagnoseFinished(false, -1, join(lines), null, "前置条件不足");
            }
            return lines;
        }
        lines.add("→ 正在向 Termux 发送诊断命令…");
        cancelResultTimeout();
        boolean ok = TermuxRunCommand.runBashLc(app, ConnectorScripts.diagnose(port),
                OP_DIAGNOSE, "诊断 Pi 连接器");
        if (!ok) {
            lines.add("→ 发送失败（厂商可能拦截后台启动）");
            for (Listener l : LISTENERS) {
                l.onDiagnoseFinished(false, -1, join(lines), null, "无法发送 RUN_COMMAND");
            }
        } else {
            scheduleResultTimeout(app, startGeneration.incrementAndGet(), OP_DIAGNOSE);
        }
        return lines;
    }

    static void onTermuxResult(@NonNull Context ctx, @Nullable String op,
                               int exitCode, int err, @Nullable String errmsg,
                               @Nullable String stdout, @Nullable String stderr) {
        cancelResultTimeout();
        ConnectorDebugLog.log(ctx, "result op=" + op + " exit=" + exitCode + " err=" + err
                + " errmsg=" + safe(errmsg)
                + " stdoutLen=" + len(stdout) + " stderrLen=" + len(stderr));
        if (stdout != null && !stdout.isEmpty()) {
            for (String line : stdout.split("\n")) {
                if (!line.isEmpty()) ConnectorDebugLog.log(ctx, "stdout| " + line);
            }
        }
        if (stderr != null && !stderr.isEmpty()) {
            for (String line : stderr.split("\n")) {
                if (!line.isEmpty()) ConnectorDebugLog.log(ctx, "stderr| " + line);
            }
        }
        boolean allowExternalBlocked = looksLikeAllowExternalBlocked(errmsg, stderr, stdout);
        if (OP_DIAGNOSE.equals(op)) {
            boolean ok = isTermuxCommandOk(err, exitCode) && !allowExternalBlocked;
            for (Listener l : LISTENERS) {
                l.onDiagnoseFinished(ok, exitCode, stdout, stderr,
                        allowExternalBlocked ? allowExternalHint() : errmsg);
            }
            return;
        }
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
        String detail;
        if (ok) {
            detail = "连接器已启动，正在重连…";
        } else if (errmsg != null && !errmsg.isEmpty()) {
            detail = errmsg;
        } else if (stderr != null && !stderr.isEmpty()) {
            detail = trimDetail(stderr);
        } else {
            detail = "启动失败 exit=" + exitCode;
        }
        notifyAutoStartFinished(ok, detail);
        if (ok) scheduleReconnects(ctx, "start");
    }

    private static void scheduleResultTimeout(@NonNull Context app, int gen, @NonNull String op) {
        cancelResultTimeout();
        pendingResultTimeout = () -> {
            pendingResultTimeout = null;
            if (OP_START.equals(op) && gen != startGeneration.get()) return;
            ConnectorDebugLog.log(app, "RESULT_TIMEOUT op=" + op + " gen=" + gen
                    + " — Termux 可能未执行（自启动/后台限制）");
            if (OP_START.equals(op)) {
                startInFlight = false;
                notifyAutoStartFinished(false, DETAIL_RESULT_TIMEOUT
                        + "Termux 未响应 RUN_COMMAND。部分手机（OPPO/小米等）会拦截后台启动，"
                        + "请允许 Termux 自启动/后台运行，或先打开 Termux 再重试。");
            } else if (OP_UPDATE.equals(op)) {
                for (Listener l : LISTENERS) {
                    l.onUpdateFinished(false, -1, null, null,
                            DETAIL_RESULT_TIMEOUT + "Termux 未返回更新结果");
                }
            } else if (OP_DIAGNOSE.equals(op)) {
                for (Listener l : LISTENERS) {
                    l.onDiagnoseFinished(false, -1, null, null,
                            DETAIL_RESULT_TIMEOUT + "Termux 未返回诊断结果");
                }
            }
        };
        MAIN.postDelayed(pendingResultTimeout, RESULT_TIMEOUT_MS);
    }

    private static void cancelResultTimeout() {
        if (pendingResultTimeout != null) {
            MAIN.removeCallbacks(pendingResultTimeout);
            pendingResultTimeout = null;
        }
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

    private static int len(@Nullable String s) {
        return s == null ? 0 : s.length();
    }

    @NonNull
    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }

    @NonNull
    private static String trimDetail(@NonNull String stderr) {
        String t = stderr.trim();
        if (t.length() > 240) t = t.substring(0, 240) + "…";
        return t;
    }

    @NonNull
    private static String join(@NonNull List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
