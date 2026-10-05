package android.content;

public class Context {
    public static final String AUDIO_SERVICE = "audio";
    public static final String CONNECTIVITY_SERVICE = "connectivity";

    /** Тесты подменяют это поле, чтобы отдать нужную службу. */
    public static java.util.function.Function<String, Object> services =
            name -> new android.media.AudioManager();

    public Object getSystemService(String name) {
        return services.apply(name);
    }

    public Context getApplicationContext() { return this; }

    public String getPackageName() { return "ru.minisip"; }

    public ComponentName startService(Intent i) { return null; }

    public boolean stopService(Intent i) { return true; }
}
