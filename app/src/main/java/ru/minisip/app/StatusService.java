package ru.minisip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import ru.minisip.R;

/**
 * Owns the single persistent foreground notification shown at all times.
 * Buttons depend on state: Connect (VPN off), Disconnect + Reconnect (VPN on),
 * Reconnect only (VPN on during a call). Button taps arrive here as intent actions.
 */
public final class StatusService extends Service {

    static final int NOTIF_ID = 1;
    private static final String CHANNEL = "status";
    private static final String CALL_CHANNEL = "call";
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
        String sig = signature((App) ctx.getApplicationContext());
        if (sig.equals(posted)) return;
        posted = sig;
        ctx.getSystemService(NotificationManager.class).notify(NOTIF_ID, build(ctx));
    }

    /**
     * Short state line displayed in the notification body.
     */
    private static String text(App app) {
        String vpn;
        if (app.vpnUp) vpn = app.vpnAttempts > 0 ? "VPN: reconnecting" : "VPN: connected";
        else if (app.vpnConnecting) vpn = "VPN: connecting";
        else vpn = "VPN: off";
        return app.state != App.IDLE ? vpn + " | Call in progress" : vpn;
    }

    /**
     * Change signature for refresh(): must include every state that affects the notification,
     * including the call controls (speaker / mute), so a toggle re-posts the notification.
     */
    private static String signature(App app) {
        return text(app) + "|" + app.state + "|" + (app.isSpeakerOn() ? "1" : "0") + "|" + (app.isMuted() ? "1" : "0");
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
        if (app.state != App.IDLE) return buildCall(ctx, app);
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Status", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
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

    /**
     * Ongoing-call notification: CallStyle on API 31+, three-action plain builder below.
     * System UI turns this into the status-bar call chip when the platform supports it.
     */
    @SuppressWarnings("deprecation")
    private static Notification buildCall(Context ctx, App app) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel call = new NotificationChannel(CALL_CHANNEL, "Call", NotificationManager.IMPORTANCE_HIGH);
        call.setSound(null, null);
        call.enableVibration(false);
        nm.createNotificationChannel(call);

        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent hangup = callAction(ctx, CallActionReceiver.ACTION_HANGUP, 5);
        PendingIntent speaker = callAction(ctx, CallActionReceiver.ACTION_SPEAKER, 6);
        PendingIntent mute = callAction(ctx, CallActionReceiver.ACTION_MUTE, 7);

        Notification.Builder b = new Notification.Builder(ctx, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("MiniSIP")
                .setContentText(text(app))
                .setOngoing(true)
                .setDeleteIntent(action(ctx, ACTION_REFRESH, 4))
		.setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setWhen(app.callStartMs())
                .setShowWhen(true)
                .setUsesChronometer(app.state == App.TALK)
                .setContentIntent(open);

        if (Build.VERSION.SDK_INT >= 31) {
            Person person = new Person.Builder()
                    .setName(app.callPeer().isEmpty() ? "Call" : app.callPeer())
                    .setImportant(true)
                    .build();
            b.setStyle(Notification.CallStyle.forOngoingCall(person, hangup));
        }
        b.addAction(R.drawable.ic_volume_up, app.isSpeakerOn() ? "Phone" : "Speaker", speaker);
        b.addAction(app.isMuted() ? R.drawable.ic_mic_off : R.drawable.ic_mic, app.isMuted() ? "Unmute" : "Mute", mute);
        if (Build.VERSION.SDK_INT < 31) b.addAction(R.drawable.ic_call_end, "Hang up", hangup);
        return b.build();
    }

    /**
     * Broadcast PendingIntent for a call action; distinct request codes keep actions apart.
     */
    private static PendingIntent callAction(Context ctx, String action, int code) {
        return PendingIntent.getBroadcast(ctx, code,
                new Intent(ctx, CallActionReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
        if (ACTION_TOGGLE.equals(action)) {
            if (!(app.vpnUp || app.vpnConnecting) && PageStore.firstConfiguredVpnPage(this) < 0) {
                msg = "VPN: page not configured";
                Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(open);
            } else {
                msg = app.toggleVpnFromTile(this);
            }
        }
        else if (ACTION_RECONNECT.equals(action)) msg = app.reconnectVpn();
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        // Button tap: service is already foreground; a notification built now shows the pre-change
        // state and can overtake the fresh one posted by refresh(), so leave posting to refresh().
        if (ACTION_TOGGLE.equals(action) || ACTION_RECONNECT.equals(action)) return START_STICKY;
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        posted = signature(app);
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
