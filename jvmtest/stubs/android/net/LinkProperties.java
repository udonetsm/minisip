package android.net;

public class LinkProperties {
    private final String iface;

    public LinkProperties(String iface) { this.iface = iface; }

    public String getInterfaceName() { return iface; }
}
