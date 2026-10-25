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
2. The red **Delete Page** button appears (using vector icon `ic_delete`).
3. Tap the button and confirm deletion. Subsequent profile cards shift left, clearing old preference files (`PageStore.deletePage`).

---

## 4. In-Depth SIP Telephony Guide

### 4.1 Configuring SIP Server Settings
In the **SIP server settings** block, enter:
- **Server**: PBX IP address or domain (e.g., `192.168.1.100` or `sip.company.com:5060`). Custom ports specified after a colon are parsed automatically.
- **Login**: Your extension or SIP username (e.g., `101`).
- **Password**: Your SIP account password.
- **SIP settings button**: Toggles advanced SIP parameters using expand/collapse vector indicators (![Expand](docs/icons/ic_expand_more.svg) / ![Collapse](docs/icons/ic_expand_less.svg)).

### 4.2 Registration & Status
- **Connect**: Sends a SIP `REGISTER` request.
- **Disconnect**: Unregisters the SIP account (`REGISTER (Expires: 0)`).
- Status Indicators:
  - **Disconnected**: Offline state.
  - **Connecting...**: Performing Digest Authentication (401/407 Challenge).
  - **Connected (Registered)**: Successfully registered with PBX.

### 4.3 In-Call Controls & Options
- **Number**: Main dial field formatted with a large font.
- **Call / Hangup**: Initiates or terminates calls using vector drawables `ic_call` and `ic_call_end`. State transitions to `Calling...`, then `Talk`.
- **Mute**: Toggles microphone input using `ic_mic` and `ic_mic_off`. Active mute highlights the button with a red border.
- **Spk**: Switches audio output between the loud speakerphone and the earpiece using `ic_volume_up`.
- **Refers**: Call transfer directory.
  - Maintains a list of saved transfer contacts (Login + Name).
  - Tapping the transfer action (`ic_transfer`) next to a contact performs an instant **Blind Transfer** via RFC 3515 `REFER`.
  - Tapping the delete action (`ic_close`) removes the contact.

### 4.4 Interactive Visual Call History & Status Chains in the Dialer
MiniSIP features an advanced chronological call history log grouped into distinct call sessions with color-coded status chains:
- **Session Grouping Logic**: A new `dial` event initiates a new session only if the previous call session has already finished with a terminal event (`hangup`, `ended`, or `error`). Otherwise, consecutive events (`dial` -> `ringing` -> `connected` -> `hangup`) of a single call remain chained together in one continuous session.
- **Color-Coded Status Dots**: Each phase of a call is represented by a distinct color-coded status indicator dot:
  - **Orange (`#FF9800`)**: Dialing (`dial`)
  - **Yellow/Gold (`#FFD54F`)**: Ringing (`ringing`)
  - **Green (`#4CAF50`)**: Call Connected / In Progress (`connected`)
  - **Purple (`#9C27B0`)**: Call Transfer (`transfer`)
  - **Red (`#F44336`)**: Call Ended / Error / Hangup (`ended`, `error`)
- **Compact Stack Preview**: Each session header displays a stacked preview of status dots summarizing the call's progression.
- **Expandable Timeline**: Tapping the session header expands or collapses using vector indicators (![Expand](docs/icons/ic_expand_more.svg) / ![Collapse](docs/icons/ic_expand_less.svg)) to show a detailed horizontal timeline of all status events. Tapping any individual event dot opens a dialog displaying full event details with a **Copy** option.
- **Quick Dialing**: Long-pressing any call history session header automatically copies and inserts the destination phone number into the main **Number** dial field.
- **Session Copy & Save**: Use the copy button (![Copy](docs/icons/ic_copy.svg)) to copy the entire call session summary to the clipboard.

---

## 5. Built-in IKEv2/IPsec VPN Guide

### 5.1 Main & Advanced VPN Parameters
In the **VPN settings (IKEv2)** section:
- **VPN settings**:
  - **VPN server**: VPN gateway address.
  - **VPN login**: User login (EAP / MS-CHAPv2).
  - **VPN password**: User password.
  - **Apps using VPN**: Split tunneling configuration.
- **Advanced VPN settings**:
  - **VPN pre-shared key**: Pre-Shared Key (PSK) for key-based authentication.
  - **VPN CA certificate (PEM)**: PEM-encoded Root CA Certificate.
  - **Healthcheck address**: Probe address (IP or domain). Domains trigger background DNS resolution before connection. Empty or `0` — only the VPN server itself is checked, not a specific host reachable through it.
  - **Ping interval (sec)**: Probe frequency in seconds (5 to 9 seconds, or 0 for default 5s).

### 5.2 Deleted.

### 5.3 Split Tunneling (Apps using VPN)
1. Tap **Apps using VPN**.
2. Select the applications that should route traffic through the VPN.
3. Tap **OK**. All unselected apps will use direct network connectivity.

---

## 6. Advanced Gestures, Logging & Updates

### 6.1 Update Channels & Gesture Shortcuts
- **Long Press Version Label vX.Y.Z (1 second)**: Opens the Update Channel selector (**Stable** or **Pre-release**) with an instant **Check** button.
- **Toggle on the Version Row**: Enables the update engine log (`updateLog`).
- **UpdateBar**: When a new GitHub release is detected, an overlay bar appears at the bottom of the screen with a download progress bar and an install trigger.

### 6.2 Log Area Controls
Each block contains an independent log console (**SIP Log**, **VPN Log**, **Call Log**, **Update Log**):
- **Enable / Disable**: Toggles real-time log output.
- **Scroll Up / Down**: Vector arrow buttons (![Collapse](docs/icons/ic_expand_less.svg) / ![Expand](docs/icons/ic_expand_more.svg)).
- **Save**: Exports log text to an external file via dialog (![Save](docs/icons/ic_save.svg)).
- **Clear**: Flushes the active log buffer (![Delete](docs/icons/ic_delete.svg)).

