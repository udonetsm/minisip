package ru.minisip.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ru.minisip.log.LogSink;

/**
 * Связывает публичный интерфейс, системный сервис и Tunnel.
 * <p>
 * Принципы живучести:
 * 1. Первый подъём и переподключение идут одним циклом recover(): сетевые сбои повторяются
 * (паузы 2, 2, 4, 4, 8, 8 с по кругу), конец только при disconnect(), onRevoke() нашего же
 * экземпляра сервиса или фатальной ошибке (неверный пароль, сертификат, алгоритмы).
 * 2. Нет сети — не тратим попытки: ждём её появления.
 * 3. Смена сети при согласованном MOBIKE: туннель и tun остаются, меняется только сокет.
 * Без MOBIKE или при неудаче — полное переподключение.
 * 4. Чужие VPN (в том числе в другом профиле) на нас не влияют: Android держит VPN отдельно на
 * каждого пользователя/профиль, а наши сети ищем только среди NOT_VPN. Вытеснение — только
 * onRevoke() текущего экземпляра TunService.
 * 5. PARTIAL_WAKE_LOCK держим всегда, пока VPN запущен (нужен постоянному NAT-keepalive).
 * Два режима (setStrict): идёт звонок — Tunnel рвёт связь жёстким watchdog'ом;
 * звонка нет — мягкий watchdog (MOBIKE-refresh, затем реконнект).
 * Все вызовы слушателя идут под замком VpnImpl.
 */
final class VpnImpl implements Vpn {

    private static final String TAG = "MiniSIP-VPN";
    /**
     * Пауза перед первой попыткой после обрыва: старый интерфейс успевает исчезнуть.
     */
    private static final long SETTLE_MS = 700;
    /**
     * Паузы после неудачной попытки; по кругу.
     */
    private static final long[] BACKOFF_MS = {5000, 5000, 10000, 10000};
    /**
     * Сколько ждём одну попытку целиком (рукопожатие).
     */
    private static final long ATTEMPT_MS = 20_000;
    /**
     * Период необязательного ping через туннель (если задан healthcheckIp).
     */
    private static final int PING_MS = 5_000;
    /**
     * Сервис создаёт система, поэтому находит владельца через статическую ссылку.
     */
    static volatile VpnImpl live;
    private final Context ctx;
    private final NetWatch watch;
    private volatile Listener lis = new Listener() {
    };
    private volatile LogSink sink;
    private volatile Tunnel tunnel;                 // действующий (или поднимающийся) сеанс; null — сеанса нет
    private TunService svc;                // живой экземпляр сервиса сеанса
    private String host, identity, psk, password, ca, apps, healthcheckIp;
    private int pingIntervalSec = 5;
    private boolean running;               // connect() был, disconnect()/сброса ещё не было
    private boolean want;                  // connect() был, сервис ещё не ответил
    private boolean strict;                // идёт звонок: жёсткий watchdog (wake lock держится всегда)
    private PowerManager.WakeLock wl;
    private int gen;                       // меняется при connect/disconnect/revoke/рестарте: отменяет циклы
    private Thread recovery;               // поток подъёма/переподключения; null — не идёт
    private volatile int migs, heals, silences, kept;   // Mig / Heal / Silence / Kept: события без реконнекта
    private int reconnects;                // реконнектов с последнего connect(): +1 на каждый обрыв/смену сети

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
    public void setLogSink(LogSink s) {
        sink = s;
    }

    /**
     * Одна точка журнала: logcat и панель лога. Секреты сюда не передавать.
     */
    private void log(String m) {
        Log.i(TAG, m);
        LogSink s = sink;
        if (s != null) s.log(m);
    }

    @Override
    public synchronized boolean mobike() {
        Tunnel t = tunnel;
        return t != null && t.mobike();
    }

    @Override
    public boolean consent(Context activity, int requestCode) {
        Intent ask = VpnService.prepare(activity);
        if (ask == null) return true;
        ((Activity) activity).startActivityForResult(ask, requestCode);
        return false;
    }

