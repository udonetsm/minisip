package android.net;

import java.net.InetAddress;

public class LinkAddress {
    private InetAddress address;
    public LinkAddress(InetAddress address, int prefixLength) {
        this.address = address;
    }
    public InetAddress getAddress() { return address; }
}
