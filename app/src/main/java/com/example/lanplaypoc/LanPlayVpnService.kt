package com.example.lanplaypoc

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LanPlayVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var thread: Thread? = null

    companion object {
        const val ACTION_VPN_LOG = "com.example.lanplaypoc.VPN_LOG"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"
    }

    private fun broadcastLog(message: String) {
        val intent = Intent(ACTION_VPN_LOG)
        intent.putExtra(EXTRA_LOG_MESSAGE, message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (thread == null) {
            thread = Thread(this, "LanPlayVpnThread").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        thread?.interrupt()
        vpnInterface?.close()
        super.onDestroy()
    }

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
                val logMsg = "[RAW] UDP $srcIp:$srcPort -> $dstIp:$dstPort (Len: $length)"
                Log.d("LanPlayPoC", logMsg)
                broadcastLog(logMsg)
            }
        } else if (protocol == 1) { // ICMP for ping testing
            val logMsg = "[RAW] ICMP $srcIp -> $dstIp"
            Log.d("LanPlayPoC", logMsg)
            broadcastLog(logMsg)
        } else {
            val logMsg = "[RAW] Protocol $protocol from $srcIp -> $dstIp"
            Log.d("LanPlayPoC", logMsg)
            broadcastLog(logMsg)
        }
    }
}
