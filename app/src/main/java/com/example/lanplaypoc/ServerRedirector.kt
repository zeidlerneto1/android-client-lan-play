package com.example.lanplaypoc

import android.net.VpnService
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
    @Volatile
    private var running = false
    private var serverIp: InetAddress? = null
    private var serverPort: Int = LanPlayProtocol.DEFAULT_PORT
    private var relay: UdpRelayServer? = null

    private val sendBuffer = ByteBuffer.allocate(LanPlayProtocol.HEADER_SIZE + 2048)

    fun setRelay(relay: UdpRelayServer) {
        this.relay = relay
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        
        receiveThread = Thread {
            try {
                val parts = serverAddress.split(":")
                serverIp = InetAddress.getByName(parts[0])
                serverPort = if (parts.size > 1) parts[1].toInt() else LanPlayProtocol.DEFAULT_PORT
                
                socket = DatagramSocket().apply {
                    vpnService.protect(this)
                }
                
                val buffer = ByteArray(4096)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    handleServerPacket(packet.data, packet.length)
                }
            } catch (e: Exception) {
                if (running) onLog("[ERROR] Redirector: ${e.message}")
            } finally {
                stop()
            }
        }.apply { start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        socket?.close()
        socket = null
        receiveThread?.interrupt()
        receiveThread = null
    }

    fun forwardToServer(payload: ByteArray, length: Int, sourcePort: Int = 0) {
        val ip = serverIp ?: return
        val s = socket ?: return
        try {
            synchronized(sendBuffer) {
                sendBuffer.clear()
                val header = LanPlayProtocol.Header(
                    magic = LanPlayProtocol.MAGIC_NUMBER,
                    type = LanPlayProtocol.PacketType.CONNECT,
                    compressed = 0,
                    length = length.toShort(),
                    decompressLength = length.toShort()
                ).toBytes()
                
                sendBuffer.put(header)
                sendBuffer.put(payload, 0, length)
                
                val datagram = DatagramPacket(sendBuffer.array(), 0, LanPlayProtocol.HEADER_SIZE + length, ip, serverPort)
                s.send(datagram)
                onLog("[REMOTO] Enviado para $serverAddress | Payload: $length bytes")
            }
        } catch (e: Exception) {
            onLog("[ERROR] Send Error: ${e.message}")
        }
    }

    private fun handleServerPacket(data: ByteArray, length: Int) {
        if (length < LanPlayProtocol.HEADER_SIZE) return
        
        val header = LanPlayProtocol.Header.fromBytes(data)
        if (header != null && header.magic == LanPlayProtocol.MAGIC_NUMBER) {
            val payloadLength = length - LanPlayProtocol.HEADER_SIZE
            val payload = data.copyOfRange(LanPlayProtocol.HEADER_SIZE, length)
            onLog("[REMOTO] Recebido de $serverAddress | Payload: $payloadLength bytes")
            
            val r = relay
            if (r != null) {
                r.processFromServer(payload, 0)
            } else {
                onPacketReceived(payload)
            }
        }
    }
}
