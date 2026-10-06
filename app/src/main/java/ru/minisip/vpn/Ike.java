package ru.minisip.vpn;

import static ru.minisip.vpn.Crypto.cat;
import static ru.minisip.vpn.Crypto.put16;
import static ru.minisip.vpn.Crypto.put32;
import static ru.minisip.vpn.Crypto.u16;
import static ru.minisip.vpn.Crypto.u32;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * IKEv2-инициатор (RFC 7296): только байты, ни сокетов, ни потоков.
 * Умеет: IKE_SA_INIT (DH группы 14), IKE_AUTH с общим ключом (PSK), запрос адреса и DNS (CP),
 * один дочерний ESP-канал, INFORMATIONAL (DPD, удаление). Перегенерации ключей нет.
 * Не потокобезопасен: handshake ведёт один поток, потом входящие разбирает другой.
 */
final class Ike {
    static final int N_MOBIKE_SUPPORTED = 16396, N_UPDATE_SA_ADDRESSES = 16400;
    // типы payload
    static final int SA = 33, KE = 34, IDI = 35, IDR = 36, CERT = 37, AUTH = 39, NONCE = 40,
            NOTIFY = 41, DELETE = 42, TSI = 44, TSR = 45, SK = 46, CP = 47, EAP = 48;
    // типы обменов
    static final int INIT = 34, AUTHEX = 35, CHILD = 36, INFO = 37;
    private static final int F_INITIATOR = 0x08, F_RESPONSE = 0x20;
    // notify
    static final int N_NO_PROPOSAL = 14, N_INVALID_KE = 17, N_AUTH_FAILED = 24, N_NO_ADDITIONAL_SAS = 35,
            N_INTERNAL_ADDRESS = 36, N_TS_UNACCEPTABLE = 38, N_NAT_SRC = 16388, N_NAT_DST = 16389,
            N_COOKIE = 16390, N_SIG_HASHES = 16431;

    /** Один payload: тип и тело без общего заголовка. */
    static final class Pl {
        final int type;
        final byte[] body;

        Pl(int type, byte[] body) {
            this.type = type;
            this.body = body;
        }
    }

    /** Разобранное защищённое сообщение. */
    static final class Rx {
        int exch, id;
        boolean response;
        List<Pl> pl;
    }

    private static final class Chosen {
        Crypto.Suite suite;
        byte[] spi;
        int dh = -1, esn = -1;
    }

    // ---------- что узнали в ходе обмена (читает Tunnel) ----------
    String error;          // причина последней неудачи, по-человечески
    String ip;             // адрес клиента внутри туннеля
    String dns = "";       // DNS-серверы через пробел
    String routes = "";    // сети через туннель, «a.b.c.d/n» через пробел
    Esp esp;
    long spiOut;           // SPI, с которым шлём мы; сервер его же присылает в Delete
    private int nextId = 1;                     // 0 занят под IKE_SA_INIT, AUTH = 1, дальше DPD и Delete
    boolean mobike; 
    private final byte[] identity, psk;
    private final String password, host;
    private final List<java.security.cert.X509Certificate> ownRoots;   // свои корни из настроек или null
    private final boolean eap;                  // нет общего ключа — вход по логину и паролю (EAP-MSCHAPv2)
    private final String caError;
    private final int idType;
    private byte[] spiI, spiR = new byte[8], ni, nr, cookie, msg1, msg2, skD, skPi, skPr;
    private BigInteger dhX;
    private byte[] kePub;
    private Crypto.Suite suite;
    private Crypto.Box boxOut, boxIn;
    private long spiIn;
    private int peerNext;                       // следующий ожидаемый номер запроса от сервера

    // состояние входа
    byte[] next;                                // следующий запрос IKE_AUTH; null — вход завершён
    int curId;                                  // номер последнего нашего запроса IKE_AUTH
    private int stage;                          // 0: ждём ответ с сертификатом; 1: идёт EAP; 2: ждём итог
    private byte[] myId, idr, msk, authCh, peerCh, ntResp;
    private int mschapId;

