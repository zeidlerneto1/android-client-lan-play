package com.example.lanplaypoc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat

class HotspotManager(context: Context, private val onStatus: (String) -> Unit) {
    private val appContext = context.applicationContext
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val fixedSSID = "Switch-Lan"
    private val fixedPass = "12345678"

    fun start() {
        if (reservation != null) {
            onStatus("Hotspot is already active")
            return
        }

        if (!hasPermissions()) {
            val missingPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            onStatus("Permission $missingPermission missing")
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    onStatus("Hotspot Started\nSSID: $fixedSSID\nPassword: $fixedPass")
                }

                override fun onFailed(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "ERROR_GENERIC"
                        2 -> "ERROR_IN_PROGRESS"
                        3 -> "ERROR_NO_CHANNEL"
                        4 -> "ERROR_TETHERING_DISALLOWED"
                        else -> "UNKNOWN_ERROR ($reason)"
                    }
                    onStatus("Hotspot Failed: $reasonStr")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onStatus("Error starting hotspot: ${e.message}")
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        onStatus("Hotspot Stopped")
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
