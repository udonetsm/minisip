package ru.minisip.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles call-control actions from the ongoing call notification.
 * Runs on the main thread; App methods expect main-thread callers.
 */
public final class CallActionReceiver extends BroadcastReceiver {

    static final String ACTION_SPEAKER = "ru.minisip.action.CALL_SPEAKER";
    static final String ACTION_MUTE = "ru.minisip.action.CALL_MUTE";
    static final String ACTION_HANGUP = "ru.minisip.action.CALL_HANGUP";

    @Override
    public void onReceive(Context context, Intent intent) {
        App app = (App) context.getApplicationContext();
        if (app.state == App.IDLE) return;
        String action = intent.getAction();
        if (ACTION_SPEAKER.equals(action)) {
            boolean on = !app.isSpeakerOn();
            PageStore.prefs(context, app.lastPage).edit().putBoolean("speaker", on).apply();
            app.setSpeaker(on);
        } else if (ACTION_MUTE.equals(action)) {
            boolean m = !app.isMuted();
            PageStore.prefs(context, app.lastPage).edit().putBoolean("muted", m).apply();
            app.setMute(m);
        } else if (ACTION_HANGUP.equals(action)) {
            app.hangup(app.lastPage);
        }
    }
}