    /**
     * psk непустой — вход по общему ключу. Иначе EAP-MSCHAPv2: identity — логин, password — пароль,
     * ca — PEM своего корня (пусто — системное хранилище), host — имя, которое обязано быть в сертификате.
     */
    Ike(String identity, String psk, String password, String ca, String host) {
        this.identity = identity.getBytes(StandardCharsets.UTF_8);
        this.psk = psk.getBytes(StandardCharsets.UTF_8);
        this.password = password;
        this.host = host;
        this.eap = psk.isEmpty();
        List<java.security.cert.X509Certificate> roots = ca.trim().isEmpty() ? null : Certs.parsePem(ca);
        this.ownRoots = roots;
        this.caError = !ca.trim().isEmpty() && roots == null ? "cannot read the CA certificate (PEM expected)" : null;
        this.idType = identity.contains("@") ? 3 : identity.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") ? 1 : 2;
    }

    /** Ошибка настроек до выхода в сеть, или null. */
    String check() {
        return caError;
    }

    /** Номер следующего нашего запроса (AUTH, DPD, Delete); зовут из разных потоков. */
    synchronized int nextId() {
        return nextId++;
    }

    // =====================================================================
    // Сборка и разбор payload'ов
    // =====================================================================

    static byte[] chain(List<Pl> l) {
        byte[][] parts = new byte[l.size()][];
        for (int i = 0; i < parts.length; i++) {
            Pl p = l.get(i);
            byte[] b = new byte[4 + p.body.length];
            b[0] = (byte) (i + 1 < parts.length ? l.get(i + 1).type : 0);
            put16(b, 2, b.length);
            System.arraycopy(p.body, 0, b, 4, p.body.length);
            parts[i] = b;
        }
        return cat(parts);
    }

    static List<Pl> parse(byte[] d, int off, int end, int first) {
        List<Pl> r = new ArrayList<>();
        int type = first;
        while (type != 0) {
            if (off + 4 > end) return null;
            int next = d[off] & 0xff, len = u16(d, off + 2);
            if (len < 4 || off + len > end) return null;
            r.add(new Pl(type, Arrays.copyOfRange(d, off + 4, off + len)));
            off += len;
            type = next;
        }
        return r;
    }

    private byte[] header(int next, int exch, int flags, int id, int len) {
        byte[] h = new byte[28];
        System.arraycopy(spiI, 0, h, 0, 8);
        System.arraycopy(spiR, 0, h, 8, 8);
        h[16] = (byte) next;
        h[17] = 0x20;                                  // версия 2.0
        h[18] = (byte) exch;
        h[19] = (byte) flags;
        put32(h, 20, id);
        put32(h, 24, len);
        return h;
    }

    static Pl notify(int type, byte[] data) {
        byte[] b = new byte[4 + data.length];
        put16(b, 2, type);                             // protocol id = 0, SPI нет
        System.arraycopy(data, 0, b, 4, data.length);
        return new Pl(NOTIFY, b);
    }

    private static byte[] transform(boolean more, int type, int id, int keyBits) {
        boolean attr = keyBits > 0;
        byte[] t = new byte[attr ? 12 : 8];
        t[0] = (byte) (more ? 3 : 0);
        put16(t, 2, t.length);
        t[4] = (byte) type;
        put16(t, 6, id);
        if (attr) {
            put16(t, 8, 0x800E);                       // атрибут «длина ключа»
            put16(t, 10, keyBits);
        }
        return t;
    }

    /** Одно предложение: transforms = {type, id, keyBits}... Последний transform помечается last. */
    private static byte[] proposal(boolean more, int num, int proto, byte[] spi, int[][] tr) {
        byte[][] parts = new byte[tr.length][];
        for (int i = 0; i < tr.length; i++) {
            parts[i] = transform(i + 1 < tr.length, tr[i][0], tr[i][1], tr[i][2]);
        }
        byte[] body = cat(parts);
        byte[] h = new byte[8];
        h[0] = (byte) (more ? 2 : 0);
        put16(h, 2, 8 + spi.length + body.length);
        h[4] = (byte) num;
        h[5] = (byte) proto;
        h[6] = (byte) spi.length;
        h[7] = (byte) tr.length;
        return cat(h, spi, body);
    }

