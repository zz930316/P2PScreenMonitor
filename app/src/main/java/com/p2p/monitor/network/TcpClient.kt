package com.p2p.monitor.network

import com.p2p.monitor.model.Protocol
import com.p2p.monitor.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TcpClient(
    private val onFrameReceived: (ByteArray) -> Unit = {},
    private val onConfigReceived: (ByteArray) -> Unit = {},
    private val onTouchReceived: (ByteArray) -> Unit = {},
    private val onResolutionReceived: (Int, Int) -> Unit = { _, _ -> }
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var readThread: Thread? = null
    private var clientScope: CoroutineScope? = null
    private val writeLock = Any()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private var currentIp: String = ""
    private var currentPort: Int = Protocol.DEFAULT_PORT

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val BASE_RECONNECT_DELAY_MS = 2000L
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    fun connect(ip: String, port: Int = Protocol.DEFAULT_PORT) {
        currentIp = ip
        currentPort = port
        _connectionState.value = ConnectionState.CONNECTING
        _reconnectAttempt.value = 0
        isRunning.set(true)

        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope?.launch {
            connectInternal()
        }
    }

    private suspend fun connectInternal() {
        try {
            disconnectInternal()

            val s = Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = 2048
            s.receiveBufferSize = 2048
            s.connect(InetSocketAddress(currentIp, currentPort), CONNECT_TIMEOUT_MS)
            socket = s
            inputStream = s.getInputStream()
            outputStream = s.getOutputStream()
            _connectionState.value = ConnectionState.CONNECTED
            _reconnectAttempt.value = 0
            Logger.d("Connected to $currentIp:$currentPort")

            startReading()
        } catch (e: Exception) {
            Logger.e("Connection failed", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect()
        }
    }

    private fun startReading() {
        readThread = Thread {
            try {
                while (isRunning.get() && socket?.isConnected == true) {
                    val header = ByteArray(Protocol.PACKET_HEADER_SIZE)
                    var bytesRead = 0
                    while (bytesRead < Protocol.PACKET_HEADER_SIZE) {
                        val read = inputStream?.read(header, bytesRead, Protocol.PACKET_HEADER_SIZE - bytesRead) ?: -1
                        if (read == -1) throw Exception("Connection closed")
                        bytesRead += read
                    }

                    val length = Protocol.readLength(header)
                    if (length <= 0 || length > 10 * 1024 * 1024) {
                        throw Exception("Invalid frame length: $length")
                    }

                    val type = Protocol.readType(header[Protocol.HEADER_SIZE])

                    val data = ByteArray(length)
                    bytesRead = 0
                    while (bytesRead < length) {
                        val read = inputStream?.read(data, bytesRead, length - bytesRead) ?: -1
                        if (read == -1) throw Exception("Connection closed")
                        bytesRead += read
                    }

                    when (type) {
                        Protocol.TYPE_CONFIG -> onConfigReceived(data)
                        Protocol.TYPE_TOUCH -> onTouchReceived(data)
                        Protocol.TYPE_RESOLUTION -> {
                            val res = Protocol.parseResolution(data)
                            if (res != null) {
                                onResolutionReceived(res.first, res.second)
                            }
                        }
                        else -> onFrameReceived(data)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Logger.e("Read error", e)
                    _connectionState.value = ConnectionState.DISCONNECTED
                    clientScope?.launch {
                        attemptReconnect()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun sendTouchData(data: ByteArray): Boolean {
        if (!isConnected()) return false
        return synchronized(writeLock) {
            try {
                val packet = Protocol.createTouchPacket(data)
                outputStream?.write(packet)
                outputStream?.flush()
                true
            } catch (e: Exception) {
                Logger.e("Error sending touch data", e)
                false
            }
        }
    }

    private suspend fun attemptReconnect() {
        if (!isRunning.get()) return

        val attempt = _reconnectAttempt.value + 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            Logger.w("Max reconnect attempts reached")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _reconnectAttempt.value = attempt
        _connectionState.value = ConnectionState.RECONNECTING

        val delay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1))
        Logger.d("Reconnect attempt $attempt in ${delay}ms")

        delay(delay)

        if (isRunning.get()) {
            connectInternal()
        }
    }

    private fun disconnectInternal() {
        try {
            outputStream?.close()
            outputStream = null
            inputStream?.close()
            inputStream = null
            socket?.close()
            socket = null
            readThread?.interrupt()
            readThread = null
        } catch (e: Exception) {
            Logger.e("Error disconnecting", e)
        }
    }

    fun disconnect() {
        isRunning.set(false)
        disconnectInternal()
    }

    fun stop() {
        disconnect()
        clientScope?.cancel()
        clientScope = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _reconnectAttempt.value = 0
    }

    fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}
