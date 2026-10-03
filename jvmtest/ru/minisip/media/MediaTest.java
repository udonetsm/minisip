package ru.minisip.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.minisip.net.Udp;

/** G.711 и RTP-петля: A передаёт синус в B через настоящие UDP-сокеты. */
public final class MediaTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static final class FakeAudio implements Audio {
        final boolean tone;
        final List<short[]> played = Collections.synchronizedList(new ArrayList<>());
        long n;

        FakeAudio(boolean tone) {
            this.tone = tone;
        }

        public boolean start() { return true; }

        public int read(short[] buf) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return -1;
            }
            for (int i = 0; i < buf.length; i++, n++) {
                buf[i] = tone ? (short) (8000 * Math.sin(2 * Math.PI * 440 * n / 8000.0)) : 0;
            }
            return buf.length;
        }

        public void write(short[] buf, int n) { played.add(buf.clone()); }

        public void stop() {}
    }

    public static void main(String[] a) throws Exception {
        // известные значения тишины
        check(G711.encode(8, (short) 0) == (byte) 0xD5, "PCMA(0) = 0xD5");
        check(G711.encode(0, (short) 0) == (byte) 0xFF, "PCMU(0) = 0xFF");
        // круговой обход: ошибка квантования в пределах нормы
        for (int pt : new int[]{8, 0}) {
            int worst = 0;
            for (int s = -32768; s <= 32767; s += 7) {
                int back = G711.decode(pt, G711.encode(pt, (short) s));
                int err = Math.abs(back - s);
                // шаг квантования растёт с амплитудой: допускаем ~3.5% + 64
                int lim = (int) (Math.abs(s) * 0.035) + 64;
                if (err > lim) worst = Math.max(worst, err - lim);
            }
            check(worst == 0, "G.711 pt=" + pt + " roundtrip в допуске");
        }
        // знак сохраняется
        check(G711.decode(8, G711.encode(8, (short) 1000)) > 0
                && G711.decode(8, G711.encode(8, (short) -1000)) < 0, "знак сохраняется");

        // RTP A -> B
        FakeAudio aa = new FakeAudio(true), ba = new FakeAudio(false);
        MediaImpl A = new MediaImpl(Udp.create(), aa), B = new MediaImpl(Udp.create(), ba);
        int pa = A.open(), pb = B.open();
        check(pa > 0 && pa % 2 == 0 && pb > 0, "open() даёт чётные порты " + pa + "/" + pb);
        check(!A.start("127.0.0.1", pb, 5), "неизвестный payload отклонён");
        check(A.start("127.0.0.1", pb, 8) && B.start("127.0.0.1", pa, 8), "start()");
        Thread.sleep(1000);
        A.stop();
        B.stop();
        int frames = ba.played.size();
        check(frames >= 35 && frames <= 55, "B принял ~50 кадров за 1 с: " + frames);
        // частота принятого сигнала: считаем переходы через ноль
        int zc = 0;
        short prev = 0;
        long total = 0;
        synchronized (ba.played) {
            for (short[] f : ba.played) {
                for (short s : f) {
                    if ((prev < 0) != (s < 0) && prev != 0) zc++;
                    prev = s;
                    total++;
                }
            }
        }
        double hz = zc / 2.0 / (total / 8000.0);
        check(Math.abs(hz - 440) < 25, "частота на приёме ≈ 440 Гц: " + Math.round(hz));
        // стоп освобождает сокет, повторный open/stop безопасен
        A.stop();
        check(A.open() > 0, "повторный open() после stop()");
        A.stop();

        System.out.println(fails == 0 ? "MEDIA OK" : "MEDIA FAILED " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
