package com.p2p.monitor.network

import com.p2p.monitor.model.Protocol
import com.p2p.monitor.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TcpServer(
    private val port: Int = Protocol.DEFAULT_PORT,
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    private val onTouchReceived: (ByteArray) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private val lock = Any()
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var clientReadThread: Thread? = null
    private var senderThread: Thread? = null
    private val sendQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var connected = false

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        startSenderThread()

        serverThread = Thread {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(java.net.InetSocketAddress("0.0.0.0", port))
                Logger.d("Server started on port $port, listening on 0.0.0.0")

                while (isRunning.get()) {
                    try {
                        val accepted = serverSocket?.accept() ?: continue
                        accepted.tcpNoDelay = true
                        accepted.sendBufferSize = 2048
                        accepted.receiveBufferSize = 2048
                        Logger.d("Client connected: ${accepted.inetAddress}")
                        synchronized(lock) {
                            stopClientReadThread()
                            sendQueue.clear()
                            clientSocket?.close()
                            clientSocket = accepted
                            outputStream = accepted.getOutputStream()
                            inputStream = accepted.getInputStream()
                        }
                        connected = true
                        startClientReadThread()
                        onClientConnected()
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Logger.e("Error accepting connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Logger.e("Server error", e)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun startSenderThread() {
        senderThread = Thread {
            while (isRunning.get()) {
                val packet = sendQueue.poll()
                if (packet != null) {
                    writePacket(packet)
                } else {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {}
                }
            }
        }.apply {
            isDaemon = true
            name = "TcpServer-Sender"
            start()
        }
    }

    private fun writePacket(packet: ByteArray) {
        synchronized(lock) {
            val socket = clientSocket ?: return
            val output = outputStream ?: return
            try {
                if (socket.isClosed || !socket.isConnected) return
                output.write(packet)
                output.flush()
            } catch (e: Exception) {
                Logger.e("Error sending packet", e)
                disconnectInternal()
            }
        }
    }

    private fun startClientReadThread() {
        clientReadThread = Thread {
            try {
                while (isRunning.get()) {
                    val stream = inputStream ?: break
                    val header = ByteArray(Protocol.PACKET_HEADER_SIZE)
                    var bytesRead = 0
                    while (bytesRead < Protocol.PACKET_HEADER_SIZE) {
                        val read = stream.read(header, bytesRead, Protocol.PACKET_HEADER_SIZE - bytesRead)
                        if (read == -1) throw Exception("Client disconnected")
                        bytesRead += read
                    }

                    val length = Protocol.readLength(header)
                    if (length <= 0 || length > 1024 * 1024) {
                        throw Exception("Invalid packet length: $length")
                    }

                    val type = Protocol.readType(header[Protocol.HEADER_SIZE])

                    val data = ByteArray(length)
                    bytesRead = 0
                    while (bytesRead < length) {
                        val read = stream.read(data, bytesRead, length - bytesRead)
                        if (read == -1) throw Exception("Client disconnected")
                        bytesRead += read
                    }

                    when (type) {
                        Protocol.TYPE_TOUCH -> onTouchReceived(data)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Logger.d("Client read ended: ${e.message}")
                    setDisconnected()
                    onClientDisconnected()
                }
            }
        }.apply {
            isDaemon = true
            name = "TcpServer-ClientRead"
            start()
        }
    }

    private fun stopClientReadThread() {
        clientReadThread?.interrupt()
        clientReadThread = null
    }

    private fun setDisconnected() {
        connected = false
        synchronized(lock) {
            outputStream?.close()
            outputStream = null
            inputStream?.close()
            inputStream = null
            clientSocket?.close()
            clientSocket = null
        }
        sendQueue.clear()
    }

    fun sendFrame(data: ByteArray): Boolean {
        if (!connected) return false
        if (sendQueue.size > 2) {
            sendQueue.clear()
            Logger.w("Send queue overflow, dropping frames")
        }
        sendQueue.offer(Protocol.createPacket(data))
        return true
    }

    fun sendConfig(data: ByteArray): Boolean {
        if (!connected) return false
        sendQueue.offer(Protocol.createConfigPacket(data))
        return true
    }

    fun sendResolution(width: Int, height: Int): Boolean {
        if (!connected) return false
        sendQueue.offer(Protocol.createResolutionPacket(width, height))
        return true
    }

    fun isConnected(): Boolean = connected

    private fun disconnectInternal() {
        setDisconnected()
        stopClientReadThread()
        onClientDisconnected()
    }

    fun disconnect() {
        setDisconnected()
        stopClientReadThread()
    }

    fun stop() {
        isRunning.set(false)
        disconnect()
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Logger.e("Error stopping server", e)
        }
    }
}
