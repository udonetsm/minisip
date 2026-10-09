package ru.minisip.vpn;

public final class MschapTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        byte[] hash = Mschap.ntHash("password");
        check(hash != null && hash.length == 16, "ntHash produces 16 bytes");

        byte[] auth = new byte[]{1,2,3,4,5,6,7,8};
        byte[] peer = new byte[]{8,7,6,5,4,3,2,1};
        byte[] user = "alice".getBytes();

        byte[] resp = Mschap.ntResponse(auth, peer, user, "password");
        check(resp != null && resp.length == 24, "ntResponse produces 24 bytes");

        String authResp = Mschap.authenticatorResponse("password", resp, peer, auth, user);
        check(authResp != null && authResp.startsWith("S="), "authenticatorResponse format is correct");

        byte[] msk = Mschap.msk("password", resp);
        check(msk != null && msk.length == 64, "msk produces 64 bytes");

        System.out.println(fails == 0 ? "MSCHAP TEST OK" : "MSCHAP TEST FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
