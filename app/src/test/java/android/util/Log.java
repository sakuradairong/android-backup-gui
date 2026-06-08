package android.util;

/**
 * Test-only stub for android.util.Log.
 * Prevents RuntimeException("Stub!") from android.jar during JVM unit tests.
 */
public final class Log {
    public static int v(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static int wtf(String tag, String msg) { return 0; }
    public static int wtf(String tag, String msg, Throwable tr) { return 0; }
    public static String getStackTraceString(Throwable tr) { return ""; }
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int println(int priority, String tag, String msg) { return 0; }
}
