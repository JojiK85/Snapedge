package com.snapedge.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages a single MediaProjection VirtualDisplay session and captures crisp screen Bitmaps on demand.
 * Sets VirtualDisplay surface to null while idle to eliminate background GPU/battery overhead.
 */
object ScreenCaptureHolder {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
        }
    }

    fun isReady(): Boolean = mediaProjection != null && virtualDisplay != null

    @SuppressLint("WrongConstant")
    fun init(context: Context, projection: MediaProjection) {
        release()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        captureThread = HandlerThread("SnapEdgeCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "SnapEdgeDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            captureHandler
        )
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        val display = virtualDisplay ?: return@withContext null
        val reader = imageReader ?: return@withContext null
        val handler = captureHandler ?: return@withContext null

        withTimeoutOrNull(1500L) {
            suspendCancellableCoroutine { cont ->
                // Clear any stale buffered images first
                while (true) {
                    val stale = reader.acquireLatestImage() ?: break
                    stale.close()
                }

                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    r.setOnImageAvailableListener(null, null)
                    display.surface = null
                    val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
                    image.close()
                    if (cont.isActive) cont.resume(bitmap)
                }, handler)

                display.surface = reader.surface

                cont.invokeOnCancellation {
                    reader.setOnImageAvailableListener(null, null)
                    display.surface = null
                }
            }
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val paddedBitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            paddedBitmap
        } else {
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, screenWidth, screenHeight)
            paddedBitmap.recycle()
            cropped
        }
    }

    fun release() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        runCatching { captureThread?.quitSafely() }

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        captureHandler = null
        captureThread = null
    }
}