    /**
     * Режим работы. true — идёт звонок: Tunnel жёстко следит за ответами (watchdog рвёт связь).
     * false — звонка нет: watchdog мягкий (MOBIKE-refresh, потом реконнект). Wake lock в обоих
     * режимах держится, пока VPN запущен (независимо от strict).
     * Можно звать в любой момент и сколько угодно раз.
     */
    @Override
    public synchronized void setStrict(boolean on) {
        if (strict == on) return;
        strict = on;
        log("mode: " + (on ? "call (strict watchdog)" : "plain VPN (soft watchdog)") + ", wake lock always");
        Tunnel t = tunnel;
        if (t != null) t.strict = on;
        updateWake();
    }

    /**
     * Partial wake lock держим ВСЕГДА, пока VPN запущен (туннель поднят или идёт переподключение),
     * независимо от режима strict: иначе при сне CPU замолкает поток vpn-keep, NAT-запись
     * умирает и сервер шлёт ESP на старый порт. Под замком.
     */
    private void updateWake() {
        if (running) {
            if (wl == null) {
                wl = ctx.getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minisip:vpn");
                wl.setReferenceCounted(false);
            }
            if (!wl.isHeld()) {
                wl.acquire();
                log("wake lock acquired");
            }
        } else if (wl != null && wl.isHeld()) {
            wl.release();
            log("wake lock released");
        }
    }

    @Override
    public synchronized void connect(String host, String identity, String password, String psk, String ca,
                                     String apps, String healthcheckIp, int pingIntervalSec) {
        gen++;
        Thread r = recovery;
        recovery = null;
        if (r != null) r.interrupt();
        Tunnel old = tunnel;
        tunnel = null;
        if (old != null) old.stop("replaced");
        // Если предыдущий сеанс уже был привязан к живому TunService, гасим его перед запуском
        // нового. Иначе startService переиспользует тот же экземпляр, serviceReady сработает
        // повторно, а асинхронный onRevoke от системы может прилететь уже в новую сессию и
        // её сбросить (гонка).
        if (svc != null) {
            svc = null;
            ctx.stopService(new Intent(ctx, TunService.class));
        }
        this.host = host;
        this.identity = identity;
        this.password = password;
        this.psk = psk;
        this.ca = ca;
        this.apps = apps;
        this.healthcheckIp = healthcheckIp;
        this.pingIntervalSec = pingIntervalSec;
        running = true;
        want = true;
        reconnects = 0;
        migs = heals = silences = kept = 0;
        updateWake();
        Intent svc = new Intent(ctx, TunService.class);
        try {
            ctx.startService(svc);
        } catch (IllegalStateException e) {
            ctx.startForegroundService(svc);
        }
    }

    /**
     * Фактический статус DPD для подписи на кнопке.
     */
    public String dpdState() {
        Tunnel t = tunnel;
        return t == null ? "" : t.dpdState();
    }

    /**
     * Сколько ping оценено в текущем сеансе (знаменатель для Loss).
     */
    public int pingEvaluated() {
        Tunnel t = tunnel;
        return t == null ? 0 : t.pingEval;
    }

    /**
     * Для строки статуса рядом с Recon/Loss: «Mig:1 | Heal:2 | Silence:3 | Kept:0».
     */
    public String extraStats() {
        return "Mig:" + migs + " | Heal:" + heals + " | Silence:" + silences + " | Kept:" + kept;
    }

    @Override
    public synchronized void disconnect() {
        want = false;
        cancel("disconnected");
    }

    /**
     * Из TunService.onStartCommand. false — запускать нечего, сервис можно гасить.
     */
    synchronized boolean serviceReady(TunService s) {
        if (!want) return false;
        want = false;
        this.svc = s;
        log("service ready, starting connection to " + host + ", online=" + watch.online());
        updateWake();
        watch.start(this::networkChanged);
        startRecovery(0, false);
        return true;
    }

    /**
     * Система отозвала VPN у экземпляра сервиса s. Реагируем только если это ТЕКУЩИЙ экземпляр:
     * onRevoke от старого, уже заменённого экземпляра (мы сами пересоздаём сервис) сеанс не трогает.
     */
    synchronized void revoked(TunService s) {
        log("onRevoke from " + System.identityHashCode(s) + ", current "
                + (svc == null ? "none" : String.valueOf(System.identityHashCode(svc))));
        if (s != svc) return;
        cancel("VPN was revoked by another VPN");
    }

