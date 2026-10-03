package ru.minisip.net;

/**
 * UDP-сокет. Наружу — только встроенные типы и вложенный интерфейс-колбэк.
 * Ни от кого не зависит.
 */
public interface Udp {

    interface Listener {
        void onPacket(byte[] data, String host, int port);
    }

    /** Открывает сокет (0 = любой порт) и запускает приём. Возвращает порт или -1. */
    int open(int port, Listener l);

    /** Отправка датаграммы. false — не удалось (нет сети, не резолвится имя). */
    boolean send(byte[] data, String host, int port);

    /** Локальный IP, с которого мы достучимся до host:port. null — нет маршрута. */
    String localIp(String host, int port);

    void close();

    static Udp create() {
        return new UdpImpl();
    }
}
