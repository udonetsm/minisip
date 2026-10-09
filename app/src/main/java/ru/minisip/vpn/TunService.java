package ru.minisip.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;

/**
 * Системный VpnService — единственный способ получить tun на Android без root.
 * Класс обязан быть public (его создаёт система) — это единственное исключение из правила
 * «реализации package-private». Снаружи пакета им никто не пользуется: его имя стоит только
 * в манифесте. Логика живёт в VpnImpl и Tunnel, тут лишь проводка.
 */
public final class TunService extends VpnService {

    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 2;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        VpnImpl v = VpnImpl.live;
        if (v == null || !v.serviceReady(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNotification();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("MiniSIP VPN")
                .setContentText("VPN service is active in background")
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    /** Нас вытеснили: другое VPN-приложение или пользователь отключил VPN в системе. */
    @Override
    public void onRevoke() {
        VpnImpl v = VpnImpl.live;
        if (v != null) v.revoked(this);
        super.onRevoke();
    }
}
