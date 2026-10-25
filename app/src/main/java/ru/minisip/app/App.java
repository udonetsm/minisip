package ru.minisip.app;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Handler;
import android.content.Context;
import android.os.Looper;
import android.service.quicksettings.TileService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ru.minisip.log.LogBuffer;
import ru.minisip.media.Media;
import ru.minisip.net.Udp;
import ru.minisip.screen.Screen;
import ru.minisip.sip.Sip;
import ru.minisip.update.Update;
import ru.minisip.vpn.Vpn;

/**
 * Composition root: the single place where component implementations are assembled.
 * All packages communicate exclusively via Udp, Media, Sip, Screen, and Vpn interfaces.
 */
public final class App extends Application implements Sip.Listener, Vpn.Listener {

    static final int IDLE = 0, CALLING = 1, TALK = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    // UI state; modify only on the main thread
    int state = IDLE;
    boolean registered = false;
    boolean connecting = false;
    String status = "🔴 Disconnected";
    boolean vpnUp = false;
    boolean vpnConnecting = false;
    int vpnAttempts = 0;
    String vpnIface = "tun0";
    String vpnStatus = "🔴 VPN: disconnected";
    Runnable onChange;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    void addChangeListener(Runnable r) {
        changeListeners.add(r);
    }

    void removeChangeListener(Runnable r) {
        changeListeners.remove(r);
    }

