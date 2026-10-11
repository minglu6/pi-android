package dev.pi.android.termux;

/** Subset of Termux RUN_COMMAND constants (avoid depending on termux-shared AAR). */
public final class TermuxConstants {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_SERVICE =
            "com.termux.app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    public static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    public static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";

    /** Result bundle key on the PendingIntent extras. */
    public static final String EXTRA_PLUGIN_RESULT_BUNDLE = "result";
    public static final String EXTRA_STDOUT = "stdout";
    public static final String EXTRA_STDERR = "stderr";
    public static final String EXTRA_EXIT_CODE = "exitCode";
    public static final String EXTRA_ERR = "err";
    public static final String EXTRA_ERRMSG = "errmsg";

    /** Termux prefix path used in RUN_COMMAND_PATH. */
    public static final String PREFIX_BASH = "/data/data/com.termux/files/usr/bin/bash";

    private TermuxConstants() {}
}
