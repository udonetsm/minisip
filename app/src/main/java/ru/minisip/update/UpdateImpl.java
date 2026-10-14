package ru.minisip.update;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import ru.minisip.log.LogSink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.File;

/**
 * Реализация проверки и установки обновлений. Проверка и скачивание — в фоновом потоке,
 * колбэки — в главном. Шаги 3–5 README добавляют check/download/install.
 */
final class UpdateImpl implements Update {

    private static final String TAG = "MiniSIP-Update";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LogSink sink;
    private volatile String channel;
    private volatile Listener persistent;  // постоянный слушатель (полоса в MainActivity)
    private volatile String assetUrl;      // browser_download_url APK из последнего успешного check()
    private volatile String assetName;     // tag_name для отображения в прогрессе

    UpdateImpl(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        SharedPreferences p = this.ctx.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        this.channel = p.getString(UpdateConfig.KEY_CHANNEL, UpdateConfig.CH_STABLE);
        cleanUpdates();
    }

    @Override
    public void setLogSink(LogSink s) {
        sink = s;
    }

    @Override
    public void setListener(Listener l) {
        persistent = l;
    }

    /** Событие уходит и постоянному слушателю (полоса), и локальному из check/download. */
    private void emit(Runnable toPersistent, Runnable toLocal) {
        Listener p = persistent;
        main.post(() -> {
            if (p != null) toPersistent.run();
            if (toLocal != null) toLocal.run();
        });
    }

    /** Одна точка журнала: logcat и панель лога. Секреты сюда не передавать. */
    private void log(String m) {
        Log.i(TAG, m);
        LogSink s = sink;
        if (s != null) s.log(m);
    }

