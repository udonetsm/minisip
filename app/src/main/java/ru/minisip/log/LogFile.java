package ru.minisip.log;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Сохраняет текстовый лог в Documents/minisip/.
 * Android 10+: MediaStore (без разрешений).
 * Android 9 и ниже: File API (нужно WRITE_EXTERNAL_STORAGE).
 * save() возвращает имя файла либо строку "error: ...".
 */
public final class LogFile {

    private LogFile() {}

    public static String save(Context ctx, String prefix, String text) {
        String name = prefix + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            return Build.VERSION.SDK_INT >= 29 ? modern(ctx, name, data) : legacy(name, data);
        } catch (Exception e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String modern(Context ctx, String name, byte[] data) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/MiniSIP/logs");
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return "error: cannot create file";
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) return "error: cannot open file";
            os.write(data);
        }
        return name;
    }

    private static String legacy(String name, byte[] data) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "MiniSIP/logs");
        if (!dir.exists() && !dir.mkdirs()) return "error: cannot create dir";
        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
            fos.write(data);
        }
        return name;
    }
}
