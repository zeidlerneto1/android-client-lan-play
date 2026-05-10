# Code Quality & Scaffolding Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix project scaffolding issues, update SDK targets, and add missing stubs for compilation.

**Architecture:** Standard Android Gradle project structure with basic Kotlin stubs for VPN service and Activity.

**Tech Stack:** Gradle 8.2, Kotlin 1.9, Android API 33.

---

### Task 1: Gradle Wrapper & Configuration

**Files:**
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle.properties`

- [ ] **Step 1: Create gradle-wrapper.properties**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.2-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 2: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 3: Run gradle wrapper command to generate binaries**

Run: `gradle wrapper --gradle-version 8.2` (Note: Since I don't have gradle installed globally in this environment, I might need to skip binary generation if I can't run it, but I will provide the script content if possible or just the properties). 

Actually, I'll just write the properties and assume the user can run the wrapper if needed, OR I will try to generate them using `write_file` for the scripts if I can find a template. Since I'm supposed to "Create the Gradle wrapper files", I'll provide the scripts content.

- [ ] **Step 4: Commit Gradle config**

```bash
git add gradle/wrapper/gradle-wrapper.properties gradle.properties
git commit -m "chore: add gradle wrapper configuration and properties"
```

### Task 2: Update Build Configuration

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Update SDK versions and JVM target**

Update `compileSdk` to 33, `targetSdk` to 33, and `jvmTarget` to "17". Also update `sourceCompatibility` and `targetCompatibility` to `VERSION_17`.

- [ ] **Step 2: Verify build configuration**

Run: `./gradlew help` (if possible) or just check file content.

- [ ] **Step 3: Commit build changes**

```bash
git add app/build.gradle.kts
git commit -m "build: update SDK targets to 33 and JVM target to 17"
```

### Task 3: Create Kotlin Stubs

**Files:**
- Create: `app/src/main/java/com/example/lanplaypoc/MainActivity.kt`
- Create: `app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt`

- [ ] **Step 1: Create MainActivity stub**

```kotlin
package com.example.lanplaypoc

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Basic stub
    }
}
```

- [ ] **Step 2: Create LanPlayVpnService stub**

```kotlin
package com.example.lanplaypoc

import android.net.VpnService
import android.content.Intent

class LanPlayVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
```

- [ ] **Step 3: Commit stubs**

```bash
git add app/src/main/java/com/example/lanplaypoc/MainActivity.kt app/src/main/java/com/example/lanplaypoc/LanPlayVpnService.kt
git commit -m "feat: add MainActivity and LanPlayVpnService stubs"
```

### Task 4: Final Verification

- [ ] **Step 1: Verify all files exist**

Run: `ls -R`

- [ ] **Step 2: Check manifest references**

Run: `grep -E "MainActivity|LanPlayVpnService" app/src/main/AndroidManifest.xml`
