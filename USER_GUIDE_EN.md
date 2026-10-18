# Comprehensive MiniSIP User Guide

---

## 1. Introduction & Overview

**MiniSIP** is a compact, high-performance Android application that integrates a **SIP/VoIP telephony client** with an **encrypted tunneling client (IKEv2/IPsec VPN)**.

Designed for privacy, high reliability, and resource efficiency:
- **Secure Voice Calls**: Connects to corporate or public PBX servers (Asterisk, FreePBX, Kamailio, 3CX, Yate, etc.) via the SIP protocol.
- **Built-in VPN (IKEv2/IPsec)**: Leverages Android's native `VpnService` API without requiring root privileges. Encrypts all device traffic or selected app traffic.
- **Zero Third-Party Dependencies**: The entire SIP and IPsec stack is implemented natively in Java using standard Android APIs.
- **Background Persistence**: Employs foreground services to prevent OS process termination during lock-screen mode or Android Doze mode.

---

## 2. System Permissions & First-Time Setup

Upon first launch or feature activation, MiniSIP requests the following Android permissions:

1. **Audio Recording (`RECORD_AUDIO`)**: Required to capture microphone audio during phone calls.
2. **Notifications (`POST_NOTIFICATIONS`)** *(Android 13+)*: Required to display active call status and VPN connectivity in the system notification shade.
3. **Battery Optimization Exclusion (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)**: MiniSIP prompts you to disable battery optimization. This is essential to prevent Android from killing background SIP sockets and VPN keepalive timers.
4. **VPN Connection Consent**: Tapping `VPN Connect` for the first time triggers Android's standard VPN dialog asking for confirmation. Tap "OK" or "Trust".
5. **App Installation (`REQUEST_INSTALL_PACKAGES`)**: Required for seamless auto-updating via GitHub Releases APK packages.

---

## 3. UI Architecture & Profile Card Management

MiniSIP uses a **card-based interface** with horizontal swiping (`ViewPager2`). Each card represents an independent configuration profile containing its own SIP and VPN settings.

### 3.1 Creating a New Profile Card
1. Fill in the mandatory fields of the current card: **Server** and **Login** in the SIP block.
2. Swipe the screen to the left (navigate right to the next page).
3. The app displays a confirmation dialog: *"Create New Card. Are you sure you want to create another settings card?"*.
4. Tap **Yes** — a new blank configuration card will be created and appended to the pager.

### 3.2 Reordering Cards (Drag & Drop)
1. Touch and hold the card header block.
2. Drag the card up or down to change its display order.
3. Release the header — the new order is saved automatically.

### 3.3 Deleting a Profile Card
1. Swipe or tap the delete action on the target card.
2. Confirm deletion in the modal dialog.
3. *Note*: If only one card remains, it cannot be deleted.

---

## 4. Step-by-Step SIP Telephony (VoIP) Guide

### 4.1 Configuring SIP Account Settings

In the **SIP Server** block, configure:
- **Server**: IP address or domain name of your PBX (e.g., `192.168.1.100` or `sip.company.com`).
- **Port**: Network port of the SIP server. Default is `5060` (UDP).
- **Login**: Your SIP extension or username (e.g., `101`).
- **Pass**: Your SIP account password.
- **Number**: Default target destination number for quick dialing.

### 4.2 Connecting & Registration Status
- Tap the **Connect** button.
- The status indicator reports the current state:
  - 🔴 **Disconnected**: Offline.
  - 🟡 **Connecting...**: Sending `REGISTER` request and performing authentication.
  - 🟢 **Connected (Registered)**: Successfully registered with PBX. Ready to make/receive calls.

### 4.3 Making & Answering Calls

#### Outgoing Call:
1. Enter the destination extension/number in the **Number** field.
2. Tap the blue **Call** button.
3. Status changes to `Calling...`, playing ringback tone. Once answered, audio streaming starts and status changes to `Talk`.

#### Incoming Call:
1. When a call arrives, the device screen wakes up automatically (even if locked) displaying the incoming call dialog.
2. Tap **Answer** to accept or **Decline** to reject.

