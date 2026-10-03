package ru.minisip.app;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Весь интерфейс собран в коде: ни одного xml-ресурса. */
public final class MainActivity extends Activity {

    private App app;
    private SharedPreferences prefs;
    private EditText server, login, pass, number;
    private TextView status;
    private Button connect, call, answer, hang;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = (App) getApplication();
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        // экран можно зажигать поверх блокировки (датчик приближения будит телефон у лица)
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        server = field(root, "Сервер (host или host:порт)", prefs.getString("server", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        login = field(root, "Логин", prefs.getString("login", ""), InputType.TYPE_CLASS_TEXT);
        pass = field(root, "Пароль", prefs.getString("pass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connect = button(root, "Подключиться", v -> doConnect());

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, dp(24), 0, dp(24));
        root.addView(status);

        number = field(root, "Номер", "", InputType.TYPE_CLASS_PHONE);
        call = button(root, "Позвонить", v -> doCall());
        answer = button(root, "Ответить", v -> app.answer());
        hang = button(root, "Отбой", v -> app.hangup());

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.onChange = this::render;
        render();
    }

    @Override
    protected void onPause() {
        app.onChange = null;
        super.onPause();
    }

    private void doConnect() {
        String s = server.getText().toString().trim();
        int port = 5060;
        int c = s.lastIndexOf(':');
        if (c > 0) {
            try {
                port = Integer.parseInt(s.substring(c + 1));
                s = s.substring(0, c);
            } catch (NumberFormatException e) {
                status.setText("Неверный порт");
                return;
            }
        }
        if (s.isEmpty() || login.getText().length() == 0) {
            status.setText("Укажите сервер и логин");
            return;
        }
        prefs.edit()
                .putString("server", server.getText().toString().trim())
                .putString("login", login.getText().toString().trim())
                .putString("pass", pass.getText().toString())
                .apply();
        app.connect(s, port, login.getText().toString().trim(), pass.getText().toString());
    }

    private void doCall() {
        String n = number.getText().toString().trim();
        if (n.isEmpty()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            status.setText("Нужен доступ к микрофону");
            return;
        }
        app.dial(n);
    }

    private void render() {
        status.setText(app.status);
        boolean idle = app.state == App.IDLE;
        call.setVisibility(idle ? View.VISIBLE : View.GONE);
        answer.setVisibility(app.state == App.INCOMING ? View.VISIBLE : View.GONE);
        hang.setVisibility(idle ? View.GONE : View.VISIBLE);
        connect.setEnabled(idle);
    }

    // ---------- мелочи вёрстки ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private EditText field(LinearLayout parent, String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(text);
        x.setOnClickListener(l);
        parent.addView(x);
        return x;
    }
}
