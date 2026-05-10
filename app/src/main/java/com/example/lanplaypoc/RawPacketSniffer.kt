package com.example.lanplaypoc

import android.util.Log

class RawPacketSniffer(private val onLog: (String) -> Unit) {

    fun parseAndLog(packet: ByteArray, length: Int) {
        if (length < 20) return // Minimum IPv4 header length

        // Basic IPv4 Parsing
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return // Only IPv4

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = getIpString(packet, 12)
        val dstIp = getIpString(packet, 16)

        // Filter for 10.13.x.x subnet
        if (!srcIp.startsWith("10.13.") && !dstIp.startsWith("10.13.")) {
            return
        }

        val protoStr = when (protocol) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "PROTO $protocol"
        }

        val ihl = (packet[0].toInt() and 0x0F) * 4
        var logMsg = "[$protoStr] "

        if (protocol == 6 || protocol == 17) {
            val srcPort = getPort(packet, ihl)
            val dstPort = getPort(packet, ihl + 2)
            logMsg += "$srcIp:$srcPort -> $dstIp:$dstPort"
        } else {
            logMsg += "$srcIp -> $dstIp"
        }

        logMsg += " (Len: $length)"
        
        Log.d("RawPacketSniffer", logMsg)
        onLog(logMsg)
    }

    private fun getIpString(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}.${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    private fun getPort(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }
}
