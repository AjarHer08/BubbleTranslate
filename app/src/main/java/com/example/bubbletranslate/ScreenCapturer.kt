package com.example.bubbletranslate

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection

/** Menangkap isi layar lewat MediaProjection. grab() selalu mengembalikan frame terbaru (atau cache). */
class ScreenCapturer(projection: MediaProjection, private var w: Int, private var h: Int, dpi: Int) {
    private var reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay = projection.createVirtualDisplay(
        "bubble-cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
    )
    private var last: Bitmap? = null

    @Synchronized
    fun grab(): Bitmap? {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
            try {
                val p = img.planes[0]
                val rowPad = p.rowStride - p.pixelStride * w
                val full = Bitmap.createBitmap(w + rowPad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(p.buffer)
                last = if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
            } finally { img.close() }
        }
        return last
    }

    @Synchronized
    fun resize(nw: Int, nh: Int, dpi: Int) {
        w = nw; h = nh
        val old = reader
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display.resize(w, h, dpi)
        display.surface = reader.surface
        old.close()
        last = null
    }

    fun release() {
        try { display.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
    }
}
