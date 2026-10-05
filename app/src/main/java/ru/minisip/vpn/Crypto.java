package ru.minisip.vpn;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Вся криптография и байтовые мелочи. Только JDK/Android (javax.crypto, BigInteger),
 * ни одной внешней библиотеки.
 */
final class Crypto {

    static final SecureRandom RND = new SecureRandom();

    // ---------- байты ----------

    static int u16(byte[] b, int o) {
        return (b[o] & 0xff) << 8 | (b[o + 1] & 0xff);
    }

    static long u32(byte[] b, int o) {
        return (long) u16(b, o) << 16 | u16(b, o + 2);
    }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }

    static void put32(byte[] b, int o, long v) {
        put16(b, o, (int) (v >> 16));
        put16(b, o + 2, (int) v);
    }

    static byte[] cat(byte[]... p) {
        int n = 0;
        for (byte[] x : p) n += x.length;
        byte[] r = new byte[n];
        n = 0;
        for (byte[] x : p) {
            System.arraycopy(x, 0, r, n, x.length);
            n += x.length;
        }
        return r;
    }

    static byte[] random(int n) {
        byte[] r = new byte[n];
        RND.nextBytes(r);
        return r;
    }

    static byte[] sha1(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Диффи-Хеллман: группа 14 (RFC 3526, MODP 2048) ----------

    static final int DH_GROUP = 14;
    static final int DH_BYTES = 256;
    private static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
            + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
            + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
            + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
            + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
            + "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    private static final BigInteger G = BigInteger.valueOf(2);

    /** Закрытая часть: 320 случайных бит со старшим битом (достаточно для ~128 бит стойкости). */
    static BigInteger dhPrivate() {
        return new BigInteger(320, RND).setBit(319);
    }

    static byte[] dhPublic(BigInteger x) {
        return fixed(G.modPow(x, P), DH_BYTES);
    }

    /** g^xy ровно в 256 байт; null, если чужое значение не годится (0, 1, p-1 и больше). */
    static byte[] dhShared(BigInteger x, byte[] peer) {
        BigInteger y = new BigInteger(1, peer);
        if (y.compareTo(BigInteger.ONE) <= 0 || y.compareTo(P.subtract(BigInteger.ONE)) >= 0) return null;
        return fixed(y.modPow(x, P), DH_BYTES);
    }

    private static byte[] fixed(BigInteger v, int n) {
        byte[] b = v.toByteArray();
        byte[] r = new byte[n];
        int skip = b.length > n ? b.length - n : 0;   // знаковый нулевой байт
        System.arraycopy(b, skip, r, n - (b.length - skip), b.length - skip);
        return r;
    }

    // ---------- набор алгоритмов ----------

    /**
     * Выбранный набор: шифр (AES-CBC или AES-GCM-16), длина ключа, целостность и PRF.
     * Номера — из реестра IANA для IKEv2.
     */
    static final class Suite {
        static final int CBC = 12, GCM = 20;           // ENCR_AES_CBC, ENCR_AES_GCM_16
        static final int SHA1_96 = 2, SHA256_128 = 12; // AUTH_HMAC_SHA1_96, AUTH_HMAC_SHA2_256_128
        static final int PRF_SHA1 = 2, PRF_SHA256 = 5;

        final int encr, bits, integ, prf;

        Suite(int encr, int bits, int integ, int prf) {
            this.encr = encr;
            this.bits = bits;
            this.integ = integ;
            this.prf = prf;
        }

        /** Годится ли то, что выбрал сервер. Для ESP PRF нет (needPrf = false, prf должен быть 0). */
        boolean supported(boolean needPrf) {
            if (encr != CBC && encr != GCM) return false;
            if (bits != 128 && bits != 256) return false;
            if (encr == CBC && integ != SHA1_96 && integ != SHA256_128) return false;
            if (encr == GCM && integ != 0) return false;
            return needPrf ? (prf == PRF_SHA1 || prf == PRF_SHA256) : prf == 0;
        }

        boolean aead() { return encr == GCM; }

        int keyLen() { return bits / 8; }

        /** Материал ключа шифра; у GCM в конце ещё 4 байта соли. */
        int encKeyLen() { return keyLen() + (aead() ? 4 : 0); }

        int integKeyLen() { return aead() ? 0 : integ == SHA256_128 ? 32 : 20; }

        int icvLen() { return aead() ? 16 : integ == SHA256_128 ? 16 : 12; }

        int ivLen() { return aead() ? 8 : 16; }

        /** Открытый текст перед шифрованием добивается до кратного этому числу. */
        int block() { return aead() ? 4 : 16; }

        int prfLen() { return prf == PRF_SHA256 ? 32 : 20; }

        byte[] prf(byte[] key, byte[]... data) {
            return hmac(prf == PRF_SHA256 ? "HmacSHA256" : "HmacSHA1", key, data);
        }

        /** prf+ из RFC 7296, п. 2.13: T1 = prf(K, S|1), T2 = prf(K, T1|S|2), ... */
        byte[] prfPlus(byte[] key, byte[] seed, int n) {
            byte[] out = new byte[n];
            byte[] t = new byte[0];
            for (int i = 1, pos = 0; pos < n; i++) {
                t = prf(key, t, seed, new byte[]{(byte) i});
                int c = Math.min(t.length, n - pos);
                System.arraycopy(t, 0, out, pos, c);
                pos += c;
            }
            return out;
        }

        byte[] mac(byte[] key, byte[] data) {
            return hmac(integ == SHA256_128 ? "HmacSHA256" : "HmacSHA1", key, data);
        }
    }

    static byte[] hmac(String alg, byte[] key, byte[]... data) {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            for (byte[] d : data) m.update(d);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- шифрование в одну сторону (и для IKE, и для ESP) ----------

    /**
     * Запечатывает и открывает «голову | IV | шифртекст | ICV».
     * Голова (заголовок IKE+SK или SPI+номер ESP) защищена, но не зашифрована:
     * у CBC она входит в HMAC, у GCM — в AAD. Выравнивание открытого текста делает вызывающий.
     */
    static final class Box {
        private final Suite s;
        private final SecretKeySpec key;
        private final byte[] salt;
        private final byte[] integKey;
        private long counter;

        Box(Suite s, byte[] encKey, byte[] integKey) {
            this.s = s;
            this.key = new SecretKeySpec(encKey, 0, s.keyLen(), "AES");
            this.salt = s.aead() ? Arrays.copyOfRange(encKey, s.keyLen(), s.keyLen() + 4) : null;
            this.integKey = integKey;
        }

        /** Длина добавки к открытому тексту: IV + ICV. */
        int overhead() {
            return s.ivLen() + s.icvLen();
        }

        private synchronized byte[] nextIv() {
            if (!s.aead()) return random(16);
            byte[] iv = new byte[8];            // GCM: IV не должен повторяться, берём счётчик
            put32(iv, 0, counter >>> 32);
            put32(iv, 4, counter++);
            return iv;
        }

        byte[] seal(byte[] head, byte[] plain) {
            try {
                byte[] iv = nextIv();
                Cipher c = Cipher.getInstance(s.aead() ? "AES/GCM/NoPadding" : "AES/CBC/NoPadding");
                if (s.aead()) {
                    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, cat(salt, iv)));
                    c.updateAAD(head);
                    return cat(head, iv, c.doFinal(plain));          // тег GCM идёт в конце = ICV
                }
                c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
                byte[] body = cat(head, iv, c.doFinal(plain));
                return cat(body, Arrays.copyOf(s.mac(integKey, body), s.icvLen()));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Проверяет ICV и расшифровывает; null, если сообщение подделано или повреждено. */
        byte[] open(byte[] msg, int headLen) {
            int ivLen = s.ivLen(), icv = s.icvLen();
            int ctLen = msg.length - headLen - ivLen - icv;
            if (ctLen < 0 || ctLen % s.block() != 0) return null;
            try {
                Cipher c = Cipher.getInstance(s.aead() ? "AES/GCM/NoPadding" : "AES/CBC/NoPadding");
                if (s.aead()) {
                    byte[] nonce = cat(salt, Arrays.copyOfRange(msg, headLen, headLen + ivLen));
                    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
                    c.updateAAD(msg, 0, headLen);
                    return c.doFinal(msg, headLen + ivLen, ctLen + icv);
                }
                byte[] want = Arrays.copyOf(s.mac(integKey, Arrays.copyOf(msg, msg.length - icv)), icv);
                byte[] got = Arrays.copyOfRange(msg, msg.length - icv, msg.length);
                if (!MessageDigest.isEqual(want, got)) return null;
                c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(msg, headLen, ivLen));
                return c.doFinal(msg, headLen + ivLen, ctLen);
            } catch (GeneralSecurityException e) {
                return null;                                          // неверный тег GCM и т.п.
            }
        }
    }
}
