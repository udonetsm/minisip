package android.net;

import java.util.List;
import java.util.Collections;

public class LinkProperties {
    private String iface;
    public LinkProperties(String iface) { this.iface = iface; }
    public String getInterfaceName() { return iface; }
    public List<LinkAddress> getLinkAddresses() { return Collections.emptyList(); }
}
