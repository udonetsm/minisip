package android.net;

import android.app.Service;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

public class VpnService extends Service {
    /** null = согласие уже дано. */
    public static Intent prepare(android.content.Context c) { return null; }

    public boolean protect(java.net.DatagramSocket s) { return true; }

    public void onRevoke() {}

    public class Builder {
        public Builder setSession(String s) { return this; }
        public Builder setMtu(int m) { return this; }
        public Builder setBlocking(boolean b) { return this; }
        public Builder addAddress(String a, int p) { return this; }
        public Builder addRoute(String a, int p) { return this; }
        public Builder addDnsServer(String a) { return this; }
        public Builder addAllowedApplication(String p) throws android.content.pm.PackageManager.NameNotFoundException { return this; }
        public ParcelFileDescriptor establish() { return null; }
    }
}
