# Bridge VPN Logs to UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bridge packet capture logs from `LanPlayVpnService` to `MainViewModel` using `LocalBroadcastManager` so they appear in the UI console.

**Architecture:** `LanPlayVpnService` will broadcast an intent with the log message. `MainViewModel` will register a `BroadcastReceiver` to listen for these intents and update its `logs` LiveData.

**Tech Stack:** Kotlin, Android, LocalBroadcastManager.

---

### Task 1: Add LocalBroadcastManager Dependency

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the dependency**
Add `implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")` to the `dependencies` block.

- [ ] **Step 2: Sync project**
Run: `./gradlew help` (to trigger a sync/check)

### Task 2: Implement Broadcasting in LanPlayVpnService

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Define Constants and Broadcast method**
Define a constant for the action and a helper method to send broadcasts.

```kotlin
companion object {
    const val ACTION_VPN_LOG = "com.example.lanplaypoc.VPN_LOG"
    const val EXTRA_LOG_MESSAGE = "extra_log_message"
}

private fun broadcastLog(message: String) {
    val intent = Intent(ACTION_VPN_LOG)
    intent.putExtra(EXTRA_LOG_MESSAGE, message)
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
}
```

- [ ] **Step 2: Update parsePacket to use broadcastLog**
Replace `Log.d` calls with `broadcastLog` (or keep both).

```kotlin
// Example in parsePacket
val logMsg = "[RAW] UDP $srcIp:$srcPort -> $dstIp:$dstPort (Len: $length)"
Log.d("LanPlayPoC", logMsg)
broadcastLog(logMsg)
```

### Task 3: Observe Broadcasts in MainViewModel

**Files:**
- Modify: `app/src/main/java/com/example/lanplaypoc/MainViewModel.kt`

- [ ] **Step 1: Register BroadcastReceiver**
In `init`, register a `BroadcastReceiver` that calls `log(message)`.

- [ ] **Step 2: Unregister BroadcastReceiver**
Override `onCleared()` to unregister the receiver to avoid leaks.

```kotlin
private val vpnLogReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
        val message = intent?.getStringExtra(LanPlayVpnService.EXTRA_LOG_MESSAGE)
        if (message != null) {
            log(message)
        }
    }
}

init {
    // ... existing init ...
    val filter = android.content.IntentFilter(LanPlayVpnService.ACTION_VPN_LOG)
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(application).registerReceiver(vpnLogReceiver, filter)
}

override fun onCleared() {
    super.onCleared()
    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(vpnLogReceiver)
}
```

### Task 4: Verification

- [ ] **Step 1: Build the project**
Run: `./gradlew assembleDebug`

- [ ] **Step 2: Manual Verification (Instructions for user)**
1. Start the app.
2. Start the VPN (Hotspot).
3. Generate some UDP traffic (e.g., ping or app activity).
4. Verify logs appear in the UI console.
