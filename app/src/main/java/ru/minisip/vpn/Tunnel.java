package ru.minisip.vpn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Один сеанс VPN: рукопожатие IKEv2, интерфейс tun и перекачка пакетов tun <-> ESP/UDP 4500.
 * Потоки: "vpn" (рукопожатие, потом таймеры: keepalive, DPD), "vpn-rx" (сокет -> tun),
 * "vpn-tun" (tun -> сокет), "vpn-mobike" (смена пути при смене сети).
 * <p>
 * Два режима (поле strict, меняется на лету):
 * - strict (АТС подключена): watchdog рвёт туннель, если за wdMs после отправки не пришло
 * ни одного ESP. Нужен быстрый отклик для телефонии.
 * - обычный VPN (strict = false): watchdog сначала пробует MOBIKE-refresh, при повторе —
 * реконнект; обрыв по тишине определяется ещё и по deadMs.
 * VpnImpl держит PARTIAL_WAKE_LOCK всегда, пока VPN запущен: поток vpn-keep (NAT-keepalive)
 * должен работать и при выключенном экране.
 * В обоих режимах есть детектор сна: если поток таймеров не работал дольше 5 с (CPU спал),
 * тишина за это время не считается обрывом: таймеры пересчитываются, сразу идут keepalive и DPD.
 * <p>
 * Правила живучести:
 * - сбой отправки UDP (нет сети) — это потеря пакета, а не обрыв (sendQuiet);
 * - пока сети нет (setOnline(false)), счётчики тишины стоят; когда она вернулась — сразу проверка;
 * - при смене сети с MOBIKE (RFC 4555) меняется только сокет, IKE SA, ESP и tun остаются.
 */
final class Tunnel {

    static final int MTU = 1400;
    /**
     * База экспоненциального retransmit IKE (RFC 7296 §2.4), совпадает с strongSwan.
     */
    static final double RTO_BASE = 1.8;
    /**
     * Сколько держим старый Esp для приёма после rekey (пока сервер не обработал наш DELETE).
     */
    static final long ESP_OLD_KEEP_MS = 10_000;
    /**
     * Таймаут ответа на наш CREATE_CHILD_SA.
     */
    static final long CHILD_REKEY_TIMEOUT_MS = 30_000;
    /**
     * Таймаут ответа на наш CREATE_CHILD_SA (IKE SA rekey).
     */
    static final long IKE_REKEY_TIMEOUT_MS = 30_000;
    static final long PROBE_GAP_MS = 10_000;
    /**
     * Сколько ждём возврата ESP после первого heal, прежде чем пересоздавать туннель.
     */
    static final long HEAL_GIVE_UP_MS = 120_000;
    /**
     * Без MOBIKE heal не умеет переключить путь: новый сокет сервер для IKE не подхватит,
     * его ответы уйдут на старый порт. Единственный рычаг — пересоздать туннель, поэтому
     * окно короче, чтобы АТС не теряла регистрацию на минуты.
     */
    static final long HEAL_GIVE_UP_PLAIN_MS = 30_000;
    static final long HEAL_MIN_GAP_MS = 5_000;
    private static final int RX_BUF = 65535;
    private static final int PING_ID = 0x4D53;
    private static final String TAG = "MiniSIP-VPN";
    private final Tun tun;
    private final Vpn.Listener ev;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean probing = new java.util.concurrent.atomic.AtomicBoolean();
    private final Object migLock = new Object();
    /**
     * Отдельный поток NAT-keepalive: не зависит от online, DPD, watchdog и остального в timers().
     */
    private final Object keepLock = new Object();
    // настраиваемое для проверок
    int ikePort = 500, natPort = 4500;
    int rto = 4000, tries = 5;
    int keepMs = 10000, dpdMs = 15000, deadMs = 30000;   // keepMs короче таймаута UDP-NAT оператора
    /**
     * Время жизни Child SA: 1 ч (strongSwan default, RFC 7296 §2.8.2).
     */
    int childLifetimeMs = 3_600_000;
    /**
     * Время жизни IKE SA: 3 ч (strongSwan default, RFC 7296 §2.8).
     */
    int ikeLifetimeMs = 3 * 3_600_000;
    /**
     * Необязательный ping через туннель: адрес (null — выключено). pingLoss промахов подряд — сначала
     * лечение (MOBIKE-refresh, а без MOBIKE — keepalive и DPD на том же сокете), не помогло — реконнект
     * через onReconnecting.
     */
    String pingHost;
    volatile int pingEval;             // сколько ping уже оценено (ответ или промах): знаменатель для Loss «потеряно/всего»
    int pingMs = 2000, pingLoss = 5;   // подряд без ответа -> MOBIKE, не вышло -> реконнект
    /**
     * true — обрыв поднятого туннеля сообщается через onReconnecting, решает владелец (VpnImpl).
     */
    boolean supervised;
    /**
     * Причина неудачи до подъёма сетевая (имеет смысл повторить), а не из-за настроек/пароля.
     */
    volatile boolean retryable;
    /**
     * true — АТС подключена: жёсткий watchdog. false — обычный VPN: watchdog только запускает DPD.
     */
    volatile boolean strict;
    volatile Log log;
    volatile PathEvents pathEvents;
    private volatile long txPk, rxEsp, rxIke, txFail;
    private volatile String lastSendErr = "";
    private volatile long rxBad;
    private volatile String lastRxErr = "";
    private volatile boolean up;
    private volatile boolean online = true;
    private volatile boolean kick;
    private volatile long lastRx;
    /**
     * Время последнего ESP от сервера (IKE сюда не входит): только он доказывает живой ESP-путь.
     */
    private volatile long lastEspRx;
    /**
     * Время последнего IKE-сообщения от сервера. probePath сверяет его, чтобы не рвать туннель,
     * когда старая SA ещё отвечает по IKE: ESP-тишина при этом может быть просто отсутствием трафика.
     */
    private volatile long lastIkeRx;
    /**
     * Сколько раз подряд путь признан мёртвым без единого ESP в ответ. Сбрасывается от ESP.
     */
    private volatile int healTries;
    private volatile long healSince, lastHealAct, lastProbe;
    private volatile String[] probeArgs;                       // identity, psk, password, ca, host: для пробного INIT
    private volatile int pongs;
    private volatile byte[] dpdReq;
    /**
     * Повторить неподтверждённый DPD сразу, с тем же номером (сбрасывать его нельзя: образуется дыра в ID).
     */
    private volatile boolean dpdNow;
    // фактическое состояние DPD (для подписи на кнопке): ответы на наши запросы и запросы сервера
    private volatile long dpdSentAt;
    private volatile int dpdOk, srvDpd;
    private volatile int dpdId = -1;
    private volatile int migId = -1;
    private volatile CountDownLatch migLatch;
    private volatile DatagramSocket sock;
    private volatile String iface;
    private byte[] pingSrc, pingDst;
    private InetAddress srv;
    private Ike ike;
    private Esp esp;
    /**
     * Старый Esp после rekey: принимаем пакеты, пока сервер не удалит старый SPI.
     */
    private volatile Esp espOld;
    private volatile long espOldAt;
    /**
     * Когда триггерить наш Child rekey; 0 — ждём ответа.
     */
    private volatile long childRekeyAt;
    private volatile long childRekeySentAt;
    private volatile int childRekeyFails;
    /**
     * Когда триггерить наш IKE SA rekey; 0 — ждём ответа.
     */
    private volatile long ikeRekeyAt;
    private volatile long ikeRekeySentAt;
    private Thread main, rx, tx, kp;
    private volatile long keepSent, lastKeepAt;
    Tunnel(Tun tun, Vpn.Listener ev) {
        this.tun = tun;
        this.ev = ev;
    }

