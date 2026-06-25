package com.p2p.monitor.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import com.p2p.monitor.util.Logger

class ScreenCapture(
    private val mediaProjection: MediaProjection,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val dpi: Int = 320
) {
    private var virtualDisplay: VirtualDisplay? = null

    fun start(surface: Surface) {
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
        Logger.d("Screen capture started: ${width}x${height}")
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        Logger.d("Screen capture stopped")
    }
}
