package ru.minisip.vpn;

import java.security.cert.X509Certificate;
import java.util.List;

public final class CertsTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        List<X509Certificate> empty = Certs.parsePem("invalid pem");
        check(empty == null, "parsePem returns null on invalid PEM");

        String oidVal = Certs.oid(new byte[]{0x30, 0x03, 0x06, 0x01, 0x03});
        check(oidVal != null, "oid parses correctly");

        System.out.println(fails == 0 ? "CERTS TEST OK" : "CERTS TEST FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
