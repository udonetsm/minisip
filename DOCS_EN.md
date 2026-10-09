# 📖 Detailed MiniSIP User Manual

This manual is written in clear and accessible language for users of any skill level. It explains the purpose and usage of every button, input field, status indicator, and gesture in the **MiniSIP** application.

---

## 🌟 1. Application Overview

**MiniSIP** is a compact, high-performance Android application that combines two main features:
1. **Internet Calls (SIP Telephony)** — Allows you to connect to your corporate or home PBX server and make phone calls over IP.
2. **Secure Communication (IKEv2 VPN)** — Allows you to encrypt and route your internet traffic through your personal VPN server.

The user interface consists of card blocks arranged on scrollable pages.

---

## 🚦 2. Status Color Indicators (Traffic Light)

All connection and call statuses are accompanied by intuitive color indicators:

- `🔴` **Red Circle**:
  - **Disconnected** (`Disconnected`) — Connection is not established or was manually stopped.
  - **Error** (`Error: ...`) — Incorrect login, password, server address, or no internet.
  - **Call Ended** (`Ended: ...`) — Call finished or was rejected.
- `🟡` **Yellow Circle**:
  - **In Progress** (`Connecting...` / `Calling...`) — Registration request in progress, network check, or dialing recipient.
  - **Reconnecting** (`VPN: reconnecting`) — Temporary network drop, VPN automatically recovering connection.
- `🟢` **Green Circle**:
  - **Connected** (`🟢 Connected`) — Successfully registered on PBX or VPN tunnel is active.
  - **Dialing** (`🟢 Connected | 🟡 Calling...`) — Outgoing call in progress while registered on PBX.
  - **Active Call** (`🟢 Connected | 🟢 Call in progress`) — Recipient picked up, active phone call in progress.
  - **Call Ended** (`🟢 Connected | 🔴 Ended: ...`) — Call finished, returning to active PBX registration.

---

## 🏛 3. Block 1: PBX Settings (SIP Server Settings)

This block manages connection to your PBX phone server.

### Fields and Buttons:
* **Button `SIP settings ▼`**: Expands or hides the PBX server configuration spoiler.
* **Field `Server`**: Address of your PBX. Can be a domain name (`pbx.example.com`) or IP address with port (`192.168.1.100:5060`).
* **Field `Login`**: Your extension number or SIP account login (e.g. `101`).
* **Field `Password`**: Password for your SIP account.
* **Button `Connect`**: Sends registration request to the PBX server.
* **Button `Disconnect`**: Unregisters and disconnects from the PBX server.
* **Status Text**: Displays current PBX connection state (e.g. `🟢 Connected` or `🔴 Disconnected`).

> 💡 **Input Guard**: As soon as PBX is connected (`🟢 Connected`), `Server`, `Login`, and `Password` fields are automatically locked against accidental modifications. To edit settings, tap `Disconnect` first.

---

## 📞 4. Block 2: Number and Voice Source (Number and Voice Source)

This block is used for dialing phone numbers and controlling audio during calls.

### Fields and Buttons:
* **Field `Number`**: Large input field for the phone number you wish to call. Digits are automatically displayed in extra-large font (`35 sp`).
* **Button `Call`**: Initiates an outgoing call.
  - *Note*: If you are not connected to PBX (`🟢 Connected`), the `Call` button is disabled to prevent unnecessary battery drain.
* **Button `Discard`**: Ends or cancels the current call.
* **Button `Voice source`**: Switches audio output source on the fly:
  - `Earpiece` — Earpiece speaker (for holding phone to your ear).
  - `Speakerphone` — Loudspeaker / speakerphone mode.
* **Button `Mute microphone 🎤` / `Unmute microphone 🎙️`**: Instantly mutes or unmutes the microphone during a call. When muted, PCM silence frames are transmitted over RTP, ensuring the remote party hears complete silence.
  
### Smart Features During Calls:
* **Proximity Sensor**: Holding the phone to your ear automatically turns off the screen to prevent accidental cheek touches.
* **Music Pause (Audio Focus)**: Playing music or YouTube videos automatically pause when a call starts and resume after hanging up.
* **Number Locking**: During an active call, the phone number input field is locked.

---

## 🛡 5. Block 3: VPN Settings (VPN Settings IKEv2)

This block secures traffic for the entire device or selected applications.

### Main Settings:
* **Button `VPN settings ▼`**: Expands or hides main VPN settings.
* **Field `VPN server`**: IP address or domain of your VPN server.
* **Field `VPN login`**: Your VPN username.
* **Field `VPN password`**: Your VPN password.
* **Button `Apps using VPN`**: Opens the list of installed Android apps. Check individual apps whose traffic should be routed through the VPN (Split Tunneling).

### Advanced Settings (Nested Spoiler):
* **Button `Advanced VPN settings ▼`**: Expands secondary VPN parameters:
  * **`VPN pre-shared key`**: Shared PSK key (if your server uses key-based authentication instead of passwords).
  * **`VPN CA certificate (PEM)`**: Custom SSL/TLS certificate (if using a self-signed server certificate).
  * **`Healthcheck address`**: Address for checking tunnel connectivity (defaults to `google.com`). Sends raw ICMP ping packets every 2 seconds.
* **Buttons `VPN connect` / `VPN disconnect`**: Starts or stops the encrypted tunnel.
* **VPN Status Text**: Displays current VPN state (e.g. `🟢 VPN: connected (tun0)`).

> 🛡 **Network Reliability & Roaming**: To avoid false tunnel disconnects in subways or poor cell signal areas, VPN checks for 6 consecutive lost pings (12 seconds of silence) before sending DPD verification requests. It also fully supports **MOBIKE (RFC 4555)**: if your VPN server supports this standard, switching between Wi-Fi and mobile data instantly migrates the tunnel on the fly without dropping connection. If the server does not support MOBIKE, the app automatically performs a full reconnect through the new network interface. VPN settings can be edited even during an active SIP call.

---

## 🔀 6. Card Drag-and-Drop Reordering

You can customize the vertical order of blocks on your screen:
1. **Long-press** any of the three cards (`SIP server settings`, `Number and voice source`, `VPN settings`).
2. Without lifting your finger, drag the card up or down.
3. The new card order is saved automatically in phone memory for that page.

---

## 📄 7. Page Management (Multi-Account Support)

The app allows configuring multiple independent profiles (e.g., Work, Home, Travel).

### Creating a New Page:
- When the current page is configured, a new page slot appears on the right.
- Swipe right.
- A confirmation dialog appears: **`Create New Card`** (*"Are you sure you want to create another settings card?"*).
  - Tap **`Yes`** to create the new page.
  - Tap **`No`** to remain on the current page.

### Deleting a Page (Hold Gesture):
1. Scroll all the way to the bottom of the page.
2. Pull upward **anywhere on the screen** and hold for **1 second**.
3. A red button **`Delete Page 🗑`** smoothly appears at the bottom, and the screen automatically scrolls to it.
4. Tap **`Delete Page 🗑`**.
5. A confirmation dialog appears asking to confirm page deletion.
> ⚠️ **Note**: If only one page remains, page deletion is disabled.

### 🎨 Dialog Button Styling (`Yes` / `No`):
In all confirmation popup dialogs:
- **`No` Button** (Cancel) is styled in **bright bold blue** (`#2196F3`) to prevent accidental actions.
- **`Yes` Button** (Confirm) is styled in **dim gray** (`#888888`) with 20% translucency (`alpha = 0.80f`).
