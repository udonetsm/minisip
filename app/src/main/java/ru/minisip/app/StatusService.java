package ru.minisip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

/**
 * Owns the single persistent foreground notification shown at all times.
 * Buttons depend on state: Connect (VPN off), Disconnect + Reconnect (VPN on),
 * Reconnect only (VPN on during a call). Button taps arrive here as intent actions.
 */
public final class StatusService extends Service {

    static final int NOTIF_ID = 1;
    private static final String CHANNEL = "status";
    private static final String ACTION_TOGGLE = "ru.minisip.action.TOGGLE";
    private static final String ACTION_RECONNECT = "ru.minisip.action.RECONNECT";
    private static final String ACTION_REFRESH = "ru.minisip.action.REFRESH";

    private static boolean running;     // main thread only
    private static String posted = "";  // text of the last posted notification: skips redundant updates

    /**
     * Starts the service; safe to call repeatedly. Background starts may be refused by the system.
     */
    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, StatusService.class));
        } catch (IllegalStateException ignored) {
            // background start not allowed: retried on the next foreground launch
        }
    }

    /**
     * Re-posts the notification when the visible state changed. No-op if the service is not running.
     */
    static void refresh(Context ctx) {
        if (!running) return;
        String t = text((App) ctx.getApplicationContext());
        if (t.equals(posted)) return;
        posted = t;
        ctx.getSystemService(NotificationManager.class).notify(NOTIF_ID, build(ctx));
    }

    /**
     * Short state line; every button-relevant state is reflected in it, so it doubles as change signature.
     */
    private static String text(App app) {
        String vpn;
        if (app.vpnUp) vpn = app.vpnAttempts > 0 ? "VPN: reconnecting" : "VPN: connected";
        else if (app.vpnConnecting) vpn = "VPN: connecting";
        else vpn = "VPN: off";
        return app.state != App.IDLE ? vpn + " | Call in progress" : vpn;
    }

    private static PendingIntent action(Context ctx, String action, int code) {
        return PendingIntent.getService(ctx, code,
                new Intent(ctx, StatusService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Opens the proxy activity that shows the system VPN consent dialog and then connects.
     */
    private static PendingIntent consent(Context ctx) {
        return PendingIntent.getActivity(ctx, 3,
                new Intent(ctx, TileProxyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Builds the notification for the current state. Shared with CallService (same NOTIF_ID).
     */
    @SuppressWarnings("deprecation")
    static Notification build(Context ctx) {
        App app = (App) ctx.getApplicationContext();
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Status", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("MiniSIP")
                .setContentText(text(app))
                .setOngoing(true)
                .setDeleteIntent(action(ctx, ACTION_REFRESH, 4))
                .setContentIntent(PendingIntent.getActivity(ctx, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (!(app.vpnUp || app.vpnConnecting)) {
            b.addAction(0, "Connect",
                    VpnService.prepare(ctx) == null ? action(ctx, ACTION_TOGGLE, 1) : consent(ctx));
        } else {
            if (app.state == App.IDLE) b.addAction(0, "Disconnect", action(ctx, ACTION_TOGGLE, 1));
            b.addAction(0, "Reconnect", action(ctx, ACTION_RECONNECT, 2));
        }
        return b.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        App app = (App) getApplication();
        String action = intent == null ? null : intent.getAction();
        String msg = null;
        if (ACTION_TOGGLE.equals(action)) msg = app.toggleVpnFromTile(this);
        else if (ACTION_RECONNECT.equals(action)) msg = app.reconnectVpn();
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        posted = text(app);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
