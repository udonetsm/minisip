package ru.minisip.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Страницы настроек: сколько их и настроена ли каждая. У каждой страницы свой файл настроек,
 * страницы друг друга не видят. Первая читает прежний файл "cfg", так что старые настройки не теряются.
 */
final class PageStore {

    private PageStore() {
    }

    static SharedPreferences prefs(Context c, int page) {
        return c.getSharedPreferences(page == 0 ? "cfg" : "cfg" + page, Context.MODE_PRIVATE);
    }

    /**
     * Сколько страниц уже создано (не меньше одной).
     */
    static int count(Context c) {
        return Math.max(1, c.getSharedPreferences("pages", Context.MODE_PRIVATE).getInt("count", 1));
    }

    static void setCount(Context c, int n) {
        c.getSharedPreferences("pages", Context.MODE_PRIVATE).edit().putInt("count", Math.max(1, n)).apply();
    }

    /**
     * АТС страницы настроена: указаны сервер и логин (минимум, без которого к АТС не подключиться).
     */
    static boolean configured(Context c, int page) {
        SharedPreferences p = prefs(c, page);
        return !p.getString("server", "").trim().isEmpty() && !p.getString("login", "").trim().isEmpty();
    }

    /**
     * Список контактов перевода страницы. Хранение: строки "login\tname", разделённые '\n'.
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
     * Удаляет указанную страницу, сдвигая последующие страницы назад.
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
     * Один контакт перевода. login обязателен, name — то, что видно в списке.
     */
    static final class Refer {
        final String login;
        final String name;

        Refer(String login, String name) {
            this.login = login == null ? "" : login.trim();
            this.name = name == null ? "" : name.trim();
        }

        /**
         * Подпись строки в UI: name, иначе login.
         */
        String label() {
            return name.isEmpty() ? login : name;
        }
    }
}
