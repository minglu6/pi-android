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
 * Fire Termux {@code RUN_COMMAND} in the background and optionally deliver
 * stdout/exit via {@link TermuxPluginResultReceiver}.
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

    /** Bring Termux to foreground so its services can start (needed after force-stop). */
    public static boolean launchTermuxApp(@NonNull Context ctx) {
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(TermuxConstants.TERMUX_PACKAGE);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(launch);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "launch Termux failed", e);
            return false;
        }
    }

    /**
     * Run {@code bash -lc <script>} in Termux background.
     *
     * @param opTag opaque tag echoed to the result receiver (e.g. {@code start}, {@code update})
     */
    public static boolean runBashLc(@NonNull Context ctx, @NonNull String script,
                                    @NonNull String opTag, @Nullable String label) {
        if (!isTermuxInstalled(ctx)) {
            Log.w(TAG, "Termux not installed");
            return false;
        }
        if (!hasRunCommandPermission(ctx)) {
            Log.w(TAG, "RUN_COMMAND permission missing");
            return false;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                TermuxConstants.TERMUX_PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE));
        intent.setAction(TermuxConstants.ACTION_RUN_COMMAND);
        intent.putExtra(TermuxConstants.EXTRA_COMMAND_PATH, TermuxConstants.PREFIX_BASH);
        intent.putExtra(TermuxConstants.EXTRA_ARGUMENTS, new String[]{"-lc", script});
        intent.putExtra(TermuxConstants.EXTRA_BACKGROUND, true);
        intent.putExtra(TermuxConstants.EXTRA_COMMAND_LABEL,
                label != null ? label : "Pi Android");
        intent.putExtra(TermuxConstants.EXTRA_PENDING_INTENT, resultPendingIntent(ctx, opTag));
        try {
            ctx.startService(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startService RUN_COMMAND failed", e);
            return false;
        }
    }

    @NonNull
    private static PendingIntent resultPendingIntent(@NonNull Context ctx, @NonNull String opTag) {
        int code = ++requestSeq;
        Intent broadcast = new Intent(ctx, TermuxPluginResultReceiver.class);
        broadcast.setAction(TermuxPluginResultReceiver.ACTION_RESULT);
        broadcast.putExtra(TermuxPluginResultReceiver.EXTRA_OP, opTag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), code, broadcast, flags);
    }
}
