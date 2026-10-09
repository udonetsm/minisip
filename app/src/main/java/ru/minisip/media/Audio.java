package ru.minisip.media;

/** Источник и приёмник PCM 8 кГц, 16 бит, моно. Реализация — AndroidAudio (в тестах подменяется). */
interface Audio {
    boolean start();

    /** Блокирующее чтение ровно buf.length сэмплов. -1 — ошибка/остановка. */
    int read(short[] buf);

    void write(short[] buf, int n);

    void stop();

    /** Вывод звука: true — громкая связь, false — разговорный динамик. Допустимо и во время разговора. */
    default void setSpeaker(boolean on) {}

    /** Отключение микрофона: true — звук заглушен (тишина), false — микрофон включен. */
    default void setMute(boolean mute) {}

    default boolean isMuted() { return false; }
}
