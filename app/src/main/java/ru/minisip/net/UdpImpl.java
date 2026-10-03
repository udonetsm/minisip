package ru.minisip.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;

final class UdpImpl implements Udp {

    private DatagramSocket sock;

    @Override
    public synchronized int open(int port, Listener l) {
        close();
        final DatagramSocket s;
        try {
            s = new DatagramSocket(port);
        } catch (SocketException e) {
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
            s.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), port));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String localIp(String host, int port) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(host), port);
            return probe.getLocalAddress().getHostAddress();
        } catch (IOException | RuntimeException e) {
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
}
