package ru.minisip.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Страницы настроек: сколько их и настроена ли каждая. У каждой страницы свой файл настроек,
 * страницы друг друга не видят. Первая читает прежний файл "cfg", так что старые настройки не теряются.
 */
final class PageStore {

    private PageStore() {}

    static SharedPreferences prefs(Context c, int page) {
        return c.getSharedPreferences(page == 0 ? "cfg" : "cfg" + page, Context.MODE_PRIVATE);
    }

    /** Сколько страниц уже создано (не меньше одной). */
    static int count(Context c) {
        return Math.max(1, c.getSharedPreferences("pages", Context.MODE_PRIVATE).getInt("count", 1));
    }

    static void setCount(Context c, int n) {
        c.getSharedPreferences("pages", Context.MODE_PRIVATE).edit().putInt("count", Math.max(1, n)).apply();
    }

    /** АТС страницы настроена: указаны сервер и логин (минимум, без которого к АТС не подключиться). */
    static boolean configured(Context c, int page) {
        SharedPreferences p = prefs(c, page);
        return !p.getString("server", "").trim().isEmpty() && !p.getString("login", "").trim().isEmpty();
    }
}
