package ru.minisip.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.IOException;
import java.net.DatagramSocket;

/**
 * Network.bindSocket: сокет уходит строго в сеть нужного интерфейса, даже если системный маршрут
 * по умолчанию другой. Сеть VPN появляется в ConnectivityManager не мгновенно после establish(),
 * поэтому ищем её до ~2 секунд (зовут из рабочих потоков, не из главного).
 */
final class AndroidBinder implements Binder {

    private final ConnectivityManager cm;

    AndroidBinder(Context ctx) {
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean bind(DatagramSocket s, String iface) throws IOException {
        // Если приложение входит в список VPN, туннель уже его сеть по умолчанию: привязка не нужна,
        // а явный bindSocket система может отклонить (EPERM).
        Network act = cm.getActiveNetwork();
        LinkProperties alp = act == null ? null : cm.getLinkProperties(act);
        String def = alp == null ? "none" : String.valueOf(alp.getInterfaceName());
        if (iface.equals(def)) return true;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            seen.setLength(0);
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                seen.append(lp == null ? "?" : lp.getInterfaceName()).append(' ');
                if (lp != null && iface.equals(lp.getInterfaceName())) {
                    try {
                        n.bindSocket(s);
                    } catch (IOException e) {
                        throw new IOException(e.getMessage() + "; default network of the app is " + def
                                + ", not " + iface + ": the app is not in the VPN's allowed apps", e);
                    }
                    return true;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        throw new IOException("network " + iface + " not visible to the app, visible: " + seen);
    }
}