    // transform-типы
    private static final int T_ENCR = 1, T_PRF = 2, T_INTEG = 3, T_DH = 4, T_ESN = 5;

    /** SA для IKE: AES-CBC + HMAC-SHA2-256/SHA1 или AES-GCM, DH 14. */
    private static byte[] saIke() {
        int[][] cbc = {{T_ENCR, 12, 256}, {T_ENCR, 12, 128}, {T_PRF, 5, 0}, {T_PRF, 2, 0},
                {T_INTEG, 12, 0}, {T_INTEG, 2, 0}, {T_DH, Crypto.DH_GROUP, 0}};
        int[][] gcm = {{T_ENCR, 20, 256}, {T_ENCR, 20, 128}, {T_PRF, 5, 0}, {T_PRF, 2, 0},
                {T_DH, Crypto.DH_GROUP, 0}};
        return cat(proposal(true, 1, 1, new byte[0], cbc), proposal(false, 2, 1, new byte[0], gcm));
    }

    /** SA для ESP с нашим входящим SPI. ESN выключен. */
    private static byte[] saEsp(byte[] spi) {
        int[][] cbc = {{T_ENCR, 12, 256}, {T_ENCR, 12, 128}, {T_INTEG, 12, 0}, {T_INTEG, 2, 0}, {T_ESN, 0, 0}};
        int[][] gcm = {{T_ENCR, 20, 256}, {T_ENCR, 20, 128}, {T_ESN, 0, 0}};
        return cat(proposal(true, 1, 3, spi, cbc), proposal(false, 2, 3, spi, gcm));
    }

    /** Разбирает SA с единственным выбранным предложением нужного протокола; null — не наше. */
    private static Chosen parseSa(byte[] b, int proto) {
        if (b.length < 8 || (b[5] & 0xff) != proto) return null;
        int spiLen = b[6] & 0xff, n = b[7] & 0xff, off = 8 + spiLen;
        if (u16(b, 2) > b.length || off > b.length) return null;
        Chosen c = new Chosen();
        c.spi = Arrays.copyOfRange(b, 8, off);
        int encr = 0, bits = 0, integ = 0, prf = 0;
        for (int i = 0; i < n; i++) {
            if (off + 8 > b.length) return null;
            int len = u16(b, off + 2), type = b[off + 4] & 0xff, id = u16(b, off + 6);
            if (len < 8 || off + len > b.length) return null;
            int keyBits = len >= 12 && u16(b, off + 8) == 0x800E ? u16(b, off + 10) : 0;
            switch (type) {
                case T_ENCR: encr = id; bits = keyBits; break;
                case T_PRF: prf = id; break;
                case T_INTEG: integ = id; break;
                case T_DH: c.dh = id; break;
                case T_ESN: c.esn = id; break;
                default: return null;
            }
            off += len;
        }
        c.suite = new Crypto.Suite(encr, bits, integ, prf);
        return c;
    }

    private static byte[] ts() {
        byte[] b = new byte[20];
        b[0] = 1;                                      // одно условие: любой IPv4, любые порты
        b[4] = 7;                                      // TS_IPV4_ADDR_RANGE
        put16(b, 6, 16);
        put16(b, 10, 0xffff);
        put32(b, 16, 0xffffffffL);
        return b;
    }

    private static String dotted(byte[] b, int o) {
        return (b[o] & 0xff) + "." + (b[o + 1] & 0xff) + "." + (b[o + 2] & 0xff) + "." + (b[o + 3] & 0xff);
    }

    private static String dotted(long a) {
        return (a >> 24 & 255) + "." + (a >> 16 & 255) + "." + (a >> 8 & 255) + "." + (a & 255);
    }

