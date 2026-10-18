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

## 2. Detailed Package & Class Specifications

### 2.1 `ru.minisip.app` Package (UI & Services)

#### [`MainActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/MainActivity.java)
- **Pager Management**: Uses `ViewPager2` backed by the `Pages` adapter.
- **Dynamic Card Creation**: Scrolling to `position >= PageStore.count()` triggers `showCustomConfirmDialog()`. Upon user confirmation, `PageStore.count` increments in `SharedPreferences`, updating `ViewPager2`.
- **Lock-Screen Flags**: `updateLockScreenFlags()` invokes `setShowWhenLocked(active)` and `setTurnScreenOn(active)` to display active or incoming calls over Android's lock screen.

#### [`SettingsFragment.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/SettingsFragment.java)
- **Code-Based UI Construction**: Entire UI is built programmatically in `onCreateView()` using Material Design 3 components.
- **Interactive Call History (`updateCallHistory`)**: Groups log events into chronological sessions, renders color-coded sequence dots (`getStatusColor`: Orange = Dial, Yellow = Ringing, Green = Connected, Purple = Transfer, Red = Ended/Error), supports scrolling, event copying, and populates the dial field via long press.
- **VPN App Picker (`pickApps`)**: Scans installed packages via `PackageManager.getInstalledPackages()` (subject to `<queries>` in `AndroidManifest.xml`) to construct split tunneling package lists.
- **Call Transfer Window (`showRefersDialog`)**: Builds an interactive list of transfer contacts (`PageStore.Refer`) with editable Login and Name fields, add/delete controls, and instant blind transfer execution (`↪️`).
- **Card Drag & Drop (`setupCardDragAndDrop`)**: Handles block dragging with `alpha = 0.2` transparency and saves custom layout order to `SharedPreferences` under the `blockOrder` key.
- **Delete Page Trigger (`revealDeleteRunnable`)**: Monitors downward pull gesture at the bottom of `NestedScrollView` for 1 second to reveal the hidden **Delete Page 🗑** button.

#### [`PageStore.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/PageStore.java)
- **Preference Storage**: `prefs(c, page)` returns `cfg` for page 0, and `cfg1`, `cfg2`, etc., for subsequent pages.
- **Page Deletion (`deletePage`)**: When a profile is deleted, shifts subsequent `SharedPreferences` files left by 1 position, clearing the last file and decrementing `count`.

#### [`CallService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/CallService.java)
- **Foreground Service**: Runs with `foregroundServiceType="microphone"`, launching a high-priority system notification to guarantee audio recording and playback when the app is backgrounded.

---

### 2.2 `ru.minisip.sip` Package (VoIP Signaling)

#### [`SipImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java)
Implements the Finite State Machine (FSM) for SIP (RFC 3261):

```
[UNREG] ---> (REGISTER sent) ---> [REGISTRING] ---> (401/407 + Auth Header) ---> [REGISTRING] ---> (200 OK) ---> [REGISTERED]
                                                                                                                       |
[IDLE] <--- (BYE / 200 OK) <--- [TALK] <--- (200 OK + ACK) <--- [RINGING] <--- (180 Ringing) <--- (INVITE sent) <-------+
```

1. **Registration Flow (`reregister()`)**:
   - Constructs an unauthenticated `REGISTER` request.
   - Handles `401 Unauthorized` or `407 Proxy Authentication Required` responses containing `WWW-Authenticate` headers.
   - Extracts `nonce`, `realm`, `qop`, and invokes `Digest` to calculate response hashes.
   - Re-sends `REGISTER` with `Authorization: Digest username=..., realm=..., nonce=..., uri=..., response=...`.
   - Schedules re-registration at half of `Expires` duration upon receiving `200 OK`.

2. **Outgoing Call Flow (`call()`)**:
   - Generates random `Call-ID` and `Via` branch identifiers.
   - Builds SDP payload offering PCMU/PCMA codecs and local RTP ports via `Sdp.build()`.
   - Sends `INVITE`. Plays ringback tone upon receiving `180 Ringing`. Parses remote SDP from `200 OK`, starts `Media.start()`, and transmits `ACK`.

3. **Call Transfer Flow (`transfer()`)**:
   - Constructs SIP `REFER` requests (RFC 3515) with `Refer-To: <sip:target@host>`.

#### [`Digest.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Digest.java)
Computes HTTP Digest Authentication (RFC 7616 / RFC 2617):
$$\text{HA1} = \text{MD5}(\text{username} : \text{realm} : \text{password})$$
$$\text{HA2} = \text{MD5}(\text{method} : \text{digestURI})$$
$$\text{Response} = \text{MD5}(\text{HA1} : \text{nonce} : \text{nc} : \text{cnonce} : \text{qop} : \text{HA2})$$

