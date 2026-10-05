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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Один сеанс VPN: рукопожатие IKEv2, интерфейс tun и перекачка пакетов tun <-> ESP/UDP 4500.
 * Потоки: "vpn" (рукопожатие, потом таймеры: keepalive, DPD и ping), "vpn-rx" (сокет -> tun),
 * "vpn-tun" (tun -> сокет). Колбэки Vpn.Listener зовутся: onUp один раз; затем либо onDown,
 * либо (supervised) onReconnecting. После любого из двух сеанс мёртв: восстановление — новым Tunnel.
 */
final class Tunnel {

    static final int MTU = 1400;       // 1500 минус IP/UDP/ESP-накладные с запасом
    /** Буфер приёма: ответ IKE_AUTH с цепочкой сертификатов легко больше 2 КБ (сервер шлёт его IP-фрагментами). */
    private static final int RX_BUF = 65535;
    private static final int PING_ID = 0x4D53;

    // настраиваемое для проверок
    int ikePort = 500, natPort = 4500;
    int rto = 3000, tries = 4;         // ожидание ответа на один запрос и число попыток
    int keepMs = 20000, dpdMs = 30000, deadMs = 90000;
    /** Ping через туннель: адрес (null — выключено), период и сколько потерянных подряд считается обрывом. */
    String pingHost;
    int pingMs = 2000, pingLoss = 5;
    /**
     * true — обрыв уже поднятого туннеля (или чужой VPN поверх) сообщается не сразу через onDown:
     * если чужого VPN нет, зовётся onReconnecting, и дальше решает владелец (VpnImpl).
     */
    boolean supervised;

    private final Tun tun;
    private final Vpn.Listener ev;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean up;
    private volatile long lastRx;
    private volatile int pongs;        // сколько ответов на наши ping пришло (пишет только поток приёма)
    private volatile byte[] dpdReq;    // неотвеченный DPD повторяем с тем же номером
    private volatile int dpdId = -1;
    private volatile DatagramSocket sock;
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

    /** Закрывает туннель по просьбе пользователя или системы (окончательно). Можно звать из любого потока. */
    void stop(String reason) {
        finish(reason);
    }

    /** Если сеанс закрыли, пока поток рукопожатия был занят, дальше идти нельзя. */
    private void alive() throws IOException {
        if (closed.get()) {
            DatagramSocket s = sock;
            if (s != null) s.close();
            throw new IOException("disconnected");
        }
    }

    // ================= рукопожатие =================

