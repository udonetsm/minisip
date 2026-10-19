package ru.minisip.screen;

import android.content.Context;

/**
 * Manages screen state and proximity sensor during calls.
 */
public interface Screen {

    static Screen create(Context ctx) {
        return new ScreenImpl(ctx);
    }

    /**
     * Enables proximity sensor and acquires partial CPU wake lock on call start.
     */
    void start();

    /**
     * Releases proximity sensor and wake locks on call end.
     */
    void stop();
}
