package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public class Service extends Context {
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int STOP_FOREGROUND_REMOVE = 1;

    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    public void onDestroy() {}
    public void stopSelf() {}
    public IBinder onBind(Intent intent) { return null; }
    public void startForeground(int id, Notification notification) {}
    public void stopForeground(int flags) {}
}
