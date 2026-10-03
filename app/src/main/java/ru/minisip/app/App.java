package ru.minisip.app;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import ru.minisip.media.Media;
import ru.minisip.net.Udp;
import ru.minisip.screen.Screen;
import ru.minisip.sip.Sip;

/**
 * Корень композиции: единственное место, где собираются реализации.
 * Все пакеты общаются только через интерфейсы Udp, Media, Sip, Screen.
 */
public final class App extends Application implements Sip.Listener {

    static final int IDLE = 0, CALLING = 1, TALK = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Sip sip;
    private Screen screen;

    // состояние для UI; трогать только из главного потока
    int state = IDLE;
    boolean registered = false;
    boolean connecting = false;
    String status = "Не подключено";
    Runnable onChange;

    @Override
    public void onCreate() {
        super.onCreate();
        sip = Sip.create(Udp.create(), Media.create(Udp.create(), this));
        sip.setListener(this);
        screen = Screen.create(this);
    }

    // ---------- команды из UI (главный поток) ----------

    void connect(String host, int port, String user, String pass) {
        registered = false;
        connecting = true;
        status = "Подключение…";
        changed();
        sip.register(host, port, user, pass);
    }

    void disconnect() {
        registered = false;
        connecting = false;
        status = "Отключено";
        sip.unregister();
        changed();
    }

    void dial(String number) {
        if (state != IDLE) return;
        begin(CALLING, "Вызов…");
        sip.call(number);
    }

    void hangup() {
        if (state == IDLE) return;
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
