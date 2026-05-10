# Hotspot Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `HotspotManager` to handle Android's `LocalOnlyHotspot` for physical layer connectivity.

**Architecture:** A standalone manager class that wraps `WifiManager` APIs, provides start/stop functionality, and uses a callback to report SSID/Password or errors.

**Tech Stack:** Kotlin, Android SDK (WifiManager).

---

### Task 2.1: Create HotspotManager Class

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/HotspotManager.kt`

- [ ] **Step 1: Create the HotspotManager.kt file with the specified implementation**

```kotlin
package com.example.lanplaypoc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat

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

    fun stop() {
        reservation?.close()
        reservation = null
        onStatus("Hotspot Stopped")
    }
}
```

- [ ] **Step 2: Verify the file exists and has correct content**

Run: `ls app/src/main/java/com/example/lanplaypoc/HotspotManager.kt`
Expected: File exists.

- [ ] **Step 3: Commit the changes**

```bash
git add app/src/main/java/com/example/lanplaypoc/HotspotManager.kt
git commit -m "feat: implement HotspotManager for LocalOnlyHotspot"
```

### Task 2.2: Verify Build

**Files:**
- Modify: N/A

- [ ] **Step 1: Run a Gradle build to ensure no compilation errors**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit any necessary fixes if build fails**
