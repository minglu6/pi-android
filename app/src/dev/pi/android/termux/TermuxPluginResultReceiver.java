package dev.pi.android.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Receives Termux RUN_COMMAND PendingIntent results and fans out to
 * {@link ConnectorController}.
 */
public final class TermuxPluginResultReceiver extends BroadcastReceiver {
    public static final String ACTION_RESULT = "dev.pi.android.TERMUX_RUN_RESULT";
    public static final String EXTRA_OP = "op";

    private static final String TAG = "TermuxResult";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String op = intent.getStringExtra(EXTRA_OP);
        Bundle result = intent.getBundleExtra(TermuxConstants.EXTRA_PLUGIN_RESULT_BUNDLE);
        String stdout = text(result, TermuxConstants.EXTRA_STDOUT);
        String stderr = text(result, TermuxConstants.EXTRA_STDERR);
        String errmsg = text(result, TermuxConstants.EXTRA_ERRMSG);
        int exitCode = result != null ? result.getInt(TermuxConstants.EXTRA_EXIT_CODE, -1) : -1;
        int err = result != null ? result.getInt(TermuxConstants.EXTRA_ERR, 0) : 0;
        Log.i(TAG, "op=" + op + " exit=" + exitCode + " err=" + err
                + " stdoutLen=" + (stdout == null ? 0 : stdout.length()));
        ConnectorDebugLog.log(context, "PendingIntent result op=" + op
                + " exit=" + exitCode + " err=" + err
                + " errmsg=" + (errmsg == null ? "" : errmsg));
        ConnectorController.onTermuxResult(context.getApplicationContext(),
                op, exitCode, err, errmsg, stdout, stderr);
    }

    @Nullable
    private static String text(@Nullable Bundle b, String key) {
        if (b == null) return null;
        Object v = b.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
