package ru.minisip.vpn;

import android.util.Log;

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
 *
 * Два режима (поле strict, меняется на лету):
 *  - strict (АТС подключена; VpnImpl держит wake lock): watchdog рвёт туннель, если за wdMs
 *    после отправки не пришло ни одного ESP. Нужен быстрый отклик для телефонии.
 *  - обычный VPN (strict = false): watchdog лишь запускает DPD, а настоящий обрыв определяется
 *    только по deadMs тишины.
 * В обоих режимах есть детектор сна: если поток таймеров не работал дольше 5 с (CPU спал),
 * тишина за это время не считается обрывом: таймеры пересчитываются, сразу идут keepalive и DPD.
 *
 * Правила живучести:
 *  - сбой отправки UDP (нет сети) — это потеря пакета, а не обрыв (sendQuiet);
 *  - пока сети нет (setOnline(false)), счётчики тишины стоят; когда она вернулась — сразу проверка;
 *  - при смене сети с MOBIKE (RFC 4555) меняется только сокет, IKE SA, ESP и tun остаются.
 */
final class Tunnel {

    /** Результат миграции пути. */
    interface Done {
        void done(boolean ok);
    }

    static final int MTU = 1400;
    private static final int RX_BUF = 65535;
    private static final int PING_ID = 0x4D53;
    private static final String TAG = "MiniSIP-VPN";

    // настраиваемое для проверок
    int ikePort = 500, natPort = 4500;
    int rto = 3000, tries = 4;
    int keepMs = 20000, dpdMs = 10000, deadMs = 30000;
    /** Необязательный ping через туннель: адрес (null — выключено). Промахи только запускают DPD. */
    String pingHost;
    int pingMs = 2000, pingLoss = 3;
    /** true — обрыв поднятого туннеля сообщается через onReconnecting, решает владелец (VpnImpl). */
    boolean supervised;
    /** Причина неудачи до подъёма сетевая (имеет смысл повторить), а не из-за настроек/пароля. */
    volatile boolean retryable;
    /** true — АТС подключена: жёсткий watchdog. false — обычный VPN: watchdog только запускает DPD. */
    volatile boolean strict;

    /** Диагностика: если задан, сюда раз в 10 с идёт сводка счётчиков. */
    interface Log {
        void d(String s);
    }

    volatile Log log;

    private volatile long txPk, rxEsp, rxIke, txFail;
    private volatile String lastSendErr = "";

    private final Tun tun;
    private final Vpn.Listener ev;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean up;
    private volatile boolean online = true;
    private volatile boolean kick;
    private volatile long lastRx;
    private volatile int pongs;
    private volatile byte[] dpdReq;
    private volatile int dpdId = -1;
    private volatile int migId = -1;
    private volatile CountDownLatch migLatch;
    private volatile DatagramSocket sock;
    private volatile String iface;
    private final Object migLock = new Object();
    private byte[] pingSrc, pingDst;
    private InetAddress srv;
    private Ike ike;
    private Esp esp;
    private Thread main, rx, tx;

