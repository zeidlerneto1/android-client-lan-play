# Restore Real Hotspot Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the reporting of actual system-generated SSID and Password in `HotspotManager.kt` while keeping the target information.

**Architecture:** Re-implement the extraction logic for `WifiConfiguration` (legacy) and `SoftApConfiguration` (Android 11+) within the `onStarted` callback of `LocalOnlyHotspotCallback`.

**Tech Stack:** Kotlin, Android WifiManager.

---

### Task 1: Update HotspotManager.kt

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/HotspotManager.kt`

- [ ] **Step 1: Restore extraction logic and update status message**

```kotlin
// ... inside HotspotManager.kt ...

                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    
                    val ssid: String?
                    val password: String?

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = res.softApConfiguration
                        ssid = config.ssid
                        password = config.passphrase
                    } else {
                        @Suppress("DEPRECATION")
                        val config = res.wifiConfiguration
                        ssid = config?.SSID?.removeSurrounding("\"")
                        password = config?.preSharedKey?.removeSurrounding("\"")
                    }

                    onStatus("Hotspot Started\nTarget SSID: $fixedSSID\nTarget Pass: $fixedPass\nActual SSID: $ssid\nActual Pass: $password")
                }
```

- [ ] **Step 2: Verify code structure**

Ensure `fixedSSID` and `fixedPass` are still defined as private properties.

```kotlin
    private val fixedSSID = "Switch-Lan"
    private val fixedPass = "12345678"
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/lanplaypoc/HotspotManager.kt
git commit -m "feat: restore real SSID/Password reporting in HotspotManager"
```
