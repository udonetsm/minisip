package ru.minisip.vpn;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;

import javax.crypto.KeyAgreement;

/**
 * Одна DH-группа IKEv2 для Child rekey: 14 (MODP 2048) и 15 (MODP 3072) из RFC 3526,
 * 19 (ECP 256, secp256r1) из RFC 5903. От Crypto не зависит; ECP делает сам JDK/Android.
 * Экземпляр на один обмен: of() -> generate() -> publicKey() -> shared(пир).
 */
final class DhGroup {

    static final int MODP2048 = 14, MODP3072 = 15, ECP256 = 19;

    private static final SecureRandom RND = new SecureRandom();
    private static final BigInteger G = BigInteger.valueOf(2);
    private static final BigInteger P14 = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    private static final BigInteger P15 = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
            + "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
            + "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
            + "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
            + "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);

    final int id;
    private final BigInteger p;         // простое для MODP; null для ECP
    private final int bytes;            // длина KE-данных
    private BigInteger x;               // MODP: закрытая часть
    private PrivateKey ecPriv;          // ECP: закрытый ключ
    private ECParameterSpec ecSpec;     // ECP: параметры кривой
    private byte[] pub;

    private DhGroup(int id, BigInteger p, int bytes) {
        this.id = id;
        this.p = p;
        this.bytes = bytes;
    }

    /**
     * Новая группа по номеру из реестра IANA; null, если не поддерживается.
     */
    static DhGroup of(int id) {
        switch (id) {
            case MODP2048:
                return new DhGroup(id, P14, 256);
            case MODP3072:
                return new DhGroup(id, P15, 384);
            case ECP256:
                return new DhGroup(id, null, 64);
            default:
                return null;
        }
    }

    static boolean supported(int id) {
        return id == MODP2048 || id == MODP3072 || id == ECP256;
    }

    /**
     * Длина KE-данных: MODP — размер простого, ECP — x | y по 32 байта.
     */
    int keBytes() {
        return bytes;
    }

    /**
     * Новая пара. MODP: 320 случайных бит со старшим битом (как в Crypto.dhPrivate).
     */
    void generate() {
        try {
            if (p != null) {
                x = new BigInteger(320, RND).setBit(319);
                pub = fixed(G.modPow(x, p), bytes);
                return;
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RND);
            KeyPair kp = g.generateKeyPair();
            ecPriv = kp.getPrivate();
            ecSpec = ((ECPublicKey) kp.getPublic()).getParams();
            ECPoint w = ((ECPublicKey) kp.getPublic()).getW();
            pub = new byte[64];
            System.arraycopy(fixed(w.getAffineX(), 32), 0, pub, 0, 32);
            System.arraycopy(fixed(w.getAffineY(), 32), 0, pub, 32, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Наше открытое значение для KE: MODP — g^x, ECP — x | y.
     */
    byte[] publicKey() {
        if (pub == null) throw new IllegalStateException("generate() not called");
        return pub;
    }

    /**
     * Общий секрет: MODP — g^xy нужной длины, ECP — координата x. null, если значение пира не годится:
     * не та длина, для MODP не в 1 < y < p-1, для ECP точка не на кривой.
     */
    byte[] shared(byte[] peer) {
        if (pub == null) throw new IllegalStateException("generate() not called");
        if (peer == null || peer.length != bytes) return null;
        if (p != null) {
            BigInteger y = new BigInteger(1, peer);
            if (y.compareTo(BigInteger.ONE) <= 0 || y.compareTo(p.subtract(BigInteger.ONE)) >= 0)
                return null;
            return fixed(y.modPow(x, p), bytes);
        }
        try {
            BigInteger px = new BigInteger(1, Arrays.copyOfRange(peer, 0, 32));
            BigInteger py = new BigInteger(1, Arrays.copyOfRange(peer, 32, 64));
            EllipticCurve c = ecSpec.getCurve();
            BigInteger q = ((ECFieldFp) c.getField()).getP();
            if (px.compareTo(q) >= 0 || py.compareTo(q) >= 0) return null;
            BigInteger rhs = px.pow(3).add(c.getA().multiply(px)).add(c.getB()).mod(q);
            if (!py.multiply(py).mod(q).equals(rhs)) return null;
            PublicKey pk = KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(px, py), ecSpec));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(ecPriv);
            ka.doPhase(pk, true);
            return fixed(new BigInteger(1, ka.generateSecret()), 32);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static byte[] fixed(BigInteger v, int n) {
        byte[] b = v.toByteArray();
        byte[] r = new byte[n];
        int skip = b.length > n ? b.length - n : 0;   // знаковый нулевой байт
        System.arraycopy(b, skip, r, n - (b.length - skip), b.length - skip);
        return r;
    }
}
