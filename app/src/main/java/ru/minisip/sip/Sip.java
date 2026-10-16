package ru.minisip.sip;

import ru.minisip.log.LogSink;
import ru.minisip.media.Media;
import ru.minisip.net.Udp;

/**
 * SIP-клиент поверх UDP: регистрация, исходящие и входящие вызовы.
 * Зависит только от интерфейсов net.Udp и media.Media. Наружу — встроенные типы.
 * Колбэки вызываются из внутреннего потока "sip".
 */
public interface Sip {

    static Sip create(Udp signalling, Media media) {
        return new SipImpl(signalling, media);
    }

    void setListener(Listener l);

    /**
     * Куда писать отладочный лог; по умолчанию — никуда.
     */
    default void setLogSink(LogSink sink) {
    }

    /**
     * Открывает сокет и регистрируется на сервере; обновляет регистрацию сама.
     */
    void register(String host, int port, String user, String password);

    /**
     * Отменяет регистрацию и закрывает сокет.
     */
    void unregister();

    void call(String number);

    /**
     * Blind transfer: REFER на target в рамках активного диалога.
     * target — логин (тогда domain берётся из текущей регистрации) или полный sip-URI.
     * Результат перевода приходит через {@link Listener#onEnded(String)}.
     */
    void transfer(String target);

    /**
     * Отмена / отбой — по состоянию вызова.
     */
    void hangup();

    interface Listener {
        default void onRegistered(boolean ok, String info) {
        }

        default void onRinging() {
        }

        default void onConnected() {
        }

        /**
         * Вызов закончился по любой причине (в том числе неудачный дозвон).
         */
        default void onEnded(String reason) {
        }
    }
}
