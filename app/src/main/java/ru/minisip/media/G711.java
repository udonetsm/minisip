package ru.minisip.media;

/** G.711 A-law (PT 8) и µ-law (PT 0). Классические алгоритмы, без таблиц на 64 КБ. */
final class G711 {

    private static final short[] SEG_A = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};
    private static final short[] SEG_U = {0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF};
    private static final short[] A2L = new short[256];
    private static final short[] U2L = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            A2L[i] = (short) alawToLin(i);
            U2L[i] = (short) ulawToLin(i);
        }
    }

    private G711() {}

    static byte encode(int pt, short pcm) {
        return pt == 8 ? linToAlaw(pcm) : linToUlaw(pcm);
    }

    static short decode(int pt, byte b) {
        return pt == 8 ? A2L[b & 0xFF] : U2L[b & 0xFF];
    }

    private static int search(int v, short[] table) {
        for (int i = 0; i < 8; i++) {
            if (v <= table[i]) return i;
        }
        return 8;
    }

    private static byte linToAlaw(int pcm) {
        int p = pcm >> 3;
        int mask;
        if (p >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            p = -p - 1;
        }
        int seg = search(p, SEG_A);
        if (seg >= 8) return (byte) (0x7F ^ mask);
        int a = seg << 4;
        a |= (seg < 2) ? (p >> 1) & 0xF : (p >> seg) & 0xF;
        return (byte) (a ^ mask);
    }

    private static int alawToLin(int a) {
        a ^= 0x55;
        int t = (a & 0xF) << 4;
        int seg = (a & 0x70) >> 4;
        switch (seg) {
            case 0:
                t += 8;
                break;
            case 1:
                t += 0x108;
                break;
            default:
                t += 0x108;
                t <<= seg - 1;
        }
        return (a & 0x80) != 0 ? t : -t;
    }

    private static byte linToUlaw(int pcm) {
        int p = pcm >> 2;
        int mask;
        if (p < 0) {
            p = -p;
            mask = 0x7F;
        } else {
            mask = 0xFF;
        }
        if (p > 8159) p = 8159;
        p += 0x84 >> 2;
        int seg = search(p, SEG_U);
        if (seg >= 8) return (byte) (0x7F ^ mask);
        int u = (seg << 4) | ((p >> (seg + 1)) & 0xF);
        return (byte) (u ^ mask);
    }

    private static int ulawToLin(int u) {
        u = ~u & 0xFF;
        int t = ((u & 0xF) << 3) + 0x84;
        t <<= (u & 0x70) >> 4;
        return (u & 0x80) != 0 ? 0x84 - t : t - 0x84;
    }
}
