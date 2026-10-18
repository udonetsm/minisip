# MiniSIP Developer Documentation

## 1. Architectural Overview & Composition Root
MiniSIP is structured following clean architectural principles with strict interface decoupling.
- **Composition Root**: [`App`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java) (`ru.minisip.app.App`) extends `Application`, implements `Sip.Listener` and `Vpn.Listener`, and serves as the single centralized container for core engine instances: [`Udp`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/Udp.java), [`Media`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/Media.java), [`Sip`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sip.java), [`Screen`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen/Screen.java), and [`Vpn`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Vpn.java).
- **Single Connection Ownership**: The application enforces that only one settings page/card can own the active SIP registration, call, or VPN session at any time (`App#lastPage`, `App#busy()`). Other pages cannot interfere while a session is active.

---

## 2. Package Breakdown & Module Logic

### 2.1 `ru.minisip.app`
- [`MainActivity`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/MainActivity.java): Host activity hosting a `ViewPager2` card pager (`Pages`), lock-screen flag management (`setShowWhenLocked`, `setTurnScreenOn`), and custom confirmation dialogs.
- [`SettingsFragment`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/SettingsFragment.java): Monolithic UI fragment rendering input fields, action buttons, dynamic logs (`LogArea`), call history viewer, and VPN app selection (`pickApps`).
- [`CallService`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/CallService.java): Foreground service ensuring audio calls remain active in the background (`foregroundServiceType="microphone"`).
- [`PageStore`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/PageStore.java): Manages the number of active configuration cards stored in `SharedPreferences`.

### 2.2 `ru.minisip.sip` (SIP Stack)
- [`Sip`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sip.java) & [`SipImpl`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java): Implements SIP signaling (RFC 3261), handling registration (`REGISTER`), call initiation/termination (`INVITE`, `BYE`, `CANCEL`), and call transfers (`REFER`).
- [`Msg`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Msg.java): Parses and builds SIP headers and message bodies.
- [`Digest`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Digest.java): Computes HTTP Digest Authentication hashes (MD5 / SHA-256) per RFC 7616 / 2617.
- [`Sdp`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sdp.java): Constructs and parses Session Description Protocol (SDP, RFC 4566) payloads for audio media negotiation.

### 2.3 `ru.minisip.media` (Audio & Codecs)
- [`Media`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/Media.java) & [`MediaImpl`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/MediaImpl.java): Manages RTP audio streaming.
- [`AndroidAudio`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/AndroidAudio.java): Interfaces with Android `AudioRecord` and `AudioTrack` APIs, handling microphone input and speaker/earpiece output.
- [`G711`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/G711.java): Implements G.711 codec encoding/decoding (PCMA / PCMU).

### 2.4 `ru.minisip.net` (Networking)
- [`Udp`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/Udp.java) & [`UdpImpl`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/UdpImpl.java): Provides UDP socket wrappers.
- [`AndroidBinder`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/AndroidBinder.java): Protects sockets against VPN routing loops and binds sockets to specific network interfaces or TUN interfaces.

### 2.5 `ru.minisip.vpn` (IKEv2 / IPsec VPN)
- [`Vpn`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Vpn.java) & [`VpnImpl`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/VpnImpl.java): High-level VPN controller.
- [`TunService`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/TunService.java): Android `VpnService` subclass establishing a virtual TUN network interface without root privileges.
- [`Tunnel`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Tunnel.java) & [`Ike`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Ike.java): Complete user-space IKEv2 protocol implementation (IKE_SA_INIT, IKE_AUTH, Child SAs).
- [`Esp`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Esp.java): Encapsulating Security Payload packet encapsulation/decapsulation.
- [`Mschap`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Mschap.java): MS-CHAPv2 authentication protocol (RFC 2759).
- [`NetWatch`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/NetWatch.java): Monitors network health and handles reconnection heuristics.

### 2.6 `ru.minisip.update` & `ru.minisip.log`
- [`UpdateImpl`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/UpdateImpl.java): Fetches release metadata from GitHub Releases and triggers APK installation via `FileProvider`.
- [`LogBufferImpl`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogBufferImpl.java): Thread-safe circular buffer storing live logs for UI display.

---

## 3. Project Limitations & Development Requirements
1. **Concurrency & Threading**: All UI interactions must occur on the main thread (`Looper.getMainLooper()`). Network and crypto operations run on dedicated background worker threads.
2. **Foreground Services**: SIP calls and VPN tunnels require explicit Android foreground service types (`microphone` and `specialUse`) to prevent OS process termination in background.
3. **Network Change Handling**: `ConnectivityManager.NetworkCallback` monitors default network changes, triggering socket re-creation and SIP re-registration (`App#onNetwork`).
4. **Memory Management**: Ring log buffers have a fixed capacity (e.g., 500 entries) to prevent OOM errors during extended runtime.
