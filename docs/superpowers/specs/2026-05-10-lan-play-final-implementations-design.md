# Design Spec - Switch LAN Play Bridge Final Implementations

## 1. Overview
This document outlines the final implementations for the Switch LAN Play Bridge project. The goal is to enable full redirection of network traffic from a Nintendo Switch (connected via hotspot) to a remote Lan-Play server, including UI controls for server configuration and improved logging.

## 2. Components

### 2.1 UI Updates (`activity_main.xml` & `MainActivity.kt`)
- **Additions:**
    - `EditText` for server address (host:port).
    - `Button` for connecting (starting the redirection).
    - `TextView` for connection status.
- **Persistence:**
    - Save the server address in `SharedPreferences` to persist across app restarts.
- **Logic:**
    - Validate the address format before starting the service.
    - Pass the address as an extra in the `Intent` used to start `LanPlayVpnService`.

### 2.2 Server Redirection (`ServerRedirector.kt`)
- **Responsibility:** Handles UDP communication with the remote Lan-Play server.
- **Key Features:**
    - `DatagramSocket` for sending/receiving.
    - `vpnService.protect(socket)` to bypass the VPN tunnel for redirector traffic.
    - **Encapsulation:** Wraps raw IP packets in the Lan-Play protocol header (Magic: `0x11451400`, Type: `CONNECT`).
    - **Decapsulation:** Removes the Lan-Play header from incoming server packets to retrieve the raw IP packet.
    - **Threading:** Runs a background thread for receiving packets from the server.

### 2.3 VPN Integration (`LanPlayVpnService.kt` & `RawPacketSniffer.kt`)
- **LanPlayVpnService:**
    - Initializes `ServerRedirector` with the provided server address.
    - **Outbound:** In the `tun0` read loop, every packet read is passed to `ServerRedirector.forwardToServer()`.
    - **Inbound:** A callback from `ServerRedirector` writes received raw IP packets back into the `tun0` `FileOutputStream`.
- **RawPacketSniffer:**
    - Updated to capture ICMP (1), TCP (6), and UDP (17).
    - Filters for the `10.13.0.0/16` subnet.
    - Formats logs as: `[PROTOCOLO] IP_ORIGEM:PORTA -> IP_DESTINO:PORTA`.

### 2.4 Hotspot Management (`HotspotManager.kt`)
- **Changes:**
    - Hardcode the reported SSID (`Switch-Lan`) and Password (`12345678`) in the UI logs.
    - This hides the actual system-generated credentials and provides a consistent instruction for the user.

## 3. Data Flow
1. **Switch** sends a packet to the **Android Hotspot**.
2. **VpnService** captures the packet via `tun0`.
3. **RawPacketSniffer** logs the packet if it belongs to the `10.13.0.0/16` subnet.
4. **ServerRedirector** wraps the packet in a Lan-Play header and sends it to the **Remote Server** via UDP.
5. **Remote Server** sends a response packet wrapped in a Lan-Play header.
6. **ServerRedirector** receives the UDP packet, unwraps it, and passes the raw IP packet to **LanPlayVpnService**.
7. **LanPlayVpnService** writes the packet back to `tun0`, making it available to the **Switch**.

## 4. Configuration Requirements (Switch)
- **IP:** `10.13.XX.YY` (e.g., `10.13.37.100`)
- **Mask:** `255.255.0.0`
- **Gateway:** `10.13.37.1`
- **DNS:** `8.8.8.8`
