package android.app;

import android.content.Context;
import android.content.Intent;

public class Service extends Context {
    public static final int START_NOT_STICKY = 2;

    public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }

    public final void stopSelf() {}
}
