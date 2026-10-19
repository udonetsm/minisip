package ru.minisip.media;

import android.content.Context;

import ru.minisip.net.Udp;

/**
 * Media engine combining RTP streaming, G.711 codec, and Audio path.
 */
public interface Media {

    static Media create(Udp rtpSocket, Context ctx) {
        return new MediaImpl(rtpSocket, new AndroidAudio(ctx));
    }

    /**
     * Allocates local RTP UDP port. Returns port number or -1 on failure.
     */
    int open();

    /**
     * Starts microphone, speaker, and RTP processing for specified payload type.
     */
    boolean start(String host, int port, int payload);

    /**
     * Stops RTP processing and audio hardware, releasing allocated socket.
     */
    void stop();

    /**
     * Configures audio output route: true for speakerphone, false for earpiece.
     */
    default void setSpeaker(boolean on) {
    }

    /**
     * Sets microphone mute state: true to mute, false to unmute.
     */
    default void setMute(boolean mute) {
    }

    default boolean isMuted() {
        return false;
    }
}
