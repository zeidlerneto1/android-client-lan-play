package com.example.lanplaypoc

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NetworkScanner(private val onDeviceFound: (String, String) -> Unit, private val onLog: (String) -> Unit) {
    private val nintendoOuis = listOf(
        "00:1e:35", "00:22:d7", "00:23:cc", "2c:10:c1", "34:af:b3", "40:d2:8a",
        "58:2f:40", "60:6b:ff", "78:a2:a0", "7c:bb:8a", "98:b6:e9", "98:e8:fa",
        "a4:c0:e1", "b8:8a:ec", "cc:9e:00", "d8:6b:f7", "e0:0c:7f", "e0:f6:b5", "e8:4e:ce"
    )

    private val isRunning = AtomicBoolean(false)
    private var scanThread: Thread? = null
    private var detectedIp: String? = null

    fun start() {
        if (isRunning.getAndSet(true)) return
        scanThread = thread {
            while (isRunning.get()) {
                scanArpTable()
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        scanThread?.interrupt()
        scanThread = null
    }

    private fun scanArpTable() {
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine() // Skip header
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3].lowercase()
                        if (nintendoOuis.any { mac.startsWith(it) }) {
                            if (detectedIp != ip) {
                                detectedIp = ip
                                onLog("[DESCOBERTA] Novo dispositivo: IP $ip, MAC $mac")
                                onDeviceFound(ip, mac)
                            }
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore if /proc/net/arp is not accessible
        }
    }
}
