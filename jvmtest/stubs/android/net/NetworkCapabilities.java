package android.net;

public class NetworkCapabilities {
    public static final int NET_CAPABILITY_INTERNET = 12;
    public static final int NET_CAPABILITY_NOT_VPN = 15;
    public static final int NET_CAPABILITY_VALIDATED = 16;
    public static final int TRANSPORT_ETHERNET = 3;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_CELLULAR = 0;
    public static final int TRANSPORT_VPN = 4;

    private boolean isVpn;
    public NetworkCapabilities(boolean isVpn) { this.isVpn = isVpn; }
    public boolean hasCapability(int capability) { return true; }
    public boolean hasTransport(int transport) { return true; }
}
