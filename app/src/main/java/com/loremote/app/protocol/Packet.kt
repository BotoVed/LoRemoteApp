package com.loremote.app.protocol

object PacketType {
    const val CONFIRM = 1
    const val STATUS  = 2
    const val PUSH    = 3
    const val CONFIG  = 4
    const val CMD     = 5
    const val PING    = 6
}

data class OutPacket(
    val tp: Int,
    val id: String?     = null,
    val s: Any?         = null,
    val bri: Int?       = null,
    val ct: Int?        = null,
    val th: Double?     = null,
    val md: String?     = null,
    val fn: String?     = null,
    val sp: Int?        = null,
    val pos: Int?       = null,
    val cmd: String?    = null,
    val pin: String?    = null,
    val req: Any?       = null,
    val cfg: Int?       = null,
    val ts: Long?       = null,
    val hl: Int?        = null
)
