package com.loremote.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OutPacket(
    val type: PacketType,
    val hop_limit: Int,
    val data: ByteArray
) {
    val id = (1..65535).random()
    val payloadSize = 2 + 1 + data.size
    val size = payloadSize + 1

    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.put(type.value)
        buf.putShort(id.toShort())
        buf.put(hop_limit.toByte())
        buf.put(data)
        return buf.array()
    }
}

data class InPacket(
    val type: PacketType,
    val id: Int,
    val hop_limit: Int,
    val data: ByteArray
)

enum class PacketType(val value: Byte) {
    PING(-1),
    PONG(-2),
    DATA(-3),
    DISCONNECT(-4)
}
