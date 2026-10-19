package ru.minisip.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * Invisible proxy launched from the Quick Settings tile.
 * Handles the VPN consent flow (which requires an Activity) and then
 * delegates to App.toggleVpnFromTile(). Closes itself immediately.
 */
public final class TileProxyActivity extends Activity {

    private static final int REQ_CONSENT = 1;
    static final String EXTRA_PICK = "pick";

    private boolean pick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
	pick = getIntent().getBooleanExtra(EXTRA_PICK, false)
                || "android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(getIntent().getAction());
        proceed();
    }

    private void proceed() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            // First run or consent was revoked: show the system dialog.
            startActivityForResult(consent, REQ_CONSENT);
            return;
        }
        if (pick) {
            showPicker();
        } else {
            runToggle();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CONSENT) return;
        if (res == RESULT_OK) {
            if (pick) {
                showPicker();
            } else {
                runToggle();
            }
        } else {
            finish();
        }
    }

    private void runToggle() {
        App app = (App) getApplication();
        String msg = app.toggleVpnFromTile(this);
        if (msg != null) {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /**
     * Shows a dialog listing all configured VPN profiles. Selecting one connects it.
     */
    private void showPicker() {
        App app = (App) getApplication();
        List<Integer> pages = PageStore.configuredVpnPages(this);
        if (pages.isEmpty()) {
            android.widget.Toast.makeText(this, "No VPN profiles configured", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String[] labels = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            SharedPreferences p = PageStore.prefs(this, pages.get(i));
            String srv = p.getString("vpnServer", "").trim();
            String login = p.getString("vpnLogin", "").trim();
            labels[i] = "Page " + (pages.get(i) + 1) + ": " + login + "@" + srv;
        }
        Context themed = new ContextThemeWrapper(this,
                com.google.android.material.R.style.Theme_Material3_DayNight_Dialog_Alert);
        new MaterialAlertDialogBuilder(themed)
                .setTitle("Connect VPN")
                .setItems(labels, (d, which) -> {
                    int page = pages.get(which);
                    String msg = app.connectVpnPage(this, page);
                    if (msg != null) {
                        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();
    }
}
