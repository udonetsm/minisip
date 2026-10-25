# MiniSIP Developer Documentation

---

## 1. Architectural Overview & Design Patterns

MiniSIP is implemented using a declarative separation of concerns architecture, where the entire functional stack is decomposed into independent modules bound via Java interfaces.

```
                    +----------------------------------+
                    |             App.java             |
                    |    (Composition Root, Context)   |
                    +----------------------------------+
                      /       |         |        |    \
                     /        |         |        |     \
                    v         v         v        v      v
             +-------+  +-------+  +------+  +-----+  +-------+
             | Udp   |  | Media |  | Sip  |  | Vpn |  |Update |
             +-------+  +-------+  +------+  +-----+  +-------+
                 |          |          |        |         |
                 v          v          v        v         v
             UdpImpl   MediaImpl   SipImpl   VpnImpl  UpdateImpl
                            |                   |
                     AndroidAudio           TunService / Tunnel
```

### 1.1 Composition Root (`App.java`)
The [`App`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java) class acts as the application singleton (`Application`), serving as the central coordinator for data flow and implementing listeners:
- `Sip.Listener`: Handles SIP engine state changes (registration status updates, incoming calls, hangup events).
- `Vpn.Listener`: Handles IKEv2/IPsec tunnel status updates (connection, drops, packet loss statistics).

### 1.2 Single Active Connection Rule
To prevent state race conditions and recursive routing conflicts, MiniSIP enforces a single connection owner rule:
- `App.lastPage` holds the index of the active `SettingsFragment` page.
- `App.busy()` returns `true` if a call, SIP registration, or VPN session is active.
- `App.allowed(int page)` blocks connection attempts from other pages while the current page is busy.

---

## 2. Comprehensive Method Inventory & Class Specifications

### 2.1 `ru.minisip.app` Package (UI & Services)

#### [`App.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java)
- `void addChangeListener(Runnable r)`: Registers a UI state change listener.
- `void removeChangeListener(Runnable r)`: Unregisters a UI state change listener.
- `boolean isCallActive()`: Returns true if call state is not IDLE.
- `boolean busy()`: Returns true if SIP registration, call, or VPN session is active/connecting.
- `boolean allowed(int page)`: Checks if settings page has exclusive connection rights.
- `void onCreate()`: Initializes UDP sockets, media engine, SIP stack, VPN, log buffers, and network callback.
- `LogBuffer vpnLog()`: Returns VPN ring buffer log.
- `LogBuffer callLog()`: Returns call event ring buffer log.
- `LogBuffer sipLog()`: Returns SIP signaling ring buffer log.
- `LogBuffer updateLog()`: Returns update checker ring buffer log.
- `Update update()`: Returns update manager instance.
- `String toggleVpnFromTile(Context ctx)`: Toggles the app-wide VPN from the Quick Settings tile without opening any activity. Returns a user-facing error message on failure, or `null` on success.
- `String connectVpnPage(Context ctx, int page)`: Connects the VPN profile configured on the given settings page. Returns a user-facing error message on failure, or `null` on success. Used by `TileProxyActivity` when the tile picker is opened.
- `void connect(int page, String host, int port, String user, String pass)`: Initiates SIP registration.
- `void disconnect(int page)`: Unregisters SIP session.
- `boolean vpnConsent(Activity a, int requestCode)`: Requests VPN permission consent.
- `void vpnConnect(...)`: Establishes IKEv2/IPsec VPN tunnel.
- `void vpnDisconnect(int page)`: Disconnects VPN tunnel.
- `String vpnDpd()`: Returns DPD status string.
- `boolean vpnMobike()`: Returns MOBIKE support status.
- `boolean isVpnStrict()`: Returns strict watchdog mode flag.
- `void setVpnStrict(boolean on)`: Configures strict watchdog mode.
- `int getVpnLossCount()`: Returns ping loss count.
- `void vpnDenied(int page)`: Handles VPN permission denial.
- `void vpnError(int page, String error)`: Handles VPN error state.
- `void onUp(String iface)`: VPN listener callback on tunnel established.
- `void onReconnecting(String reason, int attempt)`: VPN listener callback on reconnection attempt.
- `void onDown(String reason)`: VPN listener callback on tunnel disconnection.
- `void dial(int page, String number)`: Initiates outgoing SIP call.
- `void setSpeaker(boolean on)`: Toggles speakerphone audio route.
- `void setMute(boolean mute)`: Toggles microphone mute.
- `boolean isMuted()`: Returns microphone mute state.
- `void hangup(int page)`: Terminates active call or cancels outgoing call.
- `void transfer(int page, String target)`: Executes blind transfer (REFER).
- `void onRekey(String info)`: VPN listener callback on IPsec SA rekey.
- `void onPingLoss(int lostCount)`: VPN listener callback on healthcheck packet loss.
- `void onRegistered(boolean ok, String info)`: SIP listener callback on registration status.
- `void onRinging()`: SIP listener callback on remote ringback (180/183).
- `void onConnected()`: SIP listener callback on call answered (200 OK).
- `void onEnded(String reason)`: SIP listener callback on call termination.

