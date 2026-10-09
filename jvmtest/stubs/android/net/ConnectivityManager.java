package android.net;

import java.util.ArrayList;
import java.util.List;

public class ConnectivityManager {
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public final List<Network> networks = new ArrayList<>();

    public Network[] getAllNetworks() { return networks.toArray(new Network[0]); }
    public Network getActiveNetwork() { return networks.isEmpty() ? null : networks.get(0); }
    public LinkProperties getLinkProperties(Network n) { return new LinkProperties(n.iface); }
    public NetworkCapabilities getNetworkCapabilities(Network n) { return new NetworkCapabilities(n.iface.startsWith("tun")); }
    public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) {}
    public void unregisterNetworkCallback(NetworkCallback callback) {}

    public static class NetworkCallback {
        public void onAvailable(Network network) {}
        public void onLost(Network network) {}
        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {}
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {}
    }
}
