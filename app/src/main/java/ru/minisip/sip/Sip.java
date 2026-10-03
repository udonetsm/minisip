package ru.minisip.sip;

import ru.minisip.media.Media;
import ru.minisip.net.Udp;

/**
 * SIP-клиент поверх UDP: регистрация, исходящие и входящие вызовы.
 * Зависит только от интерфейсов net.Udp и media.Media. Наружу — встроенные типы.
 * Колбэки вызываются из внутреннего потока "sip".
 */
public interface Sip {

    interface Listener {
        default void onRegistered(boolean ok, String info) {}

        default void onIncoming(String from) {}

        default void onRinging() {}

        default void onConnected() {}

        /** Вызов закончился по любой причине (в том числе неудачный дозвон). */
        default void onEnded(String reason) {}
    }

    void setListener(Listener l);

    /** Открывает сокет и регистрируется на сервере; обновляет регистрацию сама. */
    void register(String host, int port, String user, String password);

    void call(String number);

    void answer();

    /** Отмена / отбой / отклонение — по состоянию вызова. */
    void hangup();

    static Sip create(Udp signalling, Media media) {
        return new SipImpl(signalling, media);
    }
}
