package ru.minisip.vpn;

import java.math.BigInteger;
import java.util.Arrays;

/** Модульные проверки вне IKE: HMAC, Box, ESP (в т.ч. окно повторов), DH, разбиение диапазонов в сети. */
public final class CryptoTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] unhex(String h) {
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    static byte[] pkt(int n, int seed) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) p[i] = (byte) (i * 7 + seed);
        p[0] = 0x45;
        return p;
    }

    static final Crypto.Suite[] SUITES = {
            new Crypto.Suite(Crypto.Suite.CBC, 256, Crypto.Suite.SHA256_128, Crypto.Suite.PRF_SHA256),
            new Crypto.Suite(Crypto.Suite.CBC, 128, Crypto.Suite.SHA1_96, Crypto.Suite.PRF_SHA1),
            new Crypto.Suite(Crypto.Suite.GCM, 256, 0, Crypto.Suite.PRF_SHA256),
            new Crypto.Suite(Crypto.Suite.GCM, 128, 0, Crypto.Suite.PRF_SHA1),
    };

    static String cidr(long a, long b) {
        StringBuilder sb = new StringBuilder();
        Ike.cidr(a, b, sb);
        return sb.toString();
    }

    public static void main(String[] args) {
        // HMAC-SHA256: RFC 4231, тест 1
        byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0x0b);
        check(hex(Crypto.hmac("HmacSHA256", key, "Hi There".getBytes())).equals(
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"), "HMAC-SHA256 по RFC 4231");
        // prf+ : первые 32 байта = prf(K, S | 0x01), следующие = prf(K, T1 | S | 0x02)
        Crypto.Suite s0 = SUITES[0];
        byte[] k = "key".getBytes(), seed = "seed".getBytes();
        byte[] t1 = s0.prf(k, seed, new byte[]{1});
        byte[] t2 = s0.prf(k, t1, seed, new byte[]{2});
        byte[] pp = s0.prfPlus(k, seed, 40);
        check(Arrays.equals(Arrays.copyOf(pp, 32), t1) && Arrays.equals(Arrays.copyOfRange(pp, 32, 40),
                Arrays.copyOf(t2, 8)), "prf+ склеивается из T1, T2");

        // Box: круг и подделка для всех наборов
        for (Crypto.Suite s : SUITES) {
            String name = (s.aead() ? "GCM" : "CBC") + s.bits + (s.aead() ? "" : s.integ == 12 ? "/SHA256" : "/SHA1");
            Crypto.Box a = new Crypto.Box(s, Crypto.random(s.encKeyLen()), new byte[0]);
            byte[] ek = Crypto.random(s.encKeyLen()), ik = Crypto.random(s.integKeyLen() == 0 ? 1 : s.integKeyLen());
            if (s.integKeyLen() == 0) ik = new byte[0];
            Crypto.Box box = new Crypto.Box(s, ek, ik);
            byte[] head = Crypto.random(8), plain = pkt(s.block() * 9, 3);
            byte[] sealed = box.seal(head, plain);
            check(sealed.length == 8 + box.overhead() + plain.length, name + ": длина = голова + IV + текст + ICV");
            byte[] back = box.open(sealed, 8);
            check(back != null && Arrays.equals(back, plain), name + ": запечатать-открыть");
            byte[] bad = sealed.clone();
            bad[3] ^= 1;
            check(box.open(bad, 8) == null, name + ": подделка головы отвергнута");
            bad = sealed.clone();
            bad[8 + s.ivLen() + 2] ^= 1;
            check(box.open(bad, 8) == null, name + ": подделка шифртекста отвергнута");
            bad = sealed.clone();
            bad[bad.length - 1] ^= 1;
            check(box.open(bad, 8) == null, name + ": подделка ICV отвергнута");
            check(box.open(Arrays.copyOf(sealed, 12), 8) == null, name + ": обрезанное сообщение отвергнуто");
            // два запечатывания одного текста не дают одинаковых IV
            check(!Arrays.equals(Arrays.copyOfRange(sealed, 8, 8 + s.ivLen()),
                    Arrays.copyOfRange(box.seal(head, plain), 8, 8 + s.ivLen())), name + ": IV не повторяется");

            // ESP: туда-обратно любых размеров, включая не кратные блоку
            byte[] ek2 = Crypto.random(s.encKeyLen()), ik2 = Crypto.random(s.integKeyLen());
            Esp tx = new Esp(s, new Crypto.Box(s, ek, ik), new Crypto.Box(s, ek2, ik2), 0xAABBCCDDL, 0x11223344L);
            Esp rx = new Esp(s, new Crypto.Box(s, ek2, ik2), new Crypto.Box(s, ek, ik), 0x11223344L, 0xAABBCCDDL);
            boolean all = true;
            for (int n = 20; n <= 1400; n += (n < 80 ? 1 : 97)) {
                byte[] p = pkt(n, n);
                byte[] w = tx.wrap(p, n);
                byte[] u = rx.unwrap(w, w.length);
                all &= u != null && Arrays.equals(u, p);
            }
            check(all, name + ": ESP wrap/unwrap для размеров 20..1400");
            byte[] w1 = tx.wrap(pkt(60, 1), 60);
            check(rx.unwrap(w1, w1.length) != null, name + ": ESP новый номер принят");
            check(rx.unwrap(w1, w1.length) == null, name + ": ESP повтор отброшен");
            byte[] w2 = tx.wrap(pkt(60, 2), 60), w3 = tx.wrap(pkt(60, 3), 60);
            check(rx.unwrap(w3, w3.length) != null && rx.unwrap(w2, w2.length) != null,
                    name + ": ESP перестановка внутри окна принята");
            byte[] w4 = tx.wrap(pkt(60, 4), 60);
            byte[] bad4 = w4.clone();
            bad4[bad4.length - 5] ^= 1;
            check(rx.unwrap(bad4, bad4.length) == null && rx.unwrap(w4, w4.length) != null,
                    name + ": подделка не «съедает» номер");
            byte[] w5 = tx.wrap(pkt(60, 5), 60);
            Crypto.put32(w5, 0, 0xDEADBEEFL);
            check(rx.unwrap(w5, w5.length) == null, name + ": чужой SPI отброшен");
            // окно: уходим вперёд на 100 — всё, что старше 64, отброшено
            byte[] old = tx.wrap(pkt(60, 6), 60);
            for (int i = 0; i < 100; i++) tx.wrap(pkt(40, 0), 40);
            byte[] far = tx.wrap(pkt(60, 7), 60);
            check(rx.unwrap(far, far.length) != null && rx.unwrap(old, old.length) == null,
                    name + ": номер старше окна отброшен");
        }

        // ESP: размер 0 и мусор не роняют
        Crypto.Suite s = SUITES[0];
        Esp e = new Esp(s, new Crypto.Box(s, Crypto.random(32), Crypto.random(32)),
                new Crypto.Box(s, Crypto.random(32), Crypto.random(32)), 1, 2);
        check(e.unwrap(new byte[5], 5) == null && e.unwrap(new byte[200], 200) == null, "ESP: мусор отброшен");

        // DH: обе стороны получают одно значение; негодные чужие значения отвергаются
        BigInteger x = Crypto.dhPrivate(), y = Crypto.dhPrivate();
        byte[] gx = Crypto.dhPublic(x), gy = Crypto.dhPublic(y);
        check(gx.length == 256 && Arrays.equals(Crypto.dhShared(x, gy), Crypto.dhShared(y, gx)), "DH-14: общий секрет совпал");
        check(Crypto.dhShared(x, new byte[]{0}) == null && Crypto.dhShared(x, new byte[]{1}) == null, "DH-14: 0 и 1 отвергнуты");
        byte[] big = new byte[256];
        Arrays.fill(big, (byte) 0xff);
        check(Crypto.dhShared(x, big) == null, "DH-14: значение >= p отвергнуто");

        // разбиение диапазонов TS на подсети
        check(cidr(0, 0xffffffffL).equals("0.0.0.0/0"), "cidr: любой адрес = 0.0.0.0/0");
        check(cidr(0x0A090000L, 0x0A0900FFL).equals("10.9.0.0/24"), "cidr: /24");
        check(cidr(0xAC100507L, 0xAC10050AL).equals("172.16.5.7/32 172.16.5.8/31 172.16.5.10/32"), "cidr: невыровненный диапазон");
        check(cidr(0x01020304L, 0x01020304L).equals("1.2.3.4/32"), "cidr: один адрес");
        check(cidr(1, 0xffffffffL).startsWith("0.0.0.1/32 0.0.0.2/31"), "cidr: хвост от 0.0.0.1");

        // MD4: RFC 1320
        check(hex(Mschap.md4(new byte[0])).equals("31d6cfe0d16ae931b73c59d7e0c089c0"), "MD4(\"\") по RFC 1320");
        check(hex(Mschap.md4("abc".getBytes())).equals("a448017aaf21d8525fc10ae87aa6729d"), "MD4(abc) по RFC 1320");
        check(hex(Mschap.md4("message digest".getBytes())).equals("d9130a8164549fe818874806e1c7014b"), "MD4(message digest)");
        check(hex(Mschap.md4("12345678901234567890123456789012345678901234567890123456789012345678901234567890".getBytes()))
                .equals("e33b4ddc9c38f2199c3e7b164fcc0536"), "MD4: сообщение длиннее блока");
        // MS-CHAPv2: RFC 2759, п. 9.2 («User» / «clientPass»), ключи MPPE: RFC 3079
        byte[] au = unhex("5B5D7C7D7B3F2F3E3C2C602132262628"), pe = unhex("21402324255E262A28295F2B3A337C7E");
        byte[] user = "User".getBytes();
        check(hex(Mschap.ntHash("clientPass")).equals("44ebba8d5312b8d611474411f56989ae"), "NtPasswordHash по RFC 2759");
        check(hex(Mschap.challengeHash(pe, au, user)).equals("d02e4386bce91226"), "ChallengeHash по RFC 2759");
        byte[] nt = Mschap.ntResponse(au, pe, user, "clientPass");
        check(hex(nt).equals("82309ecd8d708b5ea08faa3981cd83544233114a3d85d6df"), "NT-Response по RFC 2759");
        check(Mschap.authenticatorResponse("clientPass", nt, pe, au, user).equals(
                "S=407A5589115FD0D6209F510FE9C04566932CDA56"), "AuthenticatorResponse по RFC 2759");
        byte[] master = Mschap.masterKey("clientPass", nt);
        check(hex(master).equals("fdece3717a8c838cb388e527ae3cdd31"), "MasterKey по RFC 3079");
        byte[] msk = Mschap.msk("clientPass", nt);
        check(msk.length == 64 && hex(Arrays.copyOfRange(msk, 0, 16)).equals("d5f0e9521e3ea9589645e86051c82226")
                && hex(Arrays.copyOfRange(msk, 16, 32)).equals("8b7cdc149b993a1ba118cb153f56dccb")
                && hex(Arrays.copyOfRange(msk, 32, 64)).equals("00".repeat(32)), "MSK = серверные Recv | Send | 32 нуля");

        // OID из AlgorithmIdentifier (RFC 7427)
        check("1.2.840.113549.1.1.11".equals(Certs.oid(unhex("300d06092a864886f70d01010b0500"))), "OID sha256WithRSAEncryption");
        check("1.2.840.10045.4.3.2".equals(Certs.oid(unhex("300a06082a8648ce3d040302"))), "OID ecdsa-with-SHA256");
        check(Certs.oid(new byte[]{1, 2, 3}) == null && Certs.oid(unhex("30030603")) == null, "OID: мусор отвергнут");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
