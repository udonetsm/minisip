package ru.minisip.media;

/**
 * Audio PCM source and sink (8 kHz, 16-bit mono). Implemented by AndroidAudio.
 */
interface Audio {
    boolean start();

    /**
     * Blocking read for PCM samples. Returns sample count or -1 on error.
     */
    int read(short[] buf);

    void write(short[] buf, int n);

    void stop();

    /**
     * Audio route selection: true for speakerphone, false for earpiece.
     */
    default void setSpeaker(boolean on) {
    }

    /**
     * Microphone mute selection: true to mute, false to unmute.
     */
    default void setMute(boolean mute) {
    }

    default boolean isMuted() {
        return false;
    }
}
