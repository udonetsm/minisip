package android.net;

import java.util.ArrayList;
import java.util.List;

/** Список сетей задают тесты. */
public class ConnectivityManager {
    public final List<Network> networks = new ArrayList<>();

    public Network[] getAllNetworks() { return networks.toArray(new Network[0]); }

    public LinkProperties getLinkProperties(Network n) { return new LinkProperties(n.iface); }

    public NetworkCapabilities getNetworkCapabilities(Network n) {
        return new NetworkCapabilities(n.iface.startsWith("tun"));
    }
}
