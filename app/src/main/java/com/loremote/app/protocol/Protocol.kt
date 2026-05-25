package com.loremote.app.protocol

import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream

object Protocol {

    fun encode(packet: OutPacket): ByteArray {
        val out = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(out)

        val fields = linkedMapOf<String, Any?>()
        fields["tp"] = packet.tp
        packet.id?.let  { fields["id"]  = it }
        packet.s?.let   { fields["s"]   = it }
        packet.bri?.let { fields["bri"] = it }
        packet.ct?.let  { fields["ct"]  = it }
        packet.th?.let  { fields["th"]  = it }
        packet.md?.let  { fields["md"]  = it }
        packet.fn?.let  { fields["fn"]  = it }
        packet.sp?.let  { fields["sp"]  = it }
        packet.pos?.let { fields["pos"] = it }
        packet.cmd?.let { fields["cmd"] = it }
        packet.pin?.let { fields["pin"] = it }
        packet.req?.let { fields["req"] = it }
        packet.cfg?.let { fields["cfg"] = it }
        packet.ts?.let  { fields["ts"]  = it }
        packet.hl?.let  { fields["hl"]  = it }

        packer.packMapHeader(fields.size)
        for ((k, v) in fields) {
            packer.packString(k)
            when (v) {
                is Int     -> packer.packInt(v)
                is Long    -> packer.packLong(v)
                is Double  -> packer.packDouble(v)
                is Float   -> packer.packFloat(v)
                is String  -> packer.packString(v)
                is Boolean -> packer.packBoolean(v)
                else       -> packer.packString(v.toString())
            }
        }
        packer.flush()
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<String, Any?> {
        val unpacker = MessagePack.newDefaultUnpacker(bytes)
        val size = unpacker.unpackMapHeader()
        val map = mutableMapOf<String, Any?>()
        repeat(size) {
            val key = unpacker.unpackString()
            val fmt = unpacker.nextFormat
            val value: Any? = when (fmt.valueType.name) {
                "INTEGER" -> unpacker.unpackLong()
                "FLOAT"   -> unpacker.unpackDouble()
                "STRING"  -> unpacker.unpackString()
                "BOOLEAN" -> unpacker.unpackBoolean()
                "NIL"     -> { unpacker.unpackNil(); null }
                else      -> { unpacker.skipValue(); null }
            }
            map[key] = value
        }
        return map
    }

    fun ping() = OutPacket(tp = PacketType.PING, ts = System.currentTimeMillis() / 1000)
    fun requestAll() = OutPacket(tp = PacketType.CMD, req = "all")
    fun requestConfig() = OutPacket(tp = PacketType.CMD, cfg = 2)
}