    @Override
    public synchronized void setChannel(String c) {
        if (c == null) return;
        channel = c;
        ctx.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE)
                .edit().putString(UpdateConfig.KEY_CHANNEL, c).apply();
        log("channel: " + c);
    }

    @Override
    public synchronized String channel() {
        return channel;
    }

    @Override
    public void check(Listener l) {
        final String ch = channel();
        Thread t = new Thread(() -> checkIn(ch, l), "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** GET выбранного канала, парсинг, сравнение с lastUpdateTime. Под hasUpdate=true — download(). */
    private void checkIn(String ch, Listener l) {
        boolean stable = UpdateConfig.CH_STABLE.equals(ch);
        String url = stable ? UpdateConfig.STABLE : UpdateConfig.PRERELEASE;
        log("check: " + ch + " " + url);
        Result r = new Result();
        try {
            JSONObject rel = parse(Http.get(url), stable);
            r.tag = rel.optString("tag_name", "");
            r.releaseMs = Timestamps.parseIso8601(rel.optString("published_at", ""));
            r.changelog = rel.optString("body", "");
            assetUrl = pickAsset(rel);
            assetName = r.tag;
            if (assetUrl == null) throw new Http.Code(0, "no apk asset");
            long last = lastUpdateMs();
            r.hasUpdate = r.releaseMs > last;
            log("check ok: tag=" + r.tag + " ms=" + r.releaseMs + " last=" + last
                    + " hasUpdate=" + r.hasUpdate);
            final Result fr = r;
            emit(() -> { Listener p = persistent; if (p != null) p.onChecked(fr); },
                 () -> l.onChecked(fr));
            if (r.hasUpdate) download(l);
        } catch (Http.Code c) {
            log("check http " + c.code + ": " + c.getMessage());
            if (c.code == 404) {
                if (stable) {
                    r.noStable = true;
                    final Result fr = r;
                    emit(() -> { Listener p = persistent; if (p != null) p.onChecked(fr); },
                         () -> l.onChecked(fr));
                } else {
                    emit(() -> { Listener p = persistent; if (p != null) p.onError("релизов пока нет"); },
                         () -> l.onError("релизов пока нет"));
                }
            } else {
                final String msg = "HTTP " + c.code;
                emit(() -> { Listener p = persistent; if (p != null) p.onError(msg); },
                     () -> l.onError(msg));
            }
        } catch (IOException e) {
            log("check io: " + e.getMessage());
            final String msg = "сеть: " + e.getMessage();
            emit(() -> { Listener p = persistent; if (p != null) p.onError(msg); },
                 () -> l.onError(msg));
        } catch (Exception e) {
            log("check ex: " + e);
            final String msg = String.valueOf(e.getMessage());
            emit(() -> { Listener p = persistent; if (p != null) p.onError(msg); },
                 () -> l.onError(msg));
        }
    }

    /** stable-канал отдаёт объект, prerelease — массив; берём первый non-draft. Пусто → Code 404. */
    private static JSONObject parse(String body, boolean stable) throws Http.Code {
        try {
            if (stable) return new JSONObject(body);
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null || x.optBoolean("draft", false)) continue;
                return x;
            }
            throw new Http.Code(404, "no releases");
        } catch (Http.Code c) {
            throw c;
        } catch (Exception e) {
            throw new Http.Code(0, "bad json: " + e.getMessage());
        }
    }

    private long lastUpdateMs() {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Первый asset с расширением .apk. null — релиз без APK. */
    private static String pickAsset(JSONObject rel) {
        JSONArray a = rel.optJSONArray("assets");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String name = x.optString("name", "");
            if (name.toLowerCase().endsWith(".apk")) {
                return x.optString("browser_download_url", null);
            }
        }
        return null;
    }

    @Override
    public void download(Listener l) {
        final String url = assetUrl;
        final String tag = assetName;
        if (url == null) {
            emit(() -> { Listener p = persistent; if (p != null) p.onError("нет APK в релизе"); },
                 () -> l.onError("нет APK в релизе"));
            return;
        }
        Thread t = new Thread(() -> downloadIn(url, tag, l), "update-dl");
        t.setDaemon(true);
        t.start();
    }

    /** Скачивание APK в apkFile(). Прогресс — в onProgress, финал — в onDownloaded. */
    private void downloadIn(String url, String tag, Listener l) {
        log("download: " + tag + " " + url);
        File dst = apkFile();
        try {
            Http.download(url, dst, pc ->
                    emit(() -> { Listener p = persistent; if (p != null) p.onProgress(pc); },
                         () -> l.onProgress(pc)));
            log("download ok: " + dst.length() + " bytes -> " + dst.getAbsolutePath());
            final File f = dst;
            emit(() -> { Listener p = persistent; if (p != null) p.onDownloaded(f); },
                 () -> l.onDownloaded(f));
        } catch (IOException e) {
            log("download io: " + e.getMessage());
            final String msg = "скачивание: " + e.getMessage();
            emit(() -> { Listener p = persistent; if (p != null) p.onError(msg); },
                 () -> l.onError(msg));
        }
    }

    @Override
    public void install() {
        final File apk = apkFile();
        if (!apk.exists()) {
            log("install: no apk at " + apk.getAbsolutePath());
            return;
        }
        PackageManager pm = ctx.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {          // Android 8+: разрешение на установку из этого источника
            log("install: request REQUEST_INSTALL_PACKAGES");
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        }
        Uri uri = FileProvider.getUriForFile(ctx,
                ctx.getPackageName() + UpdateConfig.AUTHORITY_SUFFIX, apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        log("install: " + uri);
        ctx.startActivity(i);
    }

    /** При старте приложения: удаляем APK, оставшийся от прошлой установки/скачивания. */
    private void cleanUpdates() {
        File dir = new File(ctx.getExternalCacheDir(), "updates");
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        int n = 0;
        for (File f : files) if (f.delete()) n++;
        log("clean: removed " + n + " old apk(s)");
    }

    /** Файл APK в кэше. Создаётся по требованию на шаге 4. */
    @SuppressWarnings("unused")
    File apkFile() {
        File dir = new File(ctx.getExternalCacheDir(), "updates");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, "minisip.apk");
    }
}