    /** Диапазон адресов [a, b] -> минимальный набор подсетей «addr/len». */
    static void cidr(long a, long b, StringBuilder out) {
        while (a <= b) {
            long size = a == 0 ? 1L << 32 : Long.lowestOneBit(a);
            while (size > b - a + 1) size >>= 1;
            if (out.length() > 0) out.append(' ');
            out.append(dotted(a)).append('/').append(32 - Long.numberOfTrailingZeros(size));
            a += size;
        }
    }

    private byte[] idBody() {
        return cat(new byte[]{(byte) idType, 0, 0, 0}, identity);
    }

    // =====================================================================
    // IKE_SA_INIT
    // =====================================================================

    /** Первый запрос (или повтор с cookie). srv — IPv4 сервера, port — его порт IKE. */
    byte[] initRequest(byte[] srv, int port) {
        if (spiI == null) {
            spiI = Crypto.random(8);
            ni = Crypto.random(32);
            dhX = Crypto.dhPrivate();
            kePub = Crypto.dhPublic(dhX);
        }
        byte[] ke = new byte[4 + Crypto.DH_BYTES];
        put16(ke, 0, Crypto.DH_GROUP);
        System.arraycopy(kePub, 0, ke, 4, kePub.length);
        byte[] p = new byte[2];
        put16(p, 0, port);
        // Адрес источника в хеше нарочно «неверный»: сервер решит, что мы за NAT, и будет
        // принимать и слать ESP внутри UDP 4500. Сырого ESP (протокол 50) в приложении нет.
        byte[] srcHash = Crypto.sha1(spiI, spiR, new byte[4], new byte[2]);
        byte[] dstHash = Crypto.sha1(spiI, spiR, srv, p);
        List<Pl> l = new ArrayList<>();
        if (cookie != null) l.add(notify(N_COOKIE, cookie));        // cookie обязан идти первым
        l.add(new Pl(SA, saIke()));
        l.add(new Pl(KE, ke));
        l.add(new Pl(NONCE, ni));
        l.add(notify(N_NAT_SRC, srcHash));
        l.add(notify(N_NAT_DST, dstHash));
        if (eap) {                                                  // RFC 7427: сервер подпишет SHA-2, а не SHA-1
            l.add(notify(N_SIG_HASHES, new byte[]{0, 2, 0, 3, 0, 4}));
        }
        byte[] body = chain(l);
        msg1 = cat(header(l.get(0).type, INIT, F_INITIATOR, 0, 28 + body.length), body);
        return msg1;
    }

    /** Ответ на INIT: 0 — принят, 1 — сервер просит cookie (повторить initRequest), -1 — ошибка. */
    int onInit(byte[] m) {
        if (m.length < 28 || !Arrays.equals(Arrays.copyOf(m, 8), spiI) || (m[19] & F_RESPONSE) == 0
                || (m[18] & 0xff) != INIT || u32(m, 20) != 0 || u32(m, 24) != m.length) {
            return fail("malformed IKE_SA_INIT reply");
        }
        List<Pl> pl = parse(m, 28, m.length, m[16] & 0xff);
        if (pl == null) return fail("malformed IKE_SA_INIT reply");
        Chosen sa = null;
        byte[] ke = null;
        for (Pl p : pl) {
            if (p.type == NOTIFY && p.body.length >= 4) {
                int t = u16(p.body, 2);
                if (t == N_COOKIE) {
                    cookie = Arrays.copyOfRange(p.body, 4 + (p.body[1] & 0xff), p.body.length);
                    return 1;
                }
                if (t < 16384) return fail(notifyText(t));
            } else if (p.type == SA) {
                sa = parseSa(p.body, 1);
            } else if (p.type == KE && p.body.length == 4 + Crypto.DH_BYTES && u16(p.body, 0) == Crypto.DH_GROUP) {
                ke = Arrays.copyOfRange(p.body, 4, p.body.length);
            } else if (p.type == NONCE && p.body.length >= 16 && p.body.length <= 256) {
                nr = p.body;
            }
        }
        if (sa == null || ke == null || nr == null || sa.dh != Crypto.DH_GROUP || !sa.suite.supported(true)) {
            return fail("server chose parameters we did not offer");
        }
        byte[] g = Crypto.dhShared(dhX, ke);
        if (g == null) return fail("bad DH value from server");
        spiR = Arrays.copyOfRange(m, 8, 16);
        msg2 = m;
        suite = sa.suite;

        // SKEYSEED и ключи IKE SA (RFC 7296, п. 2.14)
        byte[] seed = suite.prf(cat(ni, nr), g);
        int pr = suite.prfLen(), il = suite.integKeyLen(), el = suite.encKeyLen();
        byte[] k = suite.prfPlus(seed, cat(ni, nr, spiI, spiR), 3 * pr + 2 * il + 2 * el);
        int o = 0;
        skD = Arrays.copyOfRange(k, o, o += pr);
        byte[] ai = Arrays.copyOfRange(k, o, o += il);
        byte[] ar = Arrays.copyOfRange(k, o, o += il);
        byte[] ei = Arrays.copyOfRange(k, o, o += el);
        byte[] er = Arrays.copyOfRange(k, o, o += el);
        skPi = Arrays.copyOfRange(k, o, o += pr);
        skPr = Arrays.copyOfRange(k, o, o + pr);
        boxOut = new Crypto.Box(suite, ei, ai);
        boxIn = new Crypto.Box(suite, er, ar);
        return 0;
    }