#### [`CallService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/CallService.java)
- `int onStartCommand(Intent intent, int flags, int startId)`: Starts foreground service with microphone type.
- `IBinder onBind(Intent intent)`: Returns null (not bound).

#### [`MainActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/MainActivity.java)
- `static void showCustomConfirmDialog(...)`: Displays styled confirmation dialog with custom buttons.
- `void onCreate(Bundle b)`: Initializes ViewPager2 settings cards, update bar, and permission requests.
- `void pagesChanged()`: Refreshes ViewPager2 adapter page count.
- `void pageDeleted(int pageToDelete)`: Deletes specified settings page and resets adapter.
- `void onRequestPermissionsResult(...)`: Handles audio record permission results.
- `void onActivityResult(...)`: Forwards VPN permission consent result to fragment.

#### [`PageStore.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/PageStore.java)
- `static SharedPreferences prefs(Context c, int page)`: Returns page-specific SharedPreferences.
- `static int count(Context c)`: Returns total created settings pages.
- `static void setCount(Context c, int n)`: Saves total settings page count.
- `static boolean configured(Context c, int page)`: Checks if server and login are configured.
- `static List<Refer> refers(Context c, int page)`: Reads transfer contacts list.
- `static void setRefers(...)`: Saves transfer contacts list.
- `static void deletePage(Context c, int pageToDelete)`: Deletes profile page and shifts remaining preference files.
- `Refer#Refer(String login, String name)`: Constructor for transfer contact.
- `String Refer#label()`: Returns display name or login.

#### [`SettingsFragment.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/SettingsFragment.java)
- `static SettingsFragment create(int page)`: Factory method creating fragment instance.
- `void onCreate(Bundle b)`: Loads page index and preferences.
- `View onCreateView(...)`: Programmatically constructs Material 3 UI layout for SIP, Call, and VPN settings.
- `void onPause()`: Unregisters log listeners and App change callback.
- `void onVpnConsent(boolean granted)`: Handles VPN permission grant.
- `String appVersion()`: Returns package version name.
- `void showChannelDialog()`: Displays update channel selection dialog.
- `void doConnect()`: Parses server address/port and triggers SIP connect.
- `void doCall()`: Checks microphone permission and initiates dial.
- `void toggleMute()`: Toggles microphone mute state.
- `void doVpnConnect()`: Validates VPN fields and requests consent/start.
- `void vpnStart()`: Resolves healthcheck IP and starts VPN connection.
- `void pickApps()`: Displays split tunneling app selection multi-choice dialog.
- `void pickSpeaker(boolean speaker)`: Sets speakerphone preference.
- `void showRefersDialog()`: Displays interactive blind transfer contacts editor.
- `void render()`: Updates UI widget enabled states and text values based on connection state.
- `void save(TextInputEditText e, String key)`: Attaches text watcher for auto-saving preferences.
- `void load(TextInputEditText e, String key)`: Reloads field value from preferences.

#### [`UpdateBar.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/UpdateBar.java)
- `void attachInset(View v)`: Attaches target view for bottom padding insets.
- `void setVisibility(int v)`: Updates visibility and adjusts target view padding.
- `void showProgress(int percent, String tag)`: Displays APK download progress bar.
- `void showInstall(OnClickListener onInstall)`: Displays clickable install button.
- `void hide()`: Hides update bar.

#### [`TileProxyActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/TileProxyActivity.java)
- `void onCreate(Bundle b)`: Invisible proxy activity launched from the Quick Settings tile; chooses between picker mode (`EXTRA_PICK == true` or action `android.service.quicksettings.action.QS_TILE_PREFERENCES`) and direct toggle mode.
- `void onActivityResult(int req, int res, Intent data)`: Continues the flow once the system VPN consent dialog returns; if consent is denied, the activity closes.
- `void runToggle()`: Calls `App.toggleVpnFromTile` and finishes; a Toast shows a user-facing error message on failure.
- `void showPicker()`: Lists all configured VPN profiles (`PageStore.configuredVpnPages`) and connects the chosen one via `App.connectVpnPage`. If none are configured, shows a Toast and finishes.
- `EXTRA_PICK` (constant `"pick"`): When `true` — or when the incoming action is `QS_TILE_PREFERENCES` — the activity opens the profile picker instead of toggling directly.

