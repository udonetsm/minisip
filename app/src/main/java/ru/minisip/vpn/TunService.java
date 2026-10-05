package ru.minisip.vpn;

import android.content.Intent;
import android.net.VpnService;

/**
 * Системный VpnService — единственный способ получить tun на Android без root.
 * Класс обязан быть public (его создаёт система) — это единственное исключение из правила
 * «реализации package-private». Снаружи пакета им никто не пользуется: его имя стоит только
 * в манифесте. Логика живёт в VpnImpl и Tunnel, тут лишь проводка.
 */
public final class TunService extends VpnService {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        VpnImpl v = VpnImpl.live;
        if (v == null || !v.serviceReady(this)) stopSelf();
        return START_NOT_STICKY;
    }

    /** Нас вытеснили: другое VPN-приложение или пользователь отключил VPN в системе. */
    @Override
    public void onRevoke() {
        VpnImpl v = VpnImpl.live;
        if (v != null) v.revoked(this);
        super.onRevoke();
    }
}
