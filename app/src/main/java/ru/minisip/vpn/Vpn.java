package ru.minisip.vpn;

import android.content.Context;

/**
 * IKEv2-клиент (общий ключ или логин/пароль, ESP поверх UDP 4500) и интерфейс tun.
 * Ни от каких наших пакетов не зависит. Наружу — только встроенные типы.
 * Колбэки вызываются из внутренних потоков.
 */
public interface Vpn {

    interface Listener {
        /** Туннель поднят; iface — имя tun-интерфейса, например "tun0". */
        default void onUp(String iface) {}

        /** Туннель не поднялся или упал; reason — причина для пользователя. */
        default void onDown(String reason) {}

        /**
         * Поднятый туннель оборвался (нет ответа на ping, смена сети), другого VPN в системе нет:
         * идёт переподключение. attempt — номер текущей попытки (1, 2, ...).
         */
        default void onReconnecting(String reason, int attempt) {}
        default void onReconnecting(String reason) { onReconnecting(reason, 1); }
        default void onPingLoss(int lostCount) {}
    }
    
    default void setStrict(boolean on) {}

    void setListener(Listener l);
    /** true — подключена АТС: wake lock и жёсткий watchdog. false — обычный VPN. */


    /**
     * Проверяет согласие пользователя на VPN. true — согласие уже есть.
     * false — показан системный диалог (нужен Activity), результат придёт в onActivityResult
     * с requestCode; после RESULT_OK надо снова вызвать connect().
     */
    boolean consent(Context activity, int requestCode);

    /**
     * Поднимает туннель к host (IKE на UDP 500, дальше NAT-T на 4500). Если туннель уже есть
     * (наш или чужой системный) — он заменяется.
     * <ul>
     * <li>psk не пустой — вход по общему ключу; login — IKE-идентификатор клиента
     *     (с «@» почта, четыре числа через точки — IPv4, иначе FQDN).</li>
     * <li>psk пустой — вход по логину и паролю (EAP-MSCHAPv2). Сервер обязан показать
     *     сертификат с именем host в SAN; ca — PEM своего корневого сертификата
     *     (пусто — системное хранилище доверия).</li>
     * </ul>
     * apps — имена пакетов через пробел или перевод строки: их трафик тоже пойдёт через туннель.
     * Само приложение всегда включено; остальная система идёт напрямую.
     */
    void connect(String host, String login, String password, String psk, String ca, String apps, String healthcheckIp);

    default void connect(String host, String login, String password, String psk, String ca, String apps) {
        connect(host, login, password, psk, ca, apps, null);
    }

    /** Закрывает туннель и интерфейс. Можно звать в любом состоянии. */
    void disconnect();

    default boolean mobike() { return false; }

    static Vpn create(Context ctx) {
        return new VpnImpl(ctx);
    }
}
