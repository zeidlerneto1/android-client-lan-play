package com.example.lanplaypoc

import android.content.Context
import android.net.VpnService
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class UdpRelayServer(
    private val vpnService: VpnService,
    private val redirector: ServerRedirector,
    private val onTunWrite: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
    private val context: Context
) {
    companion object {
        const val IDLE_TIMEOUT_MS = 60_000L
    }

    private val sockets = ConcurrentHashMap<Int, ManagedSocket>()
    private var watchdogRunning = true
    private val watchdogThread: Thread
    private var gatewayIp: String = ""

    init {
        gatewayIp = getHotspotIpAddress()
        if (gatewayIp.isNotEmpty()) {
            onLog("[INFO] Gateway IP detectado: $gatewayIp")
        } else {
            onLog("[ERROR] Não foi possível detectar o IP do Hotspot. Verifique se o Hotspot está ligado.")
        }

        watchdogThread = thread(start = true, name = "UdpRelayWatchdog") {
            while (watchdogRunning) {
                try {
                    Thread.sleep(5000)
                    cleanupIdleSockets(System.currentTimeMillis())
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun cleanupIdleSockets(now: Long) {
        val iterator = sockets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastActivity > IDLE_TIMEOUT_MS) {
                onLog("[SOCKET] Removido socket inativo na porta ${entry.key}")
                try { entry.value.socket.close() } catch (e: Exception) {}
                iterator.remove()
            }
        }
    }

    data class ManagedSocket(
        val socket: DatagramSocket,
        var lastSourceIp: InetAddress,
        var lastSourcePort: Int,
        var lastActivity: Long
    )

    fun processFromTun(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false

        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return false

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false

        val dstIp = getIpString(packet, 16)
        // Accept packets destined to the hotspot gateway or broadcast
        if (gatewayIp.isNotEmpty() && dstIp != gatewayIp && dstIp != "255.255.255.255") {
            return false
        }

        val ihl = (packet[0].toInt() and 0x0F) * 4
        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val srcPort = getPort(packet, ihl)
        val dstPort = getPort(packet, ihl + 2)
        
        val payloadOffset = ihl + 8
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) return false

        val payload = packet.copyOfRange(payloadOffset, length)
        
        handleInterceptedPacket(srcIp, srcPort, dstPort, payload)
        return true
    }

    private fun handleInterceptedPacket(srcIp: InetAddress, srcPort: Int, dstPort: Int, payload: ByteArray) {
        var managed = sockets[dstPort]
        if (managed == null) {
            try {
                // Bind dynamically on standard interface using DatagramSocket
                val socket = DatagramSocket(dstPort)
                vpnService.protect(socket)
                onLog("[SOCKET] Criado socket na porta $dstPort")
                val newManaged = ManagedSocket(socket, srcIp, srcPort, System.currentTimeMillis())
                val existing = sockets.putIfAbsent(dstPort, newManaged)
                if (existing != null) {
                    socket.close()
                    managed = existing
                } else {
                    managed = newManaged
                }
            } catch (e: Exception) {
                onLog("[ERROR] Falha ao fazer bind na porta $dstPort: ${e.message}")
                return
            }
        }

        managed.lastSourceIp = srcIp
        managed.lastSourcePort = srcPort
        managed.lastActivity = System.currentTimeMillis()

        onLog("[LOCAL] Recebido de ${srcIp.hostAddress}:$srcPort | Payload: ${payload.size} bytes")
        redirector.forwardToServer(payload, payload.size, dstPort)
    }

    fun processFromServer(payload: ByteArray, gatewayPort: Int) {
        val managed = if (gatewayPort != 0) sockets[gatewayPort] else sockets.values.maxByOrNull { it.lastActivity }
        if (managed == null) return

        try {
            val rawPacket = buildRawUdpPacket(
                srcIp = InetAddress.getByName(gatewayIp.takeIf { it.isNotEmpty() } ?: "192.168.43.1"),
                srcPort = gatewayPort.takeIf { it != 0 } ?: managed.socket.localPort,
                dstIp = managed.lastSourceIp,
                dstPort = managed.lastSourcePort,
                payload = payload
            )
            onTunWrite(rawPacket)
            onLog("[LOCAL] Enviado ${payload.size} bytes para ${managed.lastSourceIp.hostAddress}:${managed.lastSourcePort}")
        } catch (e: Exception) {
            onLog("[ERROR] Erro ao injetar: ${e.message}")
        }
    }

    private fun buildRawUdpPacket(srcIp: InetAddress, srcPort: Int, dstIp: InetAddress, dstPort: Int, payload: ByteArray): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45.toByte()
        packet[1] = 0.toByte()
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0.toByte()
        packet[5] = 0.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0.toByte()
        packet[8] = 64.toByte()
        packet[9] = 17.toByte()
        
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        val udpOffset = 20
        packet[udpOffset] = (srcPort shr 8).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = (dstPort shr 8).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        packet[udpOffset + 4] = (udpLen shr 8).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        
        // UDP Checksum with Pseudo-header
        packet[udpOffset + 6] = 0.toByte()
        packet[udpOffset + 7] = 0.toByte()
        val udpChecksum = calculateUdpChecksum(packet, udpOffset, udpLen, srcIp.address, dstIp.address)
        packet[udpOffset + 6] = (udpChecksum shr 8).toByte()
        packet[udpOffset + 7] = (udpChecksum and 0xFF).toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)

        return packet
    }

    private fun calculateUdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int, srcIp: ByteArray, dstIp: ByteArray): Int {
        var sum = 0
        
        // Pseudo-header
        for (i in 0..3 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 17 // Protocol UDP
        sum += udpLen

        // UDP Header + Payload
        var i = udpOffset
        while (i < udpOffset + udpLen - 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < udpOffset + udpLen) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val result = (sum.inv() and 0xFFFF)
        return if (result == 0) 0xFFFF else result
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            if (i == offset + 10) {
                i += 2
                continue
            }
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF)
    }

    private fun getIpString(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}.${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    private fun getPort(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }
    
    fun stop() {
        watchdogRunning = false
        watchdogThread.interrupt()
        sockets.values.forEach { try { it.socket.close() } catch (e: Exception) {} }
        sockets.clear()
    }

    private fun getHotspotIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("wlan0") || networkInterface.name.contains("ap0")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return ""
    }
}
