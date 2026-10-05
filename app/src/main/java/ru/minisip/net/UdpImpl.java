package ru.minisip.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.SocketException;
import java.util.Arrays;

final class UdpImpl implements Udp {
    private volatile String err = "";
    private final Binder binder;
    private DatagramSocket sock;
    private volatile String iface;     // null — основной стек системы

    UdpImpl(Binder binder) {
        this.binder = binder;
    }

    @Override
    public void bind(String iface) {
        this.iface = iface;
    }

    /** Привязывает свежий сокет к выбранному интерфейсу; без привязки всегда true. */
    private boolean attach(DatagramSocket s) {
        String f = iface;
        if (f == null) return true;
        try {
            boolean ok = binder.bind(s, f);
            if (!ok) err = "VPN network " + f + " not found";
            return ok;
        } catch (IOException | RuntimeException e) {
            err = "bind to VPN: " + e;
            return false;
        }
    }

    @Override
    public synchronized int open(int port, Listener l) {
        close();
        final DatagramSocket s;
        try {
            s = new DatagramSocket(port);
        } catch (SocketException e) {
            err = "socket: " + e;
            return -1;
        }
        if (!attach(s)) {          // туннеля нет — молча уходить мимо него нельзя
            s.close();
            return -1;
        }
        sock = s;
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (!s.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    l.onPacket(Arrays.copyOf(p.getData(), p.getLength()),
                            p.getAddress().getHostAddress(), p.getPort());
                } catch (IOException e) {
                    if (s.isClosed()) break;
                } catch (RuntimeException e) {
                    // ошибка в обработчике не должна убивать приём
                }
            }
        }, "udp-rx");
        t.setDaemon(true);
        t.start();
        return s.getLocalPort();
    }

    @Override
    public boolean send(byte[] data, String host, int port) {
        DatagramSocket s;
        synchronized (this) {
            s = sock;
        }
        if (s == null) return false;
        try {
            s.send(new DatagramPacket(data, data.length, v4(host), port));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String localIp(String host, int port) {
        try (DatagramSocket probe = new DatagramSocket()) {
            if (!attach(probe)) return null;
            probe.connect(v4(host), port);
            return probe.getLocalAddress().getHostAddress();
        } catch (IOException | RuntimeException e) {
            err = host + ": " + e;
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (sock != null) {
            sock.close();
            sock = null;
        }
    }

    private static InetAddress v4(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress a : all) if (a instanceof Inet4Address) return a;
        return all[0];
    }

    @Override
    public String error() { return err; }
}
