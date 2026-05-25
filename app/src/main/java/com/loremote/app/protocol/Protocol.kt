package com.loremote.app.protocol

import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object Protocol {
    fun encodeOutPacket(packet: OutPacket): ByteArray {
        val buffer = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(buffer)
        packer.packArrayHeader(4)
        packer.packInt(packet.type.value.toInt())
        packer.packInt(packet.id)
        packer.packInt(packet.hop_limit)
        packer.packBinaryHeader(packet.data.size)
        packer.writePayload(packet.data)
        packer.close()
        return buffer.toByteArray()
    }

    fun decodeInPacket(data: ByteArray): InPacket {
        val unpacker = MessagePack.newDefaultUnpacker(data)
        unpacker.unpackArrayHeader()

        val typeByte = unpacker.unpackInt()
        val id = unpacker.unpackInt()
        val hopLimit = unpacker.unpackInt()
        val bytes = ByteArray(unpacker.unpackBinaryHeader().toInt())
        unpacker.readPayload(bytes)
        unpacker.close()

        val type = PacketType.entries.find { it.value.toByte() == typeByte.toByte() }
            ?: throw IllegalArgumentException("Unknown type: $typeByte")

        return InPacket(type, id, hopLimit, bytes)
    }
}
