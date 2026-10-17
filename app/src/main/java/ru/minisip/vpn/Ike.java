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
    // notify
    static final int N_NO_PROPOSAL = 14, N_INVALID_KE = 17, N_AUTH_FAILED = 24, N_NO_ADDITIONAL_SAS = 35,
            N_INTERNAL_ADDRESS = 36, N_TS_UNACCEPTABLE = 38, N_NAT_SRC = 16388, N_NAT_DST = 16389,
            N_COOKIE = 16390, N_SIG_HASHES = 16431, N_REKEY_SA = 16393;
    private static final int F_INITIATOR = 0x08, F_RESPONSE = 0x20;
    // transform-типы
    private static final int T_ENCR = 1, T_PRF = 2, T_INTEG = 3, T_DH = 4, T_ESN = 5;
    private final byte[] identity, psk;
    private final String password, host;
    private final List<java.security.cert.X509Certificate> ownRoots;   // свои корни из настроек или null
    private final boolean eap;                  // нет общего ключа — вход по логину и паролю (EAP-MSCHAPv2)
    private final String caError;
    private final int idType;
    // ---------- что узнали в ходе обмена (читает Tunnel) ----------
    String error;          // причина последней неудачи, по-человечески
    String ip;             // адрес клиента внутри туннеля
    String dns = "";       // DNS-серверы через пробел
    String routes = "";    // сети через туннель, «a.b.c.d/n» через пробел
    Esp esp;
    long spiOut;           // SPI, с которым шлём мы; сервер его же присылает в Delete
    boolean mobike;
    // состояние входа
    byte[] next;                                // следующий запрос IKE_AUTH; null — вход завершён
    int curId;                                  // номер последнего нашего запроса IKE_AUTH
    /**
     * SPI старого (заменяемого) IKE SA — нужны для DELETE.
     */
    byte[] oldSpiI, oldSpiR;
    /**
     * Мы — исходящий инициатор текущего IKE SA (флаг I, RFC 7296 §3.1). После IKE rekey им становится
     * тот, кто начал обмен.
     */
    private boolean origInit = true;
    private int nextId = 1;                     // 0 занят под IKE_SA_INIT, AUTH = 1, дальше DPD и Delete
    private byte[] spiI, spiR = new byte[8], ni, nr, cookie, msg1, msg2, skD, skPi, skPr;
    private BigInteger dhX;
    private byte[] kePub;
    private Crypto.Suite suite;
    private Crypto.Box boxOut, boxIn;
    private long spiIn;
    private int peerNext;                       // следующий ожидаемый номер запроса от сервера
    private int stage;                          // 0: ждём ответ с сертификатом; 1: идёт EAP; 2: ждём итог
    private byte[] myId, idr, msk, authCh, peerCh, ntResp;
    private int mschapId;
    private int ackFloor = 1;                   // меньше этого номера все наши запросы сервер подтвердил
    /**
     * Номер нашего CREATE_CHILD_SA; -1 — rekey не идёт.
     */
    private int childRekeyId = -1;
    /**
     * Наш новый входящий SPI (SPI_I), уходит в SA-предложение.
     */
    private byte[] childRekeySpiI;
    /**
     * Новый исходящий SPI от сервера (SPI_R), из ответа.
     */
    private byte[] childRekeySpiR;
    private byte[] childRekeyNi;                // наш nonce
    private byte[] childRekeyNr;                // nonce сервера
    private DhGroup childRekeyDh;               // наша DH-пара (PFS)
    private boolean childPfs;                   // false: rekey без DH (дефолт strongSwan)
    private int childDhGroup = DhGroup.MODP3072;  // группа в KE (первая из предложения)

    // =====================================================================
    // Сборка и разбор payload'ов
    // =====================================================================
    private byte[] childRekeyKe;                // наш KEi
    /**
     * Номер нашего CREATE_CHILD_SA для IKE rekey; -1 — не идёт.
     */
    private int ikeRekeyId = -1;
    private byte[] ikeRekeySpiI;       // наш новый 8-байтный SPI (в SA-предложении)
    private byte[] ikeRekeySpiR;       // SPI сервера (из ответа)
    private byte[] ikeRekeyNi, ikeRekeyNr;
    private BigInteger ikeRekeyX;
    private byte[] ikeRekeyKe;

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

    /**
     * Одно предложение: transforms = {type, id, keyBits}... Последний transform помечается last.
     */
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

    /**
     * SA для IKE: AES-CBC + HMAC-SHA2-256/SHA1 или AES-GCM, DH 14.
     */
    private static byte[] saIke() {
        int[][] cbc = {{T_ENCR, 12, 256}, {T_ENCR, 12, 128}, {T_PRF, 5, 0}, {T_PRF, 2, 0},
                {T_INTEG, 12, 0}, {T_INTEG, 2, 0}, {T_DH, Crypto.DH_GROUP, 0}};
        int[][] gcm = {{T_ENCR, 20, 256}, {T_ENCR, 20, 128}, {T_PRF, 5, 0}, {T_PRF, 2, 0},
                {T_DH, Crypto.DH_GROUP, 0}};
        return cat(proposal(true, 1, 1, new byte[0], cbc), proposal(false, 2, 1, new byte[0], gcm));
    }

    /**
     * SA для ESP с нашим входящим SPI. ESN выключен.
     */
    private static byte[] saEsp(byte[] spi) {
        int[][] cbc = {{T_ENCR, 12, 256}, {T_ENCR, 12, 128}, {T_INTEG, 12, 0}, {T_INTEG, 2, 0}, {T_ESN, 0, 0}};
        int[][] gcm = {{T_ENCR, 20, 256}, {T_ENCR, 20, 128}, {T_ESN, 0, 0}};
        return cat(proposal(true, 1, 3, spi, cbc), proposal(false, 2, 3, spi, gcm));
    }

    /**
     * SA для ESP с DH (PFS) для rekey Child SA: то же, что saEsp(), плюс группа DH.
     */
    private static byte[] saEspPfs(byte[] spi) {
        int[][] cbc = {{T_ENCR, 12, 256}, {T_ENCR, 12, 128}, {T_INTEG, 12, 0}, {T_INTEG, 2, 0},
                {T_DH, DhGroup.MODP3072, 0}, {T_DH, DhGroup.ECP256, 0}, {T_DH, DhGroup.MODP2048, 0}, {T_ESN, 0, 0}};
        int[][] gcm = {{T_ENCR, 20, 256}, {T_ENCR, 20, 128},
                {T_DH, DhGroup.MODP3072, 0}, {T_DH, DhGroup.ECP256, 0}, {T_DH, DhGroup.MODP2048, 0}, {T_ESN, 0, 0}};
        return cat(proposal(true, 1, 3, spi, cbc), proposal(false, 2, 3, spi, gcm));
    }

    /**
     * Ответное SA респондера: одно предложение с алгоритмами, которые выбрал инициатор (RFC 7296 §3.3.6).
     */
    private static byte[] saEspChosen(byte[] spi, Chosen c) {
        List<int[]> t = new ArrayList<>();
        t.add(new int[]{T_ENCR, c.encr, c.bits});
        if (c.integ > 0) t.add(new int[]{T_INTEG, c.integ, 0});
        t.add(new int[]{T_DH, Crypto.DH_GROUP, 0});
        t.add(new int[]{T_ESN, 0, 0});
        return proposal(false, c.num, 3, spi, t.toArray(new int[0][]));
    }

    /**
     * Ответное SA респондера для IKE rekey: одно предложение с выбранными алгоритмами (RFC 7296 §3.3.6).
     */
    private static byte[] saIkeChosen(byte[] spi, Chosen c) {
        List<int[]> t = new ArrayList<>();
        t.add(new int[]{T_ENCR, c.encr, c.bits});
        t.add(new int[]{T_PRF, c.prf, 0});
        if (c.integ > 0) t.add(new int[]{T_INTEG, c.integ, 0});
        t.add(new int[]{T_DH, Crypto.DH_GROUP, 0});
        return proposal(false, c.num, 1, spi, t.toArray(new int[0][]));
    }

    // =====================================================================
    // IKE_SA_INIT
    // =====================================================================

    /**
     * Разбирает SA с единственным выбранным предложением нужного протокола; null — не наше.
     */
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
                case T_ENCR:
                    encr = id;
                    bits = keyBits;
                    break;
                case T_PRF:
                    prf = id;
                    break;
                case T_INTEG:
                    integ = id;
                    break;
                case T_DH:
                    c.dh = id;
                    break;
                case T_ESN:
                    c.esn = id;
                    break;
                default:
                    break;
            }
            off += len;
        }
        c.num = b[4] & 0xff;
        c.encr = encr;
        c.bits = bits;
        c.integ = integ;
        c.prf = prf;
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

    // =====================================================================
    // Защищённые сообщения (SK)
    // =====================================================================

    /**
     * Диапазон адресов [a, b] -> минимальный набор подсетей «addr/len».
     */
    static void cidr(long a, long b, StringBuilder out) {
        while (a <= b) {
            long size = a == 0 ? 1L << 32 : Long.lowestOneBit(a);
            while (size > b - a + 1) size >>= 1;
            if (out.length() > 0) out.append(' ');
            out.append(dotted(a)).append('/').append(32 - Long.numberOfTrailingZeros(size));
            a += size;
        }
    }

    private static String notifyText(int t) {
        switch (t) {
            case N_NO_PROPOSAL:
                return "server rejected our algorithms (NO_PROPOSAL_CHOSEN)";
            case N_INVALID_KE:
                return "server wants another DH group (INVALID_KE_PAYLOAD)";
            case N_AUTH_FAILED:
                return "authentication failed";
            case N_TS_UNACCEPTABLE:
                return "server rejected the traffic selectors";
            case N_INTERNAL_ADDRESS:
                return "server could not assign an address";
            default:
                return "server error " + t;
        }
    }

    // =====================================================================
    // IKE_AUTH: по общему ключу (один обмен) или EAP-MSCHAPv2 (пять обменов)
    // =====================================================================

    private static Pl find(Rx r, int type) {
        for (Pl p : r.pl) if (p.type == type) return p;
        return null;
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

    /**
     * SA для IKE SA rekey: то же, что saIke(), но с 8-байтным SPI инициатора в предложении.
     */
    private static byte[] saIkeRekey(byte[] spi) {
        int[][] cbc = {{T_ENCR, 12, 256}, {T_ENCR, 12, 128}, {T_PRF, 5, 0}, {T_PRF, 2, 0},
                {T_INTEG, 12, 0}, {T_INTEG, 2, 0}, {T_DH, Crypto.DH_GROUP, 0}};
        int[][] gcm = {{T_ENCR, 20, 256}, {T_ENCR, 20, 128}, {T_PRF, 5, 0}, {T_PRF, 2, 0},
                {T_DH, Crypto.DH_GROUP, 0}};
        return cat(proposal(true, 1, 1, spi, cbc), proposal(false, 2, 1, spi, gcm));
    }

    /**
     * Ошибка настроек до выхода в сеть, или null.
     */
    String check() {
        return caError;
    }

    /**
     * Номер следующего нашего запроса (AUTH, DPD, Delete); зовут из разных потоков.
     */
    synchronized int nextId() {
        return nextId++;
    }

    /**
     * Пришёл проверенный ответ на наш запрос с номером id.
     */
    synchronized void acked(int id) {
        if (id >= ackFloor) ackFloor = id + 1;
    }

    /**
     * Первый номер, которого сервер мог ещё не получить.
     */
    synchronized int ackFloor() {
        return ackFloor;
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

    private byte[] idBody() {
        return cat(new byte[]{(byte) idType, 0, 0, 0}, identity);
    }

    /**
     * Первый запрос (или повтор с cookie). srv — IPv4 сервера, port — его порт IKE.
     */
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

    /**
     * Ответ на INIT: 0 — принят, 1 — сервер просит cookie (повторить initRequest), -1 — ошибка.
     */
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

    // =====================================================================
    // Child SA rekey (RFC 7296 §2.8.2). Инициатор: наш CREATE_CHILD_SA с PFS.
    // =====================================================================

    /**
     * Упаковывает payload'ы в зашифрованное сообщение IKE.
     */
    byte[] seal(int exch, boolean response, int id, List<Pl> l) {
        byte[] in = chain(l);
        int block = suite.block();
        int pad = (block - (in.length + 1) % block) % block;
        byte[] plain = new byte[in.length + pad + 1];
        System.arraycopy(in, 0, plain, 0, in.length);
        plain[plain.length - 1] = (byte) pad;
        int total = 28 + 4 + boxOut.overhead() + plain.length;
        byte[] head = cat(header(SK, exch, (origInit ? F_INITIATOR : 0) | (response ? F_RESPONSE : 0), id, total),
                new byte[4]);
        head[28] = (byte) (l.isEmpty() ? 0 : l.get(0).type);
        put16(head, 30, total - 28);
        return boxOut.seal(head, plain);
    }

    /**
     * Проверяет и разбирает сообщение от сервера; null, если оно не наше или не проходит проверку.
     */
    Rx open(byte[] m) {
        if (m.length < 32 + boxIn.overhead() || !Arrays.equals(Arrays.copyOf(m, 8), spiI)
                || !Arrays.equals(Arrays.copyOfRange(m, 8, 16), spiR) || u32(m, 24) != m.length
                || (m[16] & 0xff) != SK || ((m[19] & F_INITIATOR) != 0) == origInit) {
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

    /**
     * Первый запрос IKE_AUTH. С общим ключом сразу с AUTH; в режиме EAP AUTH нет — это и есть признак EAP.
     */
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
        if (r == null)
            return "bad or forged IKE_AUTH reply (cannot decrypt: wrong keys or algorithms?)";
        if (!r.response || r.exch != AUTHEX || r.id != curId) return "bad or forged IKE_AUTH reply";
        for (Pl p : r.pl) {
            if (p.type == NOTIFY && p.body.length >= 4 && u16(p.body, 2) < 16384) {
                return eap && stage > 0 ? "wrong login or password" : notifyText(u16(p.body, 2));
            }
        }
        // MOBIKE_SUPPORTED может прийти в любом из ответов IKE_AUTH (при EAP не обязательно в последнем).
        // Ответ уже расшифрован и проверен в open(), так что нотификации можно верить.
        for (Pl p : r.pl) {
            if (p.type == NOTIFY && p.body.length >= 4 && u16(p.body, 2) == N_MOBIKE_SUPPORTED) {
                mobike = true;
            }
        }
        if (!eap) return complete(r, psk);
        switch (stage) {
            case 0:
                return serverProof(r);
            case 1:
                return eapStep(r);
            default:
                return complete(r, msk);
        }
    }

    /**
     * Ответ №1 в режиме EAP: IDr, сертификат и подпись сервера, затем первый EAP-запрос.
     */
    private String serverProof(Rx r) {
        Pl pi = find(r, IDR), pa = find(r, AUTH);
        if (pi == null || pa == null || pa.body.length < 5) return "incomplete IKE_AUTH reply";
        idr = pi.body;
        List<java.security.cert.X509Certificate> chain = new ArrayList<>();
        for (Pl p : r.pl) {
            if (p.type != CERT || p.body.length < 2 || p.body[0] != 4)
                continue;   // 4 = X.509, подпись
            java.security.cert.X509Certificate c = Certs.parseDer(Arrays.copyOfRange(p.body, 1, p.body.length));
            if (c != null) chain.add(c);
        }
        if (chain.isEmpty())
            return "server sent no certificate (login and password need a server certificate)";
        String err = Certs.verify(chain, ownRoots, host);
        if (err != null) return err;
        err = Certs.verifySig(chain.get(0), pa.body, cat(msg2, ni, suite.prf(skPr, idr)));
        if (err != null) return err;
        stage = 1;
        Pl e = find(r, EAP);
        if (e == null) return "server did not start EAP";
        return eapStep(r);
    }

    /**
     * Один шаг EAP: читает запрос сервера и готовит next (или завершает EAP).
     */
    private String eapStep(Rx r) {
        Pl e = find(r, EAP);
        if (e == null || e.body.length < 4 || u16(e.body, 2) > e.body.length)
            return "EAP message expected";
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

    /**
     * MS-CHAPv2 (RFC 2759 + draft-kamath): Challenge -> Response, Success -> подтверждение.
     */
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

    /**
     * Последний ответ: AUTH (по общему ключу или MSK), CP, SA, TS. Заполняет ip, dns, routes, esp.
     */
    private String complete(Rx r, byte[] secret) {
        byte[] id = null, auth = null, cp = null, tsr = null;
        Chosen sa = null;
        for (Pl p : r.pl) {
            switch (p.type) {
                case EAP:
                    return "server asks for EAP, enter a login and password instead of a key";
                case IDR:
                    id = p.body;
                    break;
                case AUTH:
                    auth = p.body;
                    break;
                case CP:
                    cp = p.body;
                    break;
                case SA:
                    sa = parseSa(p.body, 3);
                    break;
                case TSR:
                    tsr = p.body;
                    break;
                case NOTIFY:
                    if (p.body.length >= 4 && u16(p.body, 2) == N_MOBIKE_SUPPORTED) mobike = true;
                    break;
                default:
                    break;
            }
        }
        if (id == null) id = idr;
        if (id == null || auth == null || sa == null || cp == null || tsr == null)
            return "incomplete IKE_AUTH reply";
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
        // RFC 7296, п. 2.17: KEYMAT = prf+(SK_d, Ni | Nr), затем
        //   init_enc [0..e) | resp_enc [e..2e) | init_integ [2e..2e+i) | resp_integ [2e+i..2e+2i)
        // (для AEAD e включает 4 байта соли, i = 0).
        Crypto.Box out = new Crypto.Box(c,
                Arrays.copyOfRange(k, 0, e),
                Arrays.copyOfRange(k, e, e + i));
        Crypto.Box in = new Crypto.Box(c,
                Arrays.copyOfRange(k, e + i, 2 * e + i),
                Arrays.copyOfRange(k, 2 * e + i, 2 * (e + i)));
        spiOut = u32(sa.spi, 0);
        esp = new Esp(c, out, in, spiOut, spiIn);
        return null;
    }

    /**
     * Тело запроса INFORMATIONAL для смены пути (MOBIKE). Адрес источника в хеше «неверный», как и в INIT:
     * сервер продолжает считать нас за NAT и шлёт ESP внутри UDP 4500 на адрес, с которого пришёл запрос.
     */
    List<Pl> mobikeUpdate(byte[] srv, int port) {
        byte[] p = new byte[2];
        put16(p, 0, port);
        List<Pl> l = new ArrayList<>();
        l.add(notify(N_UPDATE_SA_ADDRESSES, new byte[0]));
        l.add(notify(N_NAT_SRC, Crypto.sha1(spiI, spiR, new byte[4], new byte[2])));
        l.add(notify(N_NAT_DST, Crypto.sha1(spiI, spiR, srv, p)));
        return l;
    }

    // =====================================================================
    // IKE SA rekey (RFC 7296 §2.8). Инициатор: наш CREATE_CHILD_SA с новым SPIi.
    // DH обязателен — на новом IKE SA обеспечивается PFS (RFC 7296 §2.18).
    // =====================================================================

    /**
     * Идёт ли сейчас наш rekey Child SA.
     */
    boolean childRekeyPending() {
        return childRekeyId >= 0;
    }

    /**
     * Номер текущего rekey-запроса (для сверки в Tunnel.onIke). -1 — нет.
     */
    int childRekeyId() {
        return childRekeyId;
    }

    /**
     * Режим Child rekey: 0 — без DH, иначе номер группы в KE. Меняется после отказа сервера.
     */
    int childMode() {
        return childPfs ? childDhGroup : 0;
    }

    /**
     * Payload'ы CREATE_CHILD_SA для Child rekey (RFC 7296 §2.8.2): SA, Ni, KEi, TSi, TSR.
     * PFS включён — KE обязателен. Вызывающий сам шлёт через seal(CHILD, false, childRekeyId(), pls).
     * null — rekey уже идёт.
     */
    List<Pl> rekeyChildPl(long oldSpiIn) {
        if (childRekeyId >= 0) return null;
        childRekeyId = nextId();
        childRekeySpiI = new byte[4];
        do {
            Crypto.RND.nextBytes(childRekeySpiI);
        } while (u32(childRekeySpiI, 0) == 0);
        childRekeyNi = Crypto.random(32);
        byte[] ke = null;
        if (childPfs) {
            childRekeyDh = DhGroup.of(childDhGroup);
            childRekeyDh.generate();
            childRekeyKe = childRekeyDh.publicKey();
            ke = new byte[4 + childRekeyKe.length];
            put16(ke, 0, childRekeyDh.id);
            System.arraycopy(childRekeyKe, 0, ke, 4, childRekeyKe.length);
        }
        List<Pl> l = new ArrayList<>();
        // RFC 7296 §1.3.3: N(REKEY_SA) с SPI заменяемого SA — тем, что мы ждём во входящих ESP; идёт первым
        byte[] rk = new byte[8];
        rk[0] = 3;                                     // протокол ESP
        rk[1] = 4;                                     // длина SPI
        put16(rk, 2, N_REKEY_SA);
        put32(rk, 4, oldSpiIn);
        l.add(new Pl(NOTIFY, rk));
        l.add(new Pl(SA, childPfs ? saEspPfs(childRekeySpiI) : saEsp(childRekeySpiI)));
        l.add(new Pl(NONCE, childRekeyNi));
        if (childPfs) l.add(new Pl(KE, ke));
        l.add(new Pl(TSI, ts()));
        l.add(new Pl(TSR, ts()));
        return l;
    }

    /**
     * Разбор ответа на CREATE_CHILD_SA (Child rekey). null — ошибка (текст в error); иначе новый Esp
     * с новыми SPI. Старый Esp остаётся у вызывающего, пока тот его не заменит (make-before-break).
     */
    Esp onRekeyChildResponse(Rx r) {
        byte[] nrNew = null, ke = null;
        Chosen sa = null;
        for (Pl p : r.pl) {
            if (p.type == NOTIFY && p.body.length >= 4 && u16(p.body, 2) < 16384) {
                error = notifyText(u16(p.body, 2));
                if (u16(p.body, 2) == N_NO_PROPOSAL && !childPfs) childPfs = true;
                else if (u16(p.body, 2) == N_INVALID_KE && p.body.length >= 6 && DhGroup.supported(u16(p.body, 4)))
                    childDhGroup = u16(p.body, 4);
                return null;
            }
            if (p.type == SA) sa = parseSa(p.body, 3);
            else if (p.type == NONCE) nrNew = p.body;
            else if (p.type == KE && childRekeyDh != null && p.body.length == 4 + childRekeyDh.keBytes()
                    && u16(p.body, 0) == childRekeyDh.id) {
                ke = Arrays.copyOfRange(p.body, 4, p.body.length);
            }
        }
        if (sa == null || nrNew == null || (childPfs && ke == null) || sa.spi.length != 4
                || !sa.suite.supported(false) || (sa.dh > 0 && (!childPfs || sa.dh != childRekeyDh.id))) {
            error = "bad CREATE_CHILD_SA reply (Child rekey)";
            return null;
        }
        byte[] g = childPfs ? childRekeyDh.shared(ke) : new byte[0];
        if (g == null) {
            error = "bad DH in Child rekey reply";
            return null;
        }
        childRekeySpiR = sa.spi;
        childRekeyNr = nrNew;
        Crypto.Suite c = sa.suite;
        int e = c.encKeyLen(), i = c.integKeyLen();
        // RFC 7296 §2.17: с PFS KEYMAT = prf+(SK_d, g^ir_new | Ni | Nr)
        byte[] k = suite.prfPlus(skD, cat(g, childRekeyNi, childRekeyNr), 2 * (e + i));
        Crypto.Box out = new Crypto.Box(c,
                Arrays.copyOfRange(k, 0, e),
                Arrays.copyOfRange(k, e, e + i));
        Crypto.Box in = new Crypto.Box(c,
                Arrays.copyOfRange(k, e + i, 2 * e + i),
                Arrays.copyOfRange(k, 2 * e + i, 2 * (e + i)));
        long newSpiOut = u32(childRekeySpiR, 0);
        long newSpiIn = u32(childRekeySpiI, 0);
        childRekeyId = -1;
        return new Esp(c, out, in, newSpiOut, newSpiIn);
    }

    /**
     * Сброс незавершённого rekey: таймаут ответа или проигранная коллизия.
     */
    void abortChildRekey() {
        childRekeyId = -1;
        childRekeySpiI = childRekeySpiR = childRekeyNi = childRekeyNr = childRekeyKe = null;
        childRekeyDh = null;
    }

    boolean ikeRekeyPending() {
        return ikeRekeyId >= 0;
    }

    int ikeRekeyId() {
        return ikeRekeyId;
    }

    /**
     * Payload'ы CREATE_CHILD_SA для IKE SA rekey (RFC 7296 §2.8): SA, Ni, KEi.
     * DH обязателен (PFS). TSi/TSR в этом обмене отсутствуют.
     * null — rekey уже идёт.
     */
    List<Pl> rekeyIkePl() {
        if (ikeRekeyId >= 0) return null;
        ikeRekeyId = nextId();
        ikeRekeySpiI = Crypto.random(8);
        ikeRekeyNi = Crypto.random(32);
        ikeRekeyX = Crypto.dhPrivate();
        ikeRekeyKe = Crypto.dhPublic(ikeRekeyX);
        byte[] ke = new byte[4 + Crypto.DH_BYTES];
        put16(ke, 0, Crypto.DH_GROUP);
        System.arraycopy(ikeRekeyKe, 0, ke, 4, ikeRekeyKe.length);
        List<Pl> l = new ArrayList<>();
        l.add(new Pl(SA, saIkeRekey(ikeRekeySpiI)));
        l.add(new Pl(NONCE, ikeRekeyNi));
        l.add(new Pl(KE, ke));
        return l;
    }

    /**
     * Разбор ответа на IKE SA rekey. Возвращает IkeRekeyMaterial, ничего не устанавливает.
     * null — ошибка (текст в error).
     */
    IkeRekeyMaterial onRekeyIkeResponse(Rx r) {
        byte[] nrNew = null, ke = null;
        Chosen sa = null;
        for (Pl p : r.pl) {
            if (p.type == NOTIFY && p.body.length >= 4 && u16(p.body, 2) < 16384) {
                error = notifyText(u16(p.body, 2));
                return null;
            }
            if (p.type == SA) sa = parseSa(p.body, 1);
            else if (p.type == NONCE) nrNew = p.body;
            else if (p.type == KE && p.body.length == 4 + Crypto.DH_BYTES) {
                ke = Arrays.copyOfRange(p.body, 4, p.body.length);
            }
        }
        if (sa == null || nrNew == null || ke == null || sa.spi.length != 8
                || !sa.suite.supported(true) || sa.dh != Crypto.DH_GROUP) {
            error = "bad CREATE_CHILD_SA reply (IKE rekey)";
            return null;
        }
        byte[] g = Crypto.dhShared(ikeRekeyX, ke);
        if (g == null) {
            error = "bad DH in IKE rekey reply";
            return null;
        }
        ikeRekeySpiR = sa.spi;
        ikeRekeyNr = nrNew;
        // RFC 7296 §2.18: SKEYSEED = prf(SK_d(old), g^ir_new | Ni | Nr) — PRF старого SA,
        // keymat = prf+(SKEYSEED, Ni | Nr | SPIi | SPIr) — PRF нового SA.
        Crypto.Suite c = sa.suite;
        byte[] seed = suite.prf(skD, cat(g, ikeRekeyNi, ikeRekeyNr));
        int pr = c.prfLen(), il = c.integKeyLen(), el = c.encKeyLen();
        byte[] k = c.prfPlus(seed, cat(ikeRekeyNi, ikeRekeyNr, ikeRekeySpiI, ikeRekeySpiR),
                3 * pr + 2 * il + 2 * el);
        int o = 0;
        IkeRekeyMaterial m = new IkeRekeyMaterial();
        m.newSpiI = ikeRekeySpiI;
        m.newSpiR = ikeRekeySpiR;
        m.newSuite = c;
        m.newSkD = Arrays.copyOfRange(k, o, o += pr);
        byte[] ai = Arrays.copyOfRange(k, o, o += il);
        byte[] ar = Arrays.copyOfRange(k, o, o += il);
        byte[] ei = Arrays.copyOfRange(k, o, o += el);
        byte[] er = Arrays.copyOfRange(k, o, o += el);
        m.newSkPi = Arrays.copyOfRange(k, o, o += pr);
        m.newSkPr = Arrays.copyOfRange(k, o, o + pr);
        m.newBoxOut = new Crypto.Box(c, ei, ai);
        m.newBoxIn = new Crypto.Box(c, er, ar);
        ikeRekeyId = -1;
        return m;
    }

    /**
     * Сброс незавершённого IKE rekey: таймаут ответа или проигранная коллизия.
     */
    void abortIkeRekey() {
        ikeRekeyId = -1;
        ikeRekeySpiI = ikeRekeySpiR = ikeRekeyNi = ikeRekeyNr = ikeRekeyKe = null;
        ikeRekeyX = null;
    }

    /**
     * Тип входящего CREATE_CHILD_SA: 1 = IKE SA rekey (SA proto=1, 8-байтный SPI), 3 = ESP, 0 = непонятно.
     */
    int peekRekeyProto(Rx r) {
        for (Pl p : r.pl) {
            if (p.type == SA) return p.body.length >= 6 ? (p.body[5] & 0xff) : 0;
        }
        return 0;
    }

    /**
     * Обработка входящего CREATE_CHILD_SA (IKE SA rekey) от сервера — мы респондер.
     * Разбирает SA/Ni/KEi (SA proto=1, SPI 8 байт), генерит Nr/KEr/SPIr, считает новые ключи.
     * Возвращает payload'ы ответа и материал; установка ключей — installRekeyedKeys() у вызывающего.
     */
    IkeRekeyResp onIncomingIkeRekey(Rx r) {
        byte[] ni = null, ke = null;
        Chosen sa = null;
        for (Pl p : r.pl) {
            if (p.type == SA) sa = parseSa(p.body, 1);
            else if (p.type == NONCE) ni = p.body;
            else if (p.type == KE && p.body.length == 4 + Crypto.DH_BYTES) {
                ke = Arrays.copyOfRange(p.body, 4, p.body.length);
            }
        }
        if (sa == null || ni == null || ke == null || sa.spi.length != 8
                || !sa.suite.supported(true) || sa.dh != Crypto.DH_GROUP) {
            error = "bad incoming CREATE_CHILD_SA (IKE rekey)";
            return null;
        }
        BigInteger x = Crypto.dhPrivate();
        byte[] kePub = Crypto.dhPublic(x);
        byte[] g = Crypto.dhShared(x, ke);
        if (g == null) {
            error = "bad DH in incoming IKE rekey";
            return null;
        }
        byte[] newSpiR = Crypto.random(8);          // наш SPI (мы респондер)
        byte[] nr = Crypto.random(32);
        List<Pl> pls = new ArrayList<>();
        pls.add(new Pl(SA, saIkeChosen(newSpiR, sa)));
        pls.add(new Pl(NONCE, nr));
        byte[] keB = new byte[4 + Crypto.DH_BYTES];
        put16(keB, 0, Crypto.DH_GROUP);
        System.arraycopy(kePub, 0, keB, 4, kePub.length);
        pls.add(new Pl(KE, keB));
        // RFC 7296 §2.18: SKEYSEED = prf(SK_d(old), g^ir' | Ni | Nr), keymat = prf+(SKEYSEED, Ni|Nr|SPIi|SPIr).
        // Мы респондер: SPIi = SPI сервера, SPIr = наш новый. Box'ы — респондерские (er/ar — out, ei/ai — in).
        Crypto.Suite c = sa.suite;
        byte[] seed = suite.prf(skD, cat(g, ni, nr));
        int pr = c.prfLen(), il = c.integKeyLen(), el = c.encKeyLen();
        byte[] k = c.prfPlus(seed, cat(ni, nr, sa.spi, newSpiR), 3 * pr + 2 * il + 2 * el);
        int o = 0;
        IkeRekeyMaterial m = new IkeRekeyMaterial();
        m.newSpiI = sa.spi;
        m.newSpiR = newSpiR;
        m.newSuite = c;
        m.newSkD = Arrays.copyOfRange(k, o, o += pr);
        byte[] ai = Arrays.copyOfRange(k, o, o += il);
        byte[] ar = Arrays.copyOfRange(k, o, o += il);
        byte[] ei = Arrays.copyOfRange(k, o, o += el);
        byte[] er = Arrays.copyOfRange(k, o, o += el);
        m.newSkPi = Arrays.copyOfRange(k, o, o += pr);
        m.newSkPr = Arrays.copyOfRange(k, o, o + pr);
        m.newBoxOut = new Crypto.Box(c, er, ar);
        m.newBoxIn = new Crypto.Box(c, ei, ai);
        IkeRekeyResp resp = new IkeRekeyResp();
        resp.pls = pls;
        resp.material = m;
        return resp;
    }

    /**
     * Устанавливает новые ключи после успешного IKE SA rekey. Атомарно: SPI, suite, boxOut/In,
     * skD/skPi/skPr. Сбрасывает nextId=0 и peerNext=0: у нового SA своё пространство номеров
     * (RFC 7296 §2.8). Старые SPI сохраняются в oldSpiI/oldSpiR.
     */
    void installRekeyedKeys(IkeRekeyMaterial m, boolean weInitiated) {
        origInit = weInitiated;
        oldSpiI = spiI;
        oldSpiR = spiR;
        spiI = m.newSpiI;
        spiR = m.newSpiR;
        suite = m.newSuite;
        boxOut = m.newBoxOut;
        boxIn = m.newBoxIn;
        skD = m.newSkD;
        skPi = m.newSkPi;
        skPr = m.newSkPr;
        nextId = 0;
        peerNext = 0;
        ackFloor = 0;
    }

    /**
     * Обработка входящего CREATE_CHILD_SA (Child rekey) от сервера — мы респондер.
     * Разбирает SA/Ni/KEi, генерит Nr/KEi/SPI, считает KEYMAT, возвращает payload'ы ответа + Esp.
     * null — ошибка (текст в error).
     */
    ChildRekeyResp onIncomingChildRekey(Rx r) {
        byte[] ni = null, ke = null;
        Chosen sa = null;
        for (Pl p : r.pl) {
            if (p.type == SA) sa = parseSa(p.body, 3);
            else if (p.type == NONCE) ni = p.body;
            else if (p.type == KE && p.body.length == 4 + Crypto.DH_BYTES) {
                ke = Arrays.copyOfRange(p.body, 4, p.body.length);
            }
        }
        if (sa == null || ni == null || sa.spi.length != 4
                || !sa.suite.supported(false) || sa.esn > 0) {
            error = "bad incoming CREATE_CHILD_SA";
            return null;
        }
        // PFS (RFC 7296 §2.8.2): KE обязателен, мы всегда предлагаем DH group 14.
        if (ke == null || sa.dh != Crypto.DH_GROUP) {
            error = "incoming rekey without DH group 14 (PFS required)";
            return null;
        }
        BigInteger x = Crypto.dhPrivate();
        byte[] kePub = Crypto.dhPublic(x);
        byte[] g = Crypto.dhShared(x, ke);
        if (g == null) {
            error = "bad DH in incoming rekey";
            return null;
        }
        byte[] spiNew = new byte[4];
        do {
            Crypto.RND.nextBytes(spiNew);
        } while (u32(spiNew, 0) == 0);
        byte[] nr = Crypto.random(32);
        List<Pl> pls = new ArrayList<>();
        pls.add(new Pl(SA, saEspChosen(spiNew, sa)));
        pls.add(new Pl(NONCE, nr));
        byte[] keB = new byte[4 + Crypto.DH_BYTES];
        put16(keB, 0, Crypto.DH_GROUP);
        System.arraycopy(kePub, 0, keB, 4, kePub.length);
        pls.add(new Pl(KE, keB));
        pls.add(new Pl(TSI, ts()));
        pls.add(new Pl(TSR, ts()));
        // RFC 7296 §2.17: с PFS KEYMAT = prf+(SK_d, g^ir_new | Ni | Nr).
        // Мы респондер: out = вторая половина, in = первая (симметрично complete()).
        Crypto.Suite c = sa.suite;
        int e = c.encKeyLen(), i = c.integKeyLen();
        byte[] k = suite.prfPlus(skD, cat(g, ni, nr), 2 * (e + i));
        Crypto.Box in = new Crypto.Box(c,
                Arrays.copyOfRange(k, 0, e),
                Arrays.copyOfRange(k, e, e + i));
        Crypto.Box out = new Crypto.Box(c,
                Arrays.copyOfRange(k, e + i, 2 * e + i),
                Arrays.copyOfRange(k, 2 * e + i, 2 * (e + i)));
        ChildRekeyResp resp = new ChildRekeyResp();
        resp.pls = pls;
        resp.esp = new Esp(c, out, in, u32(sa.spi, 0), u32(spiNew, 0));
        return resp;
    }

    /**
     * Один payload: тип и тело без общего заголовка.
     */
    static final class Pl {
        final int type;
        final byte[] body;

        Pl(int type, byte[] body) {
            this.type = type;
            this.body = body;
        }
    }

    /**
     * Разобранное защищённое сообщение.
     */
    static final class Rx {
        int exch, id;
        boolean response;
        List<Pl> pl;
    }

    private static final class Chosen {
        Crypto.Suite suite;
        byte[] spi;
        int dh = -1, esn = -1;
        int num, encr, bits, integ, prf;
    }

    /**
     * Результат разбора ответа на IKE SA rekey: новые SPI и ключи, но БЕЗ установки.
     * Установка (замена spiI/spiR/boxOut/boxIn/skD/skPi/skPr) — задача Tunnel, шаг 4b.
     */
    static final class IkeRekeyMaterial {
        byte[] newSpiI, newSpiR;
        Crypto.Suite newSuite;
        Crypto.Box newBoxOut, newBoxIn;
        byte[] newSkD, newSkPi, newSkPr;
    }

    // =====================================================================
    // Child SA rekey: мы респондер (сервер инициировал).
    // =====================================================================

    /**
     * Ответ респондера на входящий IKE SA rekey: payload'ы ответа + новый материал ключей.
     */
    static final class IkeRekeyResp {
        List<Pl> pls;
        IkeRekeyMaterial material;
    }

    /**
     * Результат разбора входящего CREATE_CHILD_SA: payload'ы ответа и новый Esp.
     */
    static final class ChildRekeyResp {
        List<Pl> pls;
        Esp esp;
    }
}