#### [`VpnTileService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/VpnTileService.java)
- `void onStartListening()`: Refreshes the Quick Settings tile label and state (`STATE_ACTIVE` while `App.vpnUp || App.vpnConnecting`, otherwise `STATE_INACTIVE`).
- `void onClick()`: Quick Settings tile entry point.
  - If `VpnService.prepare(this) == null` (consent already granted) — calls `App.toggleVpnFromTile(this)` directly, keeping the QS panel open. The tile flips optimistically, then re-syncs after 1.5 s.
  - Otherwise — launches `TileProxyActivity` via `startActivityAndCollapse` so the system can display the VPN consent dialog (Android 14+ uses the `PendingIntent` overload).

#### Control, gesture and event reference (`ru.minisip.app`)

This table is the authoritative reference for every interactive control exposed by the `ru.minisip.app` package. It complements the per-class method lists above and should be kept in sync with `SettingsFragment.onCreateView`, `MainActivity` and `PageStore`.

##### SIP block

| Control | Trigger | Effect |
|---------|---------|--------|
| `Server` field | text input | Auto-saved to page preferences. Parsed by `SettingsFragment.doConnect()` as `host[:port]` (default port `5060`). |
| `Login` / `Password` fields | text input | Auto-saved. `Password` has a reveal-toggle end icon. |
| **SIP settings** expander | tap | Shows/hides `server`, `login`, `pass`; icon flips `ic_expand_more` ⇄ `ic_expand_less`. |
| **Connect** | tap | Validates `server` / `login`, then calls `App.connect(...)`. Disabled while another page owns the session or while a call is active. |
| **Disconnect** | tap | Calls `App.disconnect(page)`; enabled only while registered or connecting. |
| **Refers** | tap | Opens the blind-transfer contacts editor (`showRefersDialog`). |
| SIP log `Log` switch | toggle | Enables live mirroring of `App.sipLog()` into the log `EditText`. |
| SIP log save icon (`ic_save`) | tap | Dialog: **Copy to clipboard** or **Save to file** (`LogFile.save`). |
| SIP log clear icon (`ic_delete`) | tap | Confirmation dialog → `App.sipLog().clear()`. |
| SIP log `▲` / `▼` | tap | Scrolls the log `EditText` to top / bottom. |

##### Dialer block

| Control | Trigger | Effect |
|---------|---------|--------|
| `Number` field | text input | Dynamically resizes text between 10sp and 24sp (`fitNumberText()`). |
| `Number` field | long press 1 s | Clears the field (`clearNumberRunnable`). |
| **Call / Hangup** round button | tap | `App.state == IDLE` → `doCall()` (checks `RECORD_AUDIO`, applies per-page `speaker` / `muted` preferences, calls `App.dial`). Otherwise → `App.hangup(page)`. Icon flips between `ic_call` (blue) and `ic_call_end` (red) based on `App.state`. |
| **Mute** (`ic_mic` ⇄ `ic_mic_off`) | tap | Toggles `prefs.muted`; calls `App.setMute(...)` unless the active call belongs to another page. Button shape switches between outline pill and filled rounded-rect when muted (`applyToggleLook`). |
| **Spk** (`ic_volume_up`) | tap | Toggles `prefs.speaker`; calls `App.setSpeaker(...)` under the same page-ownership guard (`applySpkLook`). |
| Call history session header | tap | Expands/collapses the horizontal status-dot timeline. State is remembered per session key in `expandedSessions`. |
| Call history session header | long press | Copies the destination number into `Number` and toasts "Number inserted". |
| Call history copy icon (`ic_copy`) | tap | Copies the full session summary (`Number:`, then every event line) to the clipboard. |
| Call history dot | tap | Opens a dialog showing the raw event line with **Copy** / **Close** actions. |
| Call history `▲` / `▼` | tap | Smooth-scrolls the call history list (`smoothScrollTo`). |
| Call history save icon (`ic_save`) | tap | Dialog: **Copy to clipboard** or **Save to file**. |
| Call history clear icon (`ic_delete`) | tap | Confirmation dialog → `App.callLog().clear()`. |

