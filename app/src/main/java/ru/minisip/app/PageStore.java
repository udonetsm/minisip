package ru.minisip.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Preference storage for settings pages: manages active profile count and configuration state.
 * Profile 0 reads "cfg" for backwards compatibility, while subsequent profiles read "cfg1", "cfg2", etc.
 */
final class PageStore {

    private PageStore() {
    }

    static SharedPreferences prefs(Context c, int page) {
        return c.getSharedPreferences(page == 0 ? "cfg" : "cfg" + page, Context.MODE_PRIVATE);
    }

    /**
     * Returns total created pages (minimum 1).
     */
    static int count(Context c) {
        return Math.max(1, c.getSharedPreferences("pages", Context.MODE_PRIVATE).getInt("count", 1));
    }

    static void setCount(Context c, int n) {
        c.getSharedPreferences("pages", Context.MODE_PRIVATE).edit().putInt("count", Math.max(1, n)).apply();
    }

    /**
     * Returns true if server host and login are configured for the specified page.
     */
    static boolean configured(Context c, int page) {
        SharedPreferences p = prefs(c, page);
        return !p.getString("server", "").trim().isEmpty() && !p.getString("login", "").trim().isEmpty();
    }

    /**
     * Reads blind transfer contacts list for specified page. Format: "login\tname" separated by '\n'.
     */
    static List<Refer> refers(Context c, int page) {
        List<Refer> out = new ArrayList<>();
        String raw = prefs(c, page).getString("refers", "");
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            out.add(t < 0 ? new Refer(line, "") : new Refer(line.substring(0, t), line.substring(t + 1)));
        }
        return out;
    }

    static void setRefers(Context c, int page, List<Refer> list) {
        StringBuilder sb = new StringBuilder();
        for (Refer r : list) {
            if (r.login.isEmpty()) continue;
            sb.append(r.login).append('\t').append(r.name).append('\n');
        }
        prefs(c, page).edit().putString("refers", sb.toString()).apply();
    }

    /**
     * Deletes specified profile page and left-shifts all subsequent SharedPreferences files.
     */
    static void deletePage(Context c, int pageToDelete) {
        int n = count(c);
        if (n <= 1 || pageToDelete < 0 || pageToDelete >= n) return;

        for (int i = pageToDelete; i < n - 1; i++) {
            SharedPreferences from = prefs(c, i + 1);
            SharedPreferences to = prefs(c, i);
            to.edit().clear().apply();
            SharedPreferences.Editor ed = to.edit();
            for (Map.Entry<String, ?> e : from.getAll().entrySet()) {
                Object val = e.getValue();
                if (val instanceof String) ed.putString(e.getKey(), (String) val);
                else if (val instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) val);
                else if (val instanceof Integer) ed.putInt(e.getKey(), (Integer) val);
                else if (val instanceof Float) ed.putFloat(e.getKey(), (Float) val);
                else if (val instanceof Long) ed.putLong(e.getKey(), (Long) val);
            }
            ed.apply();
        }

        prefs(c, n - 1).edit().clear().apply();
        setCount(c, n - 1);
    }

    /**
     * Transfer contact entry with mandatory login and optional display name.
     */
    static final class Refer {
        final String login;
        final String name;

        Refer(String login, String name) {
            this.login = login == null ? "" : login.trim();
            this.name = name == null ? "" : name.trim();
        }

        /**
         * Returns display label: name if present, otherwise login.
         */
        String label() {
            return name.isEmpty() ? login : name;
        }
    }
}
