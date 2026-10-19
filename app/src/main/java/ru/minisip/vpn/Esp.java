package ru.minisip.vpn;

import java.util.Arrays;

/**
 * ESP in tunnel mode for IPv4 (RFC 4303): wraps and unwraps IP packets.
 */
final class Esp {

    private static final int HEAD = 8;        // SPI + sequence number
    private static final int IPV4 = 4;        // Next Header for IPv4-in-ESP

    private final Crypto.Box out, in;
    private final long spiOut, spiIn;
    private final int block;
    // inbound packet drop diagnostics
    volatile int dropSpi, dropReplay, dropIcv, dropFmt;
    private long seq;                         // last transmitted sequence number
    private long top;                         // highest received sequence number
    private long window;                      // bit i = received packet (top - i)

    Esp(Crypto.Suite s, Crypto.Box out, Crypto.Box in, long spiOut, long spiIn) {
        this.out = out;
        this.in = in;
        this.spiOut = spiOut;
        this.spiIn = spiIn;
        this.block = s.block();
    }

    String drops() {
        return "dropSpi=" + dropSpi + " dropReplay=" + dropReplay + " dropIcv=" + dropIcv + " dropFmt=" + dropFmt;
    }

    /**
     * Our outbound SPI (same as SPI_R of old SA, needed for INFORMATIONAL DELETE).
     */
    long spiOut() {
        return spiOut;
    }

    /**
     * Our inbound SPI (for logs and DELETE).
     */
    long spiIn() {
        return spiIn;
    }

    /**
     * Wraps IP packet b[0..n) into ESP packet.
     */
    synchronized byte[] wrap(byte[] b, int n) {
        int pad = (block - (n + 2) % block) % block;
        byte[] plain = new byte[n + pad + 2];
        System.arraycopy(b, 0, plain, 0, n);
        for (int i = 0; i < pad; i++) plain[n + i] = (byte) (i + 1);   // 1, 2, 3... per RFC 4303
        plain[n + pad] = (byte) pad;
        plain[n + pad + 1] = IPV4;
        byte[] head = new byte[HEAD];
        Crypto.put32(head, 0, spiOut);
        Crypto.put32(head, 4, ++seq);
        return out.seal(head, plain);
    }

    /**
     * Unwraps ESP packet into IP packet. Returns null if invalid SPI, replay, or authentication error.
     */
    byte[] unwrap(byte[] p, int n) {
        if (n < HEAD + out.overhead()) {
            dropFmt++;
            return null;
        }
        if (Crypto.u32(p, 0) != spiIn) {
            dropSpi++;
            return null;
        }
        long s = Crypto.u32(p, 4);
        if (replayed(s)) {
            dropReplay++;
            return null;
        }   // drop replayed packet before decryption
        byte[] plain = in.open(Arrays.copyOf(p, n), HEAD);
        if (plain == null || plain.length < 2) {
            dropIcv++;
            return null;
        }
        accept(s);                                    // accept sequence number only after ICV verification
        int pad = plain[plain.length - 2] & 0xff;
        if (plain[plain.length - 1] != IPV4 || pad + 2 > plain.length) {
            dropFmt++;
            return null;
        }
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
