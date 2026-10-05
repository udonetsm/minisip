package ru.minisip.vpn;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Сертификат сервера: цепочка до доверенного корня, имя хоста и подпись AUTH (RFC 7296, RFC 7427).
 * Только java.security / javax.net.ssl платформы.
 */
final class Certs {

    /** Разбирает один или несколько PEM-сертификатов; null — не получилось или пусто. */
    static List<X509Certificate> parsePem(String pem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> c = cf.generateCertificates(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            List<X509Certificate> r = new ArrayList<>();
            for (Certificate x : c) r.add((X509Certificate) x);
            return r.isEmpty() ? null : r;
        } catch (Exception e) {
            return null;
        }
    }

    static X509Certificate parseDer(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    /** Доверенные корни: свои (если заданы) или системное хранилище. */
    private static Set<TrustAnchor> anchors(List<X509Certificate> own) throws Exception {
        Set<TrustAnchor> a = new HashSet<>();
        if (own != null) {
            for (X509Certificate c : own) a.add(new TrustAnchor(c, null));
            return a;
        }
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init((KeyStore) null);
        for (TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509TrustManager) {
                for (X509Certificate c : ((X509TrustManager) t).getAcceptedIssuers()) a.add(new TrustAnchor(c, null));
            }
        }
        return a;
    }

    /** Ставит сертификаты по цепочке: первый — сам сервер, дальше тот, кто выдал предыдущий. */
    private static List<X509Certificate> order(List<X509Certificate> in) {
        List<X509Certificate> left = new ArrayList<>(in), out = new ArrayList<>();
        out.add(left.remove(0));
        for (boolean found = true; found && !left.isEmpty(); ) {
            found = false;
            X509Certificate last = out.get(out.size() - 1);
            for (X509Certificate c : left) {
                if (c.getSubjectX500Principal().equals(last.getIssuerX500Principal())) {
                    out.add(c);
                    left.remove(c);
                    found = true;
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Проверяет сервер. own — свои корни из настроек или null (системные). Возвращает текст ошибки
     * или null, если всё хорошо. Отзыв (CRL/OCSP) не проверяется.
     */
    static String verify(List<X509Certificate> chain, List<X509Certificate> own, String host) {
        if (chain.isEmpty()) return "server sent no certificate";
        X509Certificate leaf = chain.get(0);
        try {
            leaf.checkValidity();
            Set<TrustAnchor> roots = anchors(own);
            boolean pinned = false;                       // сам сервер указан как доверенный
            List<X509Certificate> path = new ArrayList<>();
            for (X509Certificate c : order(chain)) {
                boolean root = false;
                for (TrustAnchor t : roots) root |= c.equals(t.getTrustedCert());
                if (root) {
                    pinned |= c.equals(leaf);
                    break;                                // дальше цепочку ведёт сам корень
                }
                path.add(c);
            }
            if (!pinned) {
                PKIXParameters p = new PKIXParameters(roots);
                p.setRevocationEnabled(false);
                CertPath cp = CertificateFactory.getInstance("X.509").generateCertPath(path);
                CertPathValidator.getInstance("PKIX").validate(cp, p);
            }
        } catch (Exception e) {
            return "server certificate is not trusted (" + e.getMessage() + ")";
        }
        return matches(leaf, host) ? null : "server certificate does not match " + host;
    }

    /** Имя хоста должно быть в SAN (DNS или IP); поддерживается «*.» в первой метке. */
    static boolean matches(X509Certificate c, String host) {
        try {
            Collection<List<?>> san = c.getSubjectAlternativeNames();
            if (san == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            for (List<?> e : san) {
                int type = (Integer) e.get(0);
                if (type != 2 && type != 7) continue;     // dNSName, iPAddress
                String n = ((String) e.get(1)).toLowerCase(Locale.ROOT);
                if (n.equals(h)) return true;
                if (type == 2 && n.startsWith("*.") && h.indexOf('.') > 0
                        && h.substring(h.indexOf('.')).equals(n.substring(1))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ---------- подпись AUTH ----------

    /** Из DER «SEQUENCE { OID ... }» достаёт точечную запись OID; null — не разобрать. */
    static String oid(byte[] d) {
        if (d.length < 4 || d[0] != 0x30 || d[2] != 0x06) return null;
        int len = d[3] & 0xff;
        if (len > d.length - 4 || len == 0) return null;
        StringBuilder s = new StringBuilder();
        s.append((d[4] & 0xff) / 40).append('.').append((d[4] & 0xff) % 40);
        long v = 0;
        for (int i = 1; i < len; i++) {
            v = v << 7 | (d[4 + i] & 0x7f);
            if ((d[4 + i] & 0x80) == 0) {
                s.append('.').append(v);
                v = 0;
            }
        }
        return s.toString();
    }

    private static String algorithm(String oid) {
        switch (oid == null ? "" : oid) {
            case "1.2.840.113549.1.1.5": return "SHA1withRSA";
            case "1.2.840.113549.1.1.11": return "SHA256withRSA";
            case "1.2.840.113549.1.1.12": return "SHA384withRSA";
            case "1.2.840.113549.1.1.13": return "SHA512withRSA";
            case "1.2.840.10045.4.3.2": return "SHA256withECDSA";
            case "1.2.840.10045.4.3.3": return "SHA384withECDSA";
            case "1.2.840.10045.4.3.4": return "SHA512withECDSA";
            default: return null;
        }
    }

    static final int RSA_SIG = 1, DIGITAL_SIG = 14;

    /**
     * Проверяет подпись сервера. authBody — тело payload AUTH (метод, 3 байта резерва, данные),
     * octets — то, что подписано. Метод 1: RSA PKCS#1 v1.5 с SHA-1. Метод 14: RFC 7427.
     * Возвращает null, если подпись верна, иначе причину.
     */
    static String verifySig(X509Certificate leaf, byte[] authBody, byte[] octets) {
        int method = authBody[0] & 0xff;
        byte[] data = Arrays.copyOfRange(authBody, 4, authBody.length);
        try {
            String alg;
            byte[] sig;
            if (method == RSA_SIG) {
                alg = "SHA1withRSA";
                sig = data;
            } else if (method == DIGITAL_SIG) {
                int n = data.length > 0 ? data[0] & 0xff : 0;
                if (n == 0 || 1 + n > data.length) return "malformed server signature";
                String o = oid(Arrays.copyOfRange(data, 1, 1 + n));
                alg = algorithm(o);
                if (alg == null) return "unsupported server signature algorithm " + o;
                sig = Arrays.copyOfRange(data, 1 + n, data.length);
            } else {
                return "unsupported server authentication method " + method;
            }
            Signature s = Signature.getInstance(alg);
            s.initVerify(leaf.getPublicKey());
            s.update(octets);
            return s.verify(sig) ? null : "server signature is invalid";
        } catch (Exception e) {
            return "server signature is invalid (" + e.getMessage() + ")";
        }
    }
}
