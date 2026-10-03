package ru.minisip.sip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP Digest (RFC 2617 / 3261) только с MD5. */
final class Digest {

    private static final Pattern P = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))");
    private static final SecureRandom RND = new SecureRandom();

    private Digest() {}

    /** Значение заголовка Authorization по вызову сервера. null — схема не поддерживается. */
    static String answer(String challenge, String user, String pass, String method, String uri) {
        byte[] b = new byte[8];
        RND.nextBytes(b);
        return answer(challenge, user, pass, method, uri, hex(b));
    }

    static String answer(String challenge, String user, String pass, String method, String uri,
                         String cnonce) {
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) return null;
        Map<String, String> p = new HashMap<>();
        Matcher m = P.matcher(challenge.substring(6));
        while (m.find()) {
            p.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) != null ? m.group(2) : m.group(3));
        }
        String alg = p.get("algorithm");
        if (alg != null && !alg.equalsIgnoreCase("MD5")) return null;
        String realm = p.get("realm");
        String nonce = p.get("nonce");
        if (realm == null || nonce == null) return null;

        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);
        StringBuilder sb = new StringBuilder();
        sb.append("Digest username=\"").append(user).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri)
                .append("\", algorithm=MD5");
        String qop = p.get("qop");
        if (qop != null && Arrays.asList(qop.split("\\s*,\\s*")).contains("auth")) {
            String nc = "00000001";
            sb.append(", response=\"")
                    .append(md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2))
                    .append("\", qop=auth, nc=").append(nc)
                    .append(", cnonce=\"").append(cnonce).append('"');
        } else {
            sb.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        String opaque = p.get("opaque");
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append('"');
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}
