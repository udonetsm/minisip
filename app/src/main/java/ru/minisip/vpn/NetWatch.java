package ru.minisip.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.util.Objects;

/**
 * Следит за сетью под VPN (не VPN, с интернетом). «Отпечаток» сети: какая сеть лучшая и её IPv4-адреса.
 * Сети чужих VPN (в том числе из другого профиля) игнорируются: берутся только сети без TRANSPORT_VPN.
 *
 * onChange зовётся через DEBOUNCE_MS, если:
 *  - отпечаток отличается от запомненного mark() (другая сеть, новый адрес, сеть пропала), или
 *  - сеть вернулась после пропадания (даже если отпечаток тот же: NAT-привязка могла умереть).
 * После вызова слушатель смотрит online() и sameAsMarked() и решает, что делать.
 */
final class NetWatch {

    private static final long DEBOUNCE_MS = 1000;

    private final ConnectivityManager cm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile String marked;
    private volatile boolean offline;
    private Runnable onChange;
    private ConnectivityManager.NetworkCallback cb;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            Runnable r;
            synchronized (NetWatch.this) {
                r = onChange;
            }
            if (r == null) return;
            String k = key();
            boolean was = offline;
            offline = k == null;
            if (!Objects.equals(k, marked) || (k != null && was)) r.run();
        }
    };

    NetWatch(Context ctx) {
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Начинает слежение; текущая сеть считается исходной. Повторный вызов начинает заново. */
    synchronized void start(Runnable onChange) {
        stop();
        this.onChange = onChange;
        marked = key();
        offline = marked == null;
        cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                poke();
            }

            @Override
            public void onLost(Network n) {
                poke();
            }

            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                poke();
            }

            @Override
            public void onLinkPropertiesChanged(Network n, LinkProperties p) {
                poke();
            }
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), cb);
    }

    synchronized void stop() {
        h.removeCallbacks(check);
        onChange = null;
        ConnectivityManager.NetworkCallback c = cb;
        cb = null;
        if (c != null) {
            try {
                cm.unregisterNetworkCallback(c);
            } catch (IllegalArgumentException ignored) {
            }
        }
        marked = null;
        offline = false;
    }

    /** Текущая сеть становится исходной (зовут перед каждой попыткой подключения и миграцией). */
    void mark() {
        marked = key();
    }

    /** Есть ли сейчас хоть какая-то сеть с интернетом (кроме VPN). */
    boolean online() {
        return key() != null;
    }

    /** Сеть та же, на которой мы запомнили состояние. */
    boolean sameAsMarked() {
        return Objects.equals(key(), marked);
    }

    private void poke() {
        h.removeCallbacks(check);
        h.postDelayed(check, DEBOUNCE_MS);
    }

    @SuppressWarnings("deprecation")
    private String key() {
        Network best = null;
        int bestRank = -1;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue;
            }
            int rank = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) rank += 3;
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) rank += 2;
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) rank += 1;
            if (rank > bestRank) {
                best = n;
                bestRank = rank;
            }
        }
        if (best == null) return null;
        StringBuilder sb = new StringBuilder(best.toString());
        LinkProperties lp = cm.getLinkProperties(best);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) sb.append('|').append(la.getAddress().getHostAddress());
            }
        }
        return sb.toString();
    }
}
