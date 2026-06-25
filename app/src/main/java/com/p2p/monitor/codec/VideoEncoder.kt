package com.p2p.monitor.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.p2p.monitor.util.Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class VideoEncoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitrate: Int = 3_000_000,
    private val fps: Int = 25,
    private val onConfigData: (ByteArray) -> Unit = {},
    private val onEncodedData: (ByteArray, Boolean) -> Unit = { _, _ -> },
    private val onStatsUpdate: (fps: Int, bitrate: Long) -> Unit = { _, _ -> }
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private val frameCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    private var lastStatsTime = System.currentTimeMillis()

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 1
        private const val STATS_INTERVAL_MS = 1000L
    }

    fun createInputSurface(): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }

        return inputSurface!!
    }

    fun start() {
        encoder?.start()
        isRunning.set(true)
        frameCount.set(0)
        byteCount.set(0)
        lastStatsTime = System.currentTimeMillis()
        startDrainThread()
        Logger.d("Encoder started: ${width}x${height} @ ${bitrate / 1000}kbps ${fps}fps")
    }

    private fun startDrainThread() {
        drainThread = Thread {
            Logger.d("Encoder drain thread started")
            while (isRunning.get()) {
                drainEncoder()
            }
            Logger.d("Encoder drain thread stopped")
        }.apply {
            isDaemon = true
            name = "VideoEncoder-Drain"
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        drainThread?.interrupt()
        drainThread = null
        try {
            encoder?.signalEndOfInputStream()
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Logger.e("Error stopping encoder", e)
        }
        encoder = null
        inputSurface?.release()
        inputSurface = null
    }

    private fun drainEncoder() {
        val codec = encoder ?: return

        val bufferInfo = MediaCodec.BufferInfo()
        val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                return
            }
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                Logger.d("Output format changed: ${codec.outputFormat}")
            }
            index >= 0 -> {
                val outputBuffer: ByteBuffer = codec.getOutputBuffer(index) ?: return
                val data = ByteArray(bufferInfo.size)
                outputBuffer.get(data)

                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                if (isConfig) {
                    Logger.d("Codec config (SPS/PPS) received, size: ${bufferInfo.size}")
                    onConfigData(data)
                } else if (bufferInfo.size > 0) {
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    onEncodedData(data, isKeyFrame)
                    frameCount.incrementAndGet()
                    byteCount.addAndGet(bufferInfo.size.toLong())
                    updateStats()
                }

                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun updateStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsTime
        if (elapsed >= STATS_INTERVAL_MS) {
            val frames = frameCount.getAndSet(0)
            val bytes = byteCount.getAndSet(0)
            lastStatsTime = now
            val currentFps = (frames * 1000 / elapsed).toInt()
            val currentBitrate = bytes * 8 * 1000 / elapsed
            onStatsUpdate(currentFps, currentBitrate)
        }
    }
}
