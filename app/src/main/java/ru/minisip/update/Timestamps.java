package ru.minisip.update;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * ISO 8601 из GitHub → миллисекунды UTC. Ошибка парсинга → 0.
 */
final class Timestamps {
    private Timestamps() {
    }

    static long parseIso8601(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        long ms = parse(iso, "yyyy-MM-dd'T'HH:mm:ss'Z'");
        if (ms == 0L) ms = parse(iso, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        return ms;
    }

    private static long parse(String iso, String pattern) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso);
            return d == null ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
