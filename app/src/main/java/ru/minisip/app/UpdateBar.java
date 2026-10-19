package ru.minisip.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Bottom progress bar layout displaying APK download progress or "Install" button.
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
        setBackgroundColor(0xF0222222);                 // dark background
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
     * Sets target view (usually ViewPager2) to apply bottom padding inset when bar is visible.
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
     * Shows download progress bar with percentage and optional release tag.
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
     * Displays clickable "Install" text overlay when APK download completes.
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
     * Configures label layout parameters: fullWidth for Install button, wrap_content for download progress.
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
