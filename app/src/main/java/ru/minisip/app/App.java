package ru.minisip.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import ru.minisip.media.Media;
import ru.minisip.net.Udp;
import ru.minisip.screen.Screen;
import ru.minisip.sip.Sip;
import ru.minisip.vpn.Vpn;

/**
 * Корень композиции: единственное место, где собираются реализации.
 * Все пакеты общаются только через интерфейсы Udp, Media, Sip, Screen, Vpn.
 */
public final class App extends Application implements Sip.Listener, Vpn.Listener {

    static final int IDLE = 0, CALLING = 1, TALK = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Sip sip;
    private Media media;
    private Screen screen;
    private Vpn vpn;
    private Udp sipUdp, rtpUdp;            // сокеты SIP и RTP: их привязываем к VPN
    private String sipHost, sipUser, sipPass;
    private int sipPort;

    // состояние для UI; трогать только из главного потока
    int state = IDLE;
    boolean registered = false;
    boolean connecting = false;
    String status = "Не подключено";
    boolean vpnUp = false;
    boolean vpnConnecting = false;
    int vpnAttempts = 0;
    String vpnStatus = "VPN: disconnected";
    /** SIP работал, когда VPN оборвался: после возврата (или окончательного отключения) переоткрыть. */
    private boolean sipResume;
    Runnable onChange;

    /**
     * Страница настроек, которая последней управляла подключением. SIP-стек и VPN у приложения один,
     * поэтому пока что-то активно (VPN, регистрация, вызов), им владеет одна страница, остальные
     * ничего не видят и ничего включить не могут. Состояние выше принадлежит этой странице.
     */
    int lastPage = -1;

    /** Что-то включено: вызов, регистрация на АТС или VPN (в том числе когда они поднимаются). */
    boolean busy() {
        return state != IDLE || registered || connecting || vpnUp || vpnConnecting;
    }