    private int fail(String why) {
        error = why;
        return -1;
    }

    private static String notifyText(int t) {
        switch (t) {
            case N_NO_PROPOSAL: return "server rejected our algorithms (NO_PROPOSAL_CHOSEN)";
            case N_INVALID_KE: return "server wants another DH group (only group 14 is supported)";
            case N_AUTH_FAILED: return "authentication failed";
            case N_TS_UNACCEPTABLE: return "server rejected the traffic selectors";
            case N_INTERNAL_ADDRESS: return "server could not assign an address";
            default: return "server error " + t;
        }
    }

    // =====================================================================
    // Защищённые сообщения (SK)
    // =====================================================================

    /** Упаковывает payload'ы в зашифрованное сообщение IKE. */
    byte[] seal(int exch, boolean response, int id, List<Pl> l) {
        byte[] in = chain(l);
        int block = suite.block();
        int pad = (block - (in.length + 1) % block) % block;
        byte[] plain = new byte[in.length + pad + 1];
        System.arraycopy(in, 0, plain, 0, in.length);
        plain[plain.length - 1] = (byte) pad;
        int total = 28 + 4 + boxOut.overhead() + plain.length;
        byte[] head = cat(header(SK, exch, response ? F_RESPONSE : F_INITIATOR, id, total),
                new byte[4]);
        head[28] = (byte) (l.isEmpty() ? 0 : l.get(0).type);
        put16(head, 30, total - 28);
        return boxOut.seal(head, plain);
    }

    /** Проверяет и разбирает сообщение от сервера; null, если оно не наше или не проходит проверку. */
    Rx open(byte[] m) {
        if (m.length < 32 + boxIn.overhead() || !Arrays.equals(Arrays.copyOf(m, 8), spiI)
                || !Arrays.equals(Arrays.copyOfRange(m, 8, 16), spiR) || u32(m, 24) != m.length
                || (m[16] & 0xff) != SK || (m[19] & F_INITIATOR) != 0) {
            return null;
        }
        byte[] plain = boxIn.open(m, 32);
        if (plain == null || plain.length < 1) return null;
        int pad = plain[plain.length - 1] & 0xff;
        if (pad + 1 > plain.length) return null;
        List<Pl> pl = parse(plain, 0, plain.length - 1 - pad, m[28] & 0xff);
        if (pl == null) return null;
        Rx r = new Rx();
        r.exch = m[18] & 0xff;
        r.id = (int) u32(m, 20);
        r.response = (m[19] & F_RESPONSE) != 0;
        r.pl = pl;
        if (!r.response) {                              // запросы сервера: старые номера — повтор, молча теряем
            if (r.id < peerNext) return null;
            peerNext = r.id + 1;
        }
        return r;
    }

