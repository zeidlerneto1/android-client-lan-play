# Design Spec - UdpRelayServer Architecture

**Date:** 2026-05-10
**Status:** Approved
**Topic:** Transition from RawPacketSniffer to UdpRelayServer for handling Switch ldn_mitm traffic.

## 1. Executive Summary
The goal is to replace the current `RawPacketSniffer` (which only logs traffic) and the direct RAW packet forwarding in `LanPlayVpnService` with a more robust `UdpRelayServer`. This server will intercept UDP traffic destined for the Gateway IP (`10.13.37.1`) on any port, extract the payload, and relay it through the `ServerRedirector`. It will also manage the reverse path, sending responses back to the Switch using standard `DatagramSocket`s.

## 2. Architecture

### 2.1 Packet Interception (Packet-Driven)
Since binding to all UDP ports on Android is not possible with standard APIs, we use a hybrid approach:
1.  Read raw packets from the TUN interface (`tun0`).
2.  Identify IPv4 UDP packets destined for `10.13.37.1`.
3.  Extract the Destination Port (`DP`), Source IP (`SI`), and Source Port (`SP`).
4.  Dynamically create or reuse a `DatagramSocket` bound to `10.13.37.1:DP`.
5.  Extract the UDP Payload and forward it to the `lan-play` server via `ServerRedirector`.

### 2.2 Response Routing (Reverse Path)
1.  Memorize the mapping: `GatewayPort (DP) -> (SwitchIP:SI, SwitchPort:SP)`.
2.  When `ServerRedirector` receives a packet from the remote server for `DP`, it hands it to `UdpRelayServer`.
3.  `UdpRelayServer` looks up the mapping for `DP`.
4.  It uses the `DatagramSocket` bound to `DP` to send the payload back to `SI:SP`.

### 2.3 Component Responsibilities

#### `UdpRelayServer.kt`
- **State Management**: `Map<Int, ManagedSocket>` to track active ports.
- **Packet Parsing**: Extracts IP/UDP headers to identify target traffic.
- **Relay Logic**: Coordinates between TUN data and `ServerRedirector`.
- **Logging**: Logs with prefix `[RELAY]`.

#### `LanPlayVpnService.kt`
- Configures TUN with `10.13.37.1`.
- Feeds raw bytes from TUN into `UdpRelayServer.processFromTun()`.
- Provides `vpnService.protect()` to sockets.

#### `ServerRedirector.kt`
- Modified to handle payloads instead of raw packets.
- Routes incoming server packets back to `UdpRelayServer`.

## 3. Data Structures
```kotlin
data class ManagedSocket(
    val socket: DatagramSocket,
    var lastSourceIp: InetAddress,
    var lastSourcePort: Int,
    var lastActivity: Long
)

// In UdpRelayServer
private val sockets = ConcurrentHashMap<Int, ManagedSocket>()
```

## 4. Implementation Details
- **Threading**: Use Kotlin Coroutines or dedicated threads for socket listeners.
- **Cleanup**: Implement an idle timeout (e.g., 60 seconds) to close unused sockets and free ports.
- **Protection**: `vpnService.protect(socket)` is mandatory to prevent routing loops.

## 5. Success Criteria
- [ ] UDP packets from the Switch to `10.13.37.1` are correctly intercepted.
- [ ] Payloads are relayed to the `lan-play` server.
- [ ] Responses from the server are routed back to the correct Switch IP/Port.
- [ ] Logs show `[RELAY] Recebido X bytes de 10.13.X.Y:PORTA` and `[RELAY] Enviado X bytes para 10.13.X.Y:PORTA`.
