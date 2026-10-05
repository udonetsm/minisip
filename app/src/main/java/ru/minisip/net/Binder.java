package ru.minisip.net;

import java.io.IOException;
import java.net.DatagramSocket;

/** Привязка сокета к сети по имени интерфейса. Внутри пакета: Android или подделка в тестах. */
interface Binder {

    /** true — сокет привязан; false — сети с таким интерфейсом нет. Сокет ещё не подключён. */
    boolean bind(DatagramSocket s, String iface) throws IOException;
}
