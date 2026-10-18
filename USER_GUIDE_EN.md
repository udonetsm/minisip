# Comprehensive MiniSIP User Guide

---

## 1. Introduction & Core Architecture

**MiniSIP** is a high-performance Android application that integrates a **SIP/VoIP telephony client** with a **built-in encrypted tunneling client (IKEv2/IPsec VPN)**.

Engineered for mobile networks and resource efficiency:
- **SIP Voice Calls**: Connects to any standard PBX (Asterisk, FreePBX, Kamailio, 3CX, Yate, etc.) following RFC 3261 standards.
- **Native IKEv2/IPsec VPN**: Pure Java implementation of IKEv2 and ESP protocols running via Android's `VpnService` without requiring Root privileges.
- **Background Reliability**: Utilizes foreground services (`CallService` and `TunService`) to prevent OS process termination during lock-screen mode or Android Doze Mode.

---

## 2. System Permissions & Initial Setup

Upon initial launch and feature activation, MiniSIP requests the following permissions:

1. **Audio Recording (`RECORD_AUDIO`)**: Essential for capturing microphone input during active calls.
2. **Notifications (`POST_NOTIFICATIONS`)** *(Android 13+)*: Required for foreground service persistence and active status notifications.
3. **Battery Optimization Exclusion (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)**: Disabling battery optimization ensures the Android OS does not kill background SIP sockets or VPN keepalive timers.
4. **VPN Consent**: Tapping `VPN connect` triggers Android's standard `VpnService` permission dialog to create the virtual `tun0` network interface.
5. **Install Unknown Packages (`REQUEST_INSTALL_PACKAGES`)**: Allows the app to auto-update via GitHub Releases using the integrated `UpdateBar`.

---

## 3. Profile Card Management (ViewPager2)

MiniSIP organizes settings into horizontal profile cards. Each card maintains an independent configuration file.

### 3.1 Creating a New Profile Card
1. Fill in the mandatory fields of the active card: **Server** and **Login** in the SIP section.
2. Swipe left (navigate right to the next page).
3. Confirm the dialog: *"Create New Card. Are you sure you want to create another settings card?"*.
4. Tap **Yes** — a new settings page with its own configuration file (`cfg1`, `cfg2`, etc.) will be created.

### 3.2 Reordering Cards (Drag & Drop)
1. Touch and hold the header of any card block (SIP, Call, VPN) until the card turns semi-transparent (`alpha = 0.2`).
2. Drag the block up or down.
3. Release to apply — the custom order is stored under the `blockOrder` key in preferences.

### 3.3 Deleting a Profile Card
1. Scroll to the very bottom of the page and pull downward for 1 second.
2. The red **Delete Page 🗑** button appears.
3. Tap the button and confirm deletion. Subsequent profile cards shift left, clearing old preference files (`PageStore.deletePage`).

---

## 4. In-Depth SIP Telephony Guide

### 4.1 Configuring SIP Server Settings
In the **SIP server settings** block, enter:
- **Server**: PBX IP address or domain (e.g., `192.168.1.100` or `sip.company.com:5060`). Custom ports specified after a colon are parsed automatically.
- **Login**: Your extension or SIP username (e.g., `101`).
- **Password**: Your SIP account password.
- **SIP settings ▼ button**: Toggles advanced SIP parameters.

### 4.2 Registration & Status
- **Connect**: Sends a SIP `REGISTER` request.
- **Disconnect**: Unregisters the SIP account (`REGISTER (Expires: 0)`).
- Status Indicators:
  - 🔴 **Disconnected**: Offline.
  - 🟡 **Connecting...**: Performing Digest Authentication (401/407 Challenge).
  - 🟢 **Connected (Registered)**: Successfully registered with PBX.

