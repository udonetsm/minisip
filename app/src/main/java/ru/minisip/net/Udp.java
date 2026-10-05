package ru.minisip.net;

import android.content.Context;

/**
 * UDP-сокет. Наружу — только встроенные типы и вложенный интерфейс-колбэк.
 * Ни от кого из наших пакетов не зависит.
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

    /**
     * Привязывает сокеты к сети сетевого интерфейса iface (например "tun0"): весь их трафик
     * идёт только через него. null — вернуться к основному стеку системы без вмешательства.
     * Действует на сокеты, открытые после вызова (в том числе внутри localIp). Если интерфейс
     * не найден, open() вернёт -1, а localIp() — null: мимо туннеля мы не отправляем.
     */
    void bind(String iface);

    void close();

    default String error() { return ""; }

    /** Без Android: привязка к интерфейсу недоступна (bind(x != null) делает open() неудачным). */
    static Udp create() {
        return new UdpImpl((s, iface) -> false);
    }

    /** С привязкой к сети через ConnectivityManager. */
    static Udp create(Context ctx) {
        return new UdpImpl(new AndroidBinder(ctx));
    }
}