    /**
     * Jitter ±10% (RFC 7296 §2.8.1): rekey у двух пиров не должен совпадать.
     */
    private static long jitter(long base) {
        return base + (long) ((Crypto.RND.nextDouble() - 0.5) * 0.2 * base);
    }

    private static InetAddress resolve(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) return a;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static int checksum(byte[] b, int off, int len) {
        long s = 0;
        for (int i = 0; i < len; i += 2) {
            s += ((b[off + i] & 0xff) << 8) | (i + 1 < len ? b[off + i + 1] & 0xff : 0);
        }
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    private void pathHeal(String why) {
        PathEvents p = pathEvents;
        if (p != null) p.heal(why);
    }

    private void pathSilence(String why) {
        PathEvents p = pathEvents;
        if (p != null) p.silence(why);
    }

    /**
     * Фактический статус DPD для подписи на кнопке:
     * Pending — ещё нет результата (запрос не отправлен или ждём первого ответа);
     * Active — сервер ответил на наш DPD, значит DPD поддерживается;
     * Active|Incoming — плюс сервер сам присылает DPD-запросы;
     * Unavailable — остальные случаи (наш запрос висит без ответа 5 с и больше).
     */
    String dpdState() {
        long now = System.currentTimeMillis();
        if (dpdReq != null && now - dpdSentAt >= 5000) return "Unavailable";
        if (dpdOk > 0 || srvDpd > 0) return srvDpd > 0 ? "Active|Incoming" : "Active";
        return "Pending";
    }

    Vpn.Listener listener() {
        return ev;
    }

    void start(String host, String identity, String psk, String password, String ca, String apps) {
        main = new Thread(() -> run(host, identity, psk, password, ca, apps), "vpn");
        main.setDaemon(true);
        main.start();
    }

    String ip() {
        Ike k = ike;
        return k == null ? null : k.ip;
    }

    String iface() {
        return iface;
    }

    boolean isUp() {
        return up && !closed.get();
    }

    /**
     * Сервер согласовал MOBIKE: смену сети можно пережить без пересоздания туннеля.
     */
    boolean mobike() {
        Ike k = ike;
        return up && !closed.get() && k != null && k.mobike;
    }

    /**
     * Есть ли сеть под VPN. Пока нет — не считаем тишину обрывом. Когда вернулась — проверяем путь.
     */
    void setOnline(boolean on) {
        boolean was = online;
        online = on;
        if (on && !was) {
            kick = true;
            wakeKeep();
        }
    }

    /**
     * Проверить путь сейчас: keepalive и DPD вне очереди.
     */
    void nudge() {
        kick = true;
        wakeKeep();
    }

    private void wakeKeep() {
        synchronized (keepLock) {
            keepLock.notifyAll();
        }
    }

    /**
     * NAT-keepalive (RFC 3948: один байт 0xFF на порт 4500) каждые keepMs. Безусловно: не смотрит на
     * online, DPD, watchdog, ping и migLatch. Сбой отправки — потеря пакета, следующий через keepMs.
     * setOnline(true), nudge() и пробуждение из сна будят поток для немедленной отправки.
     */
    private void keepLoop() {
        try {
            while (!closed.get()) {
                sendQuiet(new byte[]{(byte) 0xFF}, natPort);
                keepSent++;
                lastKeepAt = System.currentTimeMillis();
                synchronized (keepLock) {
                    keepLock.wait(Math.max(1000, keepMs));
                }
            }
        } catch (InterruptedException e) {
            // закрыли снаружи
        } catch (RuntimeException e) {
            // keepalive не должен умирать молча: перезапускаем цикл
            if (!closed.get()) {
                kp = daemon(this::keepLoop, "vpn-keep");
            }
        }
    }

    /**
     * Окончательное закрытие по просьбе пользователя или системы. Из любого потока.
     */
    void stop(String reason) {
        finish(reason);
    }

    private void alive() throws IOException {
        if (closed.get()) {
            DatagramSocket s = sock;
            if (s != null) s.close();
            throw new IOException("disconnected");
        }
    }

    private void fail(String reason, boolean canRetry) {
        retryable = canRetry;
        finish(reason);
    }

    // ================= рукопожатие =================

    private void run(String host, String identity, String psk, String password, String ca, String apps) {
        try {
            srv = resolve(host);
            alive();
            if (srv == null) {
                fail("cannot resolve the server (IPv4 address needed)", true);
                return;
            }
            note("resolved " + host + " -> " + srv.getHostAddress());
            sock = new DatagramSocket();
            alive();
            if (!tun.protect(sock)) {
                fail("cannot take the transport socket out of the VPN", false);
                return;
            }
            probeArgs = new String[]{identity, psk, password, ca, host};
            ike = new Ike(identity, psk, password, ca, host);
            String bad = ike.check();
            if (bad != null) {
                fail(bad, false);
                return;
            }

            byte[] r = null;
            int rc = 1;
            for (int round = 0; round < 2 && rc == 1; round++) {
                r = exchange(ike.initRequest(srv.getAddress(), ikePort), ikePort, false, 0);
                if (r == null) {
                    fail("server does not respond", true);
                    return;
                }
                rc = ike.onInit(r);
            }
            if (rc != 0) {
                fail(rc < 0 ? ike.error : "server keeps asking for a cookie", false);
                return;
            }
            note("IKE_SA_INIT done");
            byte[] req = ike.authRequest();
            for (int round = 0; req != null && round < 8; round++) {
                r = exchange(req, natPort, true, ike.curId);
                if (r == null) {
                    fail("server does not answer IKE_AUTH", true);
                    return;
                }
                int sentId = ike.curId;
                String err = ike.onAuth(r);
                if (err != null) {
                    fail(err, false);
                    return;
                }
                ike.acked(sentId);
                req = ike.next;
            }
            if (ike.esp == null) {
                fail("login did not finish", false);
                return;
            }
            note("IKE_AUTH done, ip=" + ike.ip);
            if (pingHost != null) {
                if (pingHost.isEmpty() || pingHost.equals("0")) {
                    pingHost = host;
                }
                pingSrc = InetAddress.getByName(ike.ip).getAddress();
                pingDst = InetAddress.getByName(pingHost).getAddress();
                if (pingSrc.length != 4 || pingDst.length != 4) pingHost = null;
            }
            alive();
            if (tun instanceof AndroidTun) ((AndroidTun) tun).transientFail = false;
            note("opening tun (mtu=" + MTU + ")");
            String name = tun.open(ike.ip, ike.dns, ike.routes, MTU, apps);
            if (name == null) {
                boolean isTransient = tun instanceof AndroidTun && ((AndroidTun) tun).transientFail;
                fail("cannot create the tun interface", isTransient);
                return;
            }
            note("tun " + name + " up");
            if (closed.get()) {
                tun.close();
                return;
            }
            iface = name;
            esp = ike.esp;
            sock.setSoTimeout(0);
            lastRx = System.currentTimeMillis();
            lastEspRx = lastRx;
            rx = daemon(this::rxLoop, "vpn-rx");
            tx = daemon(this::txLoop, "vpn-tun");
            kp = daemon(this::keepLoop, "vpn-keep");
            up = true;
            ev.onUp(name);
            childRekeyAt = System.currentTimeMillis() + jitter(childLifetimeMs);
            ikeRekeyAt = System.currentTimeMillis() + jitter(ikeLifetimeMs);
            timers();
        } catch (IOException | RuntimeException e) {
            retryable = true;
            lost(closed.get() ? "disconnected" : "network error: " + e.getMessage());
        }
    }

    private void note(String m) {
        Log l = log;
        if (l != null) l.d(m);
    }

    private byte[] exchange(byte[] req, int port, boolean marker, int id) throws IOException {
        byte[] buf = new byte[RX_BUF];
        double timeout = rto;
        for (int i = 0; i < tries; i++) {
            alive();
            send(req, port, marker);
            if (i > 0) {
                Log l = log;
                if (l != null)
                    l.d("retransmit #" + i + " (id=" + id + "): wait " + (long) timeout + "ms");
            }
            long end = System.currentTimeMillis() + (long) timeout;
            for (long left; (left = end - System.currentTimeMillis()) > 0; ) {
                sock.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                byte[] d = Arrays.copyOf(buf, p.getLength());
                if (marker) {
                    if (d.length < 32 || Crypto.u32(d, 0) != 0) continue;
                    d = Arrays.copyOfRange(d, 4, d.length);
                }
                if (!p.getAddress().equals(srv) || d.length < 28 || (d[19] & 0x20) == 0
                        || Crypto.u32(d, 20) != id) {
                    continue;
                }
                return d;
            }
            timeout *= RTO_BASE;
        }
        return null;
    }

    private void send(byte[] msg, int port, boolean marker) throws IOException {
        byte[] d = marker ? Crypto.cat(new byte[4], msg) : msg;
        sock.send(new DatagramPacket(d, d.length, srv, port));
    }

    /**
     * Отправка, которая не рвёт туннель: нет сети — это обычная потеря UDP-пакета.
     */
    private void sendQuiet(byte[] d, int port) {
        DatagramSocket s = sock;
        if (s == null) return;
        try {
            s.send(new DatagramPacket(d, d.length, srv, port));
        } catch (IOException | RuntimeException e) {
            txFail++;
            lastSendErr = String.valueOf(e);
        }
    }

    private void sendQuietIke(byte[] msg) {
        sendQuiet(Crypto.cat(new byte[4], msg), natPort);
    }

    private void rxLoop() {
        byte[] buf = new byte[RX_BUF];
        long lastLog = System.currentTimeMillis();
        long lastRxEsp = 0;
        try {
            while (!closed.get()) {
                long now = System.currentTimeMillis();
                if (now - lastLog >= 10000) {
                    Log l = log;
                    if (l != null)
                        l.d("rxLoop alive: rxEsp=" + rxEsp + " (delta=" + (rxEsp - lastRxEsp)
                                + "), rxIke=" + rxIke + ", online=" + online);
                    lastLog = now;
                    lastRxEsp = rxEsp;
                }
                DatagramSocket s = sock;
                if (s == null || s.isClosed()) {
                    Thread.sleep(100);
                    continue;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (IOException e) {
                    if (closed.get()) return;
                    if (s != sock) continue;
                    if (s.isClosed()) {
                        lost("rxLoop: socket closed");
                        return;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    continue;
                }
                try {
                    int n = p.getLength();
                    if (!p.getAddress().equals(srv) || n < 4) continue;
                    if (Crypto.u32(buf, 0) == 0) {
                        onIke(Arrays.copyOfRange(buf, 4, n));
                    } else {
                        byte[] ip = esp.unwrap(buf, n);
                        if (ip == null) {
                            Esp old = espOld;
                            if (old != null) ip = old.unwrap(buf, n);
                        }
                        if (ip != null) {
                            lastRx = System.currentTimeMillis();
                            lastEspRx = lastRx;
                            healTries = 0;
                            rxEsp++;
                            if (pong(ip)) {
                                pongs++;
                                continue;
                            }
                            tun.write(ip, 0, ip.length);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // Один плохой пакет (чужой формат, больше MTU, сбой tun.write) — не повод рвать туннель:
                    // если tun действительно умер, это увидит txLoop (read вернёт ошибку).
                    if (closed.get()) return;
                    rxBad++;
                    lastRxErr = String.valueOf(e);
                }
            }
        } catch (InterruptedException e) {
            // Thread.sleep interrupted
        } catch (RuntimeException e) {
            lost("rxLoop: unexpected RuntimeException: " + e.getMessage());
        }
    }

    private void txLoop() {
        byte[] b = new byte[2048];
        try {
            while (!closed.get()) {
                int n = tun.read(b);
                if (n < 0) {
                    lost("tunnel interface closed");
                    break;
                }
                if (n < 20 || (b[0] >> 4) != 4) continue;
                byte[] pkt = esp.wrap(b, n);
                sendQuiet(pkt, natPort);
                txPk++;
            }
            lost("tunnel interface closed");
        } catch (IOException e) {
            lost(closed.get() ? "disconnected" : "txLoop: " + e.getMessage());
        } catch (RuntimeException e) {
            lost(closed.get() ? "disconnected" : "txLoop: " + e.getMessage());
        }
    }

    // ================= рабочий режим =================

    /**
     * Сообщение IKE от сервера: ответ на наш DPD/MOBIKE, его DPD, удаление, попытка перегенерации.
     */
    private void onIke(byte[] m) {
        Ike.Rx x = ike.open(m);
        if (x == null) return;
        lastIkeRx = System.currentTimeMillis();
        // lastRx отражает только ESP: если сервер шлёт DPD, но ESP не идёт, путь мёртв.
        // Раньше lastRx обновлялся здесь, из-за чего deadMs никогда не наступал: сервер
        // отвечал на свои же DPD, lastRx не старел, наш DPD не отправлялся, а ESP-тишина
        // не детектилась до hangMs=60s.
        // lastRx = System.currentTimeMillis();  <-- убрано
        rxIke++;
        Log l = log;
        if (l != null) l.d("IKE from server: exch=" + x.exch + " resp=" + x.response
                + " id=" + x.id + " payloads=" + x.pl.size());
        if (x.response) {
            ike.acked(x.id);
            if (ike.childRekeyPending() && x.id == ike.childRekeyId()) {
                int mode0 = ike.childMode();
                Esp ne = ike.onRekeyChildResponse(x);
                if (ne != null) {
                    Esp oe = esp;
                    esp = ne;
                    if (oe != null) {
                        espOld = oe;
                        espOldAt = System.currentTimeMillis();
                        byte[] db = new byte[8];
                        db[0] = 3;
                        db[1] = 4;
                        db[2] = 0;
                        db[3] = 1;
                        Crypto.put32(db, 4, oe.spiIn());      // RFC 7296 §1.4.1: SPI своего входящего канала
                        byte[] del = Crypto.cat(new byte[4], ike.seal(Ike.INFO, false, ike.nextId(),
                                Collections.singletonList(new Ike.Pl(Ike.DELETE, db))));
                        sendQuiet(del, natPort);
                        if (l != null) l.d("rekey Child done: newSpiOut=" + ne.spiOut()
                                + " oldSpiOut=" + oe.spiOut());
                        ev.onRekey("Child rekey done: spi " + oe.spiOut() + " -> " + ne.spiOut());
                    }
                    childRekeyAt = System.currentTimeMillis() + jitter(childLifetimeMs);
                    childRekeyFails = 0;
                } else {
                    if (l != null) l.d("rekey Child failed: " + ike.error);
                    ev.onRekey("Child rekey failed: " + ike.error);
                    ike.abortChildRekey();
                    childRekeyAt = System.currentTimeMillis() + (ike.childMode() != mode0 && childRekeyFails++ < 4 ? 100 : 30_000L << Math.min(childRekeyFails++, 4));
                }
                return;
            }
            if (ike.ikeRekeyPending() && x.id == ike.ikeRekeyId()) {
                Ike.IkeRekeyMaterial ikem = ike.onRekeyIkeResponse(x);
                if (ikem != null) {
                    // DELETE старого IKE SA (RFC 7296 §2.8): подписываем СТАРЫМИ ключами и старым номером,
                    // это последний запрос по старому SA; только потом ставим новые ключи
                    byte[] db = new byte[]{1, 0, 0, 0};
                    byte[] del = Crypto.cat(new byte[4], ike.seal(Ike.INFO, false, ike.nextId(),
                            Collections.singletonList(new Ike.Pl(Ike.DELETE, db))));
                    ike.installRekeyedKeys(ikem, true);
                    sendQuiet(del, natPort);
                    if (l != null) l.d("IKE rekey done: DELETE old SA sent, nextId=0");
                    ev.onRekey("IKE rekey done");
                    ikeRekeyAt = System.currentTimeMillis() + jitter(ikeLifetimeMs);
                } else {
                    if (l != null) l.d("IKE rekey failed: " + ike.error);
                    ev.onRekey("IKE rekey failed: " + ike.error);
                    ike.abortIkeRekey();
                    ikeRekeyAt = System.currentTimeMillis() + 30_000;
                }
                return;
            }
            if (x.id == dpdId && dpdReq != null) {
                dpdReq = null;
                dpdOk++;
                lastRx = System.currentTimeMillis();
            }
            CountDownLatch latch = migLatch;
            if (x.id == migId && latch != null) latch.countDown();
            return;
        }
        if (x.exch == Ike.CHILD && ike.peekRekeyProto(x) == 1) {
            // ===== Входящий IKE SA rekey =====
            if (ike.ikeRekeyPending()) {
                if (l != null) l.d("IKE rekey collision: keeping our IKE, rejecting incoming");
                ev.onRekey("IKE rekey collision: keeping our IKE");
                List<Ike.Pl> no = Collections.singletonList(Ike.notify(Ike.N_NO_ADDITIONAL_SAS, new byte[0]));
                sendQuietIke(ike.seal(Ike.CHILD, true, x.id, no));
                return;
            }
            Ike.IkeRekeyResp ir = ike.onIncomingIkeRekey(x);
            if (ir == null) {
                if (l != null) l.d("incoming IKE rekey failed: " + ike.error);
                ev.onRekey("incoming IKE rekey failed: " + ike.error);
                List<Ike.Pl> no = Collections.singletonList(Ike.notify(Ike.N_NO_ADDITIONAL_SAS, new byte[0]));
                sendQuietIke(ike.seal(Ike.CHILD, true, x.id, no));
                return;
            }
            sendQuietIke(ike.seal(Ike.CHILD, true, x.id, ir.pls));
            // Мы респондер: старый IKE SA удаляет инициатор rekey, наш DELETE не нужен
            ike.installRekeyedKeys(ir.material, false);
            if (l != null) l.d("incoming IKE rekey done (initiator flag now cleared), nextId=0");
            ev.onRekey("IKE rekey done (responder)");
            ikeRekeyAt = System.currentTimeMillis() + jitter(ikeLifetimeMs);
            return;
        }
        if (x.exch == Ike.CHILD) {

            if (ike.childRekeyPending()) {
                // Коллизия: наш rekey уже в полёте. RFC 7296 §2.8.1 — упрощённо побеждает инициатор,
                // отклоняем входящий; сервер повторит позже.
                if (l != null) l.d("rekey collision: keeping our CHILD, rejecting incoming");
                ev.onRekey("rekey collision: keeping our CHILD");
                List<Ike.Pl> no = Collections.singletonList(Ike.notify(Ike.N_NO_ADDITIONAL_SAS, new byte[0]));
                sendQuietIke(ike.seal(Ike.CHILD, true, x.id, no));
                return;
            }
            Ike.ChildRekeyResp rr = ike.onIncomingChildRekey(x);
            if (rr == null) {
                if (l != null) l.d("incoming rekey failed: " + ike.error);
                ev.onRekey("incoming rekey failed: " + ike.error);
                List<Ike.Pl> no = Collections.singletonList(Ike.notify(Ike.N_NO_ADDITIONAL_SAS, new byte[0]));
                sendQuietIke(ike.seal(Ike.CHILD, true, x.id, no));
                return;
            }
            sendQuietIke(ike.seal(Ike.CHILD, true, x.id, rr.pls));
            Esp oe = esp;
            esp = rr.esp;
            if (oe != null) {
                espOld = oe;
                espOldAt = System.currentTimeMillis();
                if (l != null) l.d("incoming rekey done: newSpiIn=" + rr.esp.spiIn()
                        + " oldSpiIn=" + oe.spiIn());
                ev.onRekey("Child rekey done (responder): spi " + oe.spiIn() + " -> " + rr.esp.spiIn());
            }
            return;
        }
        if (x.exch != Ike.INFO) return;
        boolean gone = false;
        Esp curE = esp, oldE = espOld;
        long curOut = curE != null ? curE.spiOut() : ike.spiOut;
        long oldOut = oldE != null ? oldE.spiOut() : -1;
        List<Ike.Pl> reply = new ArrayList<>();
        for (Ike.Pl p : x.pl) {
            if (p.type != Ike.DELETE || p.body.length < 4) continue;
            int proto = p.body[0] & 0xff, size = p.body[1] & 0xff, cnt = Crypto.u16(p.body, 2);
            if (proto == 1) gone = true;
            for (int i = 0; proto == 3 && size == 4 && i < cnt && 4 + 4 * i + 4 <= p.body.length; i++) {
                long s = Crypto.u32(p.body, 4 + 4 * i);
                if (oldE != null && s == oldOut) {
                    // сервер закрывает старый Child SA после rekey: отвечаем Delete своего входящего SPI (§1.4.1)
                    byte[] rd = new byte[]{3, 4, 0, 1, 0, 0, 0, 0};
                    Crypto.put32(rd, 4, oldE.spiIn());
                    reply.add(new Ike.Pl(Ike.DELETE, rd));
                } else if (s == curOut) {
                    gone = true;
                }
            }
        }
        if (x.pl.isEmpty())
            srvDpd++;                    // пустой INFORMATIONAL от сервера = его DPD
        sendQuietIke(ike.seal(Ike.INFO, true, x.id, reply));
        if (gone) lost("server closed the tunnel");
    }

    /**
     * Поток "vpn" после подъёма: NAT-keepalive, DPD, необязательный ping и watchdog «шлём, а ответов нет».
     * Нет сети — стоим. Сеть вернулась — keepalive и DPD вне очереди.
     * Обрыв — когда сервер молчит deadMs при живой сети (а в strict-режиме ещё и по watchdog).
     */
    private void timers() {
        long lastDpd = 0, lastPing = 0, lastStat = 0;
        long lastWatchdog = System.currentTimeMillis();
        long prevTick = lastWatchdog;
        long wdRx = rxEsp, wdTx = txPk, wdIke = rxIke, wdPendingSince = 0;
        int totalPingLoss = 0;
        long hangRx = rxEsp, hangTx = txPk, hangSince = 0;   // ping выключен: страховка от «IKE жив, ESP нет»
        final long hangMs = 60000;
        final int hangPkts = 5;
        ev.onPingLoss(0);
        final long wdMs = 10000;   // дефолтный watchdog по RFC
        final long sleepGapMs = 5000;
        final long graceMs = 15000;   // после сна столько ждём ответа сервера, потом обрыв по deadMs
        long graceUntil = 0;
        int tick = Math.max(50, Math.min(1000, keepMs / 4));
        if (pingHost != null) tick = Math.min(tick, Math.max(50, pingMs / 4));
        int missed = 0, seen = pongs, seq = 0;
        boolean pinged = false;
        try {
            while (!closed.get()) {
                Thread.sleep(tick);
                long now = System.currentTimeMillis();

                // ===== ДЕТЕКТОР СНА: поток стоял дольше sleepGapMs (CPU спал) =====
                // Тишина за это время ничего не доказывает: счётчики пересчитываем, а путь
                // проверяем сразу (keepalive + DPD). Настоящий обрыв определится через deadMs.
                if (now - prevTick > sleepGapMs) {
                    Log l = log;
                    if (l != null)
                        l.d("resume after " + (now - prevTick) / 1000 + "s (strict=" + strict + ")");
                    sendQuiet(new byte[]{(byte) 0xFF}, natPort); // принудительный пробой NAT после сна
                    lastRx = Math.min(lastRx, now - dpdMs);   // DPD стартует сразу
                    // окно на ответ даётся один раз; повторные пробуждения его не продлевают
                    if (graceUntil <= now) graceUntil = now + graceMs;
                    dpdNow = true;
                    wdRx = rxEsp;
                    wdTx = txPk;
                    wdIke = rxIke;
                    wdPendingSince = 0;
                    hangSince = 0;
                    hangRx = rxEsp;
                    hangTx = txPk;
                    lastWatchdog = now;
                    wakeKeep();                               // keepalive вне очереди после сна
                    lastPing = now;
                    // missed и pinged НЕ сбрасываем: при Doze с периодическими пробуждениями
                    // счётчик потерь ping иначе никогда не дорастёт до pingLoss
                }
                prevTick = now;

                // ===== WATCHDOG: ушёл пакет, а ответного ESP нет wdMs =====
                // Отсчёт идёт от первого пакета без ответа. В простое не срабатывает.
                if (now - lastWatchdog >= 2000) {
                    lastWatchdog = now;
                    long rxNow = rxEsp, txNow = txPk, ikeNow = rxIke;
                    // Ping включён: живость ESP-пути доказывают только ESP (ответы на ping идут каждые pingMs),
                    // IKE-ответы её маскируют. Ping выключен: ESP в ответ приходит не всегда (приложение
                    // шлёт в пустоту, ретраи TCP/SIP), поэтому живым считаем и ответ IKE (DPD): иначе
                    // watchdog рвёт рабочий туннель каждые ~10 с. Обрыв тогда определяет DPD по deadMs.
                    boolean espOnly = pingHost != null;
                    boolean answered = rxNow != wdRx || (!espOnly && ikeNow != wdIke);
                    wdIke = ikeNow;
                    if (answered) {                   // ответ пришёл
                        wdRx = rxNow;
                        wdTx = txNow;
                        wdPendingSince = 0;
                    } else if (txNow != wdTx) {       // ушло новое, ответа нет
                        wdTx = txNow;
                        if (wdPendingSince == 0) wdPendingSince = now;
                    }
                    if (!up || !online || migLatch != null)
                        wdPendingSince = 0;   // во время migrate не считаем
                    // Ping выключен, и ESP от сервера нет hangMs, хотя мы отправили hangPkts+ пакетов, а IKE
                    // отвечает (DPD это не ловит). Сначала лечим путь (MOBIKE-refresh), повтор — реконнект.
                    if (!espOnly && up && online) {
                        if (rxNow != hangRx) {
                            hangRx = rxNow;
                            hangTx = txNow;
                            hangSince = 0;
                        } else if (hangSince == 0 && txNow - hangTx >= hangPkts) {
                            hangSince = now;
                        }
                        if (hangSince != 0 && now - hangSince >= hangMs) {
                            hangSince = 0;
                            hangTx = txNow;
                            Log hl = log;
                            if (hl != null)
                                hl.d("no ESP for " + hangMs / 1000 + "s while sending, IKE alive");
                            pathSilence("no ESP while IKE alive");
                            heal("no ESP replies while IKE answers");
                            if (closed.get()) return;
                        }
                    } else {
                        hangSince = 0;
                        hangRx = rxNow;
                        hangTx = txNow;
                    }
                    if (wdPendingSince != 0 && now - wdPendingSince >= wdMs) {
                        Log l = log;
                        long silent = (now - wdPendingSince) / 1000;
                        wdPendingSince = 0;
                        if (strict || espOnly) {
                            // Сначала лечим путь (MOBIKE-refresh, а без MOBIKE keepalive+DPD на том же сокете),
                            // и только при повторе без ESP — реконнект. Реконнект закрывает tun: система
                            // сообщает «отключено от постоянной VPN», а АТС теряет регистрацию.
                            if (l != null)
                                l.d("WATCHDOG(" + (strict ? "strict" : "soft") + "): no ESP back for "
                                        + silent + "s after send; " + esp.drops());
                            pathSilence("watchdog");
                            heal("no ESP replies while sending");
                            if (closed.get()) return;
                        } else {
                            // обычный VPN без ping: не рвём, а просим DPD проверить путь; решит deadMs
                            if (l != null)
                                l.d("WATCHDOG(soft): silence for " + silent + "s; start DPD");
                            lastRx = Math.min(lastRx, now - dpdMs);
                        }
                    }
                }

                // ================= СТАТИСТИКА =================
                if (now - lastStat >= 10000) {
                    lastStat = now;
                    Log l = log;
                    if (l != null) {
                        l.d("stats: txEsp=" + txPk + " rxEsp=" + rxEsp + " rxIke=" + rxIke
                                + " sendFail=" + txFail + (txFail > 0 ? " lastErr=" + lastSendErr : "")
                                + " idleRx=" + (now - lastRx) / 1000 + "s idleEsp=" + (now - lastEspRx) / 1000
                                + "s online=" + online
                                + " strict=" + strict
                                + " keepSent=" + keepSent + " lastKeep=" + (now - lastKeepAt) / 1000 + "s ago"
                                + " dpdPending=" + (dpdReq != null) + " rxBad=" + rxBad + (rxBad > 0 ? " lastRxErr=" + lastRxErr : "")
                                + " " + esp.drops());
                    }
                }

                // ================= СЕТЬ: если её нет — ничего не делаем =================
                if (!online) {
                    lastRx = now;
                    dpdNow = true;
                    pinged = false;
                    continue;
                }

                // ================= СЕТЬ ВЕРНУЛАСЬ: сбросить таймеры =================
                if (kick) {
                    kick = false;
                    dpdNow = true;
                    lastRx = Math.min(lastRx, now - dpdMs);
                }

                // KEEPALIVE вынесен в отдельный поток keepLoop() и шлётся всегда

                // ================= PING: healthcheck (подсчет потерь + авто-реконнект при достижении pingLoss) =================
                if (pingHost != null && now - lastPing >= pingMs) {
                    if (pinged) {
                        pingEval++;
                        if (pongs != seen || lastEspRx > lastPing) {   // только ESP, не IKE
                            seen = pongs;
                            missed = 0;
                        } else {
                            missed++;
                            totalPingLoss++;
                            Vpn.Listener l = ev;
                            if (l != null) l.onPingLoss(totalPingLoss);
                        }
                    }
                    if (missed >= pingLoss && migLatch == null) {
                        missed = 0;
                        Log l = log;
                        if (l != null) l.d("ping healthcheck: " + pingLoss + " losses reached");
                        heal("ESP path dead (ping loss)");
                        if (closed.get()) return;
                    }
                    byte[] ip = echo(++seq);
                    sendQuiet(esp.wrap(ip, ip.length), natPort);
                    pinged = true;
                    lastPing = now;
                }

                // ================= DPD: Dead Peer Detection =================
                long idle = now - lastRx;

                if (idle >= deadMs && now >= graceUntil && now - lastIkeRx > deadMs) {
                    // Сначала один раз лечим путь (после сна NAT-маппинг мёртв), потом уже реконнект.
                    pathSilence("deadMs");
                    long before = lastHealAct;
                    heal("server does not respond");
                    if (closed.get()) return;
                    // Если heal реально сработал (lastHealAct обновился), перезапускаем DPD;
                    // если троттлится — не трогаем lastRx, следующий тик снова попадёт сюда.
                    if (lastHealAct != before) {
                        lastRx = now - dpdMs;
                        idle = dpdMs;
                    }
                }

                if (dpdReq == null && idle >= dpdMs && now - dpdSentAt >= dpdMs) {
                    dpdNow = false;
                    dpdId = ike.nextId();
                    dpdSentAt = now;
                    dpdReq = Crypto.cat(new byte[4], ike.seal(Ike.INFO, false, dpdId, new ArrayList<>()));
                    lastDpd = 0;
                }

                byte[] d = dpdReq;
                if (d != null && (dpdNow || now - lastDpd >= Math.max(100, dpdMs / 6))) {
                    dpdNow = false;
                    sendQuiet(d, natPort);
                    lastDpd = now;
                }

                // ===== Child SA rekey (RFC 7296 §2.8.2) =====
                if (childRekeyAt > 0 && now >= childRekeyAt && up && online && !ike.childRekeyPending()) {
                    Esp cur = esp;
                    List<Ike.Pl> pls = cur == null ? null : ike.rekeyChildPl(cur.spiIn());
                    if (pls != null) {
                        int rid = ike.childRekeyId();
                        byte[] req = Crypto.cat(new byte[4], ike.seal(Ike.CHILD, false, rid, pls));
                        sendQuiet(req, natPort);
                        childRekeySentAt = now;
                        childRekeyAt = 0;
                        Log l = log;
                        if (l != null) l.d("rekey Child: sent id=" + rid);
                        ev.onRekey("Child rekey initiated: id=" + rid);
                    }
                }
                if (ike.childRekeyPending() && now - childRekeySentAt > CHILD_REKEY_TIMEOUT_MS) {
                    ike.abortChildRekey();
                    childRekeyAt = now + 30_000;
                    Log l = log;
                    if (l != null) l.d("rekey Child: no response, retry in 30s");
                }
                if (espOld != null && now - espOldAt > ESP_OLD_KEEP_MS) {
                    espOld = null;
                }
                Log l = log;
                // ===== IKE SA rekey (RFC 7296 §2.8). Не пересекаемся с Child rekey =====
                if (ikeRekeyAt > 0 && now >= ikeRekeyAt && up && online
                        && !ike.ikeRekeyPending() && !ike.childRekeyPending()) {
                    List<Ike.Pl> pls = ike.rekeyIkePl();
                    if (pls != null) {
                        int rid = ike.ikeRekeyId();
                        byte[] req = Crypto.cat(new byte[4], ike.seal(Ike.CHILD, false, rid, pls));
                        sendQuiet(req, natPort);
                        ikeRekeySentAt = now;
                        ikeRekeyAt = 0;
                        if (l != null) l.d("IKE rekey: sent id=" + rid);
                        ev.onRekey("IKE rekey initiated: id=" + rid);
                    }
                }
                if (ike.ikeRekeyPending() && now - ikeRekeySentAt > IKE_REKEY_TIMEOUT_MS) {
                    ike.abortIkeRekey();
                    ikeRekeyAt = now + 30_000;
                    if (l != null) l.d("IKE rekey: no response, retry in 30s");
                }
            }
        } catch (InterruptedException e) {
            // закрыли снаружи
        } catch (RuntimeException e) {
            lost(closed.get() ? "disconnected" : "internal error: " + e.getMessage());
        }
    }

    /**
     * Лечение не помогло (ESP не вернулся). Отвечает ли сервер на свежий IKE_SA_INIT с нового сокета?
     * Да: сеть жива, значит NAT-маппинг или SA на сервере мертвы (роутер переподключился, смена IP),
     * и ждать остаток окна бессмысленно: пересоздаём туннель сразу. Нет: путь ещё лежит, ждём дальше.
     */
    private void probePath(String why) {
        long now = System.currentTimeMillis();
        String[] a = probeArgs;
        if (a == null || now - lastProbe < PROBE_GAP_MS || !probing.compareAndSet(false, true))
            return;
        lastProbe = now;
        daemon(() -> {
            DatagramSocket ps = null;
            try {
                Ike k = new Ike(a[0], a[1], a[2], a[3], a[4]);
                byte[] req = k.initRequest(srv.getAddress(), ikePort);
                ps = new DatagramSocket();
                if (!tun.protect(ps)) return;
                ps.setSoTimeout(3000);
                ps.send(new DatagramPacket(req, req.length, srv, ikePort));
                byte[] buf = new byte[2048];
                DatagramPacket rp = new DatagramPacket(buf, buf.length);
                ps.receive(rp);
                long t = System.currentTimeMillis();
                if (rp.getLength() >= 28 && srv.equals(rp.getAddress()) && healTries > 0 && !closed.get()
                        && t - lastIkeRx > deadMs) {
                    Log l = log;
                    if (l != null)
                        l.d(why + ": server answers a fresh IKE_SA_INIT, old path/SA is dead: reconnect");
                    lost(why + ": server reachable, old path dead");
                }
            } catch (IOException | RuntimeException ignored) {
                // нет ответа: путь ещё недоступен, ждём возврата ESP или конца окна
            } finally {
                if (ps != null) ps.close();
                probing.set(false);
            }
        }, "vpn-probe");
    }

    private boolean heal(String why) {
        long now = System.currentTimeMillis();
        int n = ++healTries;
        if (n == 1) healSince = now;
        Log l = log;
        // Реконнект закрывает tun («отключено от постоянной VPN», АТС теряет регистрацию), а пропажи пути
        // (смена вышки, провал радиоканала) обычно проходят сами. Ждём окно тишины ESP, но считаем его
        // от реального начала тишины (lastEspRx), а не от первого heal: heal мог опоздать (hangMs
        // требует новых отправок), а без MOBIKE heal всё равно ничего не лечит — там окно короче.
        long giveUp = (ike != null && ike.mobike) ? HEAL_GIVE_UP_MS : HEAL_GIVE_UP_PLAIN_MS;
	if (now - healSince >= giveUp) {
            if (l != null)
                l.d(why + ": no ESP for " + (now - lastEspRx) / 1000 + "s: reconnect");
            lost(why);
            return true;
        }
        // В окне HEAL_MIN_GAP_MS повторный heal ничего не делает. Возвращаем true — «сейчас
        // не лечим», чтобы timers() НЕ сбрасывал lastRx и не запускал новый DPD: иначе DPD
        // уходит каждые ~1.6 с, реальной попытки нет, а окно HEAL_GIVE_UP_MS тянется.
        if (n > 1 && now - lastHealAct < HEAL_MIN_GAP_MS) {
            if (l != null) l.d(why + ": heal throttled (last attempt "
                    + (now - lastHealAct) + "ms ago)");
            return false;
        }
        lastHealAct = now;
        if (n > 1) probePath(why);
        if (ike != null && ike.mobike) {
            if (migLatch != null) return false;                           // migrate уже идёт
            if (l != null) l.d(why + ": trying MOBIKE refresh (heal #" + n + ")");
            pathHeal(why);
            migrate(ok -> {
                if (l != null) l.d(why + ": MOBIKE refresh result ok=" + ok);
                // неудача посреди окна — не повод рвать: путь может вернуться, следующий heal повторит
                if (!ok && System.currentTimeMillis() - healSince >= HEAL_GIVE_UP_MS) {
                    lost(why + ": MOBIKE refresh failed");
                }
            });
            return false;
        }
        if (l != null)
            l.d(why + ": MOBIKE not negotiated, refreshing NAT on the same socket (heal #" + n + ")");
        pathHeal(why);
        wakeKeep();
        dpdNow = true;
        lastRx = Math.min(lastRx, now - dpdMs);   // DPD стартует сразу
        return false;
    }

    /**
     * ESP-путь признан мёртвым (DPD по IKE при этом может отвечать: NAT-маппинг сменился).
     * Первая попытка лечения:
     *  - MOBIKE согласован: MOBIKE-refresh (новый сокет + UPDATE_SA_ADDRESSES); не подтвердился —
     *    реконнект;
     *  - MOBIKE не согласован: сокет НЕ меняем (новый порт сервер для IKE не подхватит, его IKE-сообщения
     *    уйдут на старый порт и потеряются). Вместо этого тот же сокет: keepalive и DPD вне очереди,
     *    и ещё одна серия пингов.
     * Повторно без единого ESP — обрыв, дальше решает владелец через onReconnecting.
     * @return true, если туннель закрыт и timers() должен завершиться.
     * false во всех остальных случаях, включая троттлинг.
     */

    /**
     * Смена сети без пересоздания туннеля (RFC 4555): новый защищённый сокет, UPDATE_SA_ADDRESSES
     * с нового пути. Асинхронно; done.done(true) — сервер подтвердил, false — полное переподключение.
     */
    void migrate(Done done) {
        Thread t = new Thread(() -> {
            boolean ok = doMigrate();
            if (!closed.get()) done.done(ok);
        }, "vpn-mobike");
        t.setDaemon(true);
        t.start();
    }

    private boolean doMigrate() {
        synchronized (migLock) {
            if (closed.get() || !up || ike == null || !ike.mobike) return false;
            CountDownLatch l = new CountDownLatch(1);
            try {
                DatagramSocket ns = new DatagramSocket();
                if (!tun.protect(ns)) {
                    ns.close();
                    return false;
                }
                DatagramSocket old = sock;
                sock = ns;                                    // дальше ESP и keepalive идут новым путём
                if (old != null) old.close();                 // rxLoop подхватит новый сокет
                // Неподтверждённый DPD доводим до ответа новым путём: иначе сервер ждёт его номер,
                // а наш UPDATE_SA_ADDRESSES с большим номером он проигнорирует.
                for (int i = 0; i < 40 && dpdReq != null && !closed.get(); i++) {
                    byte[] pend = dpdReq;
                    if (pend != null && i % 10 == 0) sendQuiet(pend, natPort);
                    Thread.sleep(50);
                }
                migId = ike.nextId();
                migLatch = l;
                byte[] req = Crypto.cat(new byte[4],
                        ike.seal(Ike.INFO, false, migId, ike.mobikeUpdate(srv.getAddress(), natPort)));
                for (int i = 0; i < 5 && !closed.get(); i++) {
                    sendQuiet(req, natPort);
                    if (l.await(2000, TimeUnit.MILLISECONDS)) {
                        lastRx = System.currentTimeMillis();
                        dpdReq = null;
                        return true;
                    }
                }
                return false;
            } catch (IOException | RuntimeException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                migLatch = null;
            }
        }
    }

    // ================= MOBIKE =================

    private byte[] echo(int seq) {
        byte[] p = new byte[36];
        p[0] = 0x45;
        Crypto.put16(p, 2, p.length);
        Crypto.put16(p, 4, seq);
        Crypto.put16(p, 6, 0x4000);
        p[8] = 64;
        p[9] = 1;
        System.arraycopy(pingSrc, 0, p, 12, 4);
        System.arraycopy(pingDst, 0, p, 16, 4);
        Crypto.put16(p, 10, checksum(p, 0, 20));
        p[20] = 8;
        Crypto.put16(p, 24, PING_ID);
        Crypto.put16(p, 26, seq & 0xffff);
        Crypto.put32(p, 28, System.nanoTime() >>> 10);
        Crypto.put16(p, 22, checksum(p, 20, 16));
        return p;
    }

    private boolean pong(byte[] ip) {
        byte[] dst = pingDst;
        if (dst == null || ip.length < 28 || (ip[0] >> 4) != 4 || ip[9] != 1) return false;
        int ihl = (ip[0] & 0x0f) * 4;
        if (ihl < 20 || ip.length < ihl + 8 || ip[ihl] != 0 || Crypto.u16(ip, ihl + 4) != PING_ID)
            return false;
        for (int i = 0; i < 4; i++) {
            if (ip[12 + i] != dst[i]) return false;
        }
        return true;
    }

    // ================= ping =================

    private void finish(String reason) {
        end(reason, false);
    }

    /**
     * Обрыв связи. Не supervised или туннель ещё не поднимался — обычное закрытие (onDown).
     * Иначе onReconnecting, дальше решает VpnImpl. Чужие VPN не учитываются: вытеснение
     * определяет только onRevoke текущего сервиса.
     */
    private void lost(String reason) {
        if (closed.get()) return;
        if (!supervised || !up) {
            finish(reason);
            return;
        }
        end(reason, true);
    }

    /**
     * Просим сервер удалить IKE SA, чтобы он не держал её и не слал DPD на закрытый порт.
     * Шлём, если IKE_AUTH хотя бы начинался (а не только при up). Номер сообщения сервер принимает
     * только ожидаемый, поэтому шлём подряд все возможные номера (от первого неподтверждённого).
     * Дважды: с текущего сокета и со свежего, чтобы Delete имел шанс уйти по новой сети.
     */
    private void sendDelete() {
        Ike k = ike;
        if (k == null || !(up || k.esp != null || k.curId > 0)) return;
        List<byte[]> msgs = new ArrayList<>();
        try {
            List<Ike.Pl> del = Collections.singletonList(new Ike.Pl(Ike.DELETE, new byte[]{1, 0, 0, 0}));
            int lo = k.ackFloor(), hi = k.nextId();
            if (hi - lo > 7) lo = hi - 7;
            for (int id = lo; id <= hi; id++) {
                msgs.add(Crypto.cat(new byte[4], k.seal(Ike.INFO, false, id, del)));
            }
        } catch (RuntimeException e) {
            return;
        }
        for (byte[] m : msgs) sendQuiet(m, natPort);
        DatagramSocket ns = null;
        try {
            ns = new DatagramSocket();
            if (tun.protect(ns)) {
                for (byte[] m : msgs) ns.send(new DatagramPacket(m, m.length, srv, natPort));
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            if (ns != null) ns.close();
        }
    }

    // ================= завершение =================

    private void end(String reason, boolean retry) {
        if (!closed.compareAndSet(false, true)) return;
        android.util.Log.w(TAG, "Tunnel.end reason=" + reason + " retry=" + retry
                + " thread=" + Thread.currentThread().getName(), new Throwable());
        // Не из вызывающего потока: stop() зовут и с главного, где сокетный I/O запрещён (StrictMode),
        // и под замком VpnImpl. Ждём недолго, чтобы Delete успел уйти до закрытия сокета.
        try {
            daemon(this::sendDelete, "vpn-delete").join(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        up = false;
        DatagramSocket s = sock;
        if (s != null) s.close();
        tun.close();
        for (Thread t : new Thread[]{main, rx, tx, kp}) {
            if (t != null && t != Thread.currentThread()) t.interrupt();
        }
        if (retry) ev.onReconnecting(reason);
        else ev.onDown(reason);
    }

    /**
     * Результат миграции пути.
     */
    interface Done {
        void done(boolean ok);
    }

    /**
     * Диагностика: если задан, сюда раз в 10 с идёт сводка счётчиков.
     */
    interface Log {
        void d(String s);
    }

    /**
     * События без пересоздания туннеля: владелец ведёт по ним отдельные счётчики (не Recon и не Loss).
     */
    interface PathEvents {
        void heal(String why);       // лечение пути: MOBIKE-refresh или NAT-refresh

        void silence(String why);    // сработал детектор тишины (watchdog, deadMs, нет ESP при живом IKE)
    }
}