    // ---------------------------------------------------------------- сеть

    /**
     * Сеть под VPN изменилась (другая, пропала, вернулась). Под замком.
     */
    private synchronized void networkChanged() {
        log("networkChanged: running=" + running + " want=" + want + " svc=" + (svc != null)
                + " tunnel=" + (tunnel != null ? "up=" + tunnel.isUp() + " mobike=" + tunnel.mobike() : "none"));
        if (!running || want || svc == null) return;
        boolean on = watch.online();
        boolean good = watch.validated();
        Tunnel t = tunnel;
        if (t != null) t.setOnline(on);
        if (!on) {
            if (t != null && t.isUp() && t.mobike()) {
                // MOBIKE: SA и tun переживают пропажу сети; по возвращении сети — migrate. Если сервер
                // за это время забыл SA, migrate не подтвердится и будет обычный реконнект.
                log("network lost: MOBIKE tunnel kept, waiting for the network");
                kept++;
                return;
            }
            log("network lost: restarting");
            restart("network lost");
            return;
        }
        if (!good && (t == null || !t.isUp())) {
            log("networkChanged: online but not validated, waiting");
            return;
        }
        if (t != null && t.isUp()) {
            if (t.mobike()) {
                final int g = gen;
                watch.mark();
                log("network changed: MOBIKE update");
                migs++;
                t.migrate(ok -> onMigrated(t, g, ok));
            } else if (watch.sameAsMarked()) {
                t.nudge();                                // та же сеть вернулась: проверить, жив ли путь
            } else {
                restart("network changed");
            }
        } else {
            restart("network changed");                   // идёт подъём или цикл: начинаем заново
        }
    }

    private synchronized void onMigrated(Tunnel t, int g, boolean ok) {
        if (g != gen || tunnel != t) return;
        if (ok) {
            log("MOBIKE update done");
            String f = t.iface();
            if (f != null) lis.onUp(f);                   // App перепривяжет сокеты
        } else {
            log("MOBIKE update failed: full reconnect");
            restart("network changed");
        }
    }

    // ---------------------------------------------------------------- управление сеансом

    /**
     * Полное переподключение с нуля (старый Tunnel молчит). Под замком.
     */
    private void restart(String reason) {
        log("restart: " + reason);
        gen++;
        Thread r = recovery;
        recovery = null;
        if (r != null) r.interrupt();
        Tunnel t = tunnel;
        tunnel = null;
        if (t != null) t.stop(reason);
        reconnects++;
        lis.onPingLoss(0);                            // новый реконнект: Loss с нуля
        lis.onReconnecting(reason, 1);                // 1 = начало нового цикла; App считает по нему Recon
        startRecovery(SETTLE_MS, false);
    }