    // =====================================================================
    // IKE_AUTH: по общему ключу (один обмен) или EAP-MSCHAPv2 (пять обменов)
    // =====================================================================

    private byte[] authValue(byte[] secret, byte[] signedPrefix, byte[] nonce, byte[] sk, byte[] idBody) {
        byte[] key = suite.prf(secret, "Key Pad for IKEv2".getBytes(StandardCharsets.US_ASCII));
        return suite.prf(key, signedPrefix, nonce, suite.prf(sk, idBody));
    }

    private byte[] send(List<Pl> l) {
        curId = nextId();
        return seal(AUTHEX, false, curId, l);
    }

    private Pl authPayload(byte[] secret) {
        return new Pl(AUTH, cat(new byte[]{2, 0, 0, 0}, authValue(secret, msg1, nr, skPi, myId)));  // метод 2 = общий ключ
    }

    /** Первый запрос IKE_AUTH. С общим ключом сразу с AUTH; в режиме EAP AUTH нет — это и есть признак EAP. */
    byte[] authRequest() {
        byte[] spi = new byte[4];
        do {
            Crypto.RND.nextBytes(spi);
        } while (u32(spi, 0) == 0);
        spiIn = u32(spi, 0);
        myId = idBody();
        byte[] cp = {1, 0, 0, 0, 0, 1, 0, 0, 0, 3, 0, 0};        // CFG_REQUEST: адрес и DNS
        List<Pl> l = new ArrayList<>();
        l.add(new Pl(IDI, myId));
        if (!eap) l.add(authPayload(psk));
        l.add(new Pl(CP, cp));
        l.add(notify(N_MOBIKE_SUPPORTED, new byte[0]));
        l.add(new Pl(SA, saEsp(spi)));
        l.add(new Pl(TSI, ts()));
        l.add(new Pl(TSR, ts()));
        return send(l);
    }

    /**
     * Разбирает ответ на очередной IKE_AUTH. null — всё хорошо: либо надо слать next, либо
     * (next == null) вход завершён и заполнены ip, dns, routes, esp. Иначе текст причины.
     */
    String onAuth(byte[] m) {
        next = null;
        if (m.length >= 28 && u32(m, 24) != m.length) {
            return "IKE_AUTH reply is damaged or cut off (" + m.length + " of " + u32(m, 24) + " bytes)";
        }
        Rx r = open(m);
        if (r == null) return "bad or forged IKE_AUTH reply (cannot decrypt: wrong keys or algorithms?)";
        if (!r.response || r.exch != AUTHEX || r.id != curId) return "bad or forged IKE_AUTH reply";
        for (Pl p : r.pl) {
            if (p.type == NOTIFY && p.body.length >= 4 && u16(p.body, 2) < 16384) {
                return eap && stage > 0 ? "wrong login or password" : notifyText(u16(p.body, 2));
            }
        }
        if (!eap) return complete(r, psk);
        switch (stage) {
            case 0: return serverProof(r);
            case 1: return eapStep(r);
            default: return complete(r, msk);
        }
    }

    private static Pl find(Rx r, int type) {
        for (Pl p : r.pl) if (p.type == type) return p;
        return null;
    }

    /** Ответ №1 в режиме EAP: IDr, сертификат и подпись сервера, затем первый EAP-запрос. */
    private String serverProof(Rx r) {
        Pl pi = find(r, IDR), pa = find(r, AUTH);
        if (pi == null || pa == null || pa.body.length < 5) return "incomplete IKE_AUTH reply";
        idr = pi.body;
        List<java.security.cert.X509Certificate> chain = new ArrayList<>();
        for (Pl p : r.pl) {
            if (p.type != CERT || p.body.length < 2 || p.body[0] != 4) continue;   // 4 = X.509, подпись
            java.security.cert.X509Certificate c = Certs.parseDer(Arrays.copyOfRange(p.body, 1, p.body.length));
            if (c != null) chain.add(c);
        }
        if (chain.isEmpty()) return "server sent no certificate (login and password need a server certificate)";
        String err = Certs.verify(chain, ownRoots, host);
        if (err != null) return err;
        err = Certs.verifySig(chain.get(0), pa.body, cat(msg2, ni, suite.prf(skPr, idr)));
        if (err != null) return err;
        stage = 1;
        Pl e = find(r, EAP);
        if (e == null) return "server did not start EAP";
        return eapStep(r);
    }

