package ru.minisip.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

import ru.minisip.update.Update;

/**
 * Main activity hosting a ViewPager2 of SettingsFragment cards.
 * Cards are constructed programmatically without XML layout files.
 */
public final class MainActivity extends AppCompatActivity {

    static final int REQ_VPN = 2;
    private static final int PAGER_ID = 0x4d530001;       // persistent ID used by ViewPager2 to restore active page

    private ViewPager2 pager;
    private Pages pages;
    private UpdateBar updateBar;

    private final Runnable appChangeListener = this::updateLockScreenFlags;

    private void updateLockScreenFlags() {
        App app = (App) getApplication();
        boolean active = app.isCallActive();
        setShowWhenLocked(active);
        setTurnScreenOn(active);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLockScreenFlags();
    }

    @Override
    protected void onDestroy() {
        ((App) getApplication()).removeChangeListener(appChangeListener);
        super.onDestroy();
    }
    /**
     * Release tag of the last checked release for update progress bar.
     */
    private String lastUpdateTag = "";

    /**
     * Custom confirmation dialog displaying styled action buttons.
     */
    static void showCustomConfirmDialog(Context ctx, String title, String message,
                                        Runnable onYes, Runnable onNo) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", (d, w) -> {
                    if (onYes != null) onYes.run();
                })
                .setNegativeButton("No", (d, w) -> {
                    if (onNo != null) onNo.run();
                })
                .setOnCancelListener(d -> {
                    if (onNo != null) onNo.run();
                })
                .create();

        dialog.show();

        Button noBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (noBtn != null) {
            noBtn.setTextColor(0xFF2196F3); // Blue No button
            noBtn.setTypeface(null, Typeface.BOLD);
        }

        Button yesBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (yesBtn != null) {
            yesBtn.setTextColor(0xFF888888); // Muted Yes button
            yesBtn.setAlpha(0.80f);          // 20% translucent
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        final App app = (App) getApplication();
        app.addChangeListener(appChangeListener);
        updateLockScreenFlags();

        pager = new ViewPager2(this);
        pager.setId(PAGER_ID);
        pages = new Pages(this);
        pager.setAdapter(pages);
        pager.setUserInputEnabled(pages.getItemCount() > 1);   // single page — user input disabled
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            private boolean isAsking = false;

            @Override
            public void onPageSelected(int position) {
                int currentCount = PageStore.count(MainActivity.this);
                if (position >= currentCount && !isAsking) {
                    isAsking = true;
                    showCustomConfirmDialog(MainActivity.this,
                            "Create New Card",
                            "Are you sure you want to create another settings card?",
                            () -> {
                                isAsking = false;
                                PageStore.setCount(MainActivity.this, position + 1);
                                pagesChanged();
                            },
                            () -> {
                                isAsking = false;
                                pager.setCurrentItem(currentCount - 1, true);
                            });
                }
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Update bar overlay anchored to bottom of ViewPager2
        updateBar = new UpdateBar(this);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(updateBar, barLp);
        updateBar.attachInset(pager);
        pager.setClipToPadding(false);

        app.update().setListener(new Update.Listener() {
            @Override
            public void onChecked(Update.Result r) {
                if (r.hasUpdate) {
                    lastUpdateTag = r.tag == null ? "" : r.tag;
                    updateBar.showProgress(0, lastUpdateTag);
                }
                // noStable and latest version notifications handled by SettingsFragment
            }

            @Override
            public void onProgress(int percent) {
                updateBar.showProgress(percent, lastUpdateTag);
            }

            @Override
            public void onDownloaded(File apk) {
                updateBar.showInstall(v -> app.update().install());
            }

            @Override
            public void onError(String reason) {
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                updateBar.postDelayed(updateBar::hide, 3000);
            }
        });

        if (b == null) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        }
    }

    /**
     * Settings or card count changed: recalculate ViewPager2 page scrolling.
     */
    void pagesChanged() {
        // posted asynchronously to prevent layout re-entrancy issues in RecyclerView
        pager.post(() -> {
            pages.refresh();
            pager.setUserInputEnabled(pages.getItemCount() > 1);
        });
    }

    /**
     * Called from SettingsFragment upon confirmed page deletion.
     */
    void pageDeleted(int pageToDelete) {
        PageStore.deletePage(this, pageToDelete);
        pager.post(() -> {
            pages = new Pages(this);
            pager.setAdapter(pages);
            int next = Math.max(0, pageToDelete - 1);
            pager.setCurrentItem(next, false);
            pager.setUserInputEnabled(pages.getItemCount() > 1);
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 1) askBatteryOnce();
    }

    private void askBatteryOnce() {
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (sp.getBoolean("batteryAsked", false)) return;
        sp.edit().putBoolean("batteryAsked", true).apply();
        requestIgnoreBatteryOptimizations();
    }

    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;   // already granted

        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);                    // system optimization prompt dialog
        } catch (ActivityNotFoundException e) {
            // fallback: general battery optimization settings screen
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /**
     * Forwards VPN system consent activity result to current SettingsFragment page.
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        boolean ok = resultCode == RESULT_OK;
        // ViewPager2 tags fragment as "f" + position; current item receives result
        Fragment f = getSupportFragmentManager().findFragmentByTag("f" + pager.getCurrentItem());
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).onVpnConsent(ok);
        } else if (!ok) {
            ((App) getApplication()).vpnDenied(pager.getCurrentItem());
        }
    }

    /**
     * Page adapter backing ViewPager2 settings cards.
     */
    private static final class Pages extends FragmentStateAdapter {
        private final FragmentActivity host;
        private int count;

        Pages(FragmentActivity host) {
            super(host);
            this.host = host;
            this.count = slots();
        }

        private int slots() {
            int n = PageStore.count(host);
            return n + (PageStore.configured(host, n - 1) ? 1 : 0);
        }

        void refresh() {
            int now = slots();
            if (now == count) return;
            int old = count;
            count = now;
            if (now > old) notifyItemRangeInserted(old, now - old);
            else notifyItemRangeRemoved(now, old - now);
        }

        @Override
        public int getItemCount() {
            return count;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return SettingsFragment.create(position);
        }
    }
}
