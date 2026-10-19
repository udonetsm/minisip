package ru.minisip.vpn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Android VpnService TUN interface provider.
 */
final class AndroidTun implements Tun {
    private final TunService svc;
    volatile boolean transientFail;
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
                    // Blackhole IPv6 to force IPv4 fallback
                    .addRoute("::", 0);
            // Route traffic only for this app and selected split tunneling apps
            Set<String> pkgs = new LinkedHashSet<>();
            pkgs.add(svc.getPackageName());
            for (String p : apps.split("[\\s,]+")) if (!p.isEmpty()) pkgs.add(p);
            for (String p : pkgs) {
                try {
                    b.addAllowedApplication(p);
                } catch (PackageManager.NameNotFoundException e) {
                    // App uninstalled after selection: ignore
                }
            }
            for (String r : routes.split(" ")) {
                int slash = r.indexOf('/');
                if (slash > 0)
                    b.addRoute(r.substring(0, slash), Integer.parseInt(r.substring(slash + 1)));
            }
            for (String d : dns.split(" ")) {
                if (!d.isEmpty()) b.addDnsServer(d);
            }
            ParcelFileDescriptor f = b.establish();     // replaces active VPN
            if (f == null) {
                // System temporarily refused VPN permission: retry as transient network error.
                transientFail = true;
                return null;
            }
            fd = f;
            in = new FileInputStream(f.getFileDescriptor());
            out = new FileOutputStream(f.getFileDescriptor());
            String name = ifaceName(ip);
            if (name == null) close();
            return name;
        } catch (Exception e) {
            transientFail = true;
            close();
            return null;
        }
    }

    /**
     * Resolves network interface name matching assigned VPN IP address.
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
     * Checks if another active VPN hijacked the tunnel in current user profile.
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
