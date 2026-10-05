package ru.minisip.vpn;

import java.io.IOException;
import java.net.DatagramSocket;

/**
 * Всё, что туннелю нужно от операционной системы. Внутри пакета: на Android это AndroidTun,
 * в проверках на JVM — подделка. Другие пакеты этого типа не видят.
 */
interface Tun {

    /** Выводит сокет из-под VPN, чтобы IKE/ESP не попадали сами в себя. */
    boolean protect(DatagramSocket s);

    /**
     * Создаёт интерфейс с адресом ip/32, DNS (через пробел), маршрутами «a.b.c.d/n» (через пробел).
     * В туннель идёт трафик только нашего приложения и пакетов из apps (через пробел или перевод строки);
     * остальная система не затронута. Возвращает имя интерфейса или null.
     */
    String open(String ip, String dns, String routes, int mtu, String apps);

    /** Читает один IP-пакет (блокируется). -1 — интерфейс закрыт. */
    int read(byte[] b) throws IOException;

    void write(byte[] b, int off, int len) throws IOException;

    /**
     * Есть ли в системе действующий VPN, который не наш. Наш узнаём по выданному нам адресу ip
     * (имя интерфейса не годится: tun0 переиспользуется). В проверках на JVM по умолчанию «нет».
     */
    default boolean otherVpn(String ip) {
        return false;
    }

    /** Закрывает интерфейс; блокированный read должен вернуться. */
    void close();
}
