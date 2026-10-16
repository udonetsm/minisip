package ru.minisip.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP-клиент для GitHub Releases. GET строки (JSON) и download-with-progress.
 * HttpURLConnection, org.json — только SDK.
 */
final class Http {

    private Http() {
    }

    /**
     * GET строки. На любой ответ != 200 бросает Code.
     */
    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(UpdateConfig.TIMEOUT_MS);
            c.setReadTimeout(UpdateConfig.TIMEOUT_MS);
            c.setRequestProperty("User-Agent", UpdateConfig.USER_AGENT);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) throw new Code(code, "HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * GET в файл. Следует редиректам. На ответ != 200 бросает Code.
     */
    static void download(String url, File dst, Progress p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(UpdateConfig.TIMEOUT_MS);
            c.setReadTimeout(UpdateConfig.TIMEOUT_MS);
            c.setRequestProperty("User-Agent", UpdateConfig.USER_AGENT);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) throw new Code(code, "HTTP " + code);
            long total = c.getContentLengthLong();
            long done = 0L;
            int last = -1;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pc = (int) (done * 100L / total);
                        if (pc != last) {
                            last = pc;
                            p.onProgress(pc);
                        }
                    } else if (last < 0) {
                        last = 0;
                        p.onProgress(-1);
                    }
                }
                out.flush();
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Прогресс скачивания, 0..100. percent=-1 — длина неизвестна.
     */
    interface Progress {
        void onProgress(int percent);
    }

    /**
     * Ответ не 200: код сохраняем, чтобы вызывающий отличил 404 (пустой канал) от прочих.
     */
    static final class Code extends IOException {
        final int code;

        Code(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}