---

## 7. Deleted.

---

## 8. Button, Gesture and Event Reference

This chapter is a single-source-of-truth cheat-sheet for every interactive element in the MiniSIP UI. It answers the question *"what exactly happens when I tap / hold / swipe this?"*.

### 8.1 SIP block

| Action | What it does |
|--------|--------------|
| Type in **Server** | Auto-saved to the current page's preferences. `host` or `host:port`; default port `5060`. |
| Type in **Login** / **Password** | Auto-saved. Tap the eye icon in **Password** to reveal the password. |
| Tap **SIP settings** | Expands/collapses the `Server`, `Login`, `Password` fields. The arrow icon flips. |
| Tap **Connect** | Validates `Server` and `Login`, then sends `REGISTER`. Greys out when another page owns the session or a call is in progress. |
| Tap **Disconnect** | Sends `REGISTER (Expires: 0)`. Enabled only while registered or connecting. |
| Tap **Refers** | Opens the blind-transfer contacts editor. |
| Toggle **Log** (SIP) | Enables live SIP signaling trace inside the log area. |
| Tap **save** (SIP log) | Dialog: **Copy to clipboard** or **Save to file** (`Documents/MiniSIP/logs`). |
| Tap **clear** (SIP log) | Asks for confirmation, then wipes the SIP log buffer. |
| Tap **▲ / ▼** (SIP log) | Scrolls the log text to top / bottom. |

### 8.2 Dialer block

| Action | What it does |
|--------|--------------|
| Type in **Number** | Font auto-shrinks between 10sp and 24sp to fit the field. |
| **Long-press Number for 1 s** | Clears the field. |
| Tap **Call / Hangup** | While idle: places the call (checks microphone permission, applies your `Spk` / `Mute` preferences). While in a call: hangs up. Icon turns red when a call is active. |
| Tap **Mute** | Toggles the microphone. Muted → rounded rectangle, colour changes. Unmuted → ellipse (pill) with an outline. |
| Tap **Spk** | Toggles speakerphone ⇄ earpiece. Speakerphone on → rounded rectangle, colour changes. Speakerphone off → ellipse (pill) with an outline. |
| Tap a **session header** in call history | Expands/collapses the horizontal status-dot timeline. |
| **Long-press a session header** | Copies the number of that session into the **Number** field and toasts "Number inserted". |
| Tap a **status dot** | Opens a dialog with the raw event line and **Copy** / **Close**. |
| Tap **copy icon** next to a session | Copies the whole session (number + every event) to clipboard. |
| Tap **▲ / ▼** under history | Smooth-scrolls the history list. |
| Tap **save icon** under history | Copy or save the call history. |
| Tap **clear icon** under history | Confirmation → wipes the call log. |

### 8.3 VPN block

| Action | What it does |
|--------|--------------|
| Tap **VPN settings** | Expands/collapses the main VPN fields plus **Apps using VPN** and **Advanced VPN settings**. |
| Tap **Advanced VPN settings** | Expands/collapses PSK, CA certificate, healthcheck, ping interval. |
| Type in **VPN pre-shared key** | Optional PSK. Leave empty to use EAP / MS-CHAPv2. |
| Type in **VPN CA certificate** | Optional PEM bundle. Empty → system trust store. |
| Type in **Healthcheck address** | IP or hostname. Empty or `0` → checks the VPN server itself. Domain names are resolved to IPv4 in the background before connecting. |
| Type in **Ping interval (sec)** | Allowed 5..9 or `0` (default 5). Out-of-range values abort connection. |
| Tap **Apps using VPN** | Opens split-tunneling picker. The label shows `MiniSIP + N` with the number of chosen apps. |
| Tap **VPN connect** | Validates the fields, requests VPN consent if needed, then connects. While connected the label shows `MOBIKE:` and `DPD:` state. |
| Tap **VPN disconnect** | Tears the tunnel down. |
| Toggle **Log** / save / clear / **▲ / ▼** (VPN) | Same as the SIP block, but for `vpnLog`. |

### 8.4 Cards and app-level gestures

| Gesture | What it does |
|---------|--------------|
| **Long-press version label `vX.Y.Z` for 2 s** | Opens the update channel selector (Stable / Pre-release) with a **Check** button. |
| **Move finger more than 20 dp away** during that long press | Cancels the channel dialog. |
| **Pull down at the very bottom of the scroll view for 1 s** | Reveals the red **Delete Page** button (only when more than one page exists). |
| **Long-press a card header (SIP / Call / VPN)** | Drags the card to reorder blocks. Order is saved in the page preferences. |
| **Swipe to the next page** when the last page is already configured | Opens the **Create New Card** dialog. |
| **Quick Settings tile** | Toggles VPN without opening the app. Long-press to open the profile picker (if you have more than one VPN profile configured). |
| First launch | System prompts ask for `RECORD_AUDIO`, `POST_NOTIFICATIONS`, battery-optimization exclusion, and (once) to add the MiniSIP VPN Quick Settings tile. |

### 8.5 Status colour legend (call history)

| Colour | Meaning |
|--------|---------|
| 🟧 Orange `#FF9800` | Dialing (`dial`) |
| 🟨 Yellow `#FFD54F` | Ringing (`ringing`) |
| 🟩 Green `#4CAF50` | Connected / Talk (`connected`) |
| 🟪 Purple `#9C27B0` | Transfer (`transfer`) |
| 🟥 Red `#F44336` | Ended / Error / Hangup (`ended`, `error`, `hangup`) |
