package com.p2p.monitor.receiver

import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.monitor.codec.VideoDecoder
import com.p2p.monitor.model.Protocol
import com.p2p.monitor.model.TouchEvent
import com.p2p.monitor.network.ConnectionState
import com.p2p.monitor.network.TcpClient
import com.p2p.monitor.util.ConnectionHistoryManager
import com.p2p.monitor.util.ConnectionRecord
import com.p2p.monitor.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReceiverViewModel : ViewModel() {
    private var tcpClient: TcpClient? = null
    private var connectionHistoryManager: ConnectionHistoryManager? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var videoDecoder: VideoDecoder? = null

    @Volatile
    private var streamWidth = 640

    @Volatile
    private var streamHeight = 360

    @Volatile
    private var lastConfigData: ByteArray? = null

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectionHistory = MutableStateFlow<List<ConnectionRecord>>(emptyList())
    val connectionHistory: StateFlow<List<ConnectionRecord>> = _connectionHistory.asStateFlow()

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    private val _streamResolution = MutableStateFlow(Pair(640, 360))
    val streamResolution: StateFlow<Pair<Int, Int>> = _streamResolution.asStateFlow()

    @Volatile
    var debugInfo = "等待连接..."
        private set

    @Volatile
    var frameCount = 0
        private set

    @Volatile
    var configReceived = false
        private set

    fun init(context: Context) {
        connectionHistoryManager = ConnectionHistoryManager(context)
        loadHistory()
    }

    fun updateIpAddress(ip: String) {
        _ipAddress.value = ip
    }

    fun toggleHistory() {
        _showHistory.value = !_showHistory.value
    }

    fun setSurface(surface: Surface) {
        this.surface = surface
        tryInitDecoder()
    }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        this.surface = surface
        surfaceWidth = width
        surfaceHeight = height
        Logger.d("Surface changed: ${width}x${height}")
        tryInitDecoder()
    }

    fun clearSurface() {
        this.surface = null
    }

    private fun tryInitDecoder() {
        val s = surface ?: run {
            Logger.d("tryInitDecoder: surface is null, skipping")
            return
        }
        val config = lastConfigData ?: run {
            Logger.d("tryInitDecoder: config is null, skipping")
            return
        }
        try {
            releaseDecoder()
            val decoder = VideoDecoder()
            decoder.onConfigData(config, s, streamWidth, streamHeight, surfaceWidth, surfaceHeight)
            videoDecoder = decoder
            Logger.d("Decoder initialized OK: ${streamWidth}x${streamHeight}, configSize=${config.size}")
        } catch (e: Exception) {
            Logger.e("Failed to init decoder", e)
        }
    }

    private fun startDecoderRetryLoop() {
        viewModelScope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                kotlinx.coroutines.delay(1000)
                if (videoDecoder == null && surface != null && lastConfigData != null) {
                    Logger.d("Retry: attempting to init decoder")
                    tryInitDecoder()
                }
            }
        }
    }

    fun connect() {
        val ip = _ipAddress.value.trim()
        if (ip.isEmpty()) {
            _statusMessage.value = "请输入IP地址"
            return
        }

        _statusMessage.value = "正在连接 $ip:8888..."
        connectionHistoryManager?.addRecord(ip)
        loadHistory()

        lastConfigData = null
        releaseDecoder()

        tcpClient = TcpClient(
            onConfigReceived = { configData ->
                Logger.d("Received config, size=${configData.size}")
                lastConfigData = configData
                configReceived = true
                debugInfo = "config: ${configData.size}B, decoder: ${if (videoDecoder != null) "OK" else "null"}"
                tryInitDecoder()
                debugInfo = "config: ${configData.size}B, decoder: ${if (videoDecoder != null) "OK" else "FAIL"}"
            },
            onFrameReceived = { data ->
                frameCount++
                videoDecoder?.decode(data)
            },
            onResolutionReceived = { width, height ->
                Logger.d("Received resolution: ${width}x${height}")
                streamWidth = width
                streamHeight = height
                _streamResolution.value = Pair(width, height)
                debugInfo = "res: ${width}x${height}, frames: $frameCount"
            }
        )

        viewModelScope.launch {
            tcpClient?.connectionState?.collect { state ->
                _connectionState.value = state
                _statusMessage.value = when (state) {
                    ConnectionState.DISCONNECTED -> "未连接"
                    ConnectionState.CONNECTING -> "正在连接..."
                    ConnectionState.CONNECTED -> "已连接 - 等待画面..."
                    ConnectionState.RECONNECTING -> "连接断开，正在重连..."
                }
                if (state == ConnectionState.CONNECTED) {
                    debugInfo = "已连接, 等待数据..."
                    frameCount = 0
                    configReceived = false
                    startDecoderRetryLoop()
                }
            }
        }

        tcpClient?.connect(ip)
    }

    private fun releaseDecoder() {
        try {
            videoDecoder?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing decoder", e)
        }
        videoDecoder = null
    }

    fun disconnect() {
        tcpClient?.stop()
        tcpClient = null
        lastConfigData = null
        releaseDecoder()
        _connectionState.value = ConnectionState.DISCONNECTED
        _statusMessage.value = "已断开"
    }

    fun sendTouchEvent(motionEvent: MotionEvent, viewWidth: Float, viewHeight: Float) {
        if (tcpClient?.isConnected() != true) return

        val x = motionEvent.x / viewWidth * streamWidth
        val y = motionEvent.y / viewHeight * streamHeight

        val action = when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchEvent.ACTION_DOWN
            MotionEvent.ACTION_UP -> TouchEvent.ACTION_UP
            MotionEvent.ACTION_MOVE -> TouchEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_DOWN -> TouchEvent.ACTION_POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchEvent.ACTION_POINTER_UP
            MotionEvent.ACTION_CANCEL -> TouchEvent.ACTION_CANCEL
            else -> return
        }

        val pointerIndex = motionEvent.actionIndex
        val pointerId = motionEvent.getPointerId(pointerIndex)

        val touchData = Protocol.createTouchAction(action, x, y.toFloat(), pointerId)
        tcpClient?.sendTouchData(touchData)
    }

    private fun loadHistory() {
        _connectionHistory.value = connectionHistoryManager?.getHistory() ?: emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
