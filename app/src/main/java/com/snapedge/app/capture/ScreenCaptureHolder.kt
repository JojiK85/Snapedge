package com.snapedge.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages a persistent MediaProjection + ImageReader session and captures screen Bitmaps on demand.
 */
object ScreenCaptureHolder {

    private const val TAG = "SnapEdgeCapture"

    private var appContext: Context? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val captureMutex = Mutex()

    @Volatile
    private var pendingContinuation: CancellableContinuation<Bitmap?>? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection onStop() triggered")
            release()
        }
    }

    fun isReady(): Boolean = mediaProjection != null && virtualDisplay != null && imageReader != null

    @SuppressLint("WrongConstant")
    fun init(context: Context, projection: MediaProjection) {
        release()
        appContext = context.applicationContext

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

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val cont = pendingContinuation
            if (cont != null && cont.isActive) {
                pendingContinuation = null
                val bitmap = runCatching { imageToBitmap(image) }
                    .onFailure { Log.e(TAG, "imageToBitmap failed", it) }
                    .getOrNull()
                image.close()
                Log.d(TAG, "Captured frame bitmap=${bitmap != null}")
                cont.resume(bitmap)
            } else {
                image.close()
            }
        }, captureHandler)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "SnapEdgeDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
        Log.d(TAG, "Initialized VirtualDisplay ${screenWidth}x${screenHeight}@$screenDensity")
    }

    suspend fun captureScreen(): Bitmap? = captureMutex.withLock {
        if (!isReady()) {
            Log.w(TAG, "captureScreen called when not ready")
            return@withLock null
        }
        withContext(Dispatchers.IO) {
            var nudgeView: View? = null
            val mainHandler = Handler(Looper.getMainLooper())
            try {
                withTimeoutOrNull(2500L) {
                    suspendCancellableCoroutine { cont ->
                        pendingContinuation = cont
                        cont.invokeOnCancellation {
                            if (pendingContinuation === cont) {
                                pendingContinuation = null
                            }
                        }
                        runCatching { virtualDisplay?.resize(screenWidth, screenHeight, screenDensity) }
                        mainHandler.post {
                            nudgeView = addCompositeNudge()
                        }
                    }
                }
            } finally {
                pendingContinuation = null
                mainHandler.post {
                    removeCompositeNudge(nudgeView)
                }
            }
        }
    }

    private fun addCompositeNudge(): View? {
        val ctx = appContext ?: return null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        return runCatching {
            val v = View(ctx).apply {
                setBackgroundColor(Color.argb(24, 255, 255, 255))
            }
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                8,
                8,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            wm.addView(v, lp)
            v
        }.onFailure {
            Log.e(TAG, "addCompositeNudge failed", it)
        }.getOrNull()
    }

    private fun removeCompositeNudge(view: View?) {
        val v = view ?: return
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(v) }
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
        pendingContinuation = null
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
