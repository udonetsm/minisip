package ru.minisip.sip;

/** Проверка Digest по вектору из RFC 2617 (пункт 3.5). */
public final class DigestTest {

    public static void main(String[] a) {
        String ch = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", "
                + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"";
        String r = Digest.answer(ch, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", "0a4f113b");
        boolean ok = r != null && r.contains("response=\"6629fae49393a05397450978507c4ef1\"");
        System.out.println((ok ? "PASS" : "FAIL") + " RFC 2617 digest: " + r);
        boolean sha = Digest.answer("Digest realm=\"x\", nonce=\"y\", algorithm=SHA-256", "u", "p", "GET", "/") == null;
        System.out.println((sha ? "PASS" : "FAIL") + " SHA-256 отклоняется");
        boolean mixed = Sdp.parse("v=0\r\nc=IN IP4 10.0.0.5\r\nm=audio 4000 RTP/AVP 96 0 8\r\n")[2].equals("0");
        System.out.println((mixed ? "PASS" : "FAIL") + " SDP: выбран первый знакомый кодек");
        boolean hold = Sdp.parse("v=0\r\nc=IN IP4 0.0.0.0\r\nm=audio 4000 RTP/AVP 8\r\n") == null;
        System.out.println((hold ? "PASS" : "FAIL") + " SDP: c=0.0.0.0 -> null");
        System.exit(ok && sha && mixed && hold ? 0 : 1);
    }
}
