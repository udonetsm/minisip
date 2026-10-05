package ru.minisip.vpn;

import java.util.Arrays;

/**
 * ESP в туннельном режиме для IPv4 (RFC 4303): оборачивает и разворачивает IP-пакеты.
 * Номера пакетов 32-битные (без ESN). Исходящее зовёт один поток, входящее — другой.
 */
final class Esp {

    private static final int HEAD = 8;        // SPI + номер
    private static final int IPV4 = 4;        // Next Header для IPv4-в-ESP

    private final Crypto.Box out, in;
    private final long spiOut, spiIn;
    private final int block;

    private long seq;                         // последний отправленный номер
    private long top;                         // наибольший принятый номер
    private long window;                      // бит i = принят номер (top - i)

    Esp(Crypto.Suite s, Crypto.Box out, Crypto.Box in, long spiOut, long spiIn) {
        this.out = out;
        this.in = in;
        this.spiOut = spiOut;
        this.spiIn = spiIn;
        this.block = s.block();
    }

    /** IP-пакет b[0..n) -> готовый пакет ESP (его кладут в UDP как есть). */
    byte[] wrap(byte[] b, int n) {
        int pad = (block - (n + 2) % block) % block;
        byte[] plain = new byte[n + pad + 2];
        System.arraycopy(b, 0, plain, 0, n);
        for (int i = 0; i < pad; i++) plain[n + i] = (byte) (i + 1);   // 1, 2, 3... по RFC 4303
        plain[n + pad] = (byte) pad;
        plain[n + pad + 1] = IPV4;
        byte[] head = new byte[HEAD];
        Crypto.put32(head, 0, spiOut);
        Crypto.put32(head, 4, ++seq);
        return out.seal(head, plain);
    }

    /** Пакет ESP -> IP-пакет. null: чужой SPI, повтор, подделка, не IPv4 или мусор. */
    byte[] unwrap(byte[] p, int n) {
        if (n < HEAD + out.overhead() || Crypto.u32(p, 0) != spiIn) return null;
        long s = Crypto.u32(p, 4);
        if (replayed(s)) return null;                 // дёшево отбрасываем до расшифровки
        byte[] plain = in.open(Arrays.copyOf(p, n), HEAD);
        if (plain == null || plain.length < 2) return null;
        accept(s);                                    // номер засчитываем только после проверки ICV
        int pad = plain[plain.length - 2] & 0xff;
        if (plain[plain.length - 1] != IPV4 || pad + 2 > plain.length) return null;
        return Arrays.copyOf(plain, plain.length - 2 - pad);
    }

    private boolean replayed(long s) {
        if (s == 0) return true;
        if (s > top) return false;
        long d = top - s;
        return d >= 64 || (window >>> d & 1) != 0;
    }

    private void accept(long s) {
        if (s > top) {
            long shift = s - top;
            window = shift >= 64 ? 0 : window << shift;
            window |= 1;
            top = s;
        } else {
            window |= 1L << (top - s);
        }
    }
}
