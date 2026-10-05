package ru.minisip.vpn;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Клиентская сторона сценария; серверная — jvmtest/fake_ike.py.
 * Аргументы: ikePort natPort kind secret mode ca. kind: psk (secret = ключ) или eap (secret = пароль);
 * mode: echo (гоняет пакеты), one (один пакет и ждёт) или wait (ждёт падения); ca: файл PEM или "-".
 * Печатает события "EV ...", "TUN ...", "ECHO ..." — их сверяет сервер.
 */
public final class VpnTest {

    /** Подделка tun: пакеты «приложения» кладём в очередь, всё, что вернулось из туннеля, собираем. */
    static final class FakeTun implements Tun {
        final LinkedBlockingQueue<byte[]> app = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> back = new LinkedBlockingQueue<>();
        volatile boolean closed;

        public boolean protect(DatagramSocket s) { return true; }

        public String open(String ip, String dns, String routes, int mtu, String apps) {
            System.out.println("TUN ip=" + ip + " dns=" + dns + " routes=" + routes + " mtu=" + mtu
                    + " apps=" + apps.replace('\n', ','));
            return "tuntest0";
        }

        public int read(byte[] b) throws IOException {
            try {
                while (!closed) {
                    byte[] p = app.poll(100, TimeUnit.MILLISECONDS);
                    if (p != null) {
                        System.arraycopy(p, 0, b, 0, p.length);
                        return p.length;
                    }
                }
            } catch (InterruptedException e) {
                // выходим как при закрытии
            }
            return -1;
        }

        public void write(byte[] b, int off, int len) { back.add(Arrays.copyOfRange(b, off, off + len)); }

        public void close() { closed = true; }
    }

    static byte[] ip(int n, int seed) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) p[i] = (byte) (i * 13 + seed);
        p[0] = 0x45;
        Crypto.put16(p, 2, n);
        p[12] = 10; p[13] = 9; p[14] = 0; p[15] = 2;      // источник 10.9.0.2
        p[16] = 10; p[17] = 9; p[18] = 0; p[19] = 1;      // назначение 10.9.0.1
        return p;
    }

    /** Ответ сервера = тот же пакет с поменянными адресами. */
    static byte[] swapped(byte[] p) {
        byte[] r = p.clone();
        System.arraycopy(p, 16, r, 12, 4);
        System.arraycopy(p, 12, r, 16, 4);
        return r;
    }

    static final Object LOCK = new Object();
    static volatile String down;
    static volatile boolean up;

    static void echo(FakeTun tun, int n, int seed) throws InterruptedException {
        byte[] p = ip(n, seed);
        tun.app.add(p);
        byte[] r = tun.back.poll(3, TimeUnit.SECONDS);
        System.out.println(r != null && Arrays.equals(r, swapped(p)) ? "ECHO ok " + n : "ECHO FAIL " + n);
    }

    public static void main(String[] a) throws Exception {
        int ike = Integer.parseInt(a[0]), nat = Integer.parseInt(a[1]);
        boolean eap = a[2].equals("eap");
        String secret = a[3], mode = a[4];
        String ca = a[5].equals("-") ? "" : new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[5])));
        FakeTun tun = new FakeTun();
        Tunnel t = new Tunnel(tun, new Vpn.Listener() {
            public void onUp(String iface) {
                System.out.println("EV up " + iface);
                up = true;
                synchronized (LOCK) { LOCK.notifyAll(); }
            }

            public void onDown(String reason) {
                System.out.println("EV down " + reason);
                down = reason;
                synchronized (LOCK) { LOCK.notifyAll(); }
            }
        });
        t.ikePort = ike;
        t.natPort = nat;
        t.rto = 400;
        t.tries = 3;
        t.keepMs = 400;
        t.dpdMs = 1000;
        t.deadMs = 3500;
        t.start("127.0.0.1", "alice@example.org", eap ? "" : secret, eap ? secret : "", ca, "org.example.one\norg.example.two");

        long end = System.currentTimeMillis() + 15000;
        synchronized (LOCK) {
            while (!up && down == null && System.currentTimeMillis() < end) LOCK.wait(100);
        }
        if (up && mode.equals("one")) {
            echo(tun, 100, 1);                           // дальше сервер сам закрывает туннель
        }
        if (up && mode.equals("echo")) {
            for (int n : new int[]{20, 21, 64, 100, 1000, 1400}) echo(tun, n, n);
            Thread.sleep(2500);                          // keepalive и DPD идут сами
            echo(tun, 300, 9);
            if (down == null) {
                t.stop("disconnected");                  // пользователь нажал Disconnect
            }
        }
        synchronized (LOCK) {
            while (down == null && System.currentTimeMillis() < end) LOCK.wait(100);
        }
        Thread.sleep(300);
        System.out.println("DONE");
        System.exit(0);
    }
}
