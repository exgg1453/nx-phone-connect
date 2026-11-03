package com.nxteam.nxconnect

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

enum class MessageKind(val value: Int) {
    HELLO(1),
    ACCEPTED(2),
    REJECTED(3),
    FRAME(4),
    TOUCH(5),
    KEY(6),
    FRAME_ACKNOWLEDGED(7);

    companion object {
        fun fromValue(value: Int): MessageKind? = values().firstOrNull { it.value == value }
    }
}

class FramedSocket(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val writeLock = Any()

    init {
        socket.tcpNoDelay = true
    }

    fun setReadTimeout(milliseconds: Int) {
        socket.soTimeout = milliseconds
    }

    fun send(kind: MessageKind, payload: ByteArray = ByteArray(0)): Boolean {
        return try {
            synchronized(writeLock) {
                output.writeByte(kind.value)
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            }
            true
        } catch (exception: IOException) {
            false
        }
    }

    fun readMessage(): Pair<MessageKind, ByteArray>? {
        try {
            while (true) {
                val kindValue = input.readUnsignedByte()
                val length = input.readInt()
                if (length < 0 || length > MAXIMUM_PAYLOAD_LENGTH) {
                    return null
                }
                val payload = ByteArray(length)
                input.readFully(payload)
                val kind = MessageKind.fromValue(kindValue)
                if (kind != null) {
                    return Pair(kind, payload)
                }
            }
        } catch (exception: IOException) {
            return null
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (exception: IOException) {
        }
    }

    companion object {
        private const val MAXIMUM_PAYLOAD_LENGTH = 8_000_000
    }
}