    Tunnel(Tun tun, Vpn.Listener ev) {
        this.tun = tun;
        this.ev = ev;
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

    /** Сервер согласовал MOBIKE: смену сети можно пережить без пересоздания туннеля. */
    boolean mobike() {
        Ike k = ike;
        return up && !closed.get() && k != null && k.mobike;
    }

    /** Есть ли сеть под VPN. Пока нет — не считаем тишину обрывом. Когда вернулась — проверяем путь. */
    void setOnline(boolean on) {
        boolean was = online;
        online = on;
        if (on && !was) kick = true;
    }

    /** Проверить путь сейчас: keepalive и DPD вне очереди. */
    void nudge() {
        kick = true;
    }

    /** Окончательное закрытие по просьбе пользователя или системы. Из любого потока. */
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
            sock = new DatagramSocket();
            alive();
            if (!tun.protect(sock)) {
                fail("cannot take the transport socket out of the VPN", false);
                return;
            }
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
            byte[] req = ike.authRequest();
            for (int round = 0; req != null && round < 8; round++) {
                r = exchange(req, natPort, true, ike.curId);
                if (r == null) {
                    fail("server does not answer IKE_AUTH", true);
                    return;
                }
                String err = ike.onAuth(r);
                if (err != null) {
                    fail(err, false);
                    return;
                }
                req = ike.next;
            }
            if (ike.esp == null) {
                fail("login did not finish", false);
                return;
            }
            if (pingHost != null) {
                pingSrc = InetAddress.getByName(ike.ip).getAddress();
                pingDst = InetAddress.getByName(pingHost).getAddress();
                if (pingSrc.length != 4 || pingDst.length != 4) pingHost = null;
            }
            alive();
            String name = tun.open(ike.ip, ike.dns, ike.routes, MTU, apps);
            if (name == null) {
                fail("cannot create the tun interface", false);
                return;
            }
            if (closed.get()) {
                tun.close();
                return;
            }
            iface = name;
            esp = ike.esp;
            sock.setSoTimeout(0);
            lastRx = System.currentTimeMillis();
            rx = daemon(this::rxLoop, "vpn-rx");
            tx = daemon(this::txLoop, "vpn-tun");
            up = true;
            ev.onUp(name);
            timers();
        } catch (IOException | RuntimeException e) {
            retryable = true;
            lost(closed.get() ? "disconnected" : "network error: " + e.getMessage());
        }
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

    private byte[] exchange(byte[] req, int port, boolean marker, int id) throws IOException {
        byte[] buf = new byte[RX_BUF];
        for (int i = 0; i < tries; i++) {
            alive();
            send(req, port, marker);
            long end = System.currentTimeMillis() + rto;
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
        }
        return null;
    }

    private void send(byte[] msg, int port, boolean marker) throws IOException {
        byte[] d = marker ? Crypto.cat(new byte[4], msg) : msg;
        sock.send(new DatagramPacket(d, d.length, srv, port));
    }

    /** Отправка, которая не рвёт туннель: нет сети — это обычная потеря UDP-пакета. */
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

    // ================= рабочий режим =================