    /** Страница может управлять подключением: ничего не занято либо оно уже её. */
    boolean allowed(int page) {
        return !busy() || lastPage == page;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sipUdp = Udp.create(this);
        rtpUdp = Udp.create(this);
        media = Media.create(rtpUdp, this);
        sip = Sip.create(sipUdp, media);
        sip.setListener(this);
        screen = Screen.create(this);
        vpn = Vpn.create(this);
        vpn.setListener(this);
        try {                                // смена основной сети системы: SIP-сокет надо переоткрыть
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network n) {
                            main.post(App.this::onNetwork);
                        }
                    });
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Появилась новая основная сеть. Пока локального VPN нет, SIP идёт стеком системы, а его сокет
     * и адрес в Via/Contact остались от старой сети: сам SipImpl заметит это лишь через ~2 минуты
     * (таймаут обновления регистрации + повтор). Поэтому сразу открываем сокет заново. Звонок через
     * сменившуюся сеть не спасти. Когда VPN поднят или поднимается, ничего не делаем: туннель
     * сам следит за связью, а onUp перерегистрирует SIP.
     */
    private void onNetwork() {
        if (sipHost == null || vpnUp || vpnConnecting) return;
        if (state != IDLE) sip.hangup();
        sipResume = true;                    // даже если регистрация уже упала сама
        reregister();
        changed();
    }

    // ---------- команды из UI (главный поток) ----------

    void connect(int page, String host, int port, String user, String pass) {
        if (!allowed(page)) return;
        lastPage = page;
        if (!vpnUp) {                        // нет локального VPN: гарантированно системный стек
            sipUdp.bind(null);
            rtpUdp.bind(null);
        }
        sipHost = host;
        sipPort = port;
        sipUser = user;
        sipPass = pass;
        registered = false;
        connecting = true;
        status = "Подключение…";
        changed();
        sip.register(host, port, user, pass);
    }

    void disconnect(int page) {
        if (lastPage != page) return;
        sipHost = null;
        sipResume = false;
        registered = false;
        connecting = false;
        status = "Отключено";
        sip.unregister();
        changed();
    }

    // ---------- VPN (главный поток) ----------

    /** true — согласие на VPN уже есть; false — показан системный диалог, результат в Activity. */
    boolean vpnConsent(Activity a, int requestCode) {
        return vpn.consent(a, requestCode);
    }

    void vpnConnect(int page, String host, String login, String password, String psk, String ca, String apps, String healthcheckIp) {
        vpn.setStrict(true);
        if (state != IDLE || vpnUp || vpnConnecting || !allowed(page)) return;
        lastPage = page;
        vpnConnecting = true;
        vpnStatus = "VPN: connecting...";
        changed();
        vpn.connect(host, login, password, psk, ca, apps, healthcheckIp);
    }

    void vpnConnect(int page, String host, String login, String password, String psk, String ca, String apps) {
        vpnConnect(page, host, login, password, psk, ca, apps, null);
    }

    void vpnDisconnect(int page) {
        if (lastPage != page) return;
        vpnAttempts = 0;
        vpn.disconnect();
    }

    void vpnDenied(int page) {
        if (!allowed(page)) return;
        lastPage = page;
        vpnAttempts = 0;
        vpnStatus = "VPN: permission denied";
        changed();
    }

    void vpnError(int page, String error) {
        if (!allowed(page)) return;
        lastPage = page;
        vpnAttempts = 0;
        vpnStatus = error;
        changed();
    }

    /** SIP-регистрацию надо переоткрыть, чтобы она шла тем же путём, что и вызов. */
    private void reregister() {
        if (sipHost == null || !(registered || connecting || sipResume)) return;
        sipResume = false;
        registered = false;
        connecting = true;
        status = "Подключение…";
        sip.register(sipHost, sipPort, sipUser, sipPass);
    }

    @Override
    public void onUp(String iface) {
        main.post(() -> {
            vpnConnecting = false;
            vpnUp = true;
            vpnAttempts = 0;                 // сброс счетчика в 0 при успешном реконнекте
            vpnStatus = "VPN: connected (" + iface + ")";
            sipUdp.bind(iface);              // SIP и RTP идут строго через туннель
            rtpUdp.bind(iface);
            reregister();
            changed();
        });
    }

    @Override
    public void onReconnecting(String reason, int attempt) {
        main.post(() -> {
            if (!vpnUp) return;
            vpnAttempts = attempt;
            String attStr = attempt > 99 ? ">99" : String.valueOf(attempt);
            vpnStatus = "VPN: reconnecting (попытка " + attStr + ")";
            sipUdp.bind(null);               // локальный VPN сейчас не работает: SIP и RTP идут стеком системы
            rtpUdp.bind(null);
            if (sipHost != null && (registered || connecting)) {
                reregister();                // регистрация сразу уходит системным стеком
                sipResume = true;            // а когда VPN вернётся, onUp переоткроет её уже через туннель
            }
            changed();
        });
    }

    @Override
    public void onDown(String reason) {
        main.post(() -> {
            vpnAttempts = 0;                 // сброс счетчика в 0 при отключении от VPN
            boolean isTakeover = reason != null &&
                    (reason.toLowerCase().contains("taken over") || reason.toLowerCase().contains("another vpn"));

            if (isTakeover) {
                if (state != IDLE) sip.hangup();
                sipHost = null;
                sipResume = false;
                registered = false;
                connecting = false;
                status = "Отключено";
                sip.unregister();

                vpnUp = false;
                vpnConnecting = false;
                vpnStatus = "VPN: disconnected (" + reason + ")";
                vpn.disconnect();

                sipUdp.bind(null);
                rtpUdp.bind(null);
                sipUdp.close();
                rtpUdp.close();

                changed();
                return;
            }

            boolean was = vpnUp;
            vpnConnecting = false;
            vpnUp = false;
            vpnStatus = "VPN: disconnected (" + reason + ")";
            sipUdp.bind(null);               // обратно на основной стек системы
            rtpUdp.bind(null);
            if (was) {
                if (state != IDLE) sip.hangup();   // вызов через упавший туннель не спасти
                reregister();
            }
            changed();
        });
    }

    void dial(int page, String number) {
        if (state != IDLE || !allowed(page)) return;
        lastPage = page;
        if (!registered) {
            status = "Ошибка: нет сети";
            changed();
            return;
        }
        begin(CALLING, "Вызов…");
        sip.call(number);
    }

    /** Куда идёт звук разговора: true — громкая связь, false — в ухо. */
    void setSpeaker(boolean on) {
        media.setSpeaker(on);
    }

    void hangup(int page) {
        if (state == IDLE || lastPage != page) return;
        sip.hangup();
    }

    /** Старт вызова: сервис (чтобы микрофон жил при погашенном экране) + датчик приближения. */
    private void begin(int newState, String text) {
        state = newState;
        status = text;
        startForegroundService(new Intent(this, CallService.class));
        screen.start();
        changed();
    }

    private void finish(String text) {
        state = IDLE;
        status = text;
        screen.stop();
        stopService(new Intent(this, CallService.class));
        changed();
    }

    private void changed() {
        vpn.setStrict(true);
        if (onChange != null) onChange.run();
    }
    // ---------- события SIP (поток "sip" -> главный поток) ----------

    @Override
    public void onRegistered(boolean ok, String info) {
        main.post(() -> {
            registered = ok;
            connecting = false;
            status = ok ? "Подключено" : "Ошибка: " + info;
            changed();
        });
    }

    @Override
    public void onRinging() {
        main.post(() -> {
            if (state == CALLING) {
                status = "Идёт вызов…";
                changed();
            }
        });
    }

    @Override
    public void onConnected() {
        main.post(() -> {
            state = TALK;
            status = "Разговор";
            changed();
        });
    }

    @Override
    public void onEnded(String reason) {
        main.post(() -> finish("Завершено: " + reason));
    }
}
