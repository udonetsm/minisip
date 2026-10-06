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
     * Имя интерфейса именно нашей VPN-сети. Система регистрирует её через долю секунды после
     * establish(), поэтому ждём до ~3 секунд. Выбираем только сеть, у которой IP-адрес
     * совпадает с ip нашей сессии.
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
     * Вытеснение другим VPN-приложением.
     * В многопрофильной среде Android (личный/рабочий профиль) ConnectivityManager.getAllNetworks()
     * видимы сети всех профилей устройства, а getOwnerUid() недоступен сторонним приложениям.
     * Вытеснение в нашем профиле отслеживается штатно системой через TunService.onRevoke().
     * Сети других профилей полностью игнорируются и не приводят к сбросу VPN.
     */
    @Override
    public boolean otherVpn(String ip) {
        return false;
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
