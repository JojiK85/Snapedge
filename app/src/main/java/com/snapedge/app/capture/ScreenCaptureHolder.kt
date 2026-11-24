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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages a persistent MediaProjection VirtualDisplay session and captures crisp screen Bitmaps on demand.
 * Creates a fresh ImageReader per capture so SurfaceFlinger always delivers an immediate frame even on static screens.
 */
object ScreenCaptureHolder {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val captureMutex = Mutex()

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
        }
    }

    fun isReady(): Boolean = mediaProjection != null && virtualDisplay != null

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

    @SuppressLint("WrongConstant")
    suspend fun captureScreen(): Bitmap? = captureMutex.withLock {
        withContext(Dispatchers.IO) {
            val display = virtualDisplay ?: return@withContext null
            val handler = captureHandler ?: return@withContext null

            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            try {
                withTimeoutOrNull(1800L) {
                    suspendCancellableCoroutine { cont ->
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
            } finally {
                display.surface = null
                runCatching { reader.setOnImageAvailableListener(null, null) }
                runCatching { reader.close() }
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
        runCatching { virtualDisplay?.surface = null }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        runCatching { captureThread?.quitSafely() }

        virtualDisplay = null
        mediaProjection = null
        captureHandler = null
        captureThread = null
    }
}
