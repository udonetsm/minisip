package ru.minisip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the process in the foreground during an active call so the microphone remains functional when screen is off.
 */
public final class CallService extends Service {

    private static final String CHANNEL = "call";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = StatusService.build(this);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(StatusService.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(StatusService.NOTIF_ID, n);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
