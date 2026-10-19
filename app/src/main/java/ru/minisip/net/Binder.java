package ru.minisip.net;

import java.io.IOException;
import java.net.DatagramSocket;

/**
 * Network socket binder interface.
 */
interface Binder {

    /**
     * Returns true if socket was bound to interface network.
     */
    boolean bind(DatagramSocket s, String iface) throws IOException;
}
