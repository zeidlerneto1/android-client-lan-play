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
    private var redirector: ServerRedirector? = null
    private var relay: UdpRelayServer? = null
    private var serverAddr: String = "lan-play.com:11451"

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
        serverAddr = intent?.getStringExtra("server_addr") ?: "lan-play.com:11451"
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
            val outStream = FileOutputStream(fd)
            redirector = ServerRedirector(this, serverAddr, { packet ->
                try {
                    outStream.write(packet)
                } catch (e: Exception) {
                    Log.e("LanPlayPoC", "Error writing to tun0", e)
                }
            }, { broadcastLog(it) })

            val r = UdpRelayServer(this, redirector!!, { broadcastLog(it) })
            this.relay = r
            redirector?.setRelay(r)
            redirector?.start()

            FileInputStream(fd).use { inputStream ->
                val buffer = ByteArray(2048)
                while (!Thread.interrupted()) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        if (!r.processFromTun(buffer, length)) {
                            redirector?.forwardToServer(buffer, length)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LanPlayPoC", "VPN Loop Error", e)
        } finally {
            relay?.stop()
            relay = null
            redirector?.stop()
            redirector = null
            vpnInterface?.close()
            vpnInterface = null
            thread = null
            Log.d("LanPlayPoC", "VPN Thread Stopped")
        }
    }
}
