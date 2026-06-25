package com.p2p.monitor

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val sendQueue = ConcurrentLinkedQueue<ByteArray>()
    val connected = AtomicBoolean(true)

    fun sendFrame(data: ByteArray): Boolean {
        if (!connected.get()) return false
        if (sendQueue.size > 5) {
            sendQueue.clear()
            println("  [DROP] Queue overflow, cleared queue")
        }
        sendQueue.offer(data)
        return true
    }

    println("=== Queue limit test (limit=5) ===")
    for (i in 1..6) {
        val result = sendFrame("frame$i".toByteArray())
        println("  Send frame$i: result=$result, queueSize=${sendQueue.size}")
    }

    println("\nResult: queue size=${sendQueue.size}, expected<=5, PASS=${sendQueue.size <= 5}")

    sendQueue.clear()
    println("\n=== Test 2: Send 10 frames ===")
    for (i in 1..10) {
        sendFrame("frame$i".toByteArray())
    }
    println("  Queue size after 10 sends: ${sendQueue.size}, PASS=${sendQueue.size <= 5}")
}