    private static byte[] eapPacket(int code, int id, int type, byte[] data) {
        int len = 4 + (type >= 0 ? 1 : 0) + data.length;
        byte[] b = new byte[len];
        b[0] = (byte) code;
        b[1] = (byte) id;
        put16(b, 2, len);
        if (type >= 0) b[4] = (byte) type;
        System.arraycopy(data, 0, b, len - data.length, data.length);
        return b;
    }

    /** Один шаг EAP: читает запрос сервера и готовит next (или завершает EAP). */
    private String eapStep(Rx r) {
        Pl e = find(r, EAP);
        if (e == null || e.body.length < 4 || u16(e.body, 2) > e.body.length) return "EAP message expected";
        int code = e.body[0] & 0xff, id = e.body[1] & 0xff, len = u16(e.body, 2);
        if (code == 4) return "wrong login or password";
        if (code == 3) {                                           // EAP Success: ключ есть, шлём итоговый AUTH
            if (msk == null) return "server finished EAP before the password was checked";
            stage = 2;
            next = send(java.util.Collections.singletonList(authPayload(msk)));
            return null;
        }
        if (code != 1 || len < 5) return "unexpected EAP message";
        int type = e.body[4] & 0xff;
        byte[] reply;
        if (type == 1) {                                           // Identity
            reply = eapPacket(2, id, 1, identity);
        } else if (type == 26) {
            reply = mschap(id, Arrays.copyOfRange(e.body, 5, len));
            if (reply == null) return error != null ? error : "bad MS-CHAPv2 message";
        } else {
            return "server wants an unsupported EAP method (" + type + "); only MS-CHAPv2 is supported";
        }
        next = send(java.util.Collections.singletonList(new Pl(EAP, reply)));
        return null;
    }

    /** MS-CHAPv2 (RFC 2759 + draft-kamath): Challenge -> Response, Success -> подтверждение. */
    private byte[] mschap(int eapId, byte[] d) {
        error = null;
        if (d.length < 4) return null;
        int op = d[0] & 0xff, id = d[1] & 0xff;
        if (op == 1) {                                             // Challenge: value-size, 16 байт, имя сервера
            if (d.length < 5 + 16 || (d[4] & 0xff) != 16) return null;
            authCh = Arrays.copyOfRange(d, 5, 21);
            peerCh = Crypto.random(16);
            ntResp = Mschap.ntResponse(authCh, peerCh, identity, password);
            msk = Mschap.msk(password, ntResp);
            mschapId = id;
            byte[] value = cat(peerCh, new byte[8], ntResp, new byte[1]);          // 49 байт
            byte[] body = new byte[5 + value.length + identity.length];
            body[0] = 2;
            body[1] = (byte) id;
            put16(body, 2, body.length);
            body[4] = 49;
            System.arraycopy(value, 0, body, 5, value.length);
            System.arraycopy(identity, 0, body, 5 + value.length, identity.length);
            return eapPacket(2, eapId, 26, body);
        }
        if (op == 3 && ntResp != null) {                           // Success: «S=<40 hex> M=...»
            String text = new String(d, 4, d.length - 4, StandardCharsets.ISO_8859_1);
            String want = Mschap.authenticatorResponse(password, ntResp, peerCh, authCh, identity);
            if (!text.regionMatches(true, 0, want, 0, want.length())) {
                error = "server failed MS-CHAPv2 authentication";
                return null;
            }
            return eapPacket(2, eapId, 26, new byte[]{3});
        }
        if (op == 4) {
            error = "wrong login or password";
            return null;
        }
        return null;
    }

