# 📱 MiniSIP — User Guide

**MiniSIP** is a lightweight, intuitive Android application for secure internet phone calls (SIP telephony) and encrypted VPN tunneling (IKEv2/IPsec).

The app is completely free of background clutter, optimizes battery usage, and lets you set up connectivity with your PBX in a matter of seconds.

---

## 📑 Table of Contents
1. [Key Features](#1-key-features)
2. [Configuring SIP Telephony](#2-configuring-sip-telephony)
3. [Making and Managing Calls](#3-making-and-managing-calls)
4. [Call Transfer (Refers)](#4-call-transfer-refers)
5. [Configuring and Connecting VPN (IKEv2)](#5-configuring-and-connecting-vpn-ikev2)
6. [Managing Profile Pages and Block Order](#6-managing-profile-pages-and-block-order)
7. [Viewing and Saving Logs](#7-viewing-and-saving-logs)
8. [App Updates](#8-app-updates)

---

## 1. Key Features

- **SIP Telephony**: Connects to corporate and public IP PBXs (Asterisk, FreePBX, 3CX, etc.) using the G.711 codec (PCMA/PCMU).
- **Built-in IKEv2 VPN**: Secure tunnel supporting passwords, pre-shared keys (PSK), CA certificates, and auto-reconnection.
- **App Traffic Routing**: Selectively route other Android apps through the encrypted VPN tunnel.
- **Blind Call Transfer (Refers)**: Instantly transfer active calls to contacts in your Refers list.
- **Multi-Profile Management**: Create and switch between multiple independent configuration pages.
- **Customizable Layout**: Reorder settings blocks via intuitive Drag-and-Drop.

---

## 2. Configuring SIP Telephony

To configure your SIP account, locate the **"SIP server settings"** block:

1. Tap **"SIP settings ▼"** to expand the input fields.
2. Enter your account credentials:
   - **Server**: IP address or domain name of your PBX (e.g., `192.168.1.100` or `pbx.example.org`). If your PBX uses a non-standard port, append it after a colon: `pbx.example.org:5060`.
   - **Login**: Your extension number or SIP username (e.g., `101`).
   - **Password**: Your SIP account password.
3. Tap **"Connect"**.
4. Check the status indicator:
   - 🟡 **Connecting...** — connecting to the SIP server.
   - 🟢 **Connected** — successfully registered and ready to make calls.
   - 🔴 **Error...** — registration failed (check your credentials and server address).

To unregister from the PBX, tap **"Disconnect"**.

---

## 3. Making and Managing Calls

The **"Number and voice source"** block is used for dialing and audio routing:

### Making a Call:
1. Enter the destination number in the large **"Number"** field.
2. Tap the blue **"📞 Call"** button.
3. Grant microphone permission when prompted by Android on the first call.
4. During an active call, the status will show 🟢 **Call in progress**.
5. To end the call, tap the red **"🚫 Discard"** button.

### Managing Audio Options:
- **📢 Speaker / 👂 Earpiece**: Switch between hands-free speakerphone and earpiece speaker. The active output is highlighted with a green outline.
- **🤫 Mute mic / 👄 Unmute mic**: Mute or unmute your microphone during a call.

---

## 4. Call Transfer (Refers)

The **Refers** feature allows you to perform an instant blind transfer of an active call to another extension.

### Setting Up Refers Contacts:
1. In the SIP block, tap **"Refers"**.
2. Tap **"Add"** to create a new contact row.
3. Fill in the **Login** (extension number) and **Name** (display label).
4. All entries are saved automatically.

### Transferring an Active Call:
1. During an active call, tap **"Refers"**.
2. Locate the desired contact and tap the transfer icon **↪️**.
3. Confirm the transfer prompt.
4. The call will immediately be transferred to the selected contact.

---

## 5. Configuring and Connecting VPN (IKEv2)

The **"VPN settings (IKEv2)"** block manages your encrypted VPN tunnel connection.

### Basic Configuration:
1. Tap **"VPN settings ▼"**.
2. Enter your connection details:
   - **VPN server**: Server IP address or domain.
   - **VPN login**: Your VPN username (e.g., `user@example.org`).
   - **VPN password**: Your VPN password.
3. Tap **"VPN connect"**. Grant the Android system VPN permission on first use.

### Advanced VPN Options:
Tap **"Advanced VPN settings ▼"** for additional configuration:
- **VPN pre-shared key**: Pre-shared secret key (PSK) if password authentication is not used.
- **VPN CA certificate (PEM)**: Root CA certificate in PEM format (optional).
- **Healthcheck address**: IP address or domain for tunnel health probing.
- **Apps using VPN**: Select which installed apps route their traffic through the VPN.

### VPN Status Indicators:
- 🟢 **VPN: connected (tun0)** — tunnel is active and connected.
- 🟡 **VPN: reconnecting...** — automatic reconnection during network transitions.
- Status bar metrics:
  - **Loss**: count of lost healthcheck probe packets.
  - **Recon**: total reconnection attempts count.
  - **MOBIKE**: status of seamless Wi-Fi / cellular interface migration.
  - **DPD**: server dead-peer-detection state.

---

## 6. Managing Profile Pages and Block Order

### Multi-Profile Setup:
- You can create separate profile pages for different environments (e.g., "Office", "Home", "Travel").
- Swipe left or right to switch between profiles.
- Scroll to the bottom of the page and hold the **"Delete Page 🗑"** button to remove a profile.

### Reordering Blocks (Drag-and-Drop):
- You can change the vertical order of the settings cards ("SIP server settings", "Number and voice source", "VPN settings").
- Long-press any card for 1 second and drag it up or down to your preferred position. Layout order is saved automatically.

---

## 7. Viewing and Saving Logs

Each settings block features an embedded log viewer for troubleshooting:

1. Tap the three dots **⋮** in the upper right corner of any block and select **"Log"**.
2. Tap **"Enable"** in the log panel to activate live logging.
3. Available log actions:
   - **Clear**: Clear current log contents.
   - **Copy**: Copy all log text to the system clipboard.
   - **Save**: Save log text to a file in your device's `Downloads/MiniSIP/logs` folder.

---

## 8. App Updates

The current app version is displayed at the bottom of the screen (e.g., `v1.0.0`).

### Selecting Update Channel:
1. Press and hold the version text for 2 seconds.
2. Select your preferred channel:
   - **Stable**: Official production releases.
   - **Pre-release**: Beta versions with early access features.
3. Tap **"Check"** to scan for updates.

When an update is found, a progress bar will appear at the top of the main screen with an **"Install"** button.

---
*Thank you for using MiniSIP!*
