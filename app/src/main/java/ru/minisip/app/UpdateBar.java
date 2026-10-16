package ru.minisip.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Полоса внизу экрана: прогресс скачивания APK либо кнопка «Установить».
 * Одна на приложение, живёт в MainActivity. Видна только при активном обновлении;
 * пока видна — ужимает ViewPager2 снизу (padding + clipToPadding=false), чтобы контент
 * страниц не заходил под полосу.
 */
final class UpdateBar extends LinearLayout {

    private static final int HEIGHT_DP = 48;

    private final ProgressBar progress;
    private final TextView label;
    private View insetTarget;

    UpdateBar(Context ctx) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(0xF0222222);                 // плотный тёмный фон
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        setVisibility(GONE);

        progress = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        addView(progress, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        label = new TextView(ctx);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14f);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        addView(label, lp);
    }

    /**
     * Кого ужимать снизу, когда полоса видна (обычно ViewPager2).
     */
    void attachInset(View v) {
        insetTarget = v;
    }

    @Override
    public void setVisibility(int v) {
        super.setVisibility(v);
        if (insetTarget == null) return;
        int px = v == VISIBLE ? dp(HEIGHT_DP) : 0;
        insetTarget.setPadding(insetTarget.getPaddingLeft(), insetTarget.getPaddingTop(),
                insetTarget.getPaddingRight(), px);
    }

    /**
     * Прогресс скачивания. percent=-1 — длина неизвестна. tag — строка релиза (может быть пустой).
     */
    void showProgress(int percent, String tag) {
        progress.setVisibility(VISIBLE);
        configureLabel(false);
        label.setClickable(false);
        label.setFocusable(false);
        label.setOnClickListener(null);
        String pct = percent < 0 ? "" : " — " + percent + "%";
        label.setText("Downloading " + (tag == null ? "" : tag) + pct);
        if (percent >= 0) progress.setProgress(percent);
        setVisibility(VISIBLE);
    }

    /**
     * Скачано: на той же полосе — кликабельная надпись «Install» на всю ширину.
     */
    void showInstall(OnClickListener onInstall) {
        progress.setVisibility(GONE);
        configureLabel(true);
        label.setText("Install");
        label.setClickable(true);
        label.setFocusable(true);
        label.setOnClickListener(onInstall);
        setVisibility(VISIBLE);
    }

    /**
     * fullWidth=true — «Install» на всю ширину полосы (progress скрыт, label занимает всё),
     * fullWidth=false — текст прогресса справа от ProgressBar, ширина по содержимому.
     */
    private void configureLabel(boolean fullWidth) {
        LayoutParams lp = (LayoutParams) label.getLayoutParams();
        if (fullWidth) {
            lp.width = 0;
            lp.weight = 1f;
            lp.leftMargin = 0;
        } else {
            lp.width = LayoutParams.WRAP_CONTENT;
            lp.weight = 0f;
            lp.leftMargin = dp(12);
        }
        label.setLayoutParams(lp);
        label.setGravity(Gravity.CENTER);
    }

    void hide() {
        setVisibility(GONE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
