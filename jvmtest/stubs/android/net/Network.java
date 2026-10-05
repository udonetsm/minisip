package android.net;

import java.io.IOException;
import java.net.DatagramSocket;

public class Network {
    public final String iface;
    public static final java.util.List<DatagramSocket> bound = new java.util.concurrent.CopyOnWriteArrayList<>();

    public Network(String iface) { this.iface = iface; }

    public void bindSocket(DatagramSocket s) throws IOException { bound.add(s); }
}