### 4.3 In-Call Controls & Options
- **Number**: Main dial field formatted with a large font (35sp).
- **Call 📞 / Hangup 🚫**: Initiates or terminates calls. State transitions to `Calling...`, then `Talk`.
- **Mute (🤫 / 👄)**: Toggles microphone input. Active mute highlights the button with a red border.
- **Spk (📢 / 👂)**: Switches audio output between the loud speakerphone and the earpiece.
- **Refers (↪️)**: Call transfer directory.
  - Maintains a list of saved transfer contacts (Login + Name).
  - Tapping `↪️` next to a contact performs an instant **Blind Transfer** via RFC 3515 `REFER`.

### 4.4 Interactive Visual Call History
- Tap **History 📄** to expand the call log.
- Sessions are grouped chronologically.
- Each call features a color-coded event sequence:
  - 🟠 **Orange**: Dialing.
  - 🟡 **Yellow**: Ringing.
  - 🟢 **Green**: Connected (Call in progress).
  - 🟣 **Purple**: Transfer.
  - 🔴 **Red**: Ended (Hangup / Error).
- **Quick Dialing**: Long-pressing any call history session header automatically populates the destination number into the **Number** field!
- Tapping event dots displays detailed event info with a copy option.

---

## 5. Built-in IKEv2/IPsec VPN Guide

### 5.1 Main & Advanced VPN Parameters
In the **VPN settings (IKEv2)** section:
- **VPN settings ▼**:
  - **VPN server**: VPN gateway address.
  - **VPN login**: User login (EAP / MS-CHAPv2).
  - **VPN password**: User password.
  - **Apps using VPN**: Split tunneling configuration.
- **Advanced VPN settings ▼**:
  - **VPN pre-shared key**: Pre-Shared Key (PSK) for key-based authentication.
  - **VPN CA certificate (PEM)**: PEM-encoded Root CA Certificate.
  - **Healthcheck address**: Host address for ICMP/HTTP probes. Domain names trigger background DNS resolution before connection.
  - **Ping interval (sec)**: Probe frequency in seconds (5 to 9 seconds, or 0 for default 5s).

### 5.2 Strict Mode (Kill-Switch)
- Located in the `⋮` menu of the VPN header.
- Enforcing Strict mode blocks all non-VPN network traffic during reconnections, preventing leaks. During active SIP calls, Strict mode is enabled automatically.

### 5.3 Split Tunneling (Apps using VPN)
1. Tap **Apps using VPN**.
2. Select the applications that should route traffic through the VPN.
3. Tap **OK**. All unselected apps will use direct network connectivity.

---

## 6. Advanced Gestures, Logging & Updates

### 6.1 Update Channels & Gesture Shortcuts
- **Long Press Version Label vX.Y.Z (2 seconds)**: Opens the Update Channel selector (**Stable** or **Pre-release**) with an instant **Check** button.
- **`⋮` Menu on Version Label**: Displays the update engine log (`updateLog`).
- **UpdateBar**: Displays a top notification bar with a download progress bar and an install trigger when new GitHub releases are found.

### 6.2 Log Area Controls
Each block contains an independent log console (**SIP Log**, **VPN Log**, **Call Log**, **Update Log**):
- **Enable / Disable**: Toggles real-time log output.
- **▲ / ▼**: Scrolls to top or bottom.
- **Copy**: Copies log text to clipboard.
- **Save**: Exports log text to an external file.
- **Clear**: Flushes the active log buffer.

---

## 7. Frequently Asked Questions & Troubleshooting

### ❓ One-Way Audio Issues
- **Cause**: ISP RTP port blocking or NAT restrictions.
- **Solution**: Enable **VPN connect**. Encapsulating SIP and RTP traffic inside IPsec ESP packets resolves NAT traversal issues completely.

### ❓ SIP Registration Error 401 / 407
- **Cause**: Incorrect Login or Password.
- **Solution**: Verify credentials in the SIP server settings block.

### ❓ Frequent VPN Disconnections
- **Cause**: Unstable mobile signal or short DPD timeouts.
- **Solution**: Increase **Ping interval** in Advanced VPN settings to `9` seconds.
