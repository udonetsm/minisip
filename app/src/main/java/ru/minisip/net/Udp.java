package ru.minisip.net;

import android.content.Context;

/**
 * UDP socket interface providing asynchronous packet listening and network interface binding.
 */
public interface Udp {

    /**
     * Creates standard UDP socket instance without Android network binding support.
     */
    static Udp create() {
        return new UdpImpl((s, iface) -> false);
    }

    /**
     * Creates UDP socket instance with Android ConnectivityManager network binding support.
     */
    static Udp create(Context ctx) {
        return new UdpImpl(new AndroidBinder(ctx));
    }

    /**
     * Opens UDP socket and starts asynchronous receiver thread. Returns allocated local port or -1.
     */
    int open(int port, Listener l);

    /**
     * Transmits UDP datagram packet to remote host and port.
     */
    boolean send(byte[] data, String host, int port);

    /**
     * Returns local IP address used to reach target host and port, or null if unreachable.
     */
    String localIp(String host, int port);

    /**
     * Binds sockets to specified network interface (e.g. "tun0") for traffic isolation.
     */
    void bind(String iface);

    void close();

    default String error() {
        return "";
    }

    interface Listener {
        void onPacket(byte[] data, String host, int port);
    }
}
