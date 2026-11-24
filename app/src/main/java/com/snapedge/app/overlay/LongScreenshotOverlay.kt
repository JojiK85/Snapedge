package com.snapedge.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.snapedge.app.R
import com.snapedge.app.capture.BitmapStitcher
import com.snapedge.app.capture.ScreenCaptureHolder
import com.snapedge.app.service.AutoScrollAccessibilityService
import com.snapedge.app.ui.MarkupEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Controls multi-frame Long Screenshot capture.
 * Uses AutoScrollAccessibilityService when enabled, or falls back to manual "+ Capture" assist mode.
 */
class LongScreenshotOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onFinished: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val frames = mutableListOf<Bitmap>()

    private var rootView: View? = null
    private var autoScrollJob: Job? = null
    private var isStitching = false

    fun show() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_SnapEdge)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_long_screenshot, null)
        rootView = view

        val tvStatus = view.findViewById<TextView>(R.id.tvLongShotStatus)
        val btnCaptureNext = view.findViewById<MaterialButton>(R.id.btnCaptureNextFrame)
        val btnFinish = view.findViewById<MaterialButton>(R.id.btnFinishLongShot)
        val btnCancel = view.findViewById<ImageButton>(R.id.btnCancelLongShot)

        val autoEnabled = AutoScrollAccessibilityService.isEnabled()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 110
        }

        windowManager.addView(view, params)

        btnCancel.setOnClickListener {
            cleanupAndDismiss()
        }

        btnFinish.setOnClickListener {
            finishAndStitch(tvStatus)
        }

        if (autoEnabled) {
            btnCaptureNext.visibility = View.GONE
            startAutoScrollLoop(view, tvStatus)
        } else {
            btnCaptureNext.visibility = View.VISIBLE
            tvStatus.text = "Scroll & tap + Capture"
            scope.launch {
                captureSingleFrame(view, tvStatus)
            }
            btnCaptureNext.setOnClickListener {
                scope.launch {
                    captureSingleFrame(view, tvStatus)
                }
            }
        }
    }

    private fun startAutoScrollLoop(pillView: View, tvStatus: TextView) {
        autoScrollJob = scope.launch {
            val first = captureFrameQuietly(pillView)
            if (first != null) {
                frames.add(first)
                tvStatus.text = "Auto-scrolling (${frames.size}/$MAX_FRAMES)"
            }

            val accessibility = AutoScrollAccessibilityService.instance
            val width = first?.width ?: 1080
            val height = first?.height ?: 2400

            while (isActive && frames.size < MAX_FRAMES && accessibility != null) {
                val scrolled = accessibility.performScrollDown(width, height)
                if (!scrolled) break

                delay(550)
                val next = captureFrameQuietly(pillView) ?: break
                val prev = frames.lastOrNull()

                if (prev != null) {
                    val delta = withContext(Dispatchers.Default) {
                        BitmapStitcher.estimateScrollDelta(prev, next)
                    }
                    if (delta <= 16) {
                        next.recycle()
                        tvStatus.text = "Bottom reached!"
                        break
                    }
                }

                frames.add(next)
                tvStatus.text = "Auto-scrolling (${frames.size}/$MAX_FRAMES)"
                delay(220)
            }

            if (isActive && frames.isNotEmpty()) {
                finishAndStitch(tvStatus)
            }
        }
    }

    private suspend fun captureSingleFrame(pillView: View, tvStatus: TextView) {
        if (isStitching || frames.size >= MAX_FRAMES) return
        val frame = captureFrameQuietly(pillView) ?: return
        frames.add(frame)
        tvStatus.text = "Captured: ${frames.size} frame${if (frames.size == 1) "" else "s"}"
    }

    private suspend fun captureFrameQuietly(pillView: View): Bitmap? {
        pillView.visibility = View.INVISIBLE
        delay(90)
        val bitmap = ScreenCaptureHolder.captureScreen()
        pillView.visibility = View.VISIBLE
        return bitmap
    }

    private fun finishAndStitch(tvStatus: TextView) {
        if (isStitching) return
        isStitching = true
        autoScrollJob?.cancel()
        tvStatus.text = "Stitching ${frames.size} frames..."

        scope.launch {
            val stitched = withContext(Dispatchers.Default) {
                BitmapStitcher.stitchFrames(frames)
            }
            val savedPath = stitched?.let { bmp ->
                val path = saveTempLongShot(bmp)
                bmp.recycle()
                path
            }
            cleanupAndDismiss()

            if (savedPath != null) {
                MarkupEditorActivity.launch(context, savedPath)
            } else {
                Toast.makeText(context, "Could not stitch long screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTempLongShot(bitmap: Bitmap): String? {
        return runCatching {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "longshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun cleanupAndDismiss() {
        autoScrollJob?.cancel()
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        frames.forEach { it.recycle() }
        frames.clear()
        onFinished()
    }

    companion object {
        private const val MAX_FRAMES = 10
    }
}
