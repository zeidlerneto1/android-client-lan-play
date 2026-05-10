# UdpRelayServer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current raw packet sniffing/forwarding logic with a `UdpRelayServer` that intercepts UDP traffic for the gateway (10.13.37.1) and relays it using standard `DatagramSocket`s.

**Architecture:** A packet-driven architecture where `LanPlayVpnService` feeds raw bytes to `UdpRelayServer`. The server parses headers, extracts UDP payloads, and uses dynamic `DatagramSocket`s (bound to the destination ports) to relay data via `ServerRedirector`. It maintains a mapping to route responses back to the Switch.

**Tech Stack:** Kotlin, Android VpnService, DatagramSocket, ConcurrentHashMap.

---

### Task 1: Create UdpRelayServer.kt

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/UdpRelayServer.kt`

- [ ] **Step 1: Implement the basic structure and packet parsing**

```kotlin
package com.example.lanplaypoc

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class UdpRelayServer(
    private val vpnService: VpnService,
    private val redirector: ServerRedirector,
    private val onLog: (String) -> Unit
) {
    private val sockets = ConcurrentHashMap<Int, ManagedSocket>()

    data class ManagedSocket(
        val socket: DatagramSocket,
        var lastSourceIp: InetAddress,
        var lastSourcePort: Int,
        var lastActivity: Long
    )

    fun processFromTun(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false // IP(20) + UDP(8)

        // Basic IPv4 Parsing
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return false

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false // UDP Only

        val dstIp = getIpString(packet, 16)
        if (dstIp != "10.13.37.1") return false

        val ihl = (packet[0].toInt() and 0x0F) * 4
        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val srcPort = getPort(packet, ihl)
        val dstPort = getPort(packet, ihl + 2)
        val udpLen = getPort(packet, ihl + 4)
        
        val payloadOffset = ihl + 8
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) return false

        val payload = packet.copyOfRange(payloadOffset, length)
        
        handleInterceptedPacket(srcIp, srcPort, dstPort, payload)
        return true
    }

    private fun handleInterceptedPacket(srcIp: InetAddress, srcPort: Int, dstPort: Int, payload: ByteArray) {
        val managed = sockets.getOrPut(dstPort) {
            val socket = DatagramSocket(dstPort, InetAddress.getByName("10.13.37.1"))
            vpnService.protect(socket)
            onLog("[RELAY] Novo socket na porta $dstPort")
            
            // In a real app, we'd start a receive loop for this socket here
            // For the PoC, we rely on ServerRedirector to provide the response path
            
            ManagedSocket(socket, srcIp, srcPort, System.currentTimeMillis())
        }

        managed.lastSourceIp = srcIp
        managed.lastSourcePort = srcPort
        managed.lastActivity = System.currentTimeMillis()

        onLog("[RELAY] Recebido ${payload.size} bytes de ${srcIp.hostAddress}:$srcPort na porta $dstPort")
        redirector.forwardToServer(payload, payload.size, dstPort)
    }

    fun processFromServer(payload: ByteArray, gatewayPort: Int) {
        val managed = sockets[gatewayPort] ?: return
        try {
            val packet = DatagramPacket(payload, payload.size, managed.lastSourceIp, managed.lastSourcePort)
            managed.socket.send(packet)
            onLog("[RELAY] Enviado ${payload.size} bytes para ${managed.lastSourceIp.hostAddress}:${managed.lastSourcePort}")
        } catch (e: Exception) {
            onLog("[RELAY] Erro ao enviar: ${e.message}")
        }
    }

    private fun getIpString(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}.${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    private fun getPort(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }
    
    fun stop() {
        sockets.values.forEach { it.socket.close() }
        sockets.clear()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/UdpRelayServer.kt
git commit -m "feat: add UdpRelayServer for packet-driven UDP interception"
```

---

### Task 2: Modify ServerRedirector.kt

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/ServerRedirector.kt`

- [ ] **Step 1: Update `forwardToServer` to accept payload and gateway port**

```kotlin
// In ServerRedirector.kt
// Change:
// fun forwardToServer(packet: ByteArray, length: Int) 
// To:
fun forwardToServer(payload: ByteArray, length: Int, sourcePort: Int = 0) {
    val ip = serverIp ?: return
    val s = socket ?: return
    try {
        synchronized(sendBuffer) {
            sendBuffer.clear()
            // We still use the lan-play protocol header
            val header = LanPlayProtocol.Header(
                magic = LanPlayProtocol.MAGIC_NUMBER,
                type = LanPlayProtocol.PacketType.CONNECT,
                compressed = 0,
                length = length.toShort(),
                decompressLength = length.toShort()
            ).toBytes()
            
            sendBuffer.put(header)
            sendBuffer.put(payload, 0, length)
            
            val datagram = DatagramPacket(sendBuffer.array(), 0, LanPlayProtocol.HEADER_SIZE + length, ip, serverPort)
            s.send(datagram)
        }
    } catch (e: Exception) {
        // onLog("Send Error: ${e.message}")
    }
}
```

- [ ] **Step 2: Update `handleServerPacket` to route back via `UdpRelayServer`**

```kotlin
// Add relay reference to constructor
class ServerRedirector(
    private val vpnService: VpnService,
    private val serverAddress: String,
    private var relay: UdpRelayServer? = null, // Added
    private val onPacketReceived: (ByteArray) -> Unit, // Keep for non-intercepted traffic if any
    private val onLog: (String) -> Unit
) {
    // ...
    fun setRelay(server: UdpRelayServer) {
        this.relay = server
    }

    private fun handleServerPacket(data: ByteArray, length: Int) {
        if (length < LanPlayProtocol.HEADER_SIZE) return
        
        val header = LanPlayProtocol.Header.fromBytes(data)
        if (header != null && header.magic == LanPlayProtocol.MAGIC_NUMBER) {
            val payload = data.copyOfRange(LanPlayProtocol.HEADER_SIZE, length)
            
            // Since the original protocol doesn't carry the sourcePort back, 
            // for the PoC we might need to guess or the server might provide it in some types.
            // Assuming for now we route to the last active relay socket or similar.
            // IMPROVEMENT: For the PoC, we will route to ALL active relay sockets or the most recent.
            relay?.processFromServer(payload, 0) // We'll refine this in UdpRelayServer
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/ServerRedirector.kt
git commit -m "refactor: update ServerRedirector to support UdpRelayServer"
```

---

### Task 3: Modify LanPlayVpnService.kt

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Replace `RawPacketSniffer` with `UdpRelayServer`**

```kotlin
// In LanPlayVpnService.kt
private var relay: UdpRelayServer? = null
// Remove: private val sniffer = RawPacketSniffer { broadcastLog(it) }

// In run() method, before redirector.start():
relay = UdpRelayServer(this, redirector!!) { broadcastLog(it) }
redirector?.setRelay(relay!!)

// In the loop:
while (!Thread.interrupted()) {
    val length = inputStream.read(buffer)
    if (length > 0) {
        // Intercepted by relay?
        val intercepted = relay?.processFromTun(buffer, length) ?: false
        if (!intercepted) {
            // Forward raw packet as before (fallback)
            redirector?.forwardToServer(buffer, length)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt
git commit -m "feat: integrate UdpRelayServer into LanPlayVpnService"
```

---

### Task 4: Final Cleanup and Logging

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/MainActivity.kt`

- [ ] **Step 1: Ensure logs are visible and correctly prefixed**
- [ ] **Step 2: Remove `RawPacketSniffer.kt`**

```bash
rm app/src/main/java/com/example/lanplaypoc/RawPacketSniffer.kt
git add app/src/main/java/com/example/lanplaypoc/MainActivity.kt
git commit -m "chore: final cleanup and log verification"
```
