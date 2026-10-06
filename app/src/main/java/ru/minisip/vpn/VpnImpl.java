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
 * Смена сети (NetWatch) или обрыв поднятого туннеля (Tunnel.onReconnecting): текущий Tunnel закрывается
 * (сокеты, tun), через SETTLE_MS идёт новая попытка. Не вышло: паузы 2, 2, 4, 4, 8, 8, 2, 2, ... секунд
 * и так без конца, пока не получится. Конец только в двух случаях: disconnect() или нас вытеснил чужой VPN
 * (onRevoke либо чужой VPN в профиле перед очередной попыткой) — тогда всё сбрасывается и onDown.
 * Все вызовы слушателя идут под замком VpnImpl, чтобы порядок событий не путался.
 */
final class VpnImpl implements Vpn {

    /** Сервис создаёт система, поэтому находит владельца через статическую ссылку. */
    static volatile VpnImpl live;

    /** Куда пингуем через туннель. */
    private static final String PING_HOST = "10.160.1.254";
    /** Пауза перед первой попыткой: старый интерфейс успевает исчезнуть из системы. */
    private static final long SETTLE_MS = 700;
    /** Паузы после неудачной попытки; по кругу. */
    private static final long[] BACKOFF_MS = {2000, 2000, 4000, 4000, 8000, 8000};
    /** Сколько ждём одну попытку целиком (рукопожатие). */
    private static final long ATTEMPT_MS = 30_000;

    private final Context ctx;
    private final NetWatch watch;
    private volatile Listener lis = new Listener() {};
    private Tunnel tunnel;                 // действующий (или поднимающийся) сеанс; null — сеанса нет
    private TunService svc;                // живой сервис: нужен для новых попыток
    private String host, identity, psk, password, ca, apps, healthcheckIp;   // параметры последнего connect()
    private String lastIp;                 // адрес, выданный нам сервером (по нему ищем чужой VPN)
    private boolean running;               // connect() был, disconnect()/сброса ещё не было
    private boolean want;                  // connect() был, сервис ещё не ответил
    private int gen;                       // меняется при connect/disconnect/revoke/смене сети: отменяет восстановление
    private Thread recovery;               // поток переподключения; null — не идёт

    VpnImpl(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.watch = new NetWatch(this.ctx);
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
    public synchronized void connect(String host, String identity, String password, String psk, String ca, String apps, String healthcheckIp) {
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
        this.healthcheckIp = healthcheckIp;
        this.lastIp = null;
        running = true;
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
        watch.start(this::networkChanged);
        launch(new Guard(false));
        return true;
    }

    /** Нас вытеснили: другое VPN-приложение или пользователь отключил VPN в системе. */
    synchronized void revoked(TunService svc) {
        cancel("VPN was taken over by another app");
    }

    /** Сеть под VPN сменилась: рвём текущее соединение и подключаемся заново (с нуля, не продолжая старое). */
    private synchronized void networkChanged() {
        if (!running || want || svc == null) return;
        gen++;
        Thread r = recovery;
        recovery = null;
        if (r != null) r.interrupt();
        Tunnel t = tunnel;
        tunnel = null;
        if (t != null) t.stop("network changed");         // его события молчат: он уже не владелец
        lis.onReconnecting("network changed", 1);
        startRecovery();
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
            clear();
            lis.onDown(reason);
        } else if (t != null) {            // Guard сам скажет onDown, погасит сервис и сбросит состояние
            t.stop(reason);
        } else {
            ctx.stopService(new Intent(ctx, TunService.class));
            clear();
        }
    }

    /** Чужой VPN вытеснил нас, пока шло переподключение: всё в исходное состояние. Под замком. */
    private void abandon(String reason) {
        gen++;
        recovery = null;                   // это сам поток переподключения, он сейчас выйдет
        Tunnel t = tunnel;
        tunnel = null;
        if (t != null) t.stop(reason);
        ctx.stopService(new Intent(ctx, TunService.class));
        clear();
        lis.onDown(reason);
    }

    /** Забывает всё о соединении: слежение, параметры (в том числе пароль), адрес, сервис. Под замком. */
    private void clear() {
        running = false;
        want = false;
        watch.stop();
        host = identity = password = psk = ca = apps = healthcheckIp = null;
        lastIp = null;
        svc = null;
    }

    private Tunnel launch(Guard g) {
        Tunnel t = new Tunnel(new AndroidTun(svc), g);
        t.supervised = true;
        t.pingHost = (healthcheckIp != null && !healthcheckIp.trim().isEmpty()) ? healthcheckIp.trim() : PING_HOST;
        g.owner = t;
        tunnel = t;
        watch.mark();                      // сеть, на которой начинаем: если она сменится — начнём заново
        t.start(host, identity, psk, password, ca, apps);
        return t;
    }

    private void startRecovery() {         // под замком
        final int g = gen;
        Thread t = new Thread(() -> recover(g), "vpn-recover");
        t.setDaemon(true);
        recovery = t;
        t.start();
    }

    /**
     * Переподключение: первая попытка через SETTLE_MS, дальше после каждой неудачи паузы 2, 2, 4, 4, 8, 8 с по кругу.
     * Конца по времени нет. Перед каждой попыткой смотрим, не занял ли место чужой VPN нашего профиля
     * (establish() вытеснил бы его): тогда всё сбрасываем и onDown.
     */
    private void recover(int g) {
        long wait = SETTLE_MS;
        int step = 0;
        int attempts = 1;
        try {
            for (;;) {
                Thread.sleep(wait);
                Guard gd = new Guard(true);
                Tunnel t;
                synchronized (this) {
                    if (g != gen) return;
                    if (lastIp != null && new AndroidTun(svc).otherVpn(lastIp)) {
                        abandon("another VPN took over");
                        return;
                    }
                    lis.onReconnecting("reconnecting", attempts);
                    t = launch(gd);
                }
                boolean finished = gd.done.await(ATTEMPT_MS, TimeUnit.MILLISECONDS);
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
                attempts++;
                wait = BACKOFF_MS[step];
                step = (step + 1) % BACKOFF_MS.length;
            }
        } catch (InterruptedException e) {
            // отменили (disconnect, новый connect, смена сети)
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
                String ip = owner.ip();
                if (ip != null) lastIp = ip;
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
                        clear();                   // до onDown: слушатель может сразу позвать connect()
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
                lis.onReconnecting(reason, 1);
                startRecovery();
            }
        }
    }
}
