package dev.pi.android.termux;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Always-on connector / RUN_COMMAND trace in {@code files/connector-debug.log}.
 * Separate from BridgeService's gated {@code debug.log}.
 */
public final class ConnectorDebugLog {
    private static final String TAG = "ConnectorDbg";
    private static final String FILE = "connector-debug.log";

    private ConnectorDebugLog() {}

    public static void log(@Nullable Context ctx, @NonNull String msg) {
        Log.i(TAG, msg);
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + msg + "\n";
            try (FileOutputStream fos = app.openFileOutput(FILE, Context.MODE_APPEND)) {
                fos.write(line.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "write failed", e);
        }
    }

    @NonNull
    public static String readTail(@NonNull Context ctx, int maxChars) {
        try {
            java.io.File f = new java.io.File(ctx.getApplicationContext().getFilesDir(), FILE);
            if (!f.isFile()) return "(尚无 connector-debug.log)";
            long len = f.length();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                if (len > maxChars) {
                    long skip = len - maxChars;
                    while (skip > 0) {
                        long n = in.skip(skip);
                        if (n <= 0) break;
                        skip -= n;
                    }
                }
                byte[] buf = new byte[(int) Math.min(len, maxChars)];
                int n = in.read(buf);
                if (n <= 0) return "(空)";
                return new String(buf, 0, n, "UTF-8");
            }
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }

    public static void clear(@NonNull Context ctx) {
        try {
            ctx.getApplicationContext().deleteFile(FILE);
        } catch (Exception ignored) {
        }
    }
}
