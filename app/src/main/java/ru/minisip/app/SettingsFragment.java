package ru.minisip.app;

import android.Manifest;
import android.animation.LayoutTransition;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.net.Inet4Address;
import java.net.InetAddress;
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

    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private Runnable revealDeleteRunnable;
    private float touchDownY;
    private MaterialButton deleteBtn;
    private MaterialCardView currentlyDraggedCard;

    private TextInputEditText server, login, pass, number, vpnServer, vpnLogin, vpnPass, vpnKey, vpnCa, vpnHealthcheck;
    private TextView status, vpnStatus;
    private MaterialButton connect, disconnect, call, hang, audioBtn, muteBtn, vpnConnect, vpnDisconnect, vpnAppsBtn, vpnMainBtn, vpnAdvancedBtn, sipAdvancedBtn;
    private LinearLayout vpnMainSpoilerContainer, vpnAdvancedSpoilerContainer, sipSpoilerContainer;

    /** Одна и та же ссылка нужна, чтобы в onPause снимать только свой колбэк. */
    private final Runnable renderer = this::render;

    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && status != null) status.setText("Microphone permission required");
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

        // --- Блок 1: Настройка АТС сервера ---
        Block sipBlock = createBlock(0);
        title(sipBlock.container, "SIP server settings", 0);

        sipSpoilerContainer = new LinearLayout(ctx);
        sipSpoilerContainer.setOrientation(LinearLayout.VERTICAL);
        sipSpoilerContainer.setVisibility(View.GONE);

        server = field(sipSpoilerContainer, "Server", "host or host:port", prefs.getString("server", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        login = field(sipSpoilerContainer, "Login", null, prefs.getString("login", ""), InputType.TYPE_CLASS_TEXT, false);
        pass = field(sipSpoilerContainer, "Password", null, prefs.getString("pass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);

        sipAdvancedBtn = button(sipBlock.container, "SIP settings ▼", true, v -> {
            boolean show = sipSpoilerContainer.getVisibility() == View.GONE;
            sipSpoilerContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            sipAdvancedBtn.setText(show ? "SIP settings ▲" : "SIP settings ▼");
        });

        sipBlock.container.addView(sipSpoilerContainer);

        connect = button(sipBlock.container, "Connect", false, v -> doConnect());
        disconnect = button(sipBlock.container, "Disconnect", true, v -> app.disconnect(page));
        status = body(sipBlock.container, dp(8));

        // --- Блок 2: Номер телефона и источник звука ---
        Block callBlock = createBlock(1);
        title(callBlock.container, "Number and voice source", 0);
        number = field(callBlock.container, "Number", null, "", InputType.TYPE_CLASS_PHONE, false);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 35f);
        number.setPadding(dp(16), dp(16), dp(16), dp(16));
        if (number.getParent() instanceof TextInputLayout) {
            ((TextInputLayout) number.getParent()).setHintTextAppearance(android.R.style.TextAppearance_Medium);
        }

        call = button(callBlock.container, "Call", false, v -> doCall());
        call.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        call.setPadding(0, dp(18), 0, dp(18));

        hang = button(callBlock.container, "Discard", true, v -> app.hangup(page));
        audioBtn = button(callBlock.container, "Voice source", true, v -> pickOutput());
        muteBtn = button(callBlock.container, "Mute microphone 🎤", true, v -> toggleMute());

        // --- Блок 3: Настройка VPN ---
        Block vpnBlock = createBlock(2);
        title(vpnBlock.container, "VPN settings (IKEv2)", 0);

        vpnMainSpoilerContainer = new LinearLayout(ctx);
        vpnMainSpoilerContainer.setOrientation(LinearLayout.VERTICAL);
        vpnMainSpoilerContainer.setVisibility(View.GONE);

        vpnServer = field(vpnMainSpoilerContainer, "VPN server", "host or IPv4", prefs.getString("vpnServer", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        vpnLogin = field(vpnMainSpoilerContainer, "VPN login", "e.g. user@example.org", prefs.getString("vpnLogin", ""),
                InputType.TYPE_CLASS_TEXT, false);
        vpnPass = field(vpnMainSpoilerContainer, "VPN password", null, prefs.getString("vpnPass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);

        // Вложенный (дополнительный) спойлер внутри настроек VPN
        vpnAdvancedSpoilerContainer = new LinearLayout(ctx);
        vpnAdvancedSpoilerContainer.setOrientation(LinearLayout.VERTICAL);
        vpnAdvancedSpoilerContainer.setVisibility(View.GONE);

        vpnKey = field(vpnAdvancedSpoilerContainer, "VPN pre-shared key", "empty — login and password authentication", prefs.getString("vpnKey", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        vpnCa = field(vpnAdvancedSpoilerContainer, "VPN CA certificate (PEM)", "empty — system trust store", prefs.getString("vpnCa", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        vpnCa.setSingleLine(false);
        vpnCa.setMaxLines(4);
        vpnHealthcheck = field(vpnAdvancedSpoilerContainer, "Healthcheck address", "IP or domain for connectivity check", prefs.getString("vpnHealthcheck", "google.com"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);

        vpnAppsBtn = button(vpnMainSpoilerContainer, "Apps using VPN", true, v -> pickApps());

        vpnAdvancedBtn = button(vpnMainSpoilerContainer, "Advanced VPN settings ▼", true, v -> {
            boolean show = vpnAdvancedSpoilerContainer.getVisibility() == View.GONE;
            vpnAdvancedSpoilerContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            vpnAdvancedBtn.setText(show ? "Advanced VPN settings ▲" : "Advanced VPN settings ▼");
        });

        vpnMainSpoilerContainer.addView(vpnAdvancedSpoilerContainer);

        vpnMainBtn = button(vpnBlock.container, "VPN settings ▼", true, v -> {
            boolean show = vpnMainSpoilerContainer.getVisibility() == View.GONE;
            vpnMainSpoilerContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            vpnMainBtn.setText(show ? "VPN settings ▲" : "VPN settings ▼");
        });

        vpnBlock.container.addView(vpnMainSpoilerContainer);

        vpnConnect = button(vpnBlock.container, "VPN connect", false, v -> doVpnConnect());
        vpnDisconnect = button(vpnBlock.container, "VPN disconnect", true, v -> app.vpnDisconnect(page));
        vpnStatus = body(vpnBlock.container, dp(8));

        setupCardDragAndDrop(sipBlock.card, 0, col);
        setupCardDragAndDrop(callBlock.card, 1, col);
        setupCardDragAndDrop(vpnBlock.card, 2, col);

        // Добавляем карточки в контейнер согласно сохраненному порядку
        String savedOrder = prefs.getString("blockOrder", "0,1,2");
        String[] orderArr = savedOrder.split(",");
        for (String idStr : orderArr) {
            try {
                int id = Integer.parseInt(idStr.trim());
                MaterialCardView targetCard = getCardByTag(id, sipBlock, callBlock, vpnBlock);
                if (targetCard != null && targetCard.getParent() == null) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(16);
                    col.addView(targetCard, lp);
                }
            } catch (NumberFormatException ignored) {}
        }
        for (Block blk : new Block[]{sipBlock, callBlock, vpnBlock}) {
            if (blk.card.getParent() == null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(16);
                col.addView(blk.card, lp);
            }
        }

        // Кнопка удаления страницы
        deleteBtn = button(col, "Delete Page 🗑", true, v -> confirmDeletePage());
        deleteBtn.setBackgroundColor(0xFFD32F2F);
        deleteBtn.setTextColor(0xFFFFFFFF);
        deleteBtn.setVisibility(View.GONE);

        // всё, что набрано, сразу запоминается: страницы-копии одной настройки не расходятся
        save(server, "server");
        save(login, "login");
        save(pass, "pass");
        save(vpnServer, "vpnServer");
        save(vpnLogin, "vpnLogin");
        save(vpnPass, "vpnPass");
        save(vpnKey, "vpnKey");
        save(vpnCa, "vpnCa");
        save(vpnHealthcheck, "vpnHealthcheck");

        NestedScrollView scroll = new NestedScrollView(ctx) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (PageStore.count(requireContext()) > 1) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            touchDownY = event.getRawY();
                            cancelHoldTimer();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            boolean isAtBottom = !canScrollVertically(1);
                            float dy = touchDownY - event.getRawY();
                            if (isAtBottom && dy > dp(10)) {
                                if (revealDeleteRunnable == null && deleteBtn != null && deleteBtn.getVisibility() == View.GONE) {
                                    revealDeleteRunnable = () -> {
                                        if (deleteBtn != null && PageStore.count(requireContext()) > 1) {
                                            deleteBtn.setVisibility(View.VISIBLE);
                                            post(() -> {
                                                smoothScrollTo(0, col.getBottom());
                                                deleteBtn.requestFocus();
                                            });
                                        }
                                    };
                                    gestureHandler.postDelayed(revealDeleteRunnable, 1000); // 1 секунда
                                }
                            } else {
                                cancelHoldTimer();
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            cancelHoldTimer();
                            break;
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        };
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
        cancelHoldTimer();
        super.onDestroyView();
        server = login = pass = number = vpnServer = vpnLogin = vpnPass = vpnKey = vpnCa = vpnHealthcheck = null;
        status = vpnStatus = null;
        connect = disconnect = call = hang = audioBtn = vpnConnect = vpnDisconnect = vpnAppsBtn = vpnMainBtn = vpnAdvancedBtn = sipAdvancedBtn = deleteBtn = null;
        vpnMainSpoilerContainer = vpnAdvancedSpoilerContainer = sipSpoilerContainer = null;
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
        load(vpnHealthcheck, "vpnHealthcheck");
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
                status.setText("Invalid port");
                return;
            }
        }
        if (s.isEmpty() || text(login).trim().isEmpty()) {
            status.setText("Specify server and login");
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
            status.setText("Microphone permission required");
            return;
        }
        app.setSpeaker(prefs.getBoolean("speaker", false));   // вывод звука, выбранный на этой странице
        app.setMute(prefs.getBoolean("muted", false));
        app.dial(page, n);
    }

    private void toggleMute() {
        boolean muted = !prefs.getBoolean("muted", false);
        prefs.edit().putBoolean("muted", muted).apply();
        if (!(app.busy() && app.lastPage != page)) app.setMute(muted);
        render();
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
        save(vpnHealthcheck, "vpnHealthcheck");
        String hc = text(vpnHealthcheck).trim();
        if (hc.isEmpty()) hc = "google.com";

        if (isIpv4(hc)) {
            app.vpnConnect(page, text(vpnServer).trim(), text(vpnLogin).trim(), text(vpnPass), text(vpnKey),
                    text(vpnCa), prefs.getString("vpnApps", ""), hc);
            return;
        }

        final String domain = hc;
        vpnStatus.setText("VPN: resolving healthcheck IP...");
        new Thread(() -> {
            String resolvedIp = null;
            try {
                InetAddress[] addrs = InetAddress.getAllByName(domain);
                for (InetAddress a : addrs) {
                    if (a instanceof Inet4Address) {
                        resolvedIp = a.getHostAddress();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            final String finalIp = resolvedIp;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (finalIp == null) {
                        app.vpnError(page, "VPN: failed to resolve healthcheck IP (" + domain + ")");
                        return;
                    }
                    app.vpnConnect(page, text(vpnServer).trim(), text(vpnLogin).trim(), text(vpnPass), text(vpnKey),
                            text(vpnCa), prefs.getString("vpnApps", ""), finalIp);
                });
            }
        }).start();
    }

    private static boolean isIpv4(String s) {
        return s != null && s.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
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
        String[] names = {"Earpiece", "Speakerphone"};
        int checked = prefs.getBoolean("speaker", false) ? 1 : 0;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Audio output")
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
        boolean inCall = app.state != App.IDLE;
        boolean reg = mine && app.registered, conn = mine && app.connecting;
        boolean vUp = mine && app.vpnUp, vConn = mine && app.vpnConnecting;

        boolean sipFieldsEnabled = !other && !inCall && !reg && !conn;
        setFieldEnabled(server, sipFieldsEnabled);
        setFieldEnabled(login, sipFieldsEnabled);
        setFieldEnabled(pass, sipFieldsEnabled);

        setFieldEnabled(number, !other && !inCall);

        boolean vpnFieldsEnabled = !other && !vUp && !vConn;
        setFieldEnabled(vpnServer, vpnFieldsEnabled);
        setFieldEnabled(vpnLogin, vpnFieldsEnabled);
        setFieldEnabled(vpnPass, vpnFieldsEnabled);
        setFieldEnabled(vpnKey, vpnFieldsEnabled);
        setFieldEnabled(vpnCa, vpnFieldsEnabled);
        setFieldEnabled(vpnHealthcheck, vpnFieldsEnabled);

        String busy = "Busy: session in use by page " + (app.lastPage + 1);
        status.setText(mine ? app.status : other ? busy : "Disconnected");
        vpnStatus.setText(mine ? app.vpnStatus : other ? busy : "VPN: disconnected");
        call.setVisibility(idle ? View.VISIBLE : View.GONE);
        hang.setVisibility(idle ? View.GONE : View.VISIBLE);
        call.setEnabled(!other && reg);
        audioBtn.setEnabled(!other);
        audioBtn.setText("Audio output: " + (prefs.getBoolean("speaker", false)
                ? "Speakerphone" : "Earpiece"));
        muteBtn.setEnabled(!other);
        muteBtn.setText(prefs.getBoolean("muted", false) ? "Unmute microphone 🎙️" : "Mute microphone 🎤");
        connect.setEnabled(!other && idle && !reg && !conn);
        disconnect.setEnabled(reg || conn);
        // VPN: подключить можно только когда он не поднят и нет вызова; отключить — когда поднят или поднимается
        String saved = prefs.getString("vpnApps", "").trim();
        int n = saved.isEmpty() ? 0 : saved.split("\\s+").length;
        vpnAppsBtn.setText("Apps using VPN (MiniSIP" + (n > 0 ? " + " + n : "") + ")");
        vpnAppsBtn.setEnabled(!other && !vUp && !vConn);   // список применяется при подключении
        vpnConnect.setEnabled(!other && idle && !vUp && !vConn);
        vpnDisconnect.setEnabled(vUp || vConn);
    }

    private static void setFieldEnabled(TextInputEditText field, boolean enabled) {
        if (field != null) {
            field.setEnabled(enabled);
            field.setFocusable(enabled);
            field.setFocusableInTouchMode(enabled);
        }
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

    private static final class Block {
        final MaterialCardView card;
        final LinearLayout container;
        Block(MaterialCardView card, LinearLayout container) {
            this.card = card;
            this.container = container;
        }
    }

    private Block createBlock(int blockTag) {
        Context ctx = requireContext();
        MaterialCardView card = new MaterialCardView(ctx);
        card.setTag(blockTag);
        card.setRadius(dp(16));
        card.setCardElevation(dp(0));
        card.setStrokeWidth(dp(1));

        int attrId = ctx.getResources().getIdentifier("colorOutline", "attr", ctx.getPackageName());
        if (attrId == 0) attrId = ctx.getResources().getIdentifier("colorOutline", "attr", "com.google.android.material");
        int strokeColor = attrId != 0 ? MaterialColors.getColor(card, attrId, 0x44888888) : 0x44888888;
        card.setStrokeColor(strokeColor);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, pad, pad, pad);

        card.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return new Block(card, container);
    }

    private void setupCardDragAndDrop(MaterialCardView card, int tag, LinearLayout col) {
        card.setOnLongClickListener(v -> {
            currentlyDraggedCard = card;
            ClipData data = ClipData.newPlainText("blockTag", String.valueOf(tag));
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            v.startDragAndDrop(data, shadow, card, 0);
            return true;
        });

        card.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (currentlyDraggedCard == card) {
                        card.setAlpha(0.2f);
                    }
                    return true;

                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                    if (currentlyDraggedCard != null && currentlyDraggedCard != card) {
                        int indexA = col.indexOfChild(currentlyDraggedCard);
                        int indexB = col.indexOfChild(card);
                        if (indexA >= 0 && indexB >= 0 && indexA != indexB) {
                            col.removeView(currentlyDraggedCard);
                            col.addView(currentlyDraggedCard, indexB);
                        }
                    }
                    return true;

                case DragEvent.ACTION_DROP:
                    if (currentlyDraggedCard != null) {
                        currentlyDraggedCard.setAlpha(1.0f);
                    }
                    saveBlockOrder(col);
                    return true;

                case DragEvent.ACTION_DRAG_ENDED:
                    if (currentlyDraggedCard != null) {
                        currentlyDraggedCard.setAlpha(1.0f);
                        currentlyDraggedCard = null;
                    }
                    saveBlockOrder(col);
                    return true;
            }
            return true;
        });
    }

    private void saveBlockOrder(LinearLayout col) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < col.getChildCount(); i++) {
            View child = col.getChildAt(i);
            if (child.getTag() instanceof Integer) {
                if (sb.length() > 0) sb.append(',');
                sb.append(child.getTag());
            }
        }
        prefs.edit().putString("blockOrder", sb.toString()).apply();
    }

    private static MaterialCardView getCardByTag(int id, Block sip, Block call, Block vpn) {
        if (id == 0) return sip.card;
        if (id == 1) return call.card;
        if (id == 2) return vpn.card;
        return null;
    }

    private void cancelHoldTimer() {
        if (revealDeleteRunnable != null) {
            gestureHandler.removeCallbacks(revealDeleteRunnable);
            revealDeleteRunnable = null;
        }
    }

    private void confirmDeletePage() {
        if (PageStore.count(requireContext()) <= 1) return;
        MainActivity.showCustomConfirmDialog(requireContext(),
                "Delete Page",
                "Are you sure you want to delete page " + (page + 1) + "?",
                () -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).pageDeleted(page);
                    }
                },
                null);
    }

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
        t.setTextAppearance(android.R.style.TextAppearance_Medium);
        parent.addView(t, row(top));
    }

    private TextView body(LinearLayout parent, int top) {
        TextView t = new TextView(parent.getContext());
        t.setTextAppearance(android.R.style.TextAppearance_Small);
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
            til.setEndIconContentDescription("Show password");
        }
        parent.addView(til, row(dp(8)));
        return e;
    }

    private MaterialButton button(LinearLayout parent, String text, boolean outlined, View.OnClickListener l) {
        Context ctx = parent.getContext();
        MaterialButton b = new MaterialButton(ctx);
        b.setText(text);
        b.setOnClickListener(l);
        parent.addView(b, row(dp(8)));
        return b;
    }
}
