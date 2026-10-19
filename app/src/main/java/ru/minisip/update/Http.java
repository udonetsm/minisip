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
 * HTTP client for GitHub Releases JSON API and file downloads.
 */
final class Http {

    private Http() {
    }

    /**
     * Executes HTTP GET request. Throws Code exception if status is not 200.
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
     * Downloads file to destination with progress updates.
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
     * Download progress listener callback (0..100).
     */
    interface Progress {
        void onProgress(int percent);
    }

    /**
     * HTTP status code exception container.
     */
    static final class Code extends IOException {
        final int code;

        Code(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }
}
