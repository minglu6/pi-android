package dev.pi.android.termux;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Fire Termux {@code RUN_COMMAND} in the background only.
 * Never brings Termux UI to the foreground — keeps the user in Pi
 * (except explicit {@link #openTermuxApp} fallback).
 */
public final class TermuxRunCommand {
    private static final String TAG = "TermuxRun";
    private static int requestSeq;

    private TermuxRunCommand() {}

    public static boolean isTermuxInstalled(@NonNull Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(TermuxConstants.TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasRunCommandPermission(@NonNull Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, TermuxConstants.PERMISSION_RUN_COMMAND)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Bring Termux to the foreground (OEM autostart / background-launch blocked).
     * Only use after RUN_COMMAND result timeout or explicit user action.
     */
    public static boolean openTermuxApp(@NonNull Context ctx) {
        try {
            Intent launch = ctx.getPackageManager()
                    .getLaunchIntentForPackage(TermuxConstants.TERMUX_PACKAGE);
            if (launch == null) {
                ConnectorDebugLog.log(ctx, "openTermux: no launch intent");
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.getApplicationContext().startActivity(launch);
            ConnectorDebugLog.log(ctx, "openTermux: started");
            return true;
        } catch (Exception e) {
            ConnectorDebugLog.log(ctx, "openTermux failed: " + e.getMessage());
            Log.w(TAG, "open Termux failed", e);
            return false;
        }
    }

    /**
     * Run {@code bash -lc <script>} via Termux {@link TermuxConstants#RUN_COMMAND_SERVICE}
     * with {@code RUN_COMMAND_BACKGROUND=true}. Does not launch Termux activities.
     *
     * @param opTag opaque tag echoed to the result receiver (e.g. {@code start}, {@code update})
     */
    public static boolean runBashLc(@NonNull Context ctx, @NonNull String script,
                                    @NonNull String opTag, @Nullable String label) {
        ConnectorDebugLog.log(ctx, "RUN_COMMAND begin op=" + opTag
                + " scriptLen=" + script.length());
        if (!isTermuxInstalled(ctx)) {
            ConnectorDebugLog.log(ctx, "RUN_COMMAND abort: Termux not installed");
            Log.w(TAG, "Termux not installed");
            return false;
        }
        if (!hasRunCommandPermission(ctx)) {
            ConnectorDebugLog.log(ctx, "RUN_COMMAND abort: permission missing");
            Log.w(TAG, "RUN_COMMAND permission missing");
            return false;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                TermuxConstants.TERMUX_PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE));
        intent.setAction(TermuxConstants.ACTION_RUN_COMMAND);
        // Absolute path required by Termux plugin API.
        intent.putExtra(TermuxConstants.EXTRA_COMMAND_PATH, TermuxConstants.PREFIX_BASH);
        intent.putExtra(TermuxConstants.EXTRA_ARGUMENTS, new String[]{"-lc", script});
        intent.putExtra(TermuxConstants.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME);
        // Must stay true — otherwise Termux opens a session Activity and steals focus.
        intent.putExtra(TermuxConstants.EXTRA_BACKGROUND, true);
        intent.putExtra(TermuxConstants.EXTRA_COMMAND_LABEL,
                label != null ? label : "Pi Android");
        intent.putExtra(TermuxConstants.EXTRA_PENDING_INTENT, resultPendingIntent(ctx, opTag));
        ConnectorDebugLog.log(ctx, "RUN_COMMAND extras: path=" + TermuxConstants.PREFIX_BASH
                + " workdir=" + TermuxConstants.TERMUX_HOME
                + " background=true component="
                + TermuxConstants.TERMUX_PACKAGE + "/" + TermuxConstants.RUN_COMMAND_SERVICE);
        try {
            // RunCommandService is a Termux FGS; startForegroundService when available
            // so Android 8+ can start it without bringing Termux UI forward.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(ctx.getApplicationContext(), intent);
                ConnectorDebugLog.log(ctx, "RUN_COMMAND dispatched via startForegroundService");
            } else {
                ctx.getApplicationContext().startService(intent);
                ConnectorDebugLog.log(ctx, "RUN_COMMAND dispatched via startService");
            }
            return true;
        } catch (Exception e) {
            ConnectorDebugLog.log(ctx, "startForegroundService failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            Log.w(TAG, "start RunCommandService failed", e);
            // Fallback: plain startService (some Termux builds accept this while already alive).
            try {
                ctx.getApplicationContext().startService(intent);
                ConnectorDebugLog.log(ctx, "RUN_COMMAND fallback startService ok");
                return true;
            } catch (Exception e2) {
                ConnectorDebugLog.log(ctx, "startService failed: " + e2.getClass().getSimpleName()
                        + ": " + e2.getMessage());
                Log.w(TAG, "startService RUN_COMMAND failed", e2);
                return false;
            }
        }
    }

    @NonNull
    private static PendingIntent resultPendingIntent(@NonNull Context ctx, @NonNull String opTag) {
        int code = ++requestSeq;
        Intent broadcast = new Intent(ctx, TermuxPluginResultReceiver.class);
        broadcast.setAction(TermuxPluginResultReceiver.ACTION_RESULT);
        broadcast.putExtra(TermuxPluginResultReceiver.EXTRA_OP, opTag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT;
        // Termux fills result extras into the PendingIntent — must be mutable on API 31+.
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), code, broadcast, flags);
    }
}
