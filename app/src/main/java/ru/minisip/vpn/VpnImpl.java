package ru.minisip.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * Связывает публичный интерфейс, системный сервис и Tunnel.
 * connect() запускает TunService; когда система его создала, сервис зовёт serviceReady(),
 * и только тогда стартует Tunnel (ему нужен сам сервис: protect() и Builder).
 */
final class VpnImpl implements Vpn {

    /** Сервис создаёт система, поэтому находит владельца через статическую ссылку. */
    static volatile VpnImpl live;

    private final Context ctx;
    private volatile Listener lis = new Listener() {};
    private Tunnel tunnel;                 // действующий (или поднимающийся) сеанс
    private String host, identity, psk, password, ca, apps;   // ждущие запуска параметры
    private boolean want;                  // connect() был, сервис ещё не ответил

    VpnImpl(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        live = this;
    }

    @Override
    public void setListener(Listener l) {
        lis = l;
    }

    @Override
    public boolean consent(Context activity, int requestCode) {
        Intent ask = VpnService.prepare(activity);
        if (ask == null) return true;
        ((Activity) activity).startActivityForResult(ask, requestCode);
        return false;
    }

    @Override
    public synchronized void connect(String host, String identity, String password, String psk, String ca, String apps) {
        Tunnel old = tunnel;
        tunnel = null;
        if (old != null) old.stop("replaced");            // свой туннель заменяем новым
        this.host = host;
        this.identity = identity;
        this.password = password;
        this.psk = psk;
        this.ca = ca;
        this.apps = apps;
        want = true;
        ctx.startService(new Intent(ctx, TunService.class));
    }

    @Override
    public synchronized void disconnect() {
        want = false;
        Tunnel t = tunnel;                 // оставляем в поле: Guard по нему узнаёт «свой» onDown
        if (t != null) {
            t.stop("disconnected");
        } else {
            ctx.stopService(new Intent(ctx, TunService.class));
        }
    }

    /** Из TunService.onStartCommand. false — запускать нечего, сервис можно гасить. */
    synchronized boolean serviceReady(TunService svc) {
        if (!want) return false;
        want = false;
        Tunnel t = new Tunnel(new AndroidTun(svc), new Guard());
        ((Guard) t.listener()).owner = t;
        tunnel = t;
        t.start(host, identity, psk, password, ca, apps);
        return true;
    }

    synchronized void revoked(TunService svc) {
        Tunnel t = tunnel;
        if (t != null) t.stop("VPN was taken over by another app");
    }

    /** Пропускает события только от действующего сеанса: старые, заменённые, молчат. */
    private final class Guard implements Listener {
        volatile Tunnel owner;

        private boolean current() {
            synchronized (VpnImpl.this) {
                return tunnel == owner;
            }
        }

        @Override
        public void onUp(String iface) {
            if (current()) lis.onUp(iface);
        }

        @Override
        public void onDown(String reason) {
            boolean cur;
            synchronized (VpnImpl.this) {
                cur = tunnel == owner;
                if (cur) tunnel = null;
            }
            if (!cur) return;
            ctx.stopService(new Intent(ctx, TunService.class));
            lis.onDown(reason);
        }
    }
}
