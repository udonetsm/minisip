package ru.minisip.sip;

import ru.minisip.log.LogSink;
import ru.minisip.media.Media;
import ru.minisip.net.Udp;

/**
 * SIP client interface over UDP supporting registration, outgoing, and incoming calls.
 */
public interface Sip {

    static Sip create(Udp signalling, Media media) {
        return new SipImpl(signalling, media);
    }

    void setListener(Listener l);

    /**
     * Sets debug log sink interface. Default is no-op.
     */
    default void setLogSink(LogSink sink) {
    }

    /**
     * Opens UDP socket and registers with SIP server, scheduling auto-renewal.
     */
    void register(String host, int port, String user, String password);

    /**
     * Unregisters from server and closes socket.
     */
    void unregister();

    void call(String number);

    /**
     * Initiates blind call transfer (REFER) to specified target.
     */
    void transfer(String target);

    /**
     * Cancels or terminates call according to active state.
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
         * Invoked when call terminates for any reason.
         */
        default void onEnded(String reason) {
        }
    }
}
