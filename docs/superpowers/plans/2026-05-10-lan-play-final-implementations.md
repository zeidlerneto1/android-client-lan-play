# Switch LAN Play Bridge Final Implementations Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the final redirection logic, UI controls, and hotspot management for the Switch LAN Play Bridge.

**Architecture:** A `ServerRedirector` component handles UDP encapsulation and decapsulation using the Lan-Play protocol. `LanPlayVpnService` integrates this redirector to bridge the `tun0` interface with the remote server. The UI allows server configuration, and `HotspotManager` is simplified for user clarity.

**Tech Stack:** Kotlin, Android VpnService, DatagramSocket (UDP), SharedPreferences.

---

### Task 1: Implement ServerRedirector.kt

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/ServerRedirector.kt`

- [ ] **Step 1: Create the ServerRedirector class with UDP socket management**

```kotlin
package com.example.lanplaypoc

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class ServerRedirector(
    private val vpnService: VpnService,
    private val serverAddress: String,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var running = false
    private val serverIp: InetAddress
    private val serverPort: Int

    init {
        val parts = serverAddress.split(":")
        serverIp = InetAddress.getByName(parts[0])
        serverPort = if (parts.size > 1) parts[1].toInt() else LanPlayProtocol.DEFAULT_PORT
    }

    fun start() {
        running = true
        socket = DatagramSocket().apply {
            vpnService.protect(this)
        }
        
        receiveThread = Thread {
            val buffer = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    handleServerPacket(packet.data, packet.length)
                } catch (e: Exception) {
                    if (running) onLog("Receive Error: ${e.message}")
                }
            }
        }.apply { start() }
        onLog("Redirector started for $serverAddress")
    }

    fun stop() {
        running = false
        socket?.close()
        receiveThread?.interrupt()
        socket = null
        receiveThread = null
    }

    fun forwardToServer(packet: ByteArray, length: Int) {
        try {
            val header = LanPlayProtocol.Header(
                magic = LanPlayProtocol.MAGIC_NUMBER,
                type = LanPlayProtocol.PacketType.CONNECT,
                compressed = 0,
                length = length.toShort(),
                decompressLength = length.toShort()
            ).toBytes()

            val combined = ByteBuffer.allocate(LanPlayProtocol.HEADER_SIZE + length)
            combined.put(header)
            combined.put(packet, 0, length)
            
            val data = combined.array()
            val datagram = DatagramPacket(data, data.size, serverIp, serverPort)
            socket?.send(datagram)
            // Log.d("ServerRedirector", "Sent ${data.size} bytes to server")
        } catch (e: Exception) {
            onLog("Send Error: ${e.message}")
        }
    }

    private fun handleServerPacket(data: ByteArray, length: Int) {
        if (length < LanPlayProtocol.HEADER_SIZE) return
        
        val header = LanPlayProtocol.Header.fromBytes(data.copyOfRange(0, LanPlayProtocol.HEADER_SIZE))
        if (header != null && header.magic == LanPlayProtocol.MAGIC_NUMBER) {
            val payloadLength = length - LanPlayProtocol.HEADER_SIZE
            val payload = data.copyOfRange(LanPlayProtocol.HEADER_SIZE, length)
            onPacketReceived(payload)
            // Log.d("ServerRedirector", "Received $payloadLength bytes from server")
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/ServerRedirector.kt
git commit -m "feat: add ServerRedirector for UDP encapsulation"
```

### Task 2: Integrate ServerRedirector into LanPlayVpnService.kt

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Update LanPlayVpnService to use ServerRedirector**

```kotlin
// ... inside LanPlayVpnService class ...
    private var redirector: ServerRedirector? = null
    private var serverAddr: String = "lan-play.com:11451"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverAddr = intent?.getStringExtra("server_addr") ?: "lan-play.com:11451"
        if (thread == null) {
            thread = Thread(this, "LanPlayVpnThread").apply { start() }
        }
        return START_STICKY
    }

    // Inside run() method, before the while loop:
    val outStream = FileOutputStream(fd)
    redirector = ServerRedirector(this, serverAddr, { packet ->
        try {
            outStream.write(packet)
        } catch (e: Exception) {
            Log.e("LanPlayPoC", "Error writing to tun0", e)
        }
    }, { broadcastLog(it) })
    redirector?.start()

    // Inside the while loop, replace sniffer call or add after it:
    if (length > 0) {
        sniffer.parseAndLog(buffer, length)
        redirector?.forwardToServer(buffer, length)
    }

    // Inside finally block:
    redirector?.stop()
    redirector = null
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt
git commit -m "feat: integrate ServerRedirector into LanPlayVpnService"
```

### Task 3: Update RawPacketSniffer.kt for All Protocols

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/RawPacketSniffer.kt`

- [ ] **Step 1: Update parseAndLog to capture ICMP, TCP, UDP and filter 10.13.0.0/16**

```kotlin
// ... inside parseAndLog ...
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = getIpString(packet, 12)
        val dstIp = getIpString(packet, 16)

        // Filter for 10.13.x.x subnet
        if (!srcIp.startsWith("10.13.") && !dstIp.startsWith("10.13.")) {
            return
        }

        val protoStr = when (protocol) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "PROTO $protocol"
        }

        val ihl = (packet[0].toInt() and 0x0F) * 4
        var logMsg = "[$protoStr] "

        if (protocol == 6 || protocol == 17) {
            val srcPort = getPort(packet, ihl)
            val dstPort = getPort(packet, ihl + 2)
            logMsg += "$srcIp:$srcPort -> $dstIp:$dstPort"
        } else {
            logMsg += "$srcIp -> $dstIp"
        }

        logMsg += " (Len: $length)"
        
        Log.d("RawPacketSniffer", logMsg)
        onLog(logMsg)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/RawPacketSniffer.kt
git commit -m "fix: improve RawPacketSniffer to capture all protocols and filter subnet"
```

### Task 4: Update UI and Persistence in MainActivity.kt

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/example/lanplaypoc/MainActivity.kt`

- [ ] **Step 1: Add EditText to layout**

```xml
<!-- activity_main.xml -->
<!-- Add before START ALL button -->
<EditText
    android:id="@+id/editServer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="lan-play.com:11451"
    android:inputType="textUri"
    android:text="lan-play.com:11451" />
```

- [ ] **Step 2: Update MainActivity to save/load address and pass to service**

```kotlin
// ... MainActivity.kt ...
    private lateinit var editServer: EditText

    // onCreate
    editServer = findViewById(R.id.editServer)
    val prefs = getSharedPreferences("lanplay", Context.MODE_PRIVATE)
    editServer.setText(prefs.getString("server_addr", "lan-play.com:11451"))

    // startVpnService
    val serverAddr = editServer.text.toString()
    prefs.edit().putString("server_addr", serverAddr).apply()
    val intent = Intent(this, LanPlayVpnService::class.java).apply {
        putExtra("server_addr", serverAddr)
    }
    startService(intent)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/example/lanplaypoc/MainActivity.kt
git commit -m "feat: add server configuration UI and persistence"
```

### Task 5: Refactor HotspotManager.kt for Fixed Info

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/HotspotManager.kt`

- [ ] **Step 1: Hardcode reported SSID and Password**

```kotlin
// ... HotspotManager.kt ...
    private val fixedSSID = "Switch-Lan"
    private val fixedPass = "12345678"

    // inside onStarted
    onStatus("Hotspot Started\nSSID: $fixedSSID\nPassword: $fixedPass")
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/HotspotManager.kt
git commit -m "refactor: simplify HotspotManager reported info"
```
