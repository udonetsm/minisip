package ru.minisip.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

/** Привязка сокетов к интерфейсу: есть туннель, нет туннеля, возврат к основному стеку. */
public final class NetTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        ConnectivityManager cm = new ConnectivityManager();
        cm.networks.add(new Network("wlan0"));
        Context.services = name -> cm;
        Udp u = Udp.create(new Context());

        int p = u.open(0, (d, h, port) -> {});
        check(p > 0 && Network.bound.isEmpty(), "без bind() сокет открыт и ни к какой сети не привязан");
        check(u.localIp("127.0.0.1", 9) != null, "без bind() localIp работает");

        u.bind("tun0");                                   // сети tun0 ещё нет: мимо туннеля не идём
        check(u.open(0, (d, h, port) -> {}) == -1, "bind(tun0) без сети tun0: open() = -1");
        check(u.localIp("127.0.0.1", 9) == null, "bind(tun0) без сети tun0: localIp() = null");
        check(!u.send(new byte[]{1}, "127.0.0.1", 9), "после неудачного open() send() = false");

        cm.networks.add(new Network("tun0"));
        p = u.open(0, (d, h, port) -> {});
        check(p > 0 && Network.bound.size() == 1, "bind(tun0) при живой сети: сокет привязан");
        check(u.localIp("127.0.0.1", 9) != null && Network.bound.size() == 2, "localIp тоже идёт через tun0");

        u.bind(null);                                     // VPN отключили
        int before = Network.bound.size();
        check(u.open(0, (d, h, port) -> {}) > 0 && Network.bound.size() == before, "bind(null): основной стек, привязки нет");

        // без Android-контекста привязка недоступна, но обычная работа не страдает
        Udp plain = Udp.create();
        check(plain.open(0, (d, h, port) -> {}) > 0, "create() без Context: open() работает");
        plain.bind("tun0");
        check(plain.open(0, (d, h, port) -> {}) == -1, "create() без Context: bind(tun0) -> open() = -1");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
