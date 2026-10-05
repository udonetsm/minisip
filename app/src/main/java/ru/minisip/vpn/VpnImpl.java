package ru.minisip.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Связывает публичный интерфейс, системный сервис и Tunnel.
 * connect() запускает TunService; когда система его создала, сервис зовёт serviceReady(),
 * и только тогда стартует Tunnel (ему нужен сам сервис: protect() и Builder).
 *
 * Обрыв поднятого туннеля (Tunnel сообщает onReconnecting, если другого VPN в системе нет):
 * переподключаемся новыми Tunnel с паузами 1, 2, 4, ... секунд, всего не дольше GIVE_UP_MS от обрыва.
 * Не вышло — onDown. Все вызовы слушателя идут под замком VpnImpl, чтобы порядок событий не путался.
 */
final class VpnImpl implements Vpn {

    /** Сервис создаёт система, поэтому находит владельца через статическую ссылку. */
    static volatile VpnImpl live;

    /** Куда пингуем через туннель. */
    private static final String PING_HOST = "10.160.1.254";
    /** Сколько после обрыва пробуем переподключиться, потом onDown. */
    private static final long GIVE_UP_MS = 120_000;

    private final Context ctx;
    private volatile Listener lis = new Listener() {};
    private Tunnel tunnel;                 // действующий (или поднимающийся) сеанс; null — сеанса нет
    private TunService svc;                // живой сервис: нужен для новых попыток
    private String host, identity, psk, password, ca, apps;   // параметры последнего connect()
    private boolean want;                  // connect() был, сервис ещё не ответил
    private int gen;                       // меняется при connect/disconnect/revoke: отменяет восстановление
    private Thread recovery;               // поток переподключения; null — не идёт

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
        gen++;
        Thread r = recovery;
        recovery = null;
        if (r != null) r.interrupt();
        Tunnel old = tunnel;
        tunnel = null;
        if (old != null) old.stop("replaced");            // свой туннель заменяем новым (его события молчат)
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
        cancel("disconnected");
    }

    /** Из TunService.onStartCommand. false — запускать нечего, сервис можно гасить. */
    synchronized boolean serviceReady(TunService svc) {
        if (!want) return false;
        want = false;
        this.svc = svc;
        launch(new Guard(false));
        return true;
    }

    /** Нас вытеснили: другое VPN-приложение или пользователь отключил VPN в системе. */
    synchronized void revoked(TunService svc) {
        cancel("VPN was taken over by another app");
    }

    /** Останавливает сеанс и восстановление, сообщает onDown (под замком). */
    private void cancel(String reason) {
        gen++;
        Thread r = recovery;
        recovery = null;
        Tunnel t = tunnel;
        if (r != null) {                   // идёт переподключение: у попытки свои молчаливые события
            r.interrupt();
            tunnel = null;
            if (t != null) t.stop(reason);
            ctx.stopService(new Intent(ctx, TunService.class));
            lis.onDown(reason);
        } else if (t != null) {            // Guard сам скажет onDown и погасит сервис
            t.stop(reason);
        } else {
            ctx.stopService(new Intent(ctx, TunService.class));
        }
    }

    private Tunnel launch(Guard g) {
        Tunnel t = new Tunnel(new AndroidTun(svc), g);
        t.supervised = true;
        t.pingHost = PING_HOST;
        g.owner = t;
        tunnel = t;
        t.start(host, identity, psk, password, ca, apps);
        return t;
    }

    /**
     * Переподключение после обрыва. Паузы 1, 2, 4, ... с; общий срок GIVE_UP_MS от обрыва,
     * включая сами попытки. Потом onDown.
     */
    private void recover(int g) {
        final long start = System.currentTimeMillis();
        long pause = 1000;
        String last = "no connection";
        try {
            for (;;) {
                long left = GIVE_UP_MS - (System.currentTimeMillis() - start);
                if (left <= 0) break;
                Thread.sleep(Math.min(pause, left));
                pause *= 2;
                left = GIVE_UP_MS - (System.currentTimeMillis() - start);
                if (left <= 0) break;

                Guard gd = new Guard(true);
                Tunnel t;
                synchronized (this) {
                    if (g != gen) return;
                    t = launch(gd);
                }
                boolean finished = gd.done.await(left, TimeUnit.MILLISECONDS);
                if (finished && gd.ok) {                       // поднялись: onUp уже ушёл слушателю
                    synchronized (this) {
                        if (recovery == Thread.currentThread()) recovery = null;
                    }
                    return;
                }
                synchronized (this) {
                    if (g != gen) return;
                    if (tunnel == t) tunnel = null;            // дальше его события молчат
                }
                t.stop("timeout");
                if (gd.why != null) last = gd.why;
            }
        } catch (InterruptedException e) {
            return;
        }
        synchronized (this) {
            if (g != gen) return;
            if (recovery == Thread.currentThread()) recovery = null;
            tunnel = null;
            ctx.stopService(new Intent(ctx, TunService.class));
            lis.onDown("connection lost, reconnect failed (" + last + ")");
        }
    }

    /** Пропускает события только от действующего сеанса: старые, заменённые, молчат. */
    private final class Guard implements Listener {
        volatile Tunnel owner;
        final boolean attempt;                     // попытка переподключения, а не первый подъём
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean ok;                       // дошла до onUp
        volatile String why;                       // почему сеанс закрылся

        Guard(boolean attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onUp(String iface) {
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                ok = true;
                lis.onUp(iface);                   // и при повторном подъёме: App перепривяжет сокеты
            }
            done.countDown();
        }

        @Override
        public void onDown(String reason) {
            why = reason;
            synchronized (VpnImpl.this) {
                if (tunnel == owner) {
                    tunnel = null;
                    if (!attempt || ok) {          // неудачная попытка молчит: решает цикл переподключения
                        ctx.stopService(new Intent(ctx, TunService.class));
                        lis.onDown(reason);
                    }
                }
            }
            done.countDown();
        }

        /** Поднятый сеанс оборвался, чужого VPN нет: запускаем переподключение. */
        @Override
        public void onReconnecting(String reason) {
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                tunnel = null;
                final int g = gen;
                lis.onReconnecting(reason);
                Thread t = new Thread(() -> recover(g), "vpn-recover");
                t.setDaemon(true);
                recovery = t;
                t.start();
            }
        }
    }
}