##### VPN block

| Control | Trigger | Effect |
|---------|---------|--------|
| **VPN settings** expander | tap | Shows/hides main VPN fields plus **Apps using VPN** and **Advanced VPN settings**. |
| `VPN server` / `VPN login` / `VPN password` fields | text input | Auto-saved. `VPN password` has a reveal toggle. |
| **Advanced VPN settings** expander | tap | Shows/hides PSK, CA certificate, healthcheck address, ping interval fields. |
| `VPN pre-shared key` | text input | Optional PSK for IKEv2; reveal toggle enabled. |
| `VPN CA certificate (PEM)` | text input, multi-line (max 4) | Optional PEM CA bundle; empty → system trust store. |
| `Healthcheck address` | text input | IPv4 or hostname. Empty or `0` → healthcheck the VPN server itself. Domains are resolved to IPv4 in a background thread (`vpnStart`) before connecting. |
| `Ping interval (sec)` | numeric input | Allowed 5..9, or `0` (default 5). Out-of-range values abort the connection with a status message. |
| **Apps using VPN** | tap | Split-tunneling picker (`pickApps()`); label shows `MiniSIP + N` where N is the number of selected packages. |
| **VPN connect** | tap | Validates VPN fields, calls `App.vpnConsent(...)`; on consent, `vpnStart()` resolves the healthcheck (if domain) and calls `App.vpnConnect`. While connected the label is decorated with `MOBIKE: Active / Not supported` and `DPD: <state>`. |
| **VPN disconnect** | tap | Calls `App.vpnDisconnect(page)`. |
| VPN log switch / save / clear / `▲` / `▼` | as in SIP block | Same behaviour over `App.vpnLog()`. |

##### Bottom row, card management and app-level gestures

| Control / gesture | Trigger | Effect |
|------------------|---------|--------|
| Version label `vX.Y.Z` | long press ≥ 2 s | Opens the update channel selector (Stable / Pre-release) with **Check**. |
| Version label | movement ≥ 20 dp from center during long press | Cancels the channel-dialog timer. |
| Version label `Log` switch | toggle | Live update area over `App.updateLog()`. |
| **Delete Page** button | pull down at the very bottom for 1 s (`dispatchTouchEvent`, requires `PageStore.count > 1`) | Button fades in; tap → confirmation dialog → `MainActivity.pageDeleted(page)`. Hidden when only one page exists. |
| SIP / Call / VPN card header | long press | Starts drag-and-drop reorder (card alpha drops to 0.2). Order persisted under `blockOrder`. |
| Horizontal swipe to next page when the last card is configured | swipe | Triggers **Create New Card** dialog (`onPageSelected`); on **Yes** `PageStore.setCount` is called and the `Pages` adapter refreshes. |
| App `onCreate` | once | Registers `ConnectivityManager.registerDefaultNetworkCallback` → `App.onNetwork()` re-registers SIP on default-network switch. |
| Mic / notifications permissions | first launch | Requested once (`RECORD_AUDIO`, and on Android 13+ `POST_NOTIFICATIONS`). |
| Battery optimization | first launch after permissions | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt (`askBatteryOnce`), skips if already granted. |
| Quick Settings tile | Android 13+, first launch | `StatusBarManager.requestAddTileService` asks once (`tileOffered`) to add the MiniSIP VPN tile. |
| `CallService` | call state → `CALLING`/`TALK` | Foreground service started (`begin`); `FOREGROUND_SERVICE_TYPE_MICROPHONE` keeps audio alive on the lock screen. |
| Lock-screen flags | call active | `setShowWhenLocked` / `setTurnScreenOn` toggled by `appChangeListener`. |

---

### 2.2 `ru.minisip.log` Package (Logging)

#### [`LogSink.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogSink.java)
- `void log(String line)`: Writes log entry string.

#### [`LogBuffer.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogBuffer.java)
- `static LogBuffer create(int maxLines)`: Factory method for LogBufferImpl.
- `boolean isEnabled()`: Returns logging enabled state.
- `void setEnabled(boolean on)`: Enables or disables logging.
- `void clear()`: Clears buffer contents.
- `String text()`: Returns complete buffered log text.
- `void setListener(Runnable onChange)`: Sets change notification callback.
- `void load(String saved)`: Loads previously saved log text.
- `void setPersist(Runnable persist)`: Sets persistence callback.

