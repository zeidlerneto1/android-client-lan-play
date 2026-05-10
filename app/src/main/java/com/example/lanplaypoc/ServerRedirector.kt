package com.example.lanplaypoc

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class ServerRedirector(
    private val vpnService: VpnService,
    private val serverAddress: String,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var running = false
    private val serverIp: InetAddress
    private val serverPort: Int

    init {
        val parts = serverAddress.split(":")
        serverIp = InetAddress.getByName(parts[0])
        serverPort = if (parts.size > 1) parts[1].toInt() else LanPlayProtocol.DEFAULT_PORT
    }

    fun start() {
        running = true
        socket = DatagramSocket().apply {
            vpnService.protect(this)
        }
        
        receiveThread = Thread {
            val buffer = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    handleServerPacket(packet.data, packet.length)
                } catch (e: Exception) {
                    if (running) onLog("Receive Error: ${e.message}")
                }
            }
        }.apply { start() }
        onLog("Redirector started for $serverAddress")
    }

    fun stop() {
        running = false
        socket?.close()
        receiveThread?.interrupt()
        socket = null
        receiveThread = null
    }

    fun forwardToServer(packet: ByteArray, length: Int) {
        try {
            val header = LanPlayProtocol.Header(
                magic = LanPlayProtocol.MAGIC_NUMBER,
                type = LanPlayProtocol.PacketType.CONNECT,
                compressed = 0,
                length = length.toShort(),
                decompressLength = length.toShort()
            ).toBytes()

            val combined = ByteBuffer.allocate(LanPlayProtocol.HEADER_SIZE + length)
            combined.put(header)
            combined.put(packet, 0, length)
            
            val data = combined.array()
            val datagram = DatagramPacket(data, data.size, serverIp, serverPort)
            socket?.send(datagram)
            // Log.d("ServerRedirector", "Sent ${data.size} bytes to server")
        } catch (e: Exception) {
            onLog("Send Error: ${e.message}")
        }
    }

    private fun handleServerPacket(data: ByteArray, length: Int) {
        if (length < LanPlayProtocol.HEADER_SIZE) return
        
        val header = LanPlayProtocol.Header.fromBytes(data.copyOfRange(0, LanPlayProtocol.HEADER_SIZE))
        if (header != null && header.magic == LanPlayProtocol.MAGIC_NUMBER) {
            val payloadLength = length - LanPlayProtocol.HEADER_SIZE
            val payload = data.copyOfRange(LanPlayProtocol.HEADER_SIZE, length)
            onPacketReceived(payload)
            // Log.d("ServerRedirector", "Received $payloadLength bytes from server")
        }
    }
}