    /**
     * Останавливает сеанс и восстановление, сообщает onDown (под замком).
     */
    private void cancel(String reason) {
        gen++;
        Thread r = recovery;
        recovery = null;
        Tunnel t = tunnel;
        if (r != null) {
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

    /**
     * Окончательный конец (фатальная ошибка): всё в исходное состояние. Под замком.
     */
    private void abandon(String reason) {
        gen++;
        recovery = null;
        Tunnel t = tunnel;
        tunnel = null;
        if (t != null) t.stop(reason);
        ctx.stopService(new Intent(ctx, TunService.class));
        clear();
        lis.onDown(reason);
    }

    private void clear() {
        running = false;
        want = false;
        updateWake();                       // VPN остановлен: wake lock не нужен (strict остаётся как есть)
        watch.stop();
        host = identity = password = psk = ca = apps = healthcheckIp = null;
        svc = null;
    }

    private Tunnel launch(Guard g) {
        Tunnel t = new Tunnel(new AndroidTun(svc), g);
        t.supervised = true;
        t.strict = strict;
        String hc = healthcheckIp == null ? "" : healthcheckIp.trim();
        t.pingHost = (hc.isEmpty() || hc.equals("0")) ? host : hc;           // если 0 или пусто — пингуем сам сервер
        int intervalSec = (pingIntervalSec <= 0) ? 5 : Math.max(5, Math.min(9, pingIntervalSec));
        t.pingMs = intervalSec * 1000;
        t.log = this::log;
        t.pathEvents = g;
        g.owner = t;
        tunnel = t;
        watch.mark();
        t.start(host, identity, psk, password, ca, apps);
        return t;
    }

    private void startRecovery(long firstWait, boolean unused) {   // под замком
        final int g = gen;
        final long w = firstWait;
        Thread t = new Thread(() -> recover(g, w), "vpn-recover");
        t.setDaemon(true);
        recovery = t;
        t.start();
    }

    /**
     * Цикл подъёма. Нет сети — ждём, попытки не тратим. Сетевая неудача — пауза и снова.
     * Фатальная (пароль, сертификат, алгоритмы, tun) — конец с onDown.
     */
    private void recover(int g, long firstWait) {
        long wait = firstWait;
        int step = 0;
        int attempts = 1;
        try {
            for (; ; ) {
                if (wait > 0) Thread.sleep(wait);
                boolean told = false;
                while (!watch.validated()) {
                    synchronized (this) {
                        if (g != gen) return;
                        if (!told) {
                            told = true;
                            lis.onReconnecting("waiting for network", attempts);
                        }
                    }
                    Thread.sleep(500);
                }
                Guard gd = new Guard();
                Tunnel t;
                synchronized (this) {
                    if (g != gen) return;
                    if (attempts > 1) lis.onReconnecting("reconnecting", attempts);
                    t = launch(gd);
                }
                boolean finished = gd.done.await(ATTEMPT_MS, TimeUnit.MILLISECONDS);
                log("attempt " + attempts + ": finished=" + finished + " ok=" + gd.ok
                        + " retryable=" + gd.retryable + " why=" + gd.why);
                if (finished && gd.ok) {
                    synchronized (this) {
                        if (recovery == Thread.currentThread()) recovery = null;
                    }
                    return;
                }
                synchronized (this) {
                    if (g != gen) return;
                    if (tunnel == t) tunnel = null;
                    if (finished && !gd.retryable) {       // фатально: повторять бессмысленно
                        abandon(gd.why == null ? "connection failed" : gd.why);
                        return;
                    }
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

    /**
     * Пропускает события только от действующего сеанса: старые, заменённые, молчат.
     */
    private final class Guard implements Listener, Tunnel.PathEvents {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Tunnel owner;
        volatile boolean ok;                       // дошла до onUp
        volatile boolean retryable;                // неудача сетевая, можно повторять
        volatile String why;

        /**
         * Лечение пути и срабатывания детекторов: отдельные счётчики, Recon и Loss не трогаем.
         */
        @Override
        public void heal(String why) {
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                heals++;
                log("heal #" + heals + ": " + why);
            }
        }

        @Override
        public void silence(String why) {
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                silences++;
                log("silence #" + silences + ": " + why);
            }
        }

        @Override
        public void onUp(String iface) {
            log("tunnel up: " + iface);
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                ok = true;
                lis.onUp(iface);
            }
            done.countDown();
        }

        @Override
        public void onDown(String reason) {
            log("tunnel down: " + reason + " (wasUp=" + ok + ")");
            why = reason;
            Tunnel o = owner;
            retryable = o != null && o.retryable;
            synchronized (VpnImpl.this) {
                if (tunnel == owner && ok) {       // поднятый сеанс закрыт окончательно
                    tunnel = null;
                    ctx.stopService(new Intent(ctx, TunService.class));
                    clear();
                    lis.onDown(reason);
                }
                // неудачная попытка молчит: решает цикл recover()
            }
            done.countDown();
        }

        /**
         * Поднятый сеанс оборвался: переподключаемся.
         */
        @Override
        public void onReconnecting(String reason) {
            log("tunnel lost: " + reason);
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                tunnel = null;
                reconnects++;
                lis.onPingLoss(0);                    // обрыв поднятого туннеля: Loss с нуля, Recon +1
                lis.onReconnecting(reason, 1);
                startRecovery(SETTLE_MS, false);
            }
        }

        @Override
        public void onPingLoss(int lostCount) {
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                lis.onPingLoss(lostCount);
            }
        }

        @Override
        public void onRekey(String info) {
            synchronized (VpnImpl.this) {
                if (tunnel != owner) return;
                lis.onRekey(info);
            }
        }
    }
}
