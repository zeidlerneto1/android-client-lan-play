package com.example.lanplaypoc

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class UdpRelayServer(
    private val vpnService: VpnService,
    private val redirector: ServerRedirector,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val GATEWAY_IP = "10.13.37.1"
    }

    private val sockets = ConcurrentHashMap<Int, ManagedSocket>()

    data class ManagedSocket(
        val socket: DatagramSocket,
        var lastSourceIp: InetAddress,
        var lastSourcePort: Int,
        var lastActivity: Long
    )

    fun processFromTun(packet: ByteArray, length: Int): Boolean {
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

        onLog("[RELAY] Recebido ${payload.size} bytes de ${srcIp.hostAddress}:$srcPort na porta $dstPort")
        redirector.forwardToServer(payload, payload.size, dstPort)
    }

    fun processFromServer(payload: ByteArray, gatewayPort: Int) {
        val managed = if (gatewayPort == 0) {
            sockets.values.maxByOrNull { it.lastActivity }
        } else {
            sockets[gatewayPort]
        } ?: return

        try {
            val packet = DatagramPacket(payload, payload.size, managed.lastSourceIp, managed.lastSourcePort)
            managed.socket.send(packet)
            onLog("[RELAY] Enviado ${payload.size} bytes para ${managed.lastSourceIp.hostAddress}:${managed.lastSourcePort}")
        } catch (e: Exception) {
            onLog("[RELAY] Erro ao enviar: ${e.message}")
        }
    }

    private fun getIpString(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}.${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    private fun getPort(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }
    
    fun stop() {
        sockets.values.forEach { 
            try { it.socket.close() } catch (e: Exception) {}
        }
        sockets.clear()
    }
}