    /** Последний ответ: AUTH (по общему ключу или MSK), CP, SA, TS. Заполняет ip, dns, routes, esp. */
    private String complete(Rx r, byte[] secret) {
        byte[] id = null, auth = null, cp = null, tsr = null;
        Chosen sa = null;
        for (Pl p : r.pl) {
            switch (p.type) {
                case EAP: return "server asks for EAP, enter a login and password instead of a key";
                case IDR: id = p.body; break;
                case AUTH: auth = p.body; break;
                case CP: cp = p.body; break;
                case SA: sa = parseSa(p.body, 3); break;
                case TSR: tsr = p.body; break;
                case NOTIFY:
                    if (p.body.length >= 4 && u16(p.body, 2) == N_MOBIKE_SUPPORTED) mobike = true;
                    break;
                default: break;
            }
        }
        if (id == null) id = idr;
        if (id == null || auth == null || sa == null || cp == null || tsr == null) return "incomplete IKE_AUTH reply";
        if ((auth[0] & 0xff) != 2) return "server uses a certificate, enter a login and password";
        byte[] want = authValue(secret, msg2, ni, skPr, id);
        if (!java.security.MessageDigest.isEqual(want, Arrays.copyOfRange(auth, 4, auth.length))) {
            return eap ? "server failed authentication (key mismatch)" : "server failed authentication (wrong pre-shared key?)";
        }
        if (!sa.suite.supported(false) || sa.spi.length != 4 || (sa.esn > 0) || sa.dh > 0) {
            return "server chose ESP parameters we did not offer";
        }
        for (int o = 4; o + 4 <= cp.length; ) {
            int t = u16(cp, o) & 0x7fff, len = u16(cp, o + 2);
            if (o + 4 + len > cp.length) return "malformed configuration payload";
            if (t == 1 && len == 4) ip = dotted(cp, o + 4);
            if (t == 3 && len == 4) dns += (dns.isEmpty() ? "" : " ") + dotted(cp, o + 4);
            o += 4 + len;
        }
        if (ip == null) return "server did not assign an address";
        StringBuilder rt = new StringBuilder();
        for (int o = 4; o + 16 <= tsr.length; o += 16) {
            if ((tsr[o] & 0xff) == 7) cidr(u32(tsr, o + 8), u32(tsr, o + 12), rt);
        }
        if (rt.length() == 0) return "server offered no routes";
        routes = rt.toString();
        Crypto.Suite c = sa.suite;
        int e = c.encKeyLen(), i = c.integKeyLen();
        byte[] k = suite.prfPlus(skD, cat(ni, nr), 2 * (e + i));
        Crypto.Box out = new Crypto.Box(c, Arrays.copyOfRange(k, 0, e), Arrays.copyOfRange(k, e, e + i));
        Crypto.Box in = new Crypto.Box(c, Arrays.copyOfRange(k, e + i, 2 * e + i),
                Arrays.copyOfRange(k, 2 * e + i, 2 * (e + i)));
        spiOut = u32(sa.spi, 0);
        esp = new Esp(c, out, in, spiOut, spiIn);
        return null;
    }

    /** Тело запроса INFORMATIONAL для смены пути (MOBIKE). Адрес источника в хеше «неверный», как и в INIT:
     *  сервер продолжает считать нас за NAT и шлёт ESP внутри UDP 4500 на адрес, с которого пришёл запрос. */
    List<Pl> mobikeUpdate(byte[] srv, int port) {
        byte[] p = new byte[2];
        put16(p, 0, port);
        List<Pl> l = new ArrayList<>();
        l.add(notify(N_UPDATE_SA_ADDRESSES, new byte[0]));
        l.add(notify(N_NAT_SRC, Crypto.sha1(spiI, spiR, new byte[4], new byte[2])));
        l.add(notify(N_NAT_DST, Crypto.sha1(spiI, spiR, srv, p)));
        return l;
    }
}
