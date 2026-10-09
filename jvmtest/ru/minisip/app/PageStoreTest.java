package ru.minisip.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class PageStoreTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        Context c = new Context();
        check(PageStore.count(c) >= 1, "Initial page count is at least 1");

        PageStore.setCount(c, 3);
        check(PageStore.count(c) == 3, "setCount updates page count correctly");

        check(!PageStore.configured(c, 0), "Unconfigured page returns false for configured()");

        SharedPreferences p0 = PageStore.prefs(c, 0);
        p0.edit().putString("server", "sip.example.com").putString("login", "alice").apply();
        check(PageStore.configured(c, 0), "Configured page returns true after server and login are set");

        PageStore.deletePage(c, 1);
        check(PageStore.count(c) == 2, "deletePage decrements page count");

        System.out.println(fails == 0 ? "PAGESTORE TEST OK" : "PAGESTORE TEST FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
