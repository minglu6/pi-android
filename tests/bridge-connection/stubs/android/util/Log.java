package android.util;

/** No logcat or log output is used as test evidence. */
public final class Log {
    public static int w(String tag, String message, Throwable error) { return 0; }
}
