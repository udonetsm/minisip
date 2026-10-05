package android.net;

public class NetworkCapabilities {
    public static final int TRANSPORT_VPN = 4;
    private final boolean vpn;

    public NetworkCapabilities(boolean vpn) { this.vpn = vpn; }

    public boolean hasTransport(int t) { return t == TRANSPORT_VPN && vpn; }
}
