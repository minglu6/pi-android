package android.content;
import java.util.HashMap;
import java.util.Map;
/** JVM-only fixture: named in-memory preferences; no Android runtime or filesystem. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> prefs = new HashMap<>();
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, k -> new MemoryPreferences());
    }
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, String> values = new HashMap<>();
        public String getString(String key, String fallback) {
            String value = values.get(key); return value == null ? fallback : value;
        }
        public Editor edit() {
            return new Editor() {
                final Map<String, String> pending = new HashMap<>();
                public Editor putString(String key, String value) { pending.put(key, value); return this; }
                public void apply() { values.putAll(pending); }
            };
        }
    }
}
