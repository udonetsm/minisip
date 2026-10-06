package ru.minisip.media;

import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import ru.minisip.net.Udp;

/** RTP-поток 20 мс на пакет: 160 сэмплов 8 кГц = 160 байт G.711 + 12 байт заголовка. */
final class MediaImpl implements Media, Udp.Listener {

    private static final int FRAME = 160;
    private static final SecureRandom RND = new SecureRandom();

    private final Udp udp;
    private final Audio audio;
    private final BlockingQueue<short[]> playQ = new ArrayBlockingQueue<>(16);

    private volatile boolean run;
    private volatile String rhost;
    private volatile int rport;
    private volatile int pt;
    private Thread capture;
    private Thread playback;

    MediaImpl(Udp udp, Audio audio) {
        this.udp = udp;
        this.audio = audio;
    }

    @Override
    public int open() {
        for (int i = 0; i < 20; i++) {
            int p = 16384 + 2 * RND.nextInt(8000);
            if (udp.open(p, this) == p) return p;
        }
        return -1;
    }

    @Override
    public synchronized boolean start(String host, int port, int payload) {
        if (payload != 0 && payload != 8) return false;
        if (run) {
            if (host != null && port > 0) {
                rhost = host;
                rport = port;
            }
            pt = payload;
            return true;
        }
        if (!audio.start()) return false;
        rhost = host;
        rport = port;
        pt = payload;
        playQ.clear();
        run = true;
        capture = new Thread(this::captureLoop, "rtp-capture");
        playback = new Thread(this::playbackLoop, "rtp-playback");
        capture.start();
        playback.start();
        return true;
    }

    @Override
    public void setSpeaker(boolean on) {
        audio.setSpeaker(on);
    }

    @Override
    public synchronized void stop() {
        boolean wasRunning = run;
        run = false;
        join(capture);
        join(playback);
        capture = null;
        playback = null;
        if (wasRunning) audio.stop();
        udp.close();
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- микрофон -> сеть ----
    private void captureLoop() {
        short[] pcm = new short[FRAME];
        byte[] pkt = new byte[12 + FRAME];
        int seq = RND.nextInt(65536);
        int ts = RND.nextInt();
        int ssrc = RND.nextInt();
        pkt[0] = (byte) 0x80;
        pkt[1] = (byte) pt;
        put32(pkt, 8, ssrc);
        while (run) {
            int n = audio.read(pcm);
            if (n < 0) break;
            if (n != FRAME) continue;
            for (int i = 0; i < FRAME; i++) pkt[12 + i] = G711.encode(pt, pcm[i]);
            pkt[2] = (byte) (seq >> 8);
            pkt[3] = (byte) seq;
            put32(pkt, 4, ts);
            udp.send(pkt, rhost, rport);
            seq = (seq + 1) & 0xFFFF;
            ts += FRAME;
        }
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }

    // ---- сеть -> очередь ----
    @Override
    public void onPacket(byte[] d, String host, int port) {
        if (!run || d.length < 13) return;
        if ((d[0] & 0xC0) != 0x80) return;              // версия 2
        if ((d[1] & 0x7F) != pt) return;                // чужие типы (telephone-event и т.п.)
        int off = 12 + (d[0] & 0x0F) * 4;
        if ((d[0] & 0x10) != 0) {                       // расширение
            if (d.length < off + 4) return;
            off += 4 + (((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF)) * 4;
        }
        int end = d.length;
        if ((d[0] & 0x20) != 0) end -= d[d.length - 1] & 0xFF;   // padding
        int n = end - off;
        if (n <= 0) return;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) pcm[i] = G711.decode(pt, d[off + i]);
        while (!playQ.offer(pcm)) playQ.poll();
        rhost = host;                                   // symmetric RTP: отвечаем туда, откуда пришло
        rport = port;
    }

    // ---- очередь -> динамик ----
    private void playbackLoop() {
        while (run) {
            try {
                short[] f = playQ.poll(100, TimeUnit.MILLISECONDS);
                if (f != null) audio.write(f, f.length);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
