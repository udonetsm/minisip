package ru.minisip.app;

import android.app.Application;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
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

    static final int IDLE = 0, CALLING = 1, INCOMING = 2, TALK = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Sip sip;
    private Screen screen;
    private Ringtone ring;

    // состояние для UI; трогать только из главного потока
    int state = IDLE;
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
        status = "Подключение…";
        changed();
        sip.register(host, port, user, pass);
    }

    void dial(String number) {
        if (state != IDLE) return;
        begin(CALLING, "Вызов…");
        sip.call(number);
    }

    void answer() {
        if (state != INCOMING) return;
        stopRing();
        begin(TALK, "Разговор");
        sip.answer();
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
        stopRing();
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
            status = ok ? "Подключено" : "Ошибка: " + info;
            changed();
        });
    }

    @Override
    public void onIncoming(String from) {
        main.post(() -> {
            if (state != IDLE) return;
            state = INCOMING;
            status = "Входящий: " + from;
            ring = RingtoneManager.getRingtone(this,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ring != null) ring.play();
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

    private void stopRing() {
        if (ring != null) {
            ring.stop();
            ring = null;
        }
    }
}
