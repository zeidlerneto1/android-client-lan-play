# LanPlayVpnService Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the `LanPlayVpnService` with better resource management, robust packet parsing, and error recovery.

**Architecture:** Enhancing the existing `LanPlayVpnService` by adding safety checks, lifecycle management, and proper resource handling using Kotlin's idiomatic features.

**Tech Stack:** Kotlin, Android VpnService.

---

### Task 1: Robust Packet Parsing

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Update `parsePacket` with safety checks**

```kotlin
    private fun parsePacket(packet: ByteArray, length: Int) {
        if (length < 20) return // Minimum IPv4 header length

        // Basic IPv4 Parsing
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return // Only IPv4

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        if (protocol == 17) { // UDP
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (length >= ihl + 8) { // UDP header is 8 bytes
                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                Log.d("LanPlayPoC", "[RAW] UDP $srcIp:$srcPort -> $dstIp:$dstPort (Len: $length)")
            }
        } else if (protocol == 1) { // ICMP for ping testing
            Log.d("LanPlayPoC", "[RAW] ICMP $srcIp -> $dstIp")
        } else {
            Log.d("LanPlayPoC", "[RAW] Protocol $protocol from $srcIp -> $dstIp")
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt
git commit -m "fix: robust packet parsing in LanPlayVpnService"
```

### Task 2: Thread Lifecycle & Error Recovery

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Update `run()` for lifecycle and error recovery**

```kotlin
    override fun run() {
        Log.d("LanPlayPoC", "VPN Thread Started")
        try {
            val builder = Builder()
            builder.addAddress("10.13.37.1", 32)
            builder.addRoute("10.13.0.0", 16)
            builder.setSession("LanPlayPoC")
            builder.setMtu(1500)
            builder.setBlocking(true)
            
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e("LanPlayPoC", "Failed to establish VPN interface")
                stopSelf()
                return
            }

            val fd = vpnInterface!!.fileDescriptor
            FileInputStream(fd).use { inputStream ->
                val buffer = ByteArray(2048)
                while (!Thread.interrupted()) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        parsePacket(buffer, length)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LanPlayPoC", "VPN Loop Error", e)
        } finally {
            vpnInterface?.close()
            vpnInterface = null
            thread = null
            Log.d("LanPlayPoC", "VPN Thread Stopped")
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt
git commit -m "fix: thread lifecycle and error recovery in LanPlayVpnService"
```
