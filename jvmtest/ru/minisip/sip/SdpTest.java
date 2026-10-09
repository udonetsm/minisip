package ru.minisip.sip;

public final class SdpTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        String sdp = Sdp.make("10.0.0.2", 5000, 8, 0);
        check(sdp != null && sdp.contains("m=audio 5000 RTP/AVP 8 0"), "Sdp.make contains correct audio line");
        check(sdp.contains("c=IN IP4 10.0.0.2"), "Sdp.make contains connection IP");

        String[] parsed = Sdp.parse(sdp);
        check(parsed != null && parsed[0].equals("10.0.0.2") && parsed[1].equals("5000") && parsed[2].equals("8"),
                "Sdp.parse correctly extracts IP, port, and preferred codec (8)");

        // Test fallback to pt 0
        String sdp0 = "v=0\r\no=- 1 1 IN IP4 192.168.1.10\r\ns=-\r\nc=IN IP4 192.168.1.10\r\nt=0 0\r\nm=audio 4004 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n";
        String[] parsed0 = Sdp.parse(sdp0);
        check(parsed0 != null && parsed0[2].equals("0"), "Sdp.parse falls back to pt 0");

        // Test invalid SDP
        check(Sdp.parse("v=0\r\nc=IN IP4 0.0.0.0\r\nm=audio 0 RTP/AVP 8\r\n") == null, "Sdp.parse returns null for zero port/IP");

        System.out.println(fails == 0 ? "SDP TEST OK" : "SDP TEST FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
