package com.example.lanplaypoc

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LanPlayProtocol {
    const val MAGIC_NUMBER = 0x11451400
    const val DEFAULT_PORT = 11451
    const val HEADER_SIZE = 10

    enum class PacketType(val value: Byte) {
        SCAN(0x00),
        SCAN_RESP(0x01),
        CONNECT(0x02),
        SYNC_NETWORK(0x03),
        UNKNOWN(-1);

        companion object {
            fun fromByte(value: Byte): PacketType =
                entries.find { it.value == value } ?: UNKNOWN
        }
    }

    data class Header(
        val magic: Int,
        val type: PacketType,
        val compressed: Byte,
        val length: Short,
        val decompressLength: Short
    ) {
        fun toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_SIZE)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(magic)
            buffer.put(type.value)
            buffer.put(compressed)
            buffer.putShort(length)
            buffer.putShort(decompressLength)
            return buffer.array()
        }

        companion object {
            fun fromBytes(data: ByteArray): Header? {
                if (data.size < HEADER_SIZE) return null
                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)
                val magic = buffer.getInt()
                if (magic != MAGIC_NUMBER) return null
                
                val type = PacketType.fromByte(buffer.get())
                val compressed = buffer.get()
                val length = buffer.getShort()
                val decompressLength = buffer.getShort()
                
                return Header(magic, type, compressed, length, decompressLength)
            }
        }
    }
}
