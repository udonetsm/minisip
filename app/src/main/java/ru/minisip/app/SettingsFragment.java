package ru.minisip.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Одна страница настроек: SIP-аккаунт, звонок и VPN. Страниц может быть сколько угодно,
 * их листает MainActivity. Интерфейс собран в коде (xml-ресурсов нет), виджеты — Material 3.
 */
public final class SettingsFragment extends Fragment {

    private static final String ARG_PAGE = "page";

    static SettingsFragment create(int page) {
        Bundle a = new Bundle();
        a.putInt(ARG_PAGE, page);
        SettingsFragment f = new SettingsFragment();
        f.setArguments(a);
        return f;
    }

    private App app;
    private SharedPreferences prefs;
    private int page;                  // номер страницы; от него зависят и настройки, и право на подключение
    private boolean loading;           // поля заполняются из настроек: изменения не сохранять заново

    private TextInputEditText server, login, pass, number, vpnServer, vpnLogin, vpnPass, vpnKey, vpnCa;
    private TextView status, vpnStatus;
    private MaterialButton connect, disconnect, call, hang, audioBtn, vpnConnect, vpnDisconnect, vpnAppsBtn;

    /** Одна и та же ссылка нужна, чтобы в onPause снимать только свой колбэк. */
    private final Runnable renderer = this::render;

    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && status != null) status.setText("Нужен доступ к микрофону");
            });

    @Override
    public void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        app = (App) requireActivity().getApplication();
        page = requireArguments().getInt(ARG_PAGE, 0);
        prefs = PageStore.prefs(requireContext(), page);       // у каждой страницы свои настройки
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle b) {
        Context ctx = requireContext();

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        title(col, "SIP", 0);
        server = field(col, "Сервер", "host или host:порт", prefs.getString("server", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        login = field(col, "Login", null, prefs.getString("login", ""), InputType.TYPE_CLASS_TEXT, false);
        pass = field(col, "Password", null, prefs.getString("pass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        connect = button(col, "Connect", false, v -> doConnect());
        disconnect = button(col, "Disconnect", true, v -> app.disconnect(page));
        status = body(col, dp(16));

        number = field(col, "Number", null, "", InputType.TYPE_CLASS_PHONE, false);
        call = button(col, "Call", false, v -> doCall());
        hang = button(col, "Discard", true, v -> app.hangup(page));
        audioBtn = button(col, "Источник вывода", true, v -> pickOutput());

        title(col, "VPN (IKEv2)", dp(32));
        vpnServer = field(col, "VPN server", "host или IPv4", prefs.getString("vpnServer", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        vpnLogin = field(col, "VPN login", "например user@example.org", prefs.getString("vpnLogin", ""),
                InputType.TYPE_CLASS_TEXT, false);
        vpnPass = field(col, "VPN password", null, prefs.getString("vpnPass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        vpnKey = field(col, "VPN pre-shared key", "пусто — вход по логину и паролю", prefs.getString("vpnKey", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        vpnCa = field(col, "VPN CA certificate (PEM)", "пусто — системное хранилище", prefs.getString("vpnCa", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        vpnCa.setSingleLine(false);
        vpnCa.setMaxLines(4);
        vpnAppsBtn = button(col, "VPN apps", true, v -> pickApps());
        vpnConnect = button(col, "VPN connect", false, v -> doVpnConnect());
        vpnDisconnect = button(col, "VPN disconnect", true, v -> app.vpnDisconnect(page));
        vpnStatus = body(col, dp(16));

        // всё, что набрано, сразу запоминается: страницы-копии одной настройки не расходятся
        save(server, "server");
        save(login, "login");
        save(pass, "pass");
        save(vpnServer, "vpnServer");
        save(vpnLogin, "vpnLogin");
        save(vpnPass, "vpnPass");
        save(vpnKey, "vpnKey");
        save(vpnCa, "vpnCa");

        NestedScrollView scroll = new NestedScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // «Страница» — карточка с небольшими полями вокруг: по краям виден фон главной активити
        MaterialCardView card = new MaterialCardView(ctx);
        card.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout root = new FrameLayout(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int gap = dp(8);
        lp.setMargins(gap, gap, gap, gap);
        root.addView(card, lp);
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        server = login = pass = number = vpnServer = vpnLogin = vpnPass = vpnKey = vpnCa = null;
        status = vpnStatus = null;
        connect = disconnect = call = hang = audioBtn = vpnConnect = vpnDisconnect = vpnAppsBtn = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        load(server, "server");
        load(login, "login");
        load(pass, "pass");
        load(vpnServer, "vpnServer");
        load(vpnLogin, "vpnLogin");
        load(vpnPass, "vpnPass");
        load(vpnKey, "vpnKey");
        load(vpnCa, "vpnCa");
        app.onChange = renderer;     // событиями App живёт только видимая страница
        render();
    }

    @Override
    public void onPause() {
        if (app.onChange == renderer) app.onChange = null;
        super.onPause();
    }

    // ---------- действия ----------

    private void doConnect() {
        String s = text(server).trim();
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
        if (s.isEmpty() || text(login).trim().isEmpty()) {
            status.setText("Укажите сервер и логин");
            return;
        }
        app.connect(page, s, port, text(login).trim(), text(pass));
    }

    private void doCall() {
        String n = text(number).trim();
        if (n.isEmpty()) return;
        if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
            status.setText("Нужен доступ к микрофону");
            return;
        }
        app.setSpeaker(prefs.getBoolean("speaker", false));   // вывод звука, выбранный на этой странице
        app.dial(page, n);
    }

    private void doVpnConnect() {
        String h = text(vpnServer).trim();
        String id = text(vpnLogin).trim();
        String pw = text(vpnPass);
        String key = text(vpnKey);
        if (h.isEmpty() || id.isEmpty() || (pw.isEmpty() && key.isEmpty())) {
            vpnStatus.setText("VPN: fill in server, login and password (or a pre-shared key)");
            return;
        }
        if (!app.allowed(page)) return;
        // false — показан системный диалог, ответ придёт в MainActivity и вернётся в onVpnConsent
        if (app.vpnConsent(requireActivity(), MainActivity.REQ_VPN)) vpnStart();
    }

    /** Ответ на системный запрос согласия на VPN (его получает MainActivity). */
    void onVpnConsent(boolean granted) {
        if (granted) vpnStart();
        else app.vpnDenied(page);
    }

    private void vpnStart() {
        app.vpnConnect(page, text(vpnServer).trim(), text(vpnLogin).trim(), text(vpnPass), text(vpnKey),
                text(vpnCa), prefs.getString("vpnApps", ""));
    }

    /** Список приложений с галочками: их трафик пойдёт через VPN. MiniSIP включён всегда. */
    private void pickApps() {
        PackageManager pm = requireContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String> pkgs = new ArrayList<>(), names = new ArrayList<>();
        List<ResolveInfo> all = pm.queryIntentActivities(main, 0);
        all.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        String self = requireContext().getPackageName();
        for (ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (p.equals(self) || pkgs.contains(p)) continue;
            pkgs.add(p);
            names.add(r.loadLabel(pm) + "\n" + p);
        }
        Set<String> chosen = new HashSet<>();
        Collections.addAll(chosen, prefs.getString("vpnApps", "").split("\\s+"));
        boolean[] on = new boolean[pkgs.size()];
        for (int i = 0; i < on.length; i++) on[i] = chosen.contains(pkgs.get(i));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Apps that use the VPN")
                .setMultiChoiceItems(names.toArray(new String[0]), on, (d, i, c) -> on[i] = c)
                .setPositiveButton("OK", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < on.length; i++) if (on[i]) sb.append(pkgs.get(i)).append('\n');
                    prefs.edit().putString("vpnApps", sb.toString().trim()).apply();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Диалог выбора, куда направлять звук разговора. Применяется сразу, в том числе посреди вызова. */
    private void pickOutput() {
        String[] names = {"Разговорный динамик", "Медиадинамик (громкая связь)"};
        int checked = prefs.getBoolean("speaker", false) ? 1 : 0;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Источник вывода")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    boolean speaker = which == 1;
                    prefs.edit().putBoolean("speaker", speaker).apply();
                    if (!(app.busy() && app.lastPage != page)) app.setSpeaker(speaker);
                    render();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void render() {
        if (getView() == null || status == null) return;
        // Подключением владеет одна страница. Чужую сессию эта страница не видит и управлять ею не может.
        boolean mine = app.lastPage == page;
        boolean other = app.busy() && !mine;
        boolean idle = !mine || app.state == App.IDLE;
        boolean reg = mine && app.registered, conn = mine && app.connecting;
        boolean vUp = mine && app.vpnUp, vConn = mine && app.vpnConnecting;
        String busy = "Занято: подключением пользуется страница " + (app.lastPage + 1);
        status.setText(mine ? app.status : other ? busy : "Не подключено");
        vpnStatus.setText(mine ? app.vpnStatus : other ? busy : "VPN: disconnected");
        call.setVisibility(idle ? View.VISIBLE : View.GONE);
        hang.setVisibility(idle ? View.GONE : View.VISIBLE);
        call.setEnabled(!other && reg);
        audioBtn.setEnabled(!other);
        audioBtn.setText("Источник вывода: " + (prefs.getBoolean("speaker", false)
                ? "медиадинамик (громкая связь)" : "разговорный динамик"));
        connect.setEnabled(!other && idle && !reg && !conn);
        disconnect.setEnabled(reg || conn);
        // VPN: подключить можно только когда он не поднят и нет вызова; отключить — когда поднят или поднимается
        String saved = prefs.getString("vpnApps", "").trim();
        int n = saved.isEmpty() ? 0 : saved.split("\\s+").length;
        vpnAppsBtn.setText("VPN apps (MiniSIP" + (n > 0 ? " + " + n : "") + ")");
        vpnAppsBtn.setEnabled(!other && !vUp && !vConn);   // список применяется при подключении
        vpnConnect.setEnabled(!other && idle && !vUp && !vConn);
        vpnDisconnect.setEnabled(vUp || vConn);
    }

    /** Запоминает поле в настройках страницы по мере набора. */
    private void save(TextInputEditText e, String key) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (loading) return;
                prefs.edit().putString(key, s.toString()).apply();
                // от сервера и логина зависит, можно ли листать дальше и создавать новую страницу
                if (key.equals("server") || key.equals("login")) {
                    if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).pagesChanged();
                }
            }
        });
    }

    /** Подтягивает поле из настроек (их мог поменять другой экземпляр этой же страницы). */
    private void load(TextInputEditText e, String key) {
        String v = prefs.getString(key, "");
        if (text(e).equals(v)) return;
        loading = true;
        e.setText(v);
        loading = false;
    }

    // ---------- мелочи вёрстки ----------

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams row(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private void title(LinearLayout parent, String text, int top) {
        TextView t = new TextView(parent.getContext());
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        parent.addView(t, row(top));
    }

    private TextView body(LinearLayout parent, int top) {
        TextView t = new TextView(parent.getContext());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        parent.addView(t, row(top));
        return t;
    }

    /**
     * Поле Material (outlined). secret — пароль: символы скрыты, справа кнопка-глаз «Показать пароль».
     * Внимание: у полей с паролем нельзя вызывать setSingleLine(true) после setInputType —
     * это сбрасывает скрытие символов. Однострочность и так задана типом ввода.
     */
    private TextInputEditText field(LinearLayout parent, String hint, String helper, String value,
                                    int inputType, boolean secret) {
        TextInputLayout til = new TextInputLayout(parent.getContext());
        til.setHint(hint);
        if (helper != null) til.setHelperText(helper);
        TextInputEditText e = new TextInputEditText(til.getContext());
        e.setInputType(inputType);
        e.setText(value);
        til.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (secret) {
            til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
            til.setEndIconContentDescription("Показать пароль");
        }
        parent.addView(til, row(dp(8)));
        return e;
    }

    private MaterialButton button(LinearLayout parent, String text, boolean outlined, View.OnClickListener l) {
        Context ctx = parent.getContext();
        MaterialButton b = outlined
                ? new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                : new MaterialButton(ctx);
        b.setText(text);
        b.setOnClickListener(l);
        parent.addView(b, row(dp(8)));
        return b;
    }
}
