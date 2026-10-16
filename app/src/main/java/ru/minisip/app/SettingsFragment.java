package ru.minisip.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

import ru.minisip.log.LogFile;
import ru.minisip.update.Update;

/**
 * Одна страница настроек: SIP-аккаунт, звонок и VPN. Страниц может быть сколько угодно,
 * их листает MainActivity. Интерфейс собран в коде (xml-ресурсов нет), виджеты — Material 3.
 */
public final class SettingsFragment extends Fragment {

    private static final String ARG_PAGE = "page";
    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private App app;
    /**
     * Мост LogBuffer -> поле панели: буфер зовёт из своего потока, поле обновляем через post.
     */
    private final Runnable vpnLogUpdate = () -> {
        LogArea a = this.vpnLog;
        if (a != null && app != null) a.setText(app.vpnLog().text());
    };
    /**
     * Мост LogBuffer -> поле панели для блока «Звонок».
     */
    private final Runnable callLogUpdate = () -> {
        LogArea a = this.callLog;
        if (a != null && app != null) a.setText(app.callLog().text());
    };
    private SharedPreferences prefs;
    private int page;                  // номер страницы; от него зависят и настройки, и право на подключение
    private boolean loading;           // поля заполняются из настроек: изменения не сохранять заново
    private Runnable revealDeleteRunnable;
    private float touchDownY;
    private MaterialButton deleteBtn;
    private MaterialCardView currentlyDraggedCard;
    private TextInputEditText server, login, pass, number, vpnServer, vpnLogin, vpnPass, vpnKey, vpnCa, vpnHealthcheck;
    private TextView status, vpnStatus;
    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && status != null) status.setText("Microphone permission required");
            });
    private MaterialButton connect, disconnect, refersBtn, call, hang, spkBtn, earBtn, muteBtn, vpnConnect, vpnDisconnect, vpnAppsBtn, vpnMainBtn, vpnAdvancedBtn, sipAdvancedBtn;
    /**
     * Одна и та же ссылка нужна, чтобы в onPause снимать только свой колбэк.
     */
    private final Runnable renderer = this::render;
    private LinearLayout vpnMainSpoilerContainer, vpnAdvancedSpoilerContainer, sipSpoilerContainer;
    private LogArea sipLog, callLog, vpnLog;
    /**
     * Мост LogBuffer -> поле панели для блока «SIP сервер».
     */
    private final Runnable sipLogUpdate = () -> {
        LogArea a = sipLog;
        if (a != null && app != null) a.setText(app.sipLog().text());
    };
    /**
     * Строка версии внизу страницы: long-press 2 с открывает диалог канала обновлений.
     */
    private TextView versionLabel;
    private LogArea updateLog;
    /**
     * Отложенный показ диалога канала: срабатывает через 2 с удержания на строке версии.
     */
    private final Runnable channelDialogRunnable = () -> {
        if (isAdded()) showChannelDialog();
    };
    /**
     * Отложенное сохранение на Android ≤ 9, пока пользователь не выдаст разрешение.
     */
    private Runnable pendingSave;
    /**
     * Разрешение на внешнее хранилище для Android ≤ 9; после выдачи продолжаем отложенное сохранение.
     */
    private final ActivityResultLauncher<String> storagePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable r = pendingSave;
                pendingSave = null;
                if (granted && r != null) r.run();
            });

    static SettingsFragment create(int page) {
        Bundle a = new Bundle();
        a.putInt(ARG_PAGE, page);
        SettingsFragment f = new SettingsFragment();
        f.setArguments(a);
        return f;
    }

    private static boolean isIpv4(String s) {
        return s != null && s.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    }

    /**
     * Двухцветный ColorStateList: цвет для enabled, цвет для disabled.
     */
    private static ColorStateList stateful(int enabled, int disabled) {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_enabled}, {}},
                new int[]{enabled, disabled});
    }

    private static void setFieldEnabled(TextInputEditText field, boolean enabled) {
        if (field != null) {
            field.setEnabled(enabled);
            field.setFocusable(enabled);
            field.setFocusableInTouchMode(enabled);
        }
    }

    private static MaterialCardView getCardByTag(int id, Block sip, Block call, Block vpn) {
        if (id == 0) return sip.card;
        if (id == 1) return call.card;
        if (id == 2) return vpn.card;
        return null;
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

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
        sipLog = title(sipBlock.container, "SIP server settings", 0);

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
        refersBtn = button(sipBlock.container, "Refers", true, v -> showRefersDialog());
        status = body(sipBlock.container, dp(8));

        sipBlock.container.addView(sipLog.panel, row(dp(8)));


        // --- Блок 2: Номер телефона и источник звука ---
        Block callBlock = createBlock(1);
        callLog = title(callBlock.container, "Number and voice source", 0);
        number = field(callBlock.container, "Number", null, "", InputType.TYPE_CLASS_PHONE, false);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 35f);
        number.setPadding(dp(16), dp(16), dp(16), dp(16));
        if (number.getParent() instanceof TextInputLayout) {
            ((TextInputLayout) number.getParent()).setHintTextAppearance(android.R.style.TextAppearance_Medium);
        }

        LinearLayout callRow = new LinearLayout(ctx);
        callRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp(8);
        callBlock.container.addView(callRow, crLp);

        call = button(callRow, "📞 Call", false, v -> doCall());
        call.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        call.setPadding(0, dp(18), 0, dp(18));
        call.setBackgroundTintList(stateful(0xFF1976D2, 0x33808080));
        call.setTextColor(stateful(0xFFFFFFFF, 0x99FFFFFF));
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        callLp.rightMargin = dp(8);
        call.setLayoutParams(callLp);

        hang = button(callRow, "🚫 Discard", true, v -> app.hangup(page));
        hang.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        hang.setPadding(0, dp(18), 0, dp(18));
        hang.setBackgroundTintList(stateful(0xB3D32F2F, 0x33808080));
        hang.setStrokeColor(stateful(0xB3D32F2F, 0x33808080));
        hang.setTextColor(stateful(0xFFFFFFFF, 0x99FFFFFF));
        hang.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout audioRow = new LinearLayout(ctx);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = dp(8);
        callBlock.container.addView(audioRow, arLp);

        spkBtn = button(audioRow, "📢 Speaker", true, v -> pickSpeaker(true));
        LinearLayout.LayoutParams spkLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        spkLp.rightMargin = dp(8);
        spkBtn.setLayoutParams(spkLp);

        earBtn = button(audioRow, "👂 Earpiece", true, v -> pickSpeaker(false));
        earBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        muteBtn = button(callBlock.container, "🤫Mute mic", true, v -> toggleMute());

        callBlock.container.addView(callLog.panel, row(dp(8)));

        // --- Блок 3: Настройка VPN ---
        Block vpnBlock = createBlock(2);
        vpnLog = titleVpn(vpnBlock.container, "VPN settings (IKEv2)", 0);

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
        vpnHealthcheck = field(vpnAdvancedSpoilerContainer, "Healthcheck address", "IP or domain for connectivity check(0 or empty to disable)", prefs.getString("vpnHealthcheck", "0"),
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

        vpnBlock.container.addView(vpnLog.panel, row(dp(8)));

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
            } catch (NumberFormatException ignored) {
            }
        }
        for (Block blk : new Block[]{sipBlock, callBlock, vpnBlock}) {
            if (blk.card.getParent() == null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(16);
                col.addView(blk.card, lp);
            }
        }

        // Строка версии: под всеми блоками. Долгое удержание 2 с — диалог канала обновлений.
        LinearLayout verRow = new LinearLayout(ctx);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setGravity(Gravity.CENTER_VERTICAL);
        versionLabel = new TextView(ctx);
        versionLabel.setText("v" + appVersion());
        versionLabel.setTextAppearance(android.R.style.TextAppearance_Small);
        versionLabel.setGravity(Gravity.CENTER);
        versionLabel.setMinHeight(dp(32));
        int vp = dp(8);
        versionLabel.setPadding(vp, vp, vp, vp);
        versionLabel.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    gestureHandler.postDelayed(channelDialogRunnable, 2000);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getY() - v.getHeight() / 2f) > dp(20)) {
                        gestureHandler.removeCallbacks(channelDialogRunnable);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    gestureHandler.removeCallbacks(channelDialogRunnable);
                    return true;
            }
            return false;
        });
        verRow.addView(versionLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView moreVer = new TextView(ctx);
        moreVer.setText("\u22EE");
        moreVer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        moreVer.setPadding(dp(10), 0, dp(10), 0);
        moreVer.setClickable(true);
        moreVer.setFocusable(true);
        verRow.addView(moreVer);
        col.addView(verRow, row(dp(8)));

        updateLog = new LogArea(ctx);
        moreVer.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(ctx, v);
            pm.getMenu().add("Log");
            pm.setOnMenuItemClickListener(item -> {
                boolean show = updateLog.panel.getVisibility() == View.GONE;
                updateLog.panel.setVisibility(show ? View.VISIBLE : View.GONE);
                return true;
            });
            pm.show();
        });
        col.addView(updateLog.panel, row(dp(8)));

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
        connect = disconnect = refersBtn = call = hang = spkBtn = earBtn = vpnConnect = vpnDisconnect = vpnAppsBtn = vpnMainBtn = vpnAdvancedBtn = sipAdvancedBtn = deleteBtn = null;
        vpnMainSpoilerContainer = vpnAdvancedSpoilerContainer = sipSpoilerContainer = null;
        versionLabel = null;
        updateLog = null;
        gestureHandler.removeCallbacks(channelDialogRunnable);
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
        if (vpnLog != null) {
            vpnLog.onEnable = vpnLogUpdate;
            vpnLog.onClear = () -> app.vpnLog().clear();
            vpnLog.onSave = () -> saveLog("vpn", vpnLog);
            vpnLog.setText(app.vpnLog().text());
            app.vpnLog().setListener(vpnLogUpdate);
        }
        if (callLog != null) {
            callLog.onEnable = callLogUpdate;
            callLog.onClear = () -> app.callLog().clear();
            callLog.onSave = () -> saveLog("call", callLog);
            callLog.setText(app.callLog().text());
            app.callLog().setListener(callLogUpdate);
        }
        if (sipLog != null) {
            sipLog.onEnable = sipLogUpdate;
            sipLog.onClear = () -> app.sipLog().clear();
            sipLog.onSave = () -> saveLog("sip", sipLog);
            sipLog.setText(app.sipLog().text());
            app.sipLog().setListener(sipLogUpdate);
        }
        if (updateLog != null) {
            updateLog.onEnable = () -> {
                if (updateLog != null) updateLog.setText(app.updateLog().text());
            };
            updateLog.onClear = () -> app.updateLog().clear();
            updateLog.onSave = () -> saveLog("update", updateLog);
            updateLog.setText(app.updateLog().text());
            app.updateLog().setListener(updateLog.onEnable);
        }
        app.onChange = renderer;     // событиями App живёт только видимая страница
        render();
    }

    // ---------- действия ----------

    @Override
    public void onPause() {
        if (app != null) app.vpnLog().setListener(null);
        if (app != null) app.callLog().setListener(null);
        if (app != null) app.sipLog().setListener(null);
        if (app != null) app.updateLog().setListener(null);
        if (app.onChange == renderer) app.onChange = null;
        super.onPause();
    }

    /**
     * versionName установленного APK — для подписи под блоками.
     */
    private String appVersion() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Диалог канала обновлений. Single-choice Stable / Pre-release, кнопка «Проверить»
     * сохраняет выбор и запускает check(). Ответ с hasUpdate=true обрабатывает полоса в
     * MainActivity; здесь — только «последняя версия» и 404-stable.
     */
    private void showChannelDialog() {
        String[] names = {"Stable", "Pre-release"};
        int checked = "prerelease".equals(app.update().channel()) ? 1 : 0;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Update channel")
                .setSingleChoiceItems(names, checked, (d, which) ->
                        app.update().setChannel(which == 1 ? "prerelease" : "stable"))
                .setPositiveButton("Check", (d, w) -> app.update().check(checkListener))
                .setNegativeButton("Cancel", null)
                .show();
    }

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

    /**
     * Локальный слушатель check(). Полоса в MainActivity подключена постоянным слушателем
     * и уже показывает скачивание; здесь — только случаи, когда полосы не будет.
     */
    private final Update.Listener checkListener = new Update.Listener() {
        @Override
        public void onChecked(Update.Result r) {
            if (r == null || r.hasUpdate) return;   // показ полосы — не наша забота
            if (r.noStable) {
                MainActivity.showCustomConfirmDialog(requireContext(),
                        "No stable versions yet",
                        "Check pre-releases?",
                        () -> {
                            app.update().setChannel("prerelease");
                            app.update().check(checkListener);
                        },
                        null);
                return;
            }
            Toast.makeText(requireContext(), "You have the latest version", Toast.LENGTH_SHORT).show();
        }
        // onError показывает MainActivity (Toast + скрытие полосы) — здесь не дублируем
    };

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

    /**
     * Ответ на системный запрос согласия на VPN (его получает MainActivity).
     */
    void onVpnConsent(boolean granted) {
        if (granted) vpnStart();
        else app.vpnDenied(page);
    }

    private void vpnStart() {
        save(vpnHealthcheck, "vpnHealthcheck");
        String hc = text(vpnHealthcheck).trim();
        if (hc.isEmpty()) hc = "0";

        if (hc.equals("0") || isIpv4(hc)) {
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

    /**
     * Список приложений с галочками: их трафик пойдёт через VPN. MiniSIP включён всегда.
     */
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
                    for (int i = 0; i < on.length; i++)
                        if (on[i]) sb.append(pkgs.get(i)).append('\n');
                    prefs.edit().putString("vpnApps", sb.toString().trim()).apply();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Переключение источника звука: применяется сразу, в том числе посреди вызова.
     */
    private void pickSpeaker(boolean speaker) {
        prefs.edit().putBoolean("speaker", speaker).apply();
        if (!(app.busy() && app.lastPage != page)) app.setSpeaker(speaker);
        render();
    }

    /**
     * Диалог со списком Refers: строки правятся на месте, Use — blind transfer, ✕ — удалить.
     */
    private void showRefersDialog() {
        final Context ctx = requireContext();
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        final NestedScrollView scroll = new NestedScrollView(ctx);
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final List<PageStore.Refer> working = PageStore.refers(ctx, page);
        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] rebuild = new Runnable[1];

        rebuild[0] = () -> {
            if (working.isEmpty()) working.add(new PageStore.Refer("", ""));
            box.removeAllViews();
            for (int i = 0; i < working.size(); i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                EditText login = new EditText(ctx);
                login.setHint("Login");
                login.setInputType(InputType.TYPE_CLASS_TEXT);
                login.setText(working.get(i).login);
                login.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                login.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        PageStore.Refer old = working.get(idx);
                        working.set(idx, new PageStore.Refer(s.toString(), old.name));
                        PageStore.setRefers(ctx, page, working);
                    }
                });
                row.addView(login);

                EditText name = new EditText(ctx);
                name.setHint("Name");
                name.setInputType(InputType.TYPE_CLASS_TEXT);
                name.setText(working.get(i).name);
                name.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                name.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        PageStore.Refer old = working.get(idx);
                        working.set(idx, new PageStore.Refer(old.login, s.toString()));
                        PageStore.setRefers(ctx, page, working);
                    }
                });
                row.addView(name);

                TextView use = new TextView(ctx);
                use.setText("\u21AA\uFE0F");
                use.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                use.setIncludeFontPadding(false);
                use.setGravity(Gravity.CENTER);
                use.setPadding(dp(8), dp(2), dp(8), dp(2));  // «выступ» вокруг эмодзи
                GradientDrawable bg =
                        new GradientDrawable();
                bg.setColor(MaterialColors.getColor(use,
                        com.google.android.material.R.attr.colorPrimary, 0xFF1976D2));
                bg.setCornerRadius(dp(10));
                use.setBackground(new RippleDrawable(
                        ColorStateList.valueOf(0x40FFFFFF), bg, null));
                use.setClickable(true);
                use.setFocusable(true);
                use.setOnClickListener(v -> {
                    if (app.state != App.TALK || app.lastPage != page) {
                        Toast.makeText(ctx, "No active call", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String loginStr = working.get(idx).login;
                    final AlertDialog parent = holder[0];
                    MainActivity.showCustomConfirmDialog(ctx,
                            "Transfer call",
                            "Transfer to " + loginStr + "?",
                            () -> {
                                app.transfer(page, loginStr);
                                if (parent != null) parent.dismiss();
                            },
                            null);
                });
                row.addView(use);

                TextView del = new TextView(ctx);
                del.setText("\u2715");
                del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                del.setPadding(dp(10), dp(6), dp(10), dp(6));
                del.setClickable(true);
                del.setOnClickListener(v -> {
                    working.remove(idx);
                    PageStore.setRefers(ctx, page, working);
                    rebuild[0].run();
                });
                row.addView(del);

                box.addView(row);
            }
        };

        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        int tp = dp(16);
        titleRow.setPadding(tp, tp, tp, 0);

        TextView titleText = new TextView(ctx);
        titleText.setText("Refers");
        titleText.setTextAppearance(android.R.style.TextAppearance_Large);
        titleRow.addView(titleText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton addBtn = new MaterialButton(ctx);
        addBtn.setText("Add");
        addBtn.setOnClickListener(v -> {
            PageStore.Refer last = working.isEmpty() ? null : working.get(working.size() - 1);
            if (last != null && last.login.isEmpty() && last.name.isEmpty()) return;
            working.add(new PageStore.Refer("", ""));
            PageStore.setRefers(ctx, page, working);
            rebuild[0].run();
        });
        titleRow.addView(addBtn);

        AlertDialog d = new MaterialAlertDialogBuilder(ctx)
                .setCustomTitle(titleRow)
                .setView(scroll)
                .setPositiveButton("OK", null)
                .create();
        holder[0] = d;
        d.setOnShowListener(x -> {
            Window w = d.getWindow();
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        rebuild[0].run();
        d.show();
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
        call.setEnabled(!other && !inCall && reg);
        hang.setEnabled(!other && inCall);
        muteBtn.setEnabled(!other);
        muteBtn.setText(prefs.getBoolean("muted", false) ? "👄 Unmute mic" : "🤫 Mute mic");
        boolean spk = prefs.getBoolean("speaker", false);
        int audioGreen = 0xFF2E7D32;
        ColorStateList greenTint =
                ColorStateList.valueOf(audioGreen);
        if (spk) {
            spkBtn.setStrokeColor(greenTint);
            spkBtn.setStrokeWidth(dp(3));
            earBtn.setStrokeColor(ColorStateList.valueOf(0x33808080));
            earBtn.setStrokeWidth(dp(1));
        } else {
            earBtn.setStrokeColor(greenTint);
            earBtn.setStrokeWidth(dp(3));
            spkBtn.setStrokeColor(ColorStateList.valueOf(0x33808080));
            spkBtn.setStrokeWidth(dp(1));
        }
        connect.setEnabled(!other && idle && !reg && !conn);
        disconnect.setEnabled(reg || conn);
        // VPN: подключить можно только когда он не поднят и нет вызова; отключить — когда поднят или поднимается
        String saved = prefs.getString("vpnApps", "").trim();
        int n = saved.isEmpty() ? 0 : saved.split("\\s+").length;
        vpnAppsBtn.setText("Apps using VPN (MiniSIP" + (n > 0 ? " + " + n : "") + ")");
        vpnAppsBtn.setEnabled(!other && !vUp && !vConn);   // список применяется при подключении
        vpnConnect.setEnabled(!other && idle && !vUp && !vConn);
        if (vUp) {
            String mobikeStatus = app.vpnMobike() ? "Active" : "Not supported";
            String sub = "\nMOBIKE: " + mobikeStatus + " | DPD: " + app.vpnDpd();
            SpannableString span = new SpannableString("VPN connect" + sub);
            span.setSpan(new RelativeSizeSpan(0.7f), 11, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            vpnConnect.setText(span);
            vpnConnect.setPadding(0, dp(12), 0, dp(12));
        } else {
            vpnConnect.setText("VPN connect");
            vpnConnect.setPadding(0, dp(14), 0, dp(14));
        }
        vpnDisconnect.setEnabled(vUp || vConn);
    }

    /**
     * Запоминает поле в настройках страницы по мере набора.
     */
    private void save(TextInputEditText e, String key) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading) return;
                prefs.edit().putString(key, s.toString()).apply();
                // от сервера и логина зависит, можно ли листать дальше и создавать новую страницу
                if (key.equals("server") || key.equals("login")) {
                    if (getActivity() instanceof MainActivity)
                        ((MainActivity) getActivity()).pagesChanged();
                }
            }
        });
    }

    /**
     * Подтягивает поле из настроек (их мог поменять другой экземпляр этой же страницы).
     */
    private void load(TextInputEditText e, String key) {
        String v = prefs.getString(key, "");
        if (text(e).equals(v)) return;
        loading = true;
        e.setText(v);
        loading = false;
    }

    private Block createBlock(int blockTag) {
        Context ctx = requireContext();
        MaterialCardView card = new MaterialCardView(ctx);
        card.setTag(blockTag);
        card.setRadius(dp(16));
        card.setCardElevation(dp(0));
        card.setStrokeWidth(dp(1));

        int attrId = ctx.getResources().getIdentifier("colorOutline", "attr", ctx.getPackageName());
        if (attrId == 0)
            attrId = ctx.getResources().getIdentifier("colorOutline", "attr", "com.google.android.material");
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------- мелочи вёрстки ----------

    private LinearLayout.LayoutParams row(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private LogArea title(LinearLayout parent, String text, int top) {
        Context ctx = parent.getContext();
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextAppearance(android.R.style.TextAppearance_Medium);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = new TextView(ctx);
        more.setText("\u22EE");                 // ⋮
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        int pad = dp(10);
        more.setPadding(pad, 0, pad, 0);
        more.setClickable(true);
        more.setFocusable(true);
        head.addView(more);

        parent.addView(head, row(top));

        LogArea area = new LogArea(ctx);

        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(ctx, v);
            pm.getMenu().add("Log");
            pm.setOnMenuItemClickListener(item -> {
                boolean show = area.panel.getVisibility() == View.GONE;
                area.panel.setVisibility(show ? View.VISIBLE : View.GONE);
                return true;
            });
            pm.show();
        });

        return area;
    }

    /**
     * Заголовок блока VPN: в ⋮-меню, кроме «Log», есть чекбокс strict-режима.
     * strict хранится per-page в prefs под ключом "vpnStrict" (по умолчанию включён);
     * при активном VPN применяется к живому туннелю сразу.
     */
    private LogArea titleVpn(LinearLayout parent, String text, int top) {
        Context ctx = parent.getContext();
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextAppearance(android.R.style.TextAppearance_Medium);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = new TextView(ctx);
        more.setText("\u22EE");                 // ⋮
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        int pad = dp(10);
        more.setPadding(pad, 0, pad, 0);
        more.setClickable(true);
        more.setFocusable(true);
        head.addView(more);

        parent.addView(head, row(top));

        LogArea area = new LogArea(ctx);

        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(ctx, v);
            pm.getMenu().add("Log");
            MenuItem strict = pm.getMenu().add("Strict mode");
            strict.setCheckable(true);
            strict.setChecked(prefs.getBoolean("vpnStrict", false));
            pm.setOnMenuItemClickListener(item -> {
                if ("Log".contentEquals(item.getTitle())) {
                    boolean show = area.panel.getVisibility() == View.GONE;
                    area.panel.setVisibility(show ? View.VISIBLE : View.GONE);
                    return true;
                }
                boolean next = !item.isChecked();
                item.setChecked(next);
                prefs.edit().putBoolean("vpnStrict", next).apply();
                if (app.lastPage == page && (app.vpnUp || app.vpnConnecting))
                    app.setVpnStrict(next);
                return true;
            });
            pm.show();
        });

        return area;
    }

    private MaterialButton smallButton(Context ctx, String label) {
        MaterialButton b = new MaterialButton(ctx);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void saveLog(String prefix, LogArea area) {
        String text = area.text.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(requireContext(), "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= 28
                && requireContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSave = () -> saveLog(prefix, area);
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        String result = LogFile.save(requireContext(), prefix, text);
        Toast.makeText(requireContext(),
                result.startsWith("error:") ? result : "Saved: " + result,
                Toast.LENGTH_LONG).show();
    }

    private TextView body(LinearLayout parent, int top) {
        TextView t = new TextView(parent.getContext());
        t.setTextAppearance(android.R.style.TextAppearance_Small);
        parent.addView(t, row(top));
        return t;
    }

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

    private static final class Block {
        final MaterialCardView card;
        final LinearLayout container;

        Block(MaterialCardView card, LinearLayout container) {
            this.card = card;
            this.container = container;
        }
    }

    /**
     * Панель лога блока: контейнер, поле для текста и кнопки.
     */
    private final class LogArea {
        final LinearLayout panel;
        final EditText text;
        final MaterialButton toggle;
        volatile boolean enabled = false;
        Runnable onEnable;
        Runnable onClear;
        Runnable onSave;

        LogArea(Context ctx) {
            panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setVisibility(View.GONE);
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.END);
            int cp = dp(10);

            TextView down = new TextView(ctx);
            down.setText("\u25BC");
            down.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            down.setPadding(cp, dp(2), cp, dp(2));
            down.setClickable(true);
            down.setFocusable(true);
            down.setOnClickListener(v -> scrollToBottom());
            head.addView(down);

            TextView close = new TextView(ctx);
            close.setText("\u2715");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            close.setPadding(cp, dp(2), cp, dp(2));
            close.setClickable(true);
            close.setFocusable(true);
            close.setOnClickListener(v -> panel.setVisibility(View.GONE));
            head.addView(close);
            panel.addView(head);
            text = new EditText(ctx);
            text.setKeyListener(null);
            text.setTextIsSelectable(true);
            text.setGravity(Gravity.TOP | Gravity.START);
            text.setTextAppearance(android.R.style.TextAppearance_Small);
            text.setHint("Log is empty");
            text.setPadding(dp(8), dp(8), dp(8), dp(8));
            text.setMovementMethod(new ScrollingMovementMethod());
            text.setVerticalScrollBarEnabled(true);
            text.setFocusable(true);
            text.setFocusableInTouchMode(true);
            text.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return false;
            });

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x14808080);
            bg.setStroke(dp(1), 0x33808080);
            bg.setCornerRadius(dp(8));
            text.setBackground(bg);

            LinearLayout band = new LinearLayout(ctx);
            band.setOrientation(LinearLayout.HORIZONTAL);
            View left = new View(ctx);
            band.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.15f));
            band.addView(text, new LinearLayout.LayoutParams(0, dp(300), 0.70f));
            View right = new View(ctx);
            band.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.15f));
            panel.addView(band, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            MaterialButton clear = smallButton(ctx, "Clear");
            MaterialButton copy = smallButton(ctx, "Copy");
            MaterialButton save = smallButton(ctx, "Save");
            toggle = smallButton(ctx, "Enable");
            clear.setOnClickListener(v -> {
                text.setText("");
                if (onClear != null) onClear.run();
            });
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("log", text.getText()));
            });
            save.setOnClickListener(v -> {
                if (onSave != null) onSave.run();
            });
            toggle.setOnClickListener(v -> {
                enabled = !enabled;
                toggle.setText(enabled ? "Disable" : "Enable");
                if (enabled && onEnable != null) onEnable.run();
            });
            row.addView(clear);
            row.addView(copy);
            row.addView(save);
            row.addView(toggle);
            panel.addView(row);
        }

        void setText(String s) {
            text.post(() -> {
                if (!enabled) return;
                boolean near = isNearBottom();
                text.setText(s);
                if (near) scrollToBottom();
            });
        }

        private boolean isNearBottom() {
            Layout l = text.getLayout();
            int lc = text.getLineCount();
            if (l == null || lc == 0) return true;
            int view = text.getHeight() - text.getPaddingTop() - text.getPaddingBottom();
            if (view <= 0) return true;
            int keepTop = l.getLineTop(Math.max(0, lc - 4));
            return keepTop < text.getScrollY() + view - 1;
        }

        private int maxScroll() {
            Layout l = text.getLayout();
            if (l == null) return 0;
            int content = l.getLineTop(text.getLineCount());
            int view = text.getHeight() - text.getPaddingTop() - text.getPaddingBottom();
            return Math.max(0, content - view);
        }

        private void scrollToBottom() {
            int max = maxScroll();
            if (max > 0) text.scrollTo(0, max);
        }
    }
}
