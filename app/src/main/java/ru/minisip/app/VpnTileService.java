package ru.minisip.app;
import android.app.PendingIntent;
import android.content.Intent;
import android.app.Application;
import android.os.Build;
import android.net.VpnService;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Quick Settings tile that toggles the app-wide VPN without opening the UI.
 * Composition root wiring (App / Vpn) is added in later steps; this skeleton only
 * exposes the tile to the system so it can be verified in isolation.
 */
public final class VpnTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t == null) return;
        Application app = getApplication();
        boolean active = app instanceof App && (((App) app).vpnUp || ((App) app).vpnConnecting);
        t.setLabel("MiniSIP VPN");
        t.setIcon(Icon.createWithResource(this, android.R.drawable.sym_def_app_icon));
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
    @Override
    public void onClick() {
        super.onClick();
        // If VPN consent is already granted, toggle directly without launching an Activity.
        // This keeps the Quick Settings panel from collapsing.
        if (VpnService.prepare(this) == null) {
            App app = (App) getApplication();
            final boolean wasActive = app.vpnUp || app.vpnConnecting;
            String msg = app.toggleVpnFromTile(this);
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
            // Toggle is async: flip the tile optimistically for instant feedback,
            // then re-sync once the operation settles. requestListeningState in
            // App.changed() is rate-limited and may not re-invoke onStartListening.
            Tile t = getQsTile();
            if (t != null) {
                t.setState(wasActive ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
                t.updateTile();
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Tile t2 = getQsTile();
                if (t2 == null) return;
                App a = (App) getApplication();
                boolean active = a.vpnUp || a.vpnConnecting;
                t2.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                t2.updateTile();
            }, 1500);
            return;
        }
        Intent intent = new Intent(this, TileProxyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}

