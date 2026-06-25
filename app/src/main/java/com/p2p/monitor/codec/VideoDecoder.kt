package com.p2p.monitor.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.p2p.monitor.util.Logger
import java.nio.ByteBuffer

class VideoDecoder {
    private var decoder: MediaCodec? = null
    private var isConfigured = false

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 1_000L
    }

    fun configureAndStart(surface: Surface, width: Int = 1280, height: Int = 720) {
        if (isConfigured) return

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Logger.d("Decoder configured and started: ${width}x${height}")
        } catch (e: Exception) {
            Logger.e("Error configuring decoder", e)
        }
    }

    fun configureWithFormat(surface: Surface, format: MediaFormat) {
        release()
        try {
            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Logger.d("Decoder configured with format: $format")
        } catch (e: Exception) {
            Logger.e("Error configuring decoder with format", e)
        }
    }

    fun onConfigData(configData: ByteArray, surface: Surface?, width: Int = 1280, height: Int = 720, renderWidth: Int = 0, renderHeight: Int = 0) {
        if (surface == null) return
        try {
            val renderW = if (renderWidth > 0) renderWidth else width
            val renderH = if (renderHeight > 0) renderHeight else height

            val format = MediaFormat.createVideoFormat(MIME_TYPE, renderW, renderH)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(configData))

            release()
            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Logger.d("Decoder configured with SPS/PPS data, size: ${configData.size}, stream: ${width}x${height}, render: ${renderW}x${renderH}")
        } catch (e: Exception) {
            Logger.e("Error configuring decoder with config data", e)
        }
    }

    fun decode(data: ByteArray) {
        val codec = decoder ?: return
        if (!isConfigured) return

        try {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val inputBuffer: ByteBuffer = codec.getInputBuffer(index) ?: return
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(index, 0, data.size, 0, 0)
            }

            drainOutput(codec)
        } catch (e: Exception) {
            Logger.e("Decode error", e)
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Logger.d("Decoder output format: ${codec.outputFormat}")
                }
                outIndex >= 0 -> {
                    codec.releaseOutputBuffer(outIndex, true)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    fun release() {
        isConfigured = false
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing decoder", e)
        }
        decoder = null
    }
}