    private void run(String host, String identity, String psk, String password, String ca, String apps) {
        try {
            srv = resolve(host);
            alive();
            if (srv == null) {
                finish("cannot resolve the server (IPv4 address needed)");
                return;
            }
            sock = new DatagramSocket();
            alive();
            if (!tun.protect(sock)) {
                finish("cannot take the transport socket out of the VPN");
                return;
            }
            ike = new Ike(identity, psk, password, ca, host);
            String bad = ike.check();
            if (bad != null) {
                finish(bad);
                return;
            }

            byte[] r = null;
            int rc = 1;
            for (int round = 0; round < 2 && rc == 1; round++) {          // второй раунд — с cookie
                r = exchange(ike.initRequest(srv.getAddress(), ikePort), ikePort, false, 0);
                if (r == null) {
                    finish("server does not respond");
                    return;
                }
                rc = ike.onInit(r);
            }
            if (rc != 0) {
                finish(rc < 0 ? ike.error : "server keeps asking for a cookie");
                return;
            }
            byte[] req = ike.authRequest();
            for (int round = 0; req != null && round < 8; round++) {   // EAP — несколько обменов подряд
                r = exchange(req, natPort, true, ike.curId);
                if (r == null) {
                    finish("server does not answer IKE_AUTH");
                    return;
                }
                String err = ike.onAuth(r);
                if (err != null) {
                    finish(err);
                    return;
                }
                req = ike.next;
            }
            if (ike.esp == null) {
                finish("login did not finish");
                return;
            }
            if (pingHost != null) {
                pingSrc = InetAddress.getByName(ike.ip).getAddress();      // оба — числовые адреса, без DNS
                pingDst = InetAddress.getByName(pingHost).getAddress();
                if (pingSrc.length != 4 || pingDst.length != 4) pingHost = null;
            }
            alive();
            String name = tun.open(ike.ip, ike.dns, ike.routes, MTU, apps);
            if (name == null) {
                finish("cannot create the tun interface");
                return;
            }
            if (closed.get()) {                                       // закрыли, пока создавался интерфейс
                tun.close();
                return;
            }
            esp = ike.esp;
            sock.setSoTimeout(0);                                    // дальше приём без таймаута
            lastRx = System.currentTimeMillis();
            rx = daemon(this::rxLoop, "vpn-rx");
            tx = daemon(this::txLoop, "vpn-tun");
            up = true;
            ev.onUp(name);
            timers();
        } catch (IOException | RuntimeException e) {
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

    /** Отправляет запрос и ждёт ответ с тем же номером; повторяет до tries раз. null — тишина. */
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
        byte[] d = marker ? Crypto.cat(new byte[4], msg) : msg;     // у IKE на 4500 впереди 4 нулевых байта
        sock.send(new DatagramPacket(d, d.length, srv, port));
    }

    // ================= рабочий режим =================

    /** Сокет -> tun: ESP разворачиваем, сообщения IKE обрабатываем, keepalive игнорируем. */
    private void rxLoop() {
        byte[] buf = new byte[RX_BUF];
        try {
            while (!closed.get()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                int n = p.getLength();
                if (!p.getAddress().equals(srv) || n < 4) continue;
                if (n == 1) continue;
                if (Crypto.u32(buf, 0) == 0) {
                    onIke(Arrays.copyOfRange(buf, 4, n));
                } else {
                    byte[] ip = esp.unwrap(buf, n);
                    if (ip != null) {
                        lastRx = System.currentTimeMillis();
                        if (pong(ip)) {                              // ответ на наш ping в tun не отдаём
                            pongs++;
                            continue;
                        }
                        tun.write(ip, 0, ip.length);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            lost(closed.get() ? "disconnected" : "network error: " + e.getMessage());
        }
    }

    /** tun -> сокет: каждый IPv4-пакет приложения заворачиваем в ESP. */
    private void txLoop() {
        byte[] b = new byte[2048];
        try {
            while (!closed.get()) {
                int n = tun.read(b);
                if (n < 0) break;
                if (n < 20 || (b[0] >> 4) != 4) continue;            // только IPv4
                byte[] pkt = esp.wrap(b, n);
                sock.send(new DatagramPacket(pkt, pkt.length, srv, natPort));
            }
            lost("tunnel interface closed");
        } catch (IOException | RuntimeException e) {
            lost(closed.get() ? "disconnected" : "tun error: " + e.getMessage());
        }
    }

    /** Сообщение IKE от сервера: ответ на наш DPD, его DPD, удаление, попытка перегенерации ключей. */
    private void onIke(byte[] m) throws IOException {
        Ike.Rx x = ike.open(m);
        if (x == null) return;
        lastRx = System.currentTimeMillis();
        if (x.response) {
            if (x.id == dpdId) dpdReq = null;
            return;
        }
        if (x.exch == Ike.CHILD) {                                   // перегенерацию ключей не умеем
            List<Ike.Pl> no = Collections.singletonList(
                    Ike.notify(Ike.N_NO_ADDITIONAL_SAS, new byte[0]));
            send(ike.seal(Ike.CHILD, true, x.id, no), natPort, true);
            return;
        }
        if (x.exch != Ike.INFO) return;
        boolean gone = false;
        for (Ike.Pl p : x.pl) {
            if (p.type != Ike.DELETE || p.body.length < 4) continue;
            int proto = p.body[0] & 0xff, size = p.body[1] & 0xff, cnt = Crypto.u16(p.body, 2);
            if (proto == 1) gone = true;                              // удалён весь IKE SA
            for (int i = 0; proto == 3 && size == 4 && i < cnt && 4 + 4 * i + 4 <= p.body.length; i++) {
                if (Crypto.u32(p.body, 4 + 4 * i) == ike.spiOut) gone = true;
            }
        }
        send(ike.seal(Ike.INFO, true, x.id, new ArrayList<>()), natPort, true);   // пустой ответ (и на DPD)
        if (gone) lost("server closed the tunnel");
    }

    /**
     * Поток "vpn" после подъёма: NAT-keepalive, DPD и ping. Ping идёт самим ESP (не через ОС),
     * поэтому проверяет именно наш путь: сокет -> сервер -> 10.x -> обратно. Каждые pingMs
     * оцениваем предыдущий ping: пришёл хоть один ответ — потерь 0, нет — +1. pingLoss подряд = обрыв.
     */
    private void timers() {
        long lastKeep = System.currentTimeMillis(), lastDpd = 0, lastPing = 0;
        int tick = Math.max(50, Math.min(1000, keepMs / 4));
        if (pingHost != null) tick = Math.min(tick, Math.max(50, pingMs / 4));
        int missed = 0, seen = pongs, seq = 0;
        boolean pinged = false;
        try {
            while (!closed.get()) {
                Thread.sleep(tick);
                long now = System.currentTimeMillis(), idle = now - lastRx;
                if (now - lastKeep >= keepMs) {
                    sock.send(new DatagramPacket(new byte[]{(byte) 0xFF}, 1, srv, natPort));
                    lastKeep = now;
                }
                if (pingHost != null && now - lastPing >= pingMs) {
                    if (pinged) {
                        if (pongs != seen) {
                            seen = pongs;
                            missed = 0;
                        } else {
                            missed++;
                        }
                    }
                    if (missed >= pingLoss) {
                        lost("no ping replies from " + pingHost);
                        return;
                    }
                    byte[] ip = echo(++seq);
                    byte[] pkt = esp.wrap(ip, ip.length);
                    try {
                        sock.send(new DatagramPacket(pkt, pkt.length, srv, natPort));
                    } catch (IOException e) {
                        // нет сети: это обычная потеря ping, посчитается на следующем круге
                    }
                    pinged = true;
                    lastPing = now;
                }
                if (idle >= deadMs) {
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
                    sock.send(new DatagramPacket(d, d.length, srv, natPort));
                    lastDpd = now;
                }
            }
        } catch (InterruptedException e) {
            // закрыли снаружи
        } catch (IOException | RuntimeException e) {
            lost(closed.get() ? "disconnected" : "network error: " + e.getMessage());
        }
    }

    // ================= ping =================

    /** IPv4 + ICMP echo request, 8 байт данных. Источник — наш адрес внутри туннеля. */
    private byte[] echo(int seq) {
        byte[] p = new byte[36];
        p[0] = 0x45;
        Crypto.put16(p, 2, p.length);
        Crypto.put16(p, 4, seq);
        Crypto.put16(p, 6, 0x4000);                                  // DF
        p[8] = 64;                                                   // TTL
        p[9] = 1;                                                    // ICMP
        System.arraycopy(pingSrc, 0, p, 12, 4);
        System.arraycopy(pingDst, 0, p, 16, 4);
        Crypto.put16(p, 10, checksum(p, 0, 20));
        p[20] = 8;                                                   // echo request
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

    /** Это echo reply на наш ping (от адреса, который мы пинговали)? */
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

    /** Окончательное закрытие: onDown один раз, из какого бы потока ни позвали. */
    private void finish(String reason) {
        end(reason, false);
    }

    /**
     * Обрыв связи. Не supervised или туннель ещё не поднимался — это обычное закрытие. Иначе:
     * есть другой VPN в системе — закрываемся окончательно (onDown); нет — onReconnecting.
     */
    private void lost(String reason) {
        if (closed.get()) return;
        if (!supervised || !up) {
            finish(reason);
            return;
        }
        boolean other = false;
        try {
            other = tun.otherVpn(ike.ip);
        } catch (RuntimeException ignored) {
        }
        if (other) end("another VPN took over (" + reason + ")", false);
        else end(reason, true);
    }

    private void end(String reason, boolean retry) {
        if (!closed.compareAndSet(false, true)) return;
        if (up) {                                                    // вежливо просим сервер закрыть SA
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
