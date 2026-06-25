package com.p2p.monitor.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val HEADER_SIZE = 4
    const val TYPE_SIZE = 1
    const val PACKET_HEADER_SIZE = HEADER_SIZE + TYPE_SIZE
    const val DEFAULT_PORT = 8888

    const val TYPE_FRAME = 0
    const val TYPE_CONFIG = 1
    const val TYPE_TOUCH = 2
    const val TYPE_RESOLUTION = 3

    fun createPacket(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + TYPE_SIZE + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(data.size)
            put(TYPE_FRAME.toByte())
            put(data)
        }
        return buffer.array()
    }

    fun createConfigPacket(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + TYPE_SIZE + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(data.size)
            put(TYPE_CONFIG.toByte())
            put(data)
        }
        return buffer.array()
    }

    fun createTouchPacket(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + TYPE_SIZE + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(data.size)
            put(TYPE_TOUCH.toByte())
            put(data)
        }
        return buffer.array()
    }

    fun createResolutionPacket(width: Int, height: Int): ByteArray {
        val data = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(width)
            putInt(height)
        }.array()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + TYPE_SIZE + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(data.size)
            put(TYPE_RESOLUTION.toByte())
            put(data)
        }
        return buffer.array()
    }

    fun readLength(header: ByteArray): Int {
        return ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
    }

    fun readType(typeByte: Byte): Int {
        return typeByte.toInt() and 0xFF
    }

    fun createTouchAction(
        action: Int,
        x: Float,
        y: Float,
        pointerId: Int = 0
    ): ByteArray {
        return ByteBuffer.allocate(13).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(action.toByte())
            putFloat(x)
            putFloat(y)
            put(pointerId.toByte())
        }.array()
    }

    fun parseTouchAction(data: ByteArray): TouchEvent? {
        if (data.size < 13) return null
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).let {
            TouchEvent(
                action = it.get().toInt(),
                x = it.float,
                y = it.float,
                pointerId = it.get().toInt()
            )
        }
    }

    fun parseResolution(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 8) return null
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).let {
            Pair(it.int, it.int)
        }
    }
}

data class TouchEvent(
    val action: Int,
    val x: Float,
    val y: Float,
    val pointerId: Int
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_POINTER_DOWN = 3
        const val ACTION_POINTER_UP = 4
        const val ACTION_CANCEL = 5
    }
}