#### [`Sdp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sdp.java)
Parses and generates SDP session descriptions (RFC 4566).

---

### 2.3 `ru.minisip.media` Package (Audio & Codecs)

#### [`MediaImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/MediaImpl.java)
Runs two background worker threads:
1. **Tx Thread**: Pulls PCM samples (8000 Hz 16-bit mono) from `AndroidAudio`, encodes them into G.711 (PCMA/PCMU) 160-byte packets (20ms audio), prepends a 12-byte RTP header (RFC 3550), and transmits via `rtpUdp`.
2. **Rx Thread**: Polls incoming RTP packets from `rtpUdp`, verifies sequence numbers, extracts payloads, decodes G.711 into linear PCM, and streams audio to `AndroidAudio`.

#### [`AndroidAudio.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/AndroidAudio.java)
- Encapsulates `AudioRecord` and `AudioTrack`.
- Initializes hardware `AcousticEchoCanceler` and `NoiseSuppressor` effects.
- `spk(boolean)` handles speakerphone toggling via `AudioManager.setSpeakerphoneOn()` and `MODE_IN_COMMUNICATION`.

---

### 2.4 `ru.minisip.vpn` Package (IKEv2 / IPsec VPN)

#### [`TunService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/TunService.java)
- Subclasses `android.net.VpnService`.
- Configures virtual interface `tun0` via `VpnService.Builder`, setting IP/mask, MTU (1400), and `addAllowedApplication` for split tunneling.
- Reads raw IP packets from `ParcelFileDescriptor` TUN interface and passes them to `Tunnel.java`.

#### [`Ike.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Ike.java) & [`Tunnel.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Tunnel.java)
Complete user-space implementation of IKEv2 (RFC 7296) and ESP (RFC 4303):
1. **IKE_SA_INIT**: Diffie-Hellman public key exchange (`DhGroup.java`), nonces, master key `SKEYSEED` and `SK_e`/`SK_a` derivation.
2. **IKE_AUTH**: Encrypted payload exchange carrying EAP MS-CHAPv2 (`Mschap.java`) or X.509 certificate (`Certs.java`) authentication, traffic selector (`TSi`/`TSr`) negotiation, and `CHILD_SA` establishment.
3. **ESP Handling (`Esp.java`)**: AES-CBC / AES-GCM encryption and HMAC-SHA1/SHA256 integrity verification.

---

### 2.5 `ru.minisip.screen` Package (Screen Control)

#### [`ScreenImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen/ScreenImpl.java)
- Leverages `PROXIMITY_SCREEN_OFF_WAKE_LOCK` to dim the screen when placed near the ear.
- Holds `PARTIAL_WAKE_LOCK` (`cpuLock`) to prevent CPU sleep during active calls.
- Uses `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` to wake up the screen during incoming/answered calls.

---

### 2.6 `ru.minisip.update` & `ru.minisip.log` Packages

#### [`UpdateImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/UpdateImpl.java)
- Executes HTTP queries against GitHub Releases API (`Http.java`).
- Compares `published_at` release timestamps against the installed application's `lastUpdateTime`.
- Deletes stale cache APK files on app startup (`cleanUpdates`).
- Verifies `pm.canRequestPackageInstalls()`. If missing, launches `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, then triggers installation via `FileProvider` (`content://${applicationId}.fileprovider`).

#### [`LogBufferImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogBufferImpl.java)
- Thread-safe fixed-size ring buffer (500 entries) with listener callbacks and `SharedPreferences` persistence support.

---

## 3. System Requirements & RFC Specifications

### 3.1 Supported RFC Standards
- **SIP**: RFC 3261 (Core SIP), RFC 3515 (SIP REFER), RFC 4566 (SDP), RFC 7616 / 2617 (HTTP Digest Auth).
- **Media**: RFC 3550 (RTP), ITU-T G.711 (PCMA/PCMU).
- **VPN / IPsec**: RFC 7296 (IKEv2), RFC 4303 (ESP), RFC 2759 (MS-CHAPv2).

### 3.2 Runtime Requirements
- **Android SDK**: Min SDK 24 (Android 7.0), Target SDK 34 (Android 14).
- **Memory**: Fixed ring buffers prevent OOM memory leaks.
- **Network Permissions**: Includes `<queries>` with `MAIN/LAUNCHER` filter for VPN app selection compatibility on Android 11+.
