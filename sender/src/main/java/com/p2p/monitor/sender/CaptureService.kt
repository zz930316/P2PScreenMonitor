package com.p2p.monitor.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.p2p.monitor.capture.ScreenCapture
import com.p2p.monitor.codec.VideoEncoder
import com.p2p.monitor.model.Protocol
import com.p2p.monitor.model.TouchEvent
import com.p2p.monitor.network.TcpServer
import com.p2p.monitor.util.Logger

class CaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var screenCapture: ScreenCapture? = null
    private var videoEncoder: VideoEncoder? = null
    private var tcpServer: TcpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var cachedConfigData: ByteArray? = null

    @Volatile
    private var actualCaptureWidth = 0

    @Volatile
    private var actualCaptureHeight = 0

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_PORT = 8888
        private const val DEFAULT_WIDTH = 640
        private const val DEFAULT_HEIGHT = 360
        private const val DEFAULT_BITRATE = 1_000_000
        private const val DEFAULT_FPS = 5

        private var resultCode: Int = 0
        private var resultData: Intent? = null

        @Volatile
        var isCapturing = false
            private set

        @Volatile
        var isClientConnected = false
            private set

        @Volatile
        var streamWidth = DEFAULT_WIDTH
            private set

        @Volatile
        var streamHeight = DEFAULT_HEIGHT
            private set

        @Volatile
        var streamBitrate = DEFAULT_BITRATE
            private set

        @Volatile
        var streamFps = DEFAULT_FPS
            private set

        @Volatile
        var currentFps = 0
            private set

        @Volatile
        var currentBitrate = 0L
            private set

        @Volatile
        var onStateChanged: (() -> Unit)? = null

        @Volatile
        var onStatsUpdate: ((fps: Int, bitrate: Long) -> Unit)? = null

        @Volatile
        var onTouchReceived: ((TouchEvent) -> Unit)? = null

        @Volatile
        var onError: ((String) -> Unit)? = null

        fun setProjectionResult(code: Int, data: Intent) {
            resultCode = code
            resultData = data
        }

        fun updateSettings(width: Int, height: Int, bitrate: Int, fps: Int) {
            streamWidth = width
            streamHeight = height
            streamBitrate = bitrate
            streamFps = fps
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT
        startServer(port)
        startCapture()

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "P2PScreenMonitor::CaptureWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SenderActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = resultData ?: run {
            Logger.e("No projection result data")
            notifyError("投屏数据丢失")
            stopSelf()
            return
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Logger.e("Failed to get MediaProjection - check device permissions")
            notifyError("无法获取投屏权限，请检查设备设置")
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Logger.d("MediaProjection stopped by system")
                notifyError("投屏被系统终止")
                stopSelf()
            }
        }, null)

        var captureWidth: Int
        var captureHeight: Int
        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            Logger.d("Device screen: ${screenWidth}x${screenHeight}")

            if (streamWidth == DEFAULT_WIDTH && streamHeight == DEFAULT_HEIGHT && screenWidth > 0 && screenHeight > 0) {
                val scale = 720.0f / maxOf(screenWidth, screenHeight)
                captureWidth = ((screenWidth * scale).toInt() / 2) * 2
                captureHeight = ((screenHeight * scale).toInt() / 2) * 2
            } else {
                captureWidth = streamWidth
                captureHeight = streamHeight
            }
        } catch (e: Exception) {
            Logger.w("Failed to get screen size, using defaults: ${e.message}")
            captureWidth = streamWidth
            captureHeight = streamHeight
        }
        if (captureWidth <= 0 || captureHeight <= 0) {
            Logger.e("Invalid capture size: ${captureWidth}x${captureHeight}")
            notifyError("获取屏幕分辨率失败")
            stopSelf()
            return
        }

        videoEncoder = VideoEncoder(
            width = captureWidth,
            height = captureHeight,
            bitrate = streamBitrate,
            fps = streamFps,
            onConfigData = { configData ->
                Logger.d("Codec config received, size: ${configData.size}")
                cachedConfigData = configData
                tcpServer?.sendConfig(configData)
            },
            onEncodedData = { frameData, _ ->
                if (tcpServer?.isConnected() == true) {
                    tcpServer?.sendFrame(frameData)
                }
            },
            onStatsUpdate = { fps, bitrate ->
                currentFps = fps
                currentBitrate = bitrate
                onStatsUpdate?.invoke(fps, bitrate)
            }
        )

        val inputSurface = videoEncoder!!.createInputSurface()
        screenCapture = ScreenCapture(mediaProjection!!, captureWidth, captureHeight)
        screenCapture?.start(inputSurface)

        videoEncoder?.start()
        isCapturing = true
        actualCaptureWidth = captureWidth
        actualCaptureHeight = captureHeight
        onStateChanged?.invoke()
        Logger.d("Screen capture started: ${captureWidth}x${captureHeight} @ ${streamBitrate / 1000}kbps ${streamFps}fps")
    }

    private fun startServer(port: Int) {
        tcpServer = TcpServer(
            port = port,
            onClientConnected = {
                Logger.d("Client connected to server")
                isClientConnected = true
                onStateChanged?.invoke()
                tcpServer?.sendResolution(actualCaptureWidth, actualCaptureHeight)
                cachedConfigData?.let { config ->
                    Logger.d("Sending cached codec config to new client, size: ${config.size}")
                    tcpServer?.sendConfig(config)
                }
                startConfigResendTimer()
            },
            onClientDisconnected = {
                Logger.d("Client disconnected from server")
                isClientConnected = false
                onStateChanged?.invoke()
                stopConfigResendTimer()
            },
            onTouchReceived = { data ->
                val touchEvent = Protocol.parseTouchAction(data)
                if (touchEvent != null) {
                    onTouchReceived?.invoke(touchEvent)
                }
            }
        )
        tcpServer?.start()
        Logger.d("TCP server started on port $port")
    }

    private var configResendRunnable: Runnable? = null
    private var configResendHandler: android.os.Handler? = null

    private fun startConfigResendTimer() {
        stopConfigResendTimer()
        configResendHandler = android.os.Handler(mainLooper)
        var count = 0
        configResendRunnable = object : Runnable {
            override fun run() {
                if (!isClientConnected || count >= 10) return
                cachedConfigData?.let { config ->
                    Logger.d("Resending config to client (attempt ${count + 1})")
                    tcpServer?.sendConfig(config)
                }
                count++
                configResendHandler?.postDelayed(this, 2000)
            }
        }
        configResendHandler?.postDelayed(configResendRunnable!!, 2000)
    }

    private fun stopConfigResendTimer() {
        configResendRunnable?.let { configResendHandler?.removeCallbacks(it) }
        configResendRunnable = null
        configResendHandler = null
    }

    override fun onDestroy() {
        stopConfigResendTimer()
        stopCapture()
        stopServer()
        releaseWakeLock()
        isCapturing = false
        isClientConnected = false
        actualCaptureWidth = 0
        actualCaptureHeight = 0
        onStateChanged?.invoke()
        super.onDestroy()
    }

    private fun stopCapture() {
        screenCapture?.stop()
        videoEncoder?.stop()
        mediaProjection?.stop()
        Logger.d("Screen capture stopped")
    }

    private fun stopServer() {
        tcpServer?.stop()
        Logger.d("TCP server stopped")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun notifyError(message: String) {
        Logger.e(message)
        onError?.invoke(message)
    }
}
