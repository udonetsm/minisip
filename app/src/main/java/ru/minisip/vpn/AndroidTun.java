package ru.minisip.vpn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.LinkAddress;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.util.LinkedHashSet;
import java.util.Set;

/** Tun на Android: VpnService.Builder.establish() даёт дескриптор интерфейса. */
final class AndroidTun implements Tun {

    private final TunService svc;
    private volatile ParcelFileDescriptor fd;
    private volatile FileInputStream in;
    private volatile FileOutputStream out;

    AndroidTun(TunService svc) {
        this.svc = svc;
    }

    @Override
    public boolean protect(DatagramSocket s) {
        return svc.protect(s);
    }

    @Override
    public String open(String ip, String dns, String routes, int mtu, String apps) {
        try {
            VpnService.Builder b = svc.new Builder()
                    .setSession("MiniSIP")
                    .setMtu(mtu)
                    .setBlocking(true)
                    .addAddress(ip, 32)
                    // IPv6 в туннель не умеет: заворачиваем его в «чёрную дыру» (tx его выбрасывает),
                    // чтобы выбранные приложения не утекали мимо VPN по IPv6, а откатывались на IPv4
                    .addRoute("::", 0);
            // в туннель идёт трафик только нашего приложения и выбранных, остальная система не затронута
            Set<String> pkgs = new LinkedHashSet<>();
            pkgs.add(svc.getPackageName());
            for (String p : apps.split("[\\s,]+")) if (!p.isEmpty()) pkgs.add(p);
            for (String p : pkgs) {
                try {
                    b.addAllowedApplication(p);
                } catch (PackageManager.NameNotFoundException e) {
                    // приложение удалили после выбора: пропускаем
                }
            }
            for (String r : routes.split(" ")) {
                int slash = r.indexOf('/');
                if (slash > 0) b.addRoute(r.substring(0, slash), Integer.parseInt(r.substring(slash + 1)));
            }
            for (String d : dns.split(" ")) {
                if (!d.isEmpty()) b.addDnsServer(d);
            }
            ParcelFileDescriptor f = b.establish();     // заодно вытесняет любой другой действующий VPN
            if (f == null) return null;
            fd = f;
            in = new FileInputStream(f.getFileDescriptor());
            out = new FileOutputStream(f.getFileDescriptor());
            String name = ifaceName(ip);
            if (name == null) close();
            return name;
        } catch (Exception e) {
            close();
            return null;
        }
    }

    /**
     * Имя интерфейса нашей VPN-сети. Система регистрирует её через долю секунды после
     * establish(), поэтому ждём до ~3 секунд. Другого VPN-интерфейса у приложения нет.
     */
    @SuppressWarnings("deprecation")
    /**
     * Имя интерфейса именно нашей VPN-сети. Чужие VPN (другой профиль, другое приложение) по порядку
     * в списке не берём: ищем сеть с VPN-транспортом, у которой есть выданный нам сервером адрес ip.
     * Запасной признак — сеть по умолчанию самого приложения, если она VPN. Нет признака — null, а не наугад.
     */
    private String ifaceName(String ip) {
        ConnectivityManager cm = (ConnectivityManager) svc.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (int i = 0; i < 30; i++) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                if (nc == null || lp == null || lp.getInterfaceName() == null
                        || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (ip.equals(la.getAddress().getHostAddress())) return lp.getInterfaceName();
                }
            }
            Network act = cm.getActiveNetwork();
            if (act != null) {
                NetworkCapabilities anc = cm.getNetworkCapabilities(act);
                LinkProperties alp = cm.getLinkProperties(act);
                if (anc != null && anc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && alp != null && alp.getInterfaceName() != null) {
                    return alp.getInterfaceName();
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Нас вытеснил чужой VPN этого же профиля: нашей VPN-сети в системе уже нет, а чужая есть.
     * VPN другого профиля (личный, пока мы в рабочем) нас вытеснить не может: у каждого профиля
     * свой VPN, и мы его игнорируем. Профиль владельца сети узнаём по uid (uid / 100000); если система
     * его не сообщает (Android до 12), чужая сеть учитывается только когда нашей уже нет, поэтому
     * живой туннель из-за чужого личного VPN не закрывается.
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean otherVpn(String ip) {
        ConnectivityManager cm = (ConnectivityManager) svc.getSystemService(Context.CONNECTIVITY_SERVICE);
        int myProfile = Process.myUid() / 100000;
        boolean oursPresent = false, foreign = false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (nc == null || lp == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            boolean ours = false;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (ip.equals(la.getAddress().getHostAddress())) ours = true;
            }
            if (ours) {
                oursPresent = true;
                continue;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                int owner = nc.getOwnerUid();
                if (owner > 0 && owner / 100000 != myProfile) continue;   // VPN другого профиля
            }
            foreign = true;
        }
        return foreign && !oursPresent;
    }

    @Override
    public int read(byte[] b) throws IOException {
        FileInputStream s = in;
        return s == null ? -1 : s.read(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        FileOutputStream s = out;
        if (s != null) s.write(b, off, len);
    }

    @Override
    public void close() {
        ParcelFileDescriptor f = fd;
        fd = null;
        in = null;
        out = null;
        if (f != null) {
            try {
                f.close();
            } catch (IOException ignored) {
            }
        }
    }
}
