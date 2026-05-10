# UDP Interception PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a Proof of Concept (PoC) Android app that captures UDP packets from a Nintendo Switch via a LocalOnlyHotspot using a VpnService "Fake Gateway" approach on Android 13.

**Architecture:** The app starts a LocalOnlyHotspot for physical connectivity and a VpnService configured with the Switch's expected gateway IP (10.13.37.1) and route (10.13.0.0/16). A background thread reads raw IP packets from the VPN's file descriptor and parses them to identify UDP traffic from the Switch.

**Tech Stack:** Kotlin, Android SDK (API 33/Android 13), VpnService, WifiManager (LocalOnlyHotspot).

---

### Task 1: Project Scaffolding & Manifest

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`

- [ ] **Step 1: Create root build and settings files**
- [ ] **Step 2: Create app module build file with required dependencies**
- [ ] **Step 3: Define AndroidManifest.xml with VpnService and Permissions**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.lanplaypoc">
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <application android:label="LanPlay PoC">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service android:name=".LanPlayVpnService"
                 android:permission="android.permission.BIND_VPN_SERVICE"
                 android:exported="false">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

---

### Task 2: Hotspot Management

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/HotspotManager.kt`

- [ ] **Step 1: Implement HotspotManager to start/stop LocalOnlyHotspot**
- [ ] **Step 2: Add callback to capture SSID and Password**

```kotlin
class HotspotManager(private val context: Context, private val onStatus: (String) -> Unit) {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun start() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Permission NEARBY_WIFI_DEVICES missing")
            return
        }
        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                val config = res.wifiConfiguration
                onStatus("Hotspot Started\nSSID: ${config.SSID}\nPass: ${config.preSharedKey}")
            }
            override fun onFailed(reason: Int) {
                onStatus("Hotspot Failed: $reason")
            }
        }, Handler(Looper.getMainLooper()))
    }
}
```

---

### Task 3: VpnService & Raw Sniffer

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Implement LanPlayVpnService with Fake Gateway configuration**
- [ ] **Step 2: Implement the raw packet reading loop**
- [ ] **Step 3: Implement raw IP/UDP parsing**

```kotlin
class LanPlayVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var thread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        thread = Thread(this, "LanPlayVpnThread").apply { start() }
        return START_STICKY
    }

    override fun run() {
        val builder = Builder()
        builder.addAddress("10.13.37.1", 32)
        builder.addRoute("10.13.0.0", 16)
        builder.setSession("LanPlayPoC")
        builder.setMtu(1500)
        builder.setBlocking(true)
        
        vpnInterface = builder.establish()
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val buffer = ByteArray(2048)

        while (!Thread.interrupted()) {
            val length = inputStream.read(buffer)
            if (length > 0) {
                parsePacket(buffer, length)
            }
        }
    }

    private fun parsePacket(packet: ByteArray, length: Int) {
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return // Only IPv4

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        if (protocol == 17) { // UDP
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            Log.d("LanPlayPoC", "[RAW] UDP $srcIp:$srcPort -> $dstIp:$dstPort (Len: $length)")
        } else if (protocol == 1) { // ICMP for ping testing
            Log.d("LanPlayPoC", "[RAW] ICMP $srcIp -> $dstIp")
        }
    }
}
```

---

### Task 4: UI & Integration

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Create simple UI with Start/Stop buttons and Log console**
- [ ] **Step 2: Wire buttons to HotspotManager and VpnService**
- [ ] **Step 3: Handle runtime permissions (Location, Nearby Devices)**

---

### Task 5: README & Documentation

**Files:**
- Create: `README.md`

- [ ] **Step 1: Document architecture**
- [ ] **Step 2: Document Switch configuration (Manual IP 10.13.0.100, Gateway 10.13.37.1)**
- [ ] **Step 3: Define success criteria and debug steps**