#### [`LogBufferImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogBufferImpl.java)
- Implements all methods of `LogBuffer` with thread-safe `ArrayDeque` storage and timestamp formatting (`yyyy-MM-dd HH:mm:ss.SSS`).

#### [`LogFile.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogFile.java)
- `static String save(Context ctx, String prefix, String text)`: Saves text log to `Documents/MiniSIP/logs` via MediaStore API (Android 10+) or File API (legacy).

---

### 2.3 `ru.minisip.media` Package (Audio & RTP)

#### [`Audio.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/Audio.java)
- `boolean start()`: Starts audio hardware recording and playback.
- `int read(short[] buf)`: Reads PCM samples (blocking).
- `void write(short[] buf, int n)`: Writes PCM playback samples.
- `void stop()`: Stops audio hardware.
- `void setSpeaker(boolean on)`: Configures audio output route.
- `void setMute(boolean mute)`: Sets microphone mute.
- `boolean isMuted()`: Returns mute state.

#### [`AndroidAudio.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/AndroidAudio.java)
- Implements `Audio` using `AudioRecord`, `AudioTrack`, `AudioManager`, and communication device routing APIs with acoustic echo cancellation.

#### [`G711.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/G711.java)
- `static byte encode(int pt, short pcm)`: Encodes linear PCM into G.711 A-law (8) or u-law (0).
- `static short decode(int pt, byte b)`: Decodes G.711 A-law or u-law byte into linear PCM.

#### [`Media.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/Media.java)
- `static Media create(Udp rtpSocket, Context ctx)`: Factory method.
- `int open()`: Allocates local RTP UDP port.
- `boolean start(String host, int port, int payload)`: Starts RTP capture and playback threads.
- `void stop()`: Stops RTP threads and closes socket.

#### [`MediaImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/MediaImpl.java)
- Implements `Media` and `Udp.Listener`: manages `rtp-capture` thread (mic -> G.711 -> RTP packet) and `rtp-playback` thread (RTP packet -> G.711 -> speaker). Handles symmetric RTP.

---

### 2.4 `ru.minisip.net` Package (Network & UDP)

#### [`Binder.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/Binder.java)
- `boolean bind(DatagramSocket s, String iface) throws IOException`: Binds socket to network interface.

#### [`AndroidBinder.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/AndroidBinder.java)
- Implements `Binder` using `ConnectivityManager.getActiveNetwork()` and `Network.bindSocket()`.

#### [`Udp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/Udp.java)
- `static Udp create()`: Creates standard UDP socket instance.
- `static Udp create(Context ctx)`: Creates network-bound UDP socket instance.
- `int open(int port, Listener l)`: Opens UDP socket and starts background receiver thread (`udp-rx`).
- `boolean send(byte[] data, String host, int port)`: Transmits UDP datagram packet.
- `String localIp(String host, int port)`: Returns local IP for target destination.
- `void bind(String iface)`: Binds subsequent sockets to network interface (e.g. `tun0`).
- `void close()`: Closes UDP socket.
- `String error()`: Returns last error string.

#### [`UdpImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/UdpImpl.java)
- Implements `Udp` with asynchronous datagram packet reception and interface attachment.

---

### 2.5 `ru.minisip.screen` Package (Screen Control)

#### [`Screen.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen/Screen.java)
- `static Screen create(Context ctx)`: Factory method.
- `void start()`: Enables proximity wake lock and CPU partial wake lock.
- `void stop()`: Releases wake locks and unregisters proximity sensor listener.

#### [`ScreenImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen/ScreenImpl.java)
- Implements `Screen` and `SensorEventListener`: manages proximity screen dimming and screen wake actions.

---

### 2.6 `ru.minisip.sip` Package (SIP/SDP Protocol)

#### [`Digest.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Digest.java)
- `static String answer(...)`: Computes HTTP Digest MD5 authentication response string (RFC 2617 / RFC 7616).

#### [`Msg.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Msg.java)
- `static Msg parse(byte[] data)`: Parses raw SIP message bytes.
- `static String param(String hdr, String name)`: Extracts header parameter value.
- `static String uri(String nameAddr)`: Extracts URI from Name-Addr.
- `static String user(String nameAddr)`: Extracts user portion for caller ID.
- `static String eventName(String hdr)`: Extracts event name from Event header.
- `int status()`, `String reason()`, `String method()`, `String get(String)`, `List<String> all(String)`, `int cseq()`, `String key()`: SIP message introspection helpers.