    private void rxLoop() {
        byte[] buf = new byte[RX_BUF];
        long lastLog = System.currentTimeMillis();
        long lastRxEsp = 0;
        try {
            while (!closed.get()) {
                long now = System.currentTimeMillis();
                if (now - lastLog >= 10000) {
                    Log l = log;
                    if (l != null) l.d("rxLoop alive: rxEsp=" + rxEsp + " (delta=" + (rxEsp - lastRxEsp)
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
                        if (ip != null) {
                            lastRx = System.currentTimeMillis();
                            rxEsp++;
                            if (pong(ip)) {
                                pongs++;
                                continue;
                            }
                            tun.write(ip, 0, ip.length);
                        }
                    }
                } catch (IOException e) {
                    lost("rxLoop: tun.write() failed: " + e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    lost("rxLoop: RuntimeException: " + e.getMessage());
                    return;
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

    /** Сообщение IKE от сервера: ответ на наш DPD/MOBIKE, его DPD, удаление, попытка перегенерации. */
    private void onIke(byte[] m) {
        Ike.Rx x = ike.open(m);
        if (x == null) return;
        lastRx = System.currentTimeMillis();
        rxIke++;
        Log l = log;
        if (l != null) l.d("IKE from server: exch=" + x.exch + " resp=" + x.response
                + " id=" + x.id + " payloads=" + x.pl.size());
        if (x.response) {
            if (x.id == dpdId) dpdReq = null;
            CountDownLatch latch = migLatch;
            if (x.id == migId && latch != null) latch.countDown();
            return;
        }
        if (x.exch == Ike.CHILD) {
            List<Ike.Pl> no = Collections.singletonList(Ike.notify(Ike.N_NO_ADDITIONAL_SAS, new byte[0]));
            sendQuietIke(ike.seal(Ike.CHILD, true, x.id, no));
            return;
        }
        if (x.exch != Ike.INFO) return;
        boolean gone = false;
        for (Ike.Pl p : x.pl) {
            if (p.type != Ike.DELETE || p.body.length < 4) continue;
            int proto = p.body[0] & 0xff, size = p.body[1] & 0xff, cnt = Crypto.u16(p.body, 2);
            if (proto == 1) gone = true;
            for (int i = 0; proto == 3 && size == 4 && i < cnt && 4 + 4 * i + 4 <= p.body.length; i++) {
                if (Crypto.u32(p.body, 4 + 4 * i) == ike.spiOut) gone = true;
            }
        }
        sendQuietIke(ike.seal(Ike.INFO, true, x.id, new ArrayList<>()));
        if (gone) lost("server closed the tunnel");
    }

    /**
     * Поток "vpn" после подъёма: NAT-keepalive, DPD, необязательный ping и watchdog «шлём, а ответов нет».
     * Нет сети — стоим. Сеть вернулась — keepalive и DPD вне очереди.
     * Обрыв — когда сервер молчит deadMs при живой сети (а в strict-режиме ещё и по watchdog).
     */
    private void timers() {
        long lastKeep = System.currentTimeMillis(), lastDpd = 0, lastPing = 0, lastStat = 0;
        long lastWatchdog = System.currentTimeMillis();
        long prevTick = lastWatchdog;
        long wdRx = rxEsp, wdTx = txPk, wdIke = rxIke, wdPendingSince = 0;
        int totalPingLoss = 0;
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
                    if (l != null) l.d("resume after " + (now - prevTick) / 1000 + "s (strict=" + strict + ")");
                    sendQuiet(new byte[]{(byte) 0xFF}, natPort); // принудительный пробой NAT после сна
                    lastRx = Math.min(lastRx, now - dpdMs);   // DPD стартует сразу
                    // окно на ответ даётся один раз; повторные пробуждения его не продлевают
                    if (graceUntil <= now) graceUntil = now + graceMs;
                    dpdReq = null;
                    wdRx = rxEsp;
                    wdTx = txPk;
                    wdIke = rxIke;
                    wdPendingSince = 0;
                    lastWatchdog = now;
                    lastKeep = 0;
                    lastPing = now;
                    pinged = false;
                    missed = 0;
                }
                prevTick = now;

                // ===== WATCHDOG: ушёл пакет, а ответного ESP нет wdMs =====
                // Отсчёт идёт от первого пакета без ответа. В простое не срабатывает.
                if (now - lastWatchdog >= 2000) {
                    lastWatchdog = now;
                    long rxNow = rxEsp, txNow = txPk, ikeNow = rxIke;
                    if (rxNow != wdRx || ikeNow != wdIke) {              // ответ пришёл
                        wdRx = rxNow;
                        wdIke = ikeNow;
                        wdTx = txNow;
                        wdPendingSince = 0;
                    } else if (txNow != wdTx) {       // ушло новое, ответа нет
                        wdTx = txNow;
                        if (wdPendingSince == 0) wdPendingSince = now;
                    }
                    if (!up || !online) wdPendingSince = 0;
                    if (wdPendingSince != 0 && now - wdPendingSince >= wdMs) {
                        Log l = log;
                        long silent = (now - wdPendingSince) / 1000;
                        if (strict) {
                            if (l != null) l.d("WATCHDOG(strict): no ESP back for " + silent
                                    + "s after send; " + esp.drops());
                            lost("no ESP replies while sending");
                            return;
                        }
                        // обычный VPN: не рвём, а просим DPD проверить путь; решит deadMs
                        if (l != null) l.d("WATCHDOG(soft): no ESP back for " + silent + "s; start DPD");
                        wdPendingSince = 0;
                        lastRx = Math.min(lastRx, now - dpdMs);
                    }
                }

                // ================= СТАТИСТИКА =================
                if (now - lastStat >= 10000) {
                    lastStat = now;
                    Log l = log;
                    if (l != null) {
                        l.d("stats: txEsp=" + txPk + " rxEsp=" + rxEsp + " rxIke=" + rxIke
                                + " sendFail=" + txFail + (txFail > 0 ? " lastErr=" + lastSendErr : "")
                                + " idleRx=" + (now - lastRx) / 1000 + "s online=" + online
                                + " strict=" + strict
                                + " dpdPending=" + (dpdReq != null) + " " + esp.drops());
                    }
                }

                // ================= СЕТЬ: если её нет — ничего не делаем =================
                if (!online) {
                    lastRx = now;
                    dpdReq = null;
                    pinged = false;
                    continue;
                }

                // ================= СЕТЬ ВЕРНУЛАСЬ: сбросить таймеры =================
                if (kick) {
                    kick = false;
                    lastKeep = 0;
                    dpdReq = null;
                    lastRx = Math.min(lastRx, now - dpdMs);
                }

                // ================= KEEPALIVE =================
                if (now - lastKeep >= keepMs) {
                    sendQuiet(new byte[]{(byte) 0xFF}, natPort);
                    lastKeep = now;
                }

                // ================= PING: healthcheck (подсчет потерь + авто-реконнект при достижении pingLoss) =================
                if (pingHost != null && now - lastPing >= pingMs) {
                    if (pinged) {
                        if (pongs != seen || lastRx > lastPing) {
                            seen = pongs;
                            missed = 0;
                        } else {
                            missed++;
                            totalPingLoss++;
                            Vpn.Listener l = ev;
                            if (l != null) l.onPingLoss(totalPingLoss);
                        }
                    }
                    if (missed >= pingLoss) {
                        missed = 0;
                        Log l = log;
                        if (l != null) l.d("ping healthcheck: " + pingLoss + " losses reached; restarting tunnel to refresh socket");
                        lost("ping healthcheck timeout");
                        return;
                    }
                    byte[] ip = echo(++seq);
                    sendQuiet(esp.wrap(ip, ip.length), natPort);
                    pinged = true;
                    lastPing = now;
                }

                // ================= DPD: Dead Peer Detection =================
                long idle = now - lastRx;

                if (idle >= deadMs && now >= graceUntil) {
                    lost("server does not respond");
                    return;
                }

                if (dpdReq == null && idle >= dpdMs) {
                    dpdId = ike.nextId();
                    dpdReq = Crypto.cat(new byte[4], ike.seal(Ike.INFO, false, dpdId, new ArrayList<>()));
                    lastDpd = 0;
                }

                byte[] d = dpdReq;
                if (d != null && now - lastDpd >= Math.max(100, dpdMs / 6)) {
                    sendQuiet(d, natPort);
                    lastDpd = now;
                }
            }
        } catch (InterruptedException e) {
            // закрыли снаружи
        } catch (RuntimeException e) {
            lost(closed.get() ? "disconnected" : "internal error: " + e.getMessage());
        }
    }

    // ================= MOBIKE =================

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

    // ================= ping =================

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

    private static int checksum(byte[] b, int off, int len) {
        long s = 0;
        for (int i = 0; i < len; i += 2) {
            s += ((b[off + i] & 0xff) << 8) | (i + 1 < len ? b[off + i + 1] & 0xff : 0);
        }
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    private boolean pong(byte[] ip) {
        byte[] dst = pingDst;
        if (dst == null || ip.length < 28 || (ip[0] >> 4) != 4 || ip[9] != 1) return false;
        int ihl = (ip[0] & 0x0f) * 4;
        if (ihl < 20 || ip.length < ihl + 8 || ip[ihl] != 0 || Crypto.u16(ip, ihl + 4) != PING_ID) return false;
        for (int i = 0; i < 4; i++) {
            if (ip[12 + i] != dst[i]) return false;
        }
        return true;
    }

    // ================= завершение =================

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

    private void end(String reason, boolean retry) {
        if (!closed.compareAndSet(false, true)) return;
        android.util.Log.w(TAG, "Tunnel.end reason=" + reason + " retry=" + retry
                + " thread=" + Thread.currentThread().getName(), new Throwable());
        if (up) {
            try {
                List<Ike.Pl> del = Collections.singletonList(new Ike.Pl(Ike.DELETE, new byte[]{1, 0, 0, 0}));
                send(ike.seal(Ike.INFO, false, ike.nextId(), del), natPort, true);
            } catch (IOException | RuntimeException ignored) {
            }
        }
        up = false;
        DatagramSocket s = sock;
        if (s != null) s.close();
        tun.close();
        for (Thread t : new Thread[]{main, rx, tx}) {
            if (t != null && t != Thread.currentThread()) t.interrupt();
        }
        if (retry) ev.onReconnecting(reason);
        else ev.onDown(reason);
    }
}
