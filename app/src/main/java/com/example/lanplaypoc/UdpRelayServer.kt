package com.example.lanplaypoc

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class UdpRelayServer(
    private val vpnService: VpnService,
    private val redirector: ServerRedirector,
    private val onTunWrite: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val GATEWAY_IP = "10.13.37.1"
        const val IDLE_TIMEOUT_MS = 60_000L
    }

    private val sockets = ConcurrentHashMap<Int, ManagedSocket>()
    private val bytesReadThisSecond = AtomicLong(0)
    private val lastPacketTimestamp = AtomicLong(System.currentTimeMillis())
    private var lastLoggedFirstByte = -1
    private var lastFirstByteLogTime = 0L
    private var watchdogRunning = true
    private val watchdogThread: Thread

    init {
        watchdogThread = thread(start = true, name = "UdpRelayWatchdog") {
            onLog("[DEBUG] Watchdog iniciado")
            while (watchdogRunning) {
                try {
                    Thread.sleep(1000)
                    val bytes = bytesReadThisSecond.getAndSet(0)
                    if (bytes > 0) {
                        onLog("[DEBUG] tun0 throughput: $bytes bytes/s")
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastPacketTimestamp.get() > 10000) {
                        onLog("[DEBUG] Nenhum dado na tun0 - Switch pode estar desconectado")
                        lastPacketTimestamp.set(now) // Reset to avoid constant logging
                    }
                    
                    cleanupIdleSockets(now)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    onLog("[DEBUG] Watchdog Erro: ${e.message}")
                }
            }
            onLog("[DEBUG] Watchdog parado")
        }
    }

    private fun cleanupIdleSockets(now: Long) {
        val iterator = sockets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastActivity > IDLE_TIMEOUT_MS) {
                onLog("[RELAY] Fechando socket ocioso na porta ${entry.key}")
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
        // Log diagnostics BEFORE any filter
        bytesReadThisSecond.addAndGet(length.toLong())
        lastPacketTimestamp.set(System.currentTimeMillis())

        val firstByte = packet[0].toInt() and 0xFF
        val now = System.currentTimeMillis()
        if (firstByte != lastLoggedFirstByte || now - lastFirstByteLogTime > 5000) {
            val type = when (firstByte shr 4) {
                4 -> "IPv4"
                6 -> "IPv6"
                else -> "Desconhecido"
            }
            onLog("[DEBUG] tun0 packet start: 0x${Integer.toHexString(firstByte).uppercase()} ($type) Len: $length")
            lastLoggedFirstByte = firstByte
            lastFirstByteLogTime = now
        }

        if (length < 28) return false // IP(20) + UDP(8)

        // Basic IPv4 Parsing
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return false

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false // UDP Only

        val dstIp = getIpString(packet, 16)
        if (dstIp != GATEWAY_IP) return false

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
                val socket = DatagramSocket(dstPort, InetAddress.getByName(GATEWAY_IP))
                vpnService.protect(socket)
                onLog("[RELAY] Novo socket na porta $dstPort")
                val newManaged = ManagedSocket(socket, srcIp, srcPort, System.currentTimeMillis())
                val existing = sockets.putIfAbsent(dstPort, newManaged)
                if (existing != null) {
                    socket.close()
                    managed = existing
                } else {
                    managed = newManaged
                }
            } catch (e: Exception) {
                onLog("[RELAY] Erro ao criar socket na porta $dstPort: ${e.message}")
                return
            }
        }

        managed.lastSourceIp = srcIp
        managed.lastSourcePort = srcPort
        managed.lastActivity = System.currentTimeMillis()

        onLog("[RELAY] Recebido ${payload.size} bytes de ${srcIp.hostAddress}:$srcPort -> porta $dstPort")
        redirector.forwardToServer(payload, payload.size, dstPort)
    }

    fun processFromServer(payload: ByteArray, gatewayPort: Int) {
        val managed = if (gatewayPort == 0) {
            sockets.values.maxByOrNull { it.lastActivity }
        } else {
            sockets[gatewayPort]
        } ?: return

        try {
            val rawPacket = buildRawUdpPacket(
                srcIp = InetAddress.getByName(GATEWAY_IP),
                srcPort = gatewayPort.takeIf { it != 0 } ?: managed.socket.localPort,
                dstIp = managed.lastSourceIp,
                dstPort = managed.lastSourcePort,
                payload = payload
            )
            onTunWrite(rawPacket)
            onLog("[RELAY] Enviado ${payload.size} bytes para ${managed.lastSourceIp.hostAddress}:${managed.lastSourcePort}")
        } catch (e: Exception) {
            onLog("[RELAY] Erro ao injetar: ${e.message}")
        }
    }

    private fun buildRawUdpPacket(
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        // IP Header
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[1] = 0.toByte()    // TOS
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0.toByte()    // ID
        packet[5] = 0.toByte()
        packet[6] = 0x40.toByte() // Flags: Don't Fragment
        packet[7] = 0.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // Protocol UDP
        
        val srcIpBytes = srcIp.address
        val dstIpBytes = dstIp.address
        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(dstIpBytes, 0, packet, 16, 4)

        // IP Checksum
        val ipChecksum = calculateChecksum(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        val udpOffset = 20
        packet[udpOffset] = (srcPort shr 8).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = (dstPort shr 8).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        packet[udpOffset + 4] = (udpLen shr 8).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0.toByte() // Checksum 0
        packet[udpOffset + 7] = 0.toByte()

        // Payload
        System.arraycopy(payload, 0, packet, 28, payload.size)

        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            if (i == offset + 10) { // Skip checksum field itself
                i += 2
                continue
            }
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
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
        sockets.values.forEach { 
            try { it.socket.close() } catch (e: Exception) {}
        }
        sockets.clear()
    }
}