### 4.4 In-Call Controls
- **Spk (Speakerphone)**: Toggles audio output between the loud speakerphone and the earpiece.
- **Mute (Microphone)**: Mutes your microphone input so the remote party cannot hear you.
- **Refers (Call Transfer)**:
  1. Tap **Refers** during an active call.
  2. Enter the target transfer destination extension.
  3. Choose transfer type (blind/attended) and tap **Send REFER**. The call is transferred, releasing your line.
- **Hangup**: Tap the red **Hangup** button to end the active call.

### 4.5 Call History Management
- Tap **History** to open the call log panel.
- Each call session is shown in a collapsible card displaying:
  - Call direction (incoming/outgoing).
  - Remote party number/extension.
  - Timestamp and call duration.
  - Call outcome status (success, busy, declined, error).
- Tap **Clear History** at the bottom to erase the log.

---

## 5. Step-by-Step Built-in VPN Guide (IKEv2/IPsec)

### 5.1 Configuring VPN Settings

In the **VPN Connection** block, enter:
- **VPN Server**: Gateway IP address or domain name (e.g., `vpn.company.com`).
- **VPN Login**: VPN username (EAP / MS-CHAPv2).
- **VPN Pass**: VPN user password.
- **VPN Key (PSK)**: Pre-Shared Key (if required by your gateway).
- **VPN CA**: PEM-encoded Root CA Certificate string (if using self-signed gateway certificates).

### 5.2 Advanced VPN Settings
- **Healthcheck URL**: HTTP URL used to verify tunnel connectivity (e.g., `http://10.0.0.1/ping`).
- **Ping Interval**: Interval in seconds between healthcheck probes (default is `10`).

### 5.3 Split Tunneling (`Apps` Button)
Route specific app traffic through the VPN while keeping other traffic on direct connection:
1. Tap the **Apps** button.
2. Check the applications that should route traffic through the VPN.
3. Tap **Save**. All unselected apps will bypass the VPN tunnel.

### 5.4 VPN Connection & Monitoring
- Tap **VPN Connect**.
- Confirm Android's system VPN prompt on first use.
- Live status indicators:
  - 🔴 **VPN: disconnected**: Inactive.
  - 🟡 **VPN: connecting...**: Negotiating IKE_SA_INIT and IKE_AUTH phases.
  - 🟢 **VPN: connected (tun0)**: Active tunnel. Shows interface name (`tun0`), packet loss counter (`Loss`), and reconnect count (`Reconnects`).

---

## 6. Real-Time Logging & Diagnostics

MiniSIP features real-time circular log consoles:
1. **SIP Log**: Displays raw SIP message traffic (`REGISTER`, `INVITE`, `200 OK`, `401 Unauthorized`, etc.).
2. **VPN Log**: Records IKEv2 handshakes, key derivation, ESP decryption, and NetWatch events.
3. **Call Log**: Tracks user actions and audio media state transitions.
4. **Update Log**: Logs GitHub release update checks and download status.

*Log Console Options*:
- **Auto-scroll**: Automatically scrolls to new entries.
- **Save Log**: Exports the log buffer to a text file.
- **Clear Log**: Flushes the active log buffer.

---

## 7. Automatic Application Updates

1. MiniSIP checks GitHub Releases for new APK versions.
2. When an update is detected, the **UpdateBar** appears at the top displaying the release tag (e.g., `v1.2.4`).
3. Tap the download button to start downloading the APK.
4. Once downloaded, Android's package installer launches automatically.

---

## 8. Frequently Asked Questions & Troubleshooting

### ❓ One-Way Audio Issues
- **Cause**: NAT traversal restriction on the SIP server or ISP RTP port blocking.
- **Solution**: Enable **VPN Connect**. Tunneling hides SIP and RTP inside IPsec UDP encapsulation, resolving NAT issues.

### ❓ SIP Registration Error 401 / 407
- **Cause**: Incorrect Login or Password.
- **Solution**: Recheck your credentials in the SIP Server block.

### ❓ VPN Reconnects Frequently
- **Cause**: Unstable mobile network or short DPD timeout.
- **Solution**: Increase **Ping Interval** in Advanced VPN to `20`-`30` seconds.