#### [`Sdp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sdp.java)
- `static String make(String ip, int port, int... pts)`: Generates SDP session description for G.711 (PCMA/PCMU).
- `static String[] parse(String sdp)`: Parses remote SDP body and returns `{ip, port, payloadType}`.

#### [`Sip.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sip.java)
- `static Sip create(Udp signalling, Media media)`: Factory method.
- `void setListener(Listener l)`: Sets SIP state change listener.
- `void setLogSink(LogSink sink)`: Sets debug log sink.
- `void register(String host, int port, String user, String password)`: Registers SIP account.
- `void unregister()`: Unregisters SIP account.
- `void call(String number)`: Initiates outgoing SIP call.
- `void transfer(String target)`: Initiates blind transfer (REFER).
- `void hangup()`: Terminates active call or cancels invite.

#### [`SipImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java)
- Implements `Sip`: single-threaded SIP Finite State Machine running on dedicated `"sip"` thread with Digest authentication, SDP negotiation, and transaction retransmission timers.

---

### 2.7 `ru.minisip.update` Package (Auto-Update)

#### [`Http.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/Http.java)
- `static String get(String url)`: Executes HTTP GET request returning response string.
- `static void download(String url, File dst, Progress p)`: Downloads file with progress reporting.

#### [`Timestamps.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/Timestamps.java)
- `static long parseIso8601(String iso)`: Parses ISO 8601 date string into UTC milliseconds.

#### [`Update.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/Update.java)
- `static Update create(Context ctx)`: Factory method.
- `void setChannel(String channel)`: Sets update channel ("stable" or "prerelease").
- `void check(Listener l)`: Checks GitHub Releases for updates.
- `void download(Listener l)`: Downloads release APK.
- `void install()`: Launches system APK installer.

#### [`UpdateImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/UpdateImpl.java)
- Implements `Update`: background worker checking GitHub Releases API, downloading APKs, and managing FileProvider installations.

---

### 2.8 `ru.minisip.vpn` Package (IKEv2 / IPsec VPN)

#### [`Tun.java` & `AndroidTun.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/AndroidTun.java)
- Manages virtual TUN network interface (`tun0`) via `VpnService.Builder`, configuring IP addresses, DNS, split tunneling routes, and protected UDP sockets.

#### [`Certs.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Certs.java)
- Parses X.509 PEM/DER certificates, validates PKIX trust chains, checks SAN names and AUTH signatures (RFC 7296 / RFC 7427).

#### [`Crypto.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Crypto.java)
- Provides cryptographic primitives for IPsec/IKEv2: Diffie-Hellman Group 14 (MODP 2048), AES-CBC, AES-GCM-16, HMAC-SHA1/SHA256, PRF, and `Crypto.Box` encryption/decryption channels.

#### [`Esp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Esp.java)
- Implements IPsec ESP (RFC 4303) packet encapsulation, decapsulation, and anti-replay sliding window checking (`wrap`, `unwrap`).

#### [`Ike.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Ike.java)
- Implements IKEv2 protocol (RFC 7296): IKE_SA_INIT exchange, IKE_AUTH authentication (PSK and EAP-MSCHAPv2), MOBIKE (RFC 4555), and DPD.

#### [`Tunnel.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Tunnel.java)
- Manages active VPN session threads (`vpn`, `vpn-rx`, `vpn-tun`, `vpn-keep`, `vpn-mobike`), handling network health checks, keepalives, and automatic recovery.

#### [`VpnImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/VpnImpl.java)
- Orchestrates VPN lifecycle, user consent dialogs, wake locks, network change subscriptions, and automatic reconnects.

---

## 3. Supported RFC Standards & Runtime Requirements

### 3.1 Supported RFC Standards
- **SIP**: RFC 3261 (Core SIP), RFC 3515 (SIP REFER), RFC 4566 (SDP), RFC 7616 / 2617 (HTTP Digest Auth).
- **Media**: RFC 3550 (RTP), ITU-T G.711 (PCMA/PCMU).
- **VPN / IPsec**: RFC 7296 (IKEv2), RFC 4303 (ESP), RFC 2759 (MS-CHAPv2), RFC 4555 (MOBIKE).

### 3.2 Runtime Requirements
- **Android SDK**: Min SDK 24 (Android 7.0), Target SDK 34 (Android 14).
- **Memory**: Fixed ring buffers prevent OOM memory leaks.
- **Network Permissions**: Includes `<queries>` with `MAIN/LAUNCHER` filter for VPN app selection compatibility on Android 11+.