    boolean isCallActive() {
        return state != IDLE;
    }
    /**
     * Settings page that last managed the connection. SIP stack and VPN are shared app-wide,
     * so while an operation is active (VPN, registration, call), it is owned by one page while
     * other pages are blocked. The state variables above belong to this active page.
     */
    int lastPage = -1;
    private Sip sip;
    private Media media;
    private Screen screen;
    private Vpn vpn;
    private Udp sipUdp, rtpUdp;            // SIP and RTP sockets: bound to VPN interface
    private LogBuffer vpnLog;              // VPN log: single instance per app, held in memory
    private LogBuffer callLog;             // Call log: Sip.Listener events and user actions
    private LogBuffer sipLog;              // SIP log: registration, calls, server responses
    private Update update;                 // Update checker and installer from GitHub Releases
    private LogBuffer updateLog;           // Update log: single instance per app
    private String sipHost, sipUser, sipPass;
    private int sipPort;
    private short vpnLossCount = 0;
    private int vpnLossTotal = 0;           // Loss denominator: fixed at the time of loss event
    private short vpnReconnectCount = 0;
    /**
     * SIP was active when VPN dropped: reopen upon return (or final disconnect).
     */
    private boolean sipResume;
    private volatile String vpnReason = "";
    /**
     * While VPN is connected, periodically updates the status string every 2 seconds.
     */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (!vpnUp) return;
            // vpnUp remains true during reconnection to reflect connection state
            vpnStatus = vpnAttempts == 0 ? "🟢 VPN: connected (" + vpnIface + ")" + counters() : reconStatus();
            changed();
            main.postDelayed(this, 2000);
        }
    };

    private static String cap99(int v) {
        return v > 99 ? "99+" : String.valueOf(v);
    }

    /**
     * True if a call, PBX registration, or VPN session is currently active or connecting.
     */
    boolean busy() {
        return state != IDLE || registered || connecting || vpnUp || vpnConnecting;
    }

    /**
     * True if the specified settings page is allowed to control the connection.
     */
    boolean allowed(int page) {
        return !busy() || lastPage == page;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sipUdp = Udp.create(this);
        rtpUdp = Udp.create(this);
        media = Media.create(rtpUdp, this);
        sip = Sip.create(sipUdp, media);
        sip.setListener(this);
        screen = Screen.create(this);
        vpn = Vpn.create(this);
        vpn.setListener(this);
        vpnLog = LogBuffer.create(500);
        vpn.setLogSink(vpnLog);
        callLog = LogBuffer.create(500);
        SharedPreferences callLogPrefs = getSharedPreferences("minisip", MODE_PRIVATE);
        callLog.load(callLogPrefs.getString("callLog", ""));
        callLog.setPersist(() -> callLogPrefs.edit().putString("callLog", callLog.text()).apply());
        sipLog = LogBuffer.create(500);
        sip.setLogSink(sipLog);
        updateLog = LogBuffer.create(500);
        update = Update.create(this);
        update.setLogSink(updateLog);
        try {                                // system default network changed: reopen SIP socket
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network n) {
                            main.post(App.this::onNetwork);
                        }
                    });
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Ring buffer log for VPN displayed on the settings page.
     */
    LogBuffer vpnLog() {
        return vpnLog;
    }

    /**
     * Ring buffer log for Call events displayed on the settings page.
     */
    LogBuffer callLog() {
        return callLog;
    }

    /**
     * Ring buffer log for SIP server signaling displayed on the settings page.
     */
    LogBuffer sipLog() {
        return sipLog;
    }

    // ---------- UI commands (main thread) ----------

    /**
     * Ring buffer log for update checks and installations.
     */
    LogBuffer updateLog() {
        return updateLog;
    }

    /**
     * Update manager for GitHub Releases checks and installation.
     */
    Update update() {
        return update;
    }

    // ---------- VPN (main thread) ----------

    /**
     * Handles default network switches. Reopens SIP socket immediately.
     */
    private void onNetwork() {
        if (sipHost == null || vpnUp || vpnConnecting) return;
        if (state != IDLE) sip.hangup();
        sipResume = true;                    // even if registration failed independently
        reregister();
        changed();
    }

    void connect(int page, String host, int port, String user, String pass) {
        if (!allowed(page)) return;
        lastPage = page;
        if (!vpnUp) {                        // no local VPN: strictly system network stack
            sipUdp.bind(null);
            rtpUdp.bind(null);
        }
        sipHost = host;
        sipPort = port;
        sipUser = user;
        sipPass = pass;
        registered = false;
        connecting = true;
        status = "🟡 Connecting...";
        changed();
        sip.register(host, port, user, pass);
    }

    void disconnect(int page) {
        if (lastPage != page) return;
        sipHost = null;
        sipResume = false;
        registered = false;
        connecting = false;
        status = "🔴 Disconnected";
        sip.unregister();
        changed();
    }

    /**
     * Toggles VPN from the Quick Settings tile without opening any activity.
     * Uses App.lastPage (falls back to page 0 on a fresh install). Requires granted
     * VPN consent and a configured profile on that page. Returns a user-facing message
     * on failure, or null on success (the tile then reflects state via changed()).
     */
    String toggleVpnFromTile(Context ctx) {
        if (vpnUp || vpnConnecting) {
            if (lastPage < 0) return "VPN: nothing to disconnect";
            vpnDisconnect(lastPage);
            return null;
        }
        if (state != IDLE) return "MiniSIP: call in progress";
        int page = lastPage >= 0 ? lastPage : PageStore.vpnPage(ctx);
        if (page < 0 || !PageStore.vpnConfigured(ctx, page)) {
            page = PageStore.firstConfiguredVpnPage(ctx);
        }
        if (page < 0) page = 0;
        return connectVpnPage(ctx, page);
    }

    /**
     * Connects the VPN profile on the specified page. Returns a user-facing
     * message on failure, or null on success.
     */
    String connectVpnPage(Context ctx, int page) {
        if (vpnUp || vpnConnecting) return "VPN: already connected";
        if (state != IDLE) return "MiniSIP: call in progress";
        if (page < 0 || !PageStore.vpnConfigured(ctx, page)) return "VPN: page not configured";
        if (!allowed(page)) return "MiniSIP: busy on another page";
        if (VpnService.prepare(ctx) != null) return "Open MiniSIP once to grant VPN permission";
        SharedPreferences p = PageStore.prefs(ctx, page);
        String host = p.getString("vpnServer", "").trim();
        String login = p.getString("vpnLogin", "").trim();
        String pass = p.getString("vpnPass", "");
        String key = p.getString("vpnKey", "");
        String ca = p.getString("vpnCa", "");
        String apps = p.getString("vpnApps", "");
        String hc = p.getString("vpnHealthcheck", "").trim();
        int pingSec;
        try {
            pingSec = Integer.parseInt(p.getString("vpnPingInterval", "").trim());
        } catch (NumberFormatException e) {
            pingSec = 5;
        }
        if (pingSec < 5 || pingSec > 9) pingSec = 5;
        vpnConnect(page, host, login, pass, key, ca, apps, hc, pingSec);
        return null;
    }

    /**
     * True if VPN permission is already granted; false if system dialog is shown.
     */
    boolean vpnConsent(Activity a, int requestCode) {
        return vpn.consent(a, requestCode);
    }

    void vpnConnect(int page, String host, String login, String password, String psk, String ca, String apps, String healthcheckIp, int pingIntervalSec) {
        if (state != IDLE || vpnUp || vpnConnecting || !allowed(page)) return;
        lastPage = page;
        vpnConnecting = true;
        vpnStatus = "🟡 VPN: connecting...";
        changed();
        PageStore.setVpnPage(this, page);
        vpn.connect(host, login, password, psk, ca, apps, healthcheckIp, pingIntervalSec);
    }

    void vpnConnect(int page, String host, String login, String password, String psk, String ca, String apps, String healthcheckIp) {
        vpnConnect(page, host, login, password, psk, ca, apps, healthcheckIp, 5);
    }

    void vpnConnect(int page, String host, String login, String password, String psk, String ca, String apps) {
        vpnConnect(page, host, login, password, psk, ca, apps, null, 5);
    }

    void vpnDisconnect(int page) {
        if (lastPage != page) return;
        vpnAttempts = 0;
        vpn.disconnect();
    }

    String vpnDpd() {
        String s = vpn.dpdState();
        return s == null || s.isEmpty() ? "unknown" : s;
    }

    boolean vpnMobike() {
        return vpn.mobike();
    }

    int getVpnLossCount() {
        return vpnLossCount;
    }

    void vpnDenied(int page) {
        if (!allowed(page)) return;
        lastPage = page;
        vpnAttempts = 0;
        vpnStatus = "🔴 VPN: permission denied";
        changed();
    }

    void vpnError(int page, String error) {
        if (!allowed(page)) return;
        lastPage = page;
        vpnAttempts = 0;
        vpnStatus = error.startsWith("🔴") || error.startsWith("🟡") || error.startsWith("🟢") ? error : "🔴 " + error;
        changed();
    }

    /**
     * Reopens SIP registration to align with current network route.
     */
    private void reregister() {
        if (sipHost == null || !(registered || connecting || sipResume)) return;
        sipResume = false;
        registered = false;
        connecting = true;
        status = "🟡 Connecting...";
        sip.register(sipHost, sipPort, sipUser, sipPass);
    }

    @Override
    public void onUp(String iface) {
        main.post(() -> {
            vpnIface = iface;
            vpnConnecting = false;
            vpnUp = true;
            vpnAttempts = 0;                 // reset attempt counter to 0 on successful reconnect
            String cnt = counters();
            vpnStatus = "🟢 VPN: connected (" + iface + ")" + cnt;
            main.removeCallbacks(statusTick);
            main.postDelayed(statusTick, 2000);
            sipUdp.bind(iface);              // SIP and RTP routed strictly through tunnel
            rtpUdp.bind(iface);
            reregister();
            changed();
        });
    }

    /**
     * Loss | Recon and event statistics without reconnection (Mig | Heal | Silence | Kept).
     */
    private String counters() {
        int lost = vpnLossCount, total = Math.max(lost, vpnLossTotal);
        String c = " | Loss: " + cap99(lost) + "/" + cap99(total)
                + " | Recon: " + (vpnReconnectCount > 99 ? "99+" : vpnReconnectCount);
        String x = vpn.extraStats();
        return x == null || x.isEmpty() ? c : c + " | " + x;
    }

    /**
     * Formats status during reconnection; handles "no network" state separately.
     */
    private String reconStatus() {
        String att = vpnAttempts > 99 ? ">99" : String.valueOf(vpnAttempts);
        boolean noNet = "waiting for network".equals(vpnReason);
        return (noNet ? "🔴 VPN: no network, waiting" : "🟡 VPN: reconnecting (attempt " + att + ")") + counters();
    }

    @Override
    public void onReconnecting(String reason, int attempt) {
        main.post(() -> {
            if (!vpnUp) return;
            boolean newCycle = vpnAttempts == 0;          // new reconnection cycle
            vpnAttempts = attempt;
            if (newCycle) {
                vpnReconnectCount = (short) Math.min(vpnReconnectCount + 1, 32767);
            }
            vpnReason = reason == null ? "" : reason;
            vpnStatus = reconStatus();
            sipUdp.bind(null);               // local VPN is down: route SIP and RTP via system stack
            rtpUdp.bind(null);
            if (sipHost != null && (registered || connecting)) {
                reregister();                // send registration via system stack
                sipResume = true;            // reopen via tunnel when VPN recovers
            }
            changed();
        });
    }

    @Override
    public void onDown(String reason) {
        main.post(() -> {
            vpnAttempts = 0;                 // reset counter to 0 on VPN disconnect
            boolean isManual = reason != null && (reason.toLowerCase().contains("disconnected") || reason.toLowerCase().contains("user"));
            if (isManual) {
                vpnLossCount = 0;
                vpnLossTotal = 0;
                vpnReconnectCount = 0;
            }
            boolean isTakeover = reason != null &&
                    (reason.toLowerCase().contains("taken over") || reason.toLowerCase().contains("another vpn"));

            if (isTakeover) {
                if (state != IDLE) sip.hangup();
                sipHost = null;
                sipResume = false;
                registered = false;
                connecting = false;
                status = "🔴 Disconnected";
                sip.unregister();

                vpnUp = false;
                vpnConnecting = false;
                vpnStatus = "🔴 VPN: disconnected (" + reason + ")";
                vpn.disconnect();

                sipUdp.bind(null);
                rtpUdp.bind(null);
                sipUdp.close();
                rtpUdp.close();

                changed();
                return;
            }

            boolean was = vpnUp;
            vpnConnecting = false;
            vpnUp = false;
            vpnStatus = "🔴 VPN: disconnected (" + reason + ")";
            sipUdp.bind(null);               // back to primary system stack
            rtpUdp.bind(null);
            if (was) {
                if (state != IDLE) sip.hangup();   // call over dropped tunnel cannot be preserved
                reregister();
            }
            changed();
        });
    }

    void dial(int page, String number) {
        if (state != IDLE || !allowed(page)) return;
        lastPage = page;
        callLog.log("dial " + number);
        if (!registered) {
            status = "🔴 Error: no network";
            changed();
            return;
        }
        begin(CALLING, "🟢 Connected | 🟡 Calling...");
        sip.call(number);
    }

    /**
     * Sets audio output route: true for speakerphone, false for earpiece.
     */
    void setSpeaker(boolean on) {
        media.setSpeaker(on);
    }

    /**
     * Sets microphone mute state: true to mute, false to unmute.
     */
    void setMute(boolean mute) {
        media.setMute(mute);
    }

    boolean isMuted() {
        return media.isMuted();
    }

    void hangup(int page) {
        if (state == IDLE || lastPage != page) return;
        callLog.log("hangup (state=" + state + ")");
        sip.hangup();
    }

    /**
     * Blind transfers active call to target (username or SIP URI).
     */
    void transfer(int page, String target) {
        if (state != TALK || lastPage != page) return;
        String t = target == null ? "" : target.trim();
        if (t.isEmpty()) return;
        callLog.log("transfer → " + t);
        sip.transfer(t);
    }

    /**
     * Initiates call state: starts foreground service and proximity sensor.
     */
    private void begin(int newState, String text) {
        state = newState;
        status = text;
        vpn.setStrict(true);
        startForegroundService(new Intent(this, CallService.class));
        screen.start();
        changed();
    }

    private void finish(String text) {
        state = IDLE;
        vpn.setStrict(false);
        if (registered) {
            status = "🟢 Connected | 🔴 " + text;
        } else {
            status = text.startsWith("🔴") || text.startsWith("🟡") || text.startsWith("🟢") ? text : "🔴 " + text;
        }
        screen.stop();
        stopService(new Intent(this, CallService.class));
        changed();
    }

    private void changed() {
        if (onChange != null) onChange.run();
        for (Runnable r : changeListeners) {
            r.run();
        }
        TileService.requestListeningState(this, new ComponentName(this, VpnTileService.class));
    }


    @Override
    public void onRekey(String info) {
        main.post(() -> {
            vpnLog.log(info);
            changed();
        });
    }

    @Override
    public void onPingLoss(int lostCount) {
        main.post(() -> {
            vpnLossCount = (short) Math.min(lostCount, 32767);
            // update denominator together with numerator; reset (0) clears both
            vpnLossTotal = lostCount == 0 ? 0 : vpn.pingEvaluated();
            String cnt = counters();
            if (vpnAttempts > 0) {
                vpnStatus = reconStatus();
                changed();
            } else if (vpnUp) {
                vpnStatus = "🟢 VPN: connected (" + vpnIface + ")" + cnt;
                changed();
            }
        });
    }

    // ---------- SIP events ("sip" thread -> main thread) ----------

    @Override
    public void onRegistered(boolean ok, String info) {
        main.post(() -> {
            callLog.log("registered=" + ok + (ok ? "" : " (" + info + ")"));
            registered = ok;
            connecting = false;
            status = ok ? "🟢 Connected" : "🔴 Error: " + info;
            changed();
        });
    }

    @Override
    public void onRinging() {
        main.post(() -> {
            callLog.log("ringing");
            if (state == CALLING) {
                status = registered ? "🟢 Connected | 🟡 Calling..." : "🟡 Calling...";
                changed();
            }
        });
    }

    @Override
    public void onConnected() {
        main.post(() -> {
            callLog.log("call connected");
            state = TALK;
            status = "🟢 Connected | 🟢 Call in progress";
            changed();
        });
    }

    @Override
    public void onEnded(String reason) {
        main.post(() -> {
            callLog.log("call ended: " + reason);
            finish("Ended: " + reason);
        });
    }
}
