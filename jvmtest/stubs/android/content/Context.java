package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import android.app.NotificationManager;
import android.os.PowerManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;

public class Context {
    public static final String AUDIO_SERVICE = "audio";
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final String POWER_SERVICE = "power";
    public static final int MODE_PRIVATE = 0;

    public static java.util.function.Function<String, Object> services =
            name -> new AudioManager();

    public Object getSystemService(String name) {
        return services.apply(name);
    }

    public <T> T getSystemService(Class<T> serviceClass) {
        if (serviceClass == NotificationManager.class) return (T) new NotificationManager();
        if (serviceClass == PowerManager.class) return (T) new PowerManager();
        if (serviceClass == AudioManager.class) return (T) new AudioManager();
        if (serviceClass == ConnectivityManager.class) return (T) new ConnectivityManager();
        return null;
    }

    public Context getApplicationContext() { return this; }
    public String getPackageName() { return "ru.minisip"; }
    public ComponentName startService(Intent i) { return null; }
    public boolean stopService(Intent i) { return true; }

    private static final Map<String, SharedPreferences> prefsMap = new HashMap<>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefsMap.computeIfAbsent(name, k -> new InMemorySharedPreferences());
    }

    public static class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<>();

        public Map<String, ?> getAll() { return new HashMap<>(map); }
        public String getString(String k, String def) { Object v = map.get(k); return v instanceof String ? (String) v : def; }
        public Set<String> getStringSet(String k, Set<String> def) { Object v = map.get(k); return v instanceof Set ? (Set<String>) v : def; }
        public int getInt(String k, int def) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : def; }
        public long getLong(String k, long def) { Object v = map.get(k); return v instanceof Long ? (Long) v : def; }
        public float getFloat(String k, float def) { Object v = map.get(k); return v instanceof Float ? (Float) v : def; }
        public boolean getBoolean(String k, boolean def) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : def; }
        public boolean contains(String k) { return map.containsKey(k); }
        public Editor edit() { return new EditorImpl(this); }

        private static class EditorImpl implements Editor {
            private final InMemorySharedPreferences p;
            private final Map<String, Object> batch = new HashMap<>();
            private boolean clear;

            EditorImpl(InMemorySharedPreferences p) { this.p = p; }

            public Editor putString(String k, String v) { batch.put(k, v); return this; }
            public Editor putStringSet(String k, Set<String> v) { batch.put(k, v); return this; }
            public Editor putInt(String k, int v) { batch.put(k, v); return this; }
            public Editor putLong(String k, long v) { batch.put(k, v); return this; }
            public Editor putFloat(String k, float v) { batch.put(k, v); return this; }
            public Editor putBoolean(String k, boolean v) { batch.put(k, v); return this; }
            public Editor remove(String k) { batch.put(k, null); return this; }
            public Editor clear() { clear = true; batch.clear(); return this; }
            public boolean commit() { apply(); return true; }
            public void apply() {
                synchronized (p.map) {
                    if (clear) p.map.clear();
                    for (Map.Entry<String, Object> e : batch.entrySet()) {
                        if (e.getValue() == null) p.map.remove(e.getKey());
                        else p.map.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
    }
}
