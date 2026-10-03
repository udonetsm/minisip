package ru.minisip.media;

import android.content.Context;

import ru.minisip.net.Udp;

/**
 * Голос: RTP + G.711 + микрофон/динамик.
 * Зависит только от интерфейса net.Udp. Наружу — встроенные типы.
 */
public interface Media {

    /** Занимает локальный RTP-порт. Возвращает порт или -1. */
    int open();

    /** Запускает микрофон, динамик и обмен RTP. payload: 0 = PCMU, 8 = PCMA. */
    boolean start(String host, int port, int payload);

    /** Останавливает всё и освобождает порт. Можно звать в любом состоянии. */
    void stop();

    static Media create(Udp rtpSocket, Context ctx) {
        return new MediaImpl(rtpSocket, new AndroidAudio(ctx));
    }
}
