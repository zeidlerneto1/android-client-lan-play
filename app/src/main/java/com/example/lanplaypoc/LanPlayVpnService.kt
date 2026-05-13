package com.example.lanplaypoc

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

class LanPlayVpnService : VpnService() {
    companion object {
        const val ACTION_VPN_LOG = "com.example.lanplaypoc.VPN_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var relay: UdpRelayServer? = null
    private var redirector: ServerRedirector? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverAddr = intent?.getStringExtra("server_addr") ?: "lan-play.com:11451"
        startVpn(serverAddr)
        return START_NOT_STICKY
    }

    private fun startVpn(serverAddr: String) {
        if (running) return
        running = true

        try {
            val builder = Builder()
                .setSession("Switch LAN Play")
                .setMtu(1500)
                .addAddress("192.168.49.1", 24) // Dummy address needed to establish TUN interface
                .addRoute("0.0.0.0", 0) // Catch all traffic

            vpnInterface = builder.establish()

            redirector = ServerRedirector(
                vpnService = this,
                serverAddress = serverAddr,
                onPacketReceived = { /* handled by relay */ },
                onLog = ::sendLog
            )

            relay = UdpRelayServer(
                vpnService = this,
                redirector = redirector!!,
                onTunWrite = ::writeToTun,
                onLog = ::sendLog,
                context = this
            )
            redirector?.setRelay(relay!!)
            redirector?.start()

            thread {
                val input = FileInputStream(vpnInterface?.fileDescriptor)
                val buffer = ByteArray(32767)
                while (running) {
                    try {
                        val length = input.read(buffer)
                        if (length > 0) {
                            relay?.processFromTun(buffer, length)
                        }
                    } catch (e: Exception) {
                        if (running) sendLog("[ERROR] TUN Read error: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            sendLog("[ERROR] Failed to start VPN: ${e.message}")
        }
    }

    private fun writeToTun(packet: ByteArray) {
        try {
            val output = FileOutputStream(vpnInterface?.fileDescriptor)
            output.write(packet)
        } catch (e: Exception) {
            // sendLog("TUN Write error: ${e.message}")
        }
    }

    private fun sendLog(message: String) {
        val intent = Intent(ACTION_VPN_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        relay?.stop()
        redirector?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        sendLog("[INFO] VPN Service stopped")
    }
}
