package ru.minisip.vpn;

import static ru.minisip.vpn.Crypto.cat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * MS-CHAPv2 (RFC 2759) и ключи MPPE (RFC 3079) для EAP-MSCHAPv2. Свой MD4 — в JDK его нет.
 * Только байты и строки, никаких состояний.
 */
final class Mschap {

    private static final byte[] MAGIC1 = ascii("Magic server to client signing constant");
    private static final byte[] MAGIC2 = ascii("Pad to make it do more than one iteration");
    private static final byte[] MPPE_MASTER = ascii("This is the MPPE Master Key");
    private static final byte[] SEND_CLIENT = ascii(
            "On the client side, this is the send key; on the server side, it is the receive key.");
    private static final byte[] RECV_CLIENT = ascii(
            "On the client side, this is the receive key; on the server side, it is the send key.");

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // ---------- MD4 (RFC 1320) ----------

    static byte[] md4(byte[] msg) {
        int n = msg.length;
        int total = ((n + 8) / 64 + 1) * 64;
        byte[] p = Arrays.copyOf(msg, total);
        p[n] = (byte) 0x80;
        long bits = (long) n * 8;
        for (int i = 0; i < 8; i++) p[total - 8 + i] = (byte) (bits >>> (8 * i));
        int[] v = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
        int[] x = new int[16];
        int[] o2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
        int[] o3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
        int[] s1 = {3, 7, 11, 19}, s2 = {3, 5, 9, 13}, s3 = {3, 9, 11, 15};
        for (int off = 0; off < total; off += 64) {
            for (int i = 0; i < 16; i++) {
                x[i] = (p[off + 4 * i] & 0xff) | (p[off + 4 * i + 1] & 0xff) << 8
                        | (p[off + 4 * i + 2] & 0xff) << 16 | (p[off + 4 * i + 3] & 0xff) << 24;
            }
            int[] save = v.clone();
            for (int i = 0; i < 48; i++) {
                int a = -i & 3, b = (1 - i) & 3, c = (2 - i) & 3, d = (3 - i) & 3;
                int f, k, add, s;
                if (i < 16) {
                    f = (v[b] & v[c]) | (~v[b] & v[d]); k = i; add = 0; s = s1[i & 3];
                } else if (i < 32) {
                    f = (v[b] & v[c]) | (v[b] & v[d]) | (v[c] & v[d]); k = o2[i - 16]; add = 0x5a827999; s = s2[i & 3];
                } else {
                    f = v[b] ^ v[c] ^ v[d]; k = o3[i - 32]; add = 0x6ed9eba1; s = s3[i & 3];
                }
                v[a] = Integer.rotateLeft(v[a] + f + x[k] + add, s);
            }
            for (int i = 0; i < 4; i++) v[i] += save[i];
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) (v[i / 4] >>> (8 * (i % 4)));
        return out;
    }

    // ---------- RFC 2759 ----------

    static byte[] ntHash(String password) {
        return md4(password.getBytes(StandardCharsets.UTF_16LE));
    }

    static byte[] challengeHash(byte[] peer, byte[] auth, byte[] user) {
        return Arrays.copyOf(Crypto.sha1(peer, auth, user), 8);
    }

    /** DES по ключу из 7 байт: добавляем биты чётности и шифруем один блок. */
    private static byte[] des(byte[] k7, int off, byte[] block) {
        byte[] k = new byte[8];
        long bits = 0;
        for (int i = 0; i < 7; i++) bits = bits << 8 | (k7[off + i] & 0xff);
        for (int i = 0; i < 8; i++) {
            int b = (int) (bits >>> (49 - 7 * i) & 0x7f) << 1;
            k[i] = (byte) (Integer.bitCount(b) % 2 == 0 ? b | 1 : b);
        }
        try {
            Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "DES"));
            return c.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** NT-Response: хеш пароля, дополненный нулями до 21 байта, режется на три DES-ключа. */
    static byte[] ntResponse(byte[] auth, byte[] peer, byte[] user, String password) {
        byte[] ch = challengeHash(peer, auth, user);
        byte[] h = Arrays.copyOf(ntHash(password), 21);
        return cat(des(h, 0, ch), des(h, 7, ch), des(h, 14, ch));
    }

    /** Ответ сервера «S=40 шестнадцатеричных цифр»: доказывает клиенту, что сервер знает пароль. */
    static String authenticatorResponse(String password, byte[] ntResp, byte[] peer, byte[] auth, byte[] user) {
        byte[] phh = md4(ntHash(password));
        byte[] d = Crypto.sha1(phh, ntResp, MAGIC1);
        byte[] r = Crypto.sha1(d, challengeHash(peer, auth, user), MAGIC2);
        StringBuilder sb = new StringBuilder("S=");
        for (byte b : r) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    // ---------- RFC 3079 и EAP-MSCHAPv2 ----------

    static byte[] masterKey(String password, byte[] ntResp) {
        return Arrays.copyOf(Crypto.sha1(md4(ntHash(password)), ntResp, MPPE_MASTER), 16);
    }

    /** GetAsymmetricStartKey для 128 бит: SHA1(master | 40 x 0x00 | magic | 40 x 0xF2). */
    static byte[] startKey(byte[] master, byte[] magic) {
        byte[] z = new byte[40], f = new byte[40];
        Arrays.fill(f, (byte) 0xF2);
        return Arrays.copyOf(Crypto.sha1(master, z, magic, f), 16);
    }

    /**
     * MSK для IKEv2 (draft-kamath-pppext-eap-mschapv2): 64 байта =
     * MS-MPPE-Recv-Key сервера (она же ключ отправки клиента) | MS-MPPE-Send-Key сервера | 32 нуля.
     */
    static byte[] msk(String password, byte[] ntResp) {
        byte[] m = masterKey(password, ntResp);
        return cat(startKey(m, SEND_CLIENT), startKey(m, RECV_CLIENT), new byte[32]);
    }
}
