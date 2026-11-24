package com.snapedge.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.snapedge.app.R
import com.snapedge.app.capture.CapturePermissionActivity
import com.snapedge.app.capture.ScreenCaptureHolder
import com.snapedge.app.data.PreferencesManager
import com.snapedge.app.model.EdgeSide
import com.snapedge.app.overlay.LassoCaptureOverlay
import com.snapedge.app.overlay.PinnedFloatingWindow
import com.snapedge.app.ui.MainActivity
import com.snapedge.app.ui.MarkupEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Persistent Edge Sidebar service.
 * Renders a slim translucent edge handle across any app and expands a floating action dock on swipe or tap.
 */
class EdgeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager
    private lateinit var themedContext: Context
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var edgeHandleView: View? = null
    private var sidebarView: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null
    private var isSidebarOpen = false
    private var isCaptureOverlayActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)
        themedContext = ContextThemeWrapper(this, R.style.Theme_SnapEdge)
        startForegroundSafely()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant 'Display over other apps' first", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        attachEdgeHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_REFRESH_CONFIG -> applyEdgeHandleConfig()
            ACTION_CIRCLE_OCR -> triggerCircleOcr()
            ACTION_QUICK_SHOT -> triggerQuickFullShot()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeSidebar()
        edgeHandleView?.let { runCatching { windowManager.removeView(it) } }
        edgeHandleView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.notif_edge_title))
            .setContentText(getString(R.string.notif_edge_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachEdgeHandle() {
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_edge_handle, null)
        edgeHandleView = view

        val density = resources.displayMetrics.density
        val handleWidthPx = (24 * density).toInt()
        val handleHeightPx = (180 * density).toInt()

        val layoutType = overlayLayoutType()
        val params = WindowManager.LayoutParams(
            handleWidthPx,
            handleHeightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        edgeParams = params
        applyEdgeHandleConfig()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.width > 0 && v.height > 0) {
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }
        }

        var downX = 0f
        var downY = 0f
        var startY = 0
        var movedVertically = false

        view.setOnTouchListener { _, event ->
            val config = prefs.getEdgeConfig()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startY = params.y
                    movedVertically = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val swipeInward = if (config.side == EdgeSide.RIGHT) dx < -28f else dx > 28f

                    if (swipeInward && abs(dx) > abs(dy)) {
                        vibrateLight()
                        openSidebar()
                        true
                    } else if (!config.stealthMode && abs(dy) > 20f) {
                        movedVertically = true
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        true
                    } else {
                        true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val isTap = abs(dx) < 18f && abs(dy) < 18f && !movedVertically
                    if (isTap && !config.stealthMode) {
                        vibrateLight()
                        openSidebar()
                    } else if (movedVertically) {
                        val screenHeight = getScreenMetrics().heightPixels.coerceAtLeast(1)
                        val pct = ((params.y.toFloat() / screenHeight) * 100f).toInt().coerceIn(5, 80)
                        prefs.saveEdgeConfig(config.copy(verticalPercent = pct))
                    }
                    true
                }

                else -> false
            }
        }

        runCatching {
            windowManager.addView(view, params)
            val sideLabel = if (prefs.getEdgeConfig().side == EdgeSide.RIGHT) "right" else "left"
            val modeText = if (prefs.getEdgeConfig().stealthMode) "stealth" else "visible"
            Toast.makeText(this, "SnapEdge active ($modeText on $sideLabel edge)", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
            Toast.makeText(this, "Overlay error: ${err.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun applyEdgeHandleConfig() {
        val view = edgeHandleView ?: return
        val params = edgeParams ?: return
        val config = prefs.getEdgeConfig()
        val metrics = getScreenMetrics()

        val pill = view.findViewById<View>(R.id.edgeHandlePill)
        val lp = pill.layoutParams as android.widget.FrameLayout.LayoutParams
        if (config.side == EdgeSide.RIGHT) {
            params.gravity = Gravity.TOP or Gravity.END
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            pill.setBackgroundResource(R.drawable.bg_edge_handle_right)
        } else {
            params.gravity = Gravity.TOP or Gravity.START
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            pill.setBackgroundResource(R.drawable.bg_edge_handle_left)
        }
        pill.layoutParams = lp
        pill.animate().cancel()
        pill.alpha = if (config.stealthMode) {
            0f
        } else {
            (config.opacityPercent.coerceIn(15, 100) / 100f)
        }

        params.x = 0
        params.y = (metrics.heightPixels * (config.verticalPercent / 100f)).toInt()

        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun openSidebar() {
        if (isSidebarOpen || isCaptureOverlayActive) return
        val config = prefs.getEdgeConfig()
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_sidebar_panel, null)
        sidebarView = view
        isSidebarOpen = true
        edgeHandleView?.visibility = View.GONE

        view.findViewById<ImageButton>(R.id.btnCloseSidebar).setOnClickListener { removeSidebar() }

        view.findViewById<View>(R.id.btnSidebarCircleOcr).setOnClickListener {
            removeSidebar()
            triggerCircleOcr()
        }
        view.findViewById<View>(R.id.btnSidebarQuickShot).setOnClickListener {
            removeSidebar()
            triggerQuickFullShot()
        }
        view.findViewById<View>(R.id.btnSidebarPinClipboard).setOnClickListener {
            removeSidebar()
            pinCurrentClipboard()
        }
        view.findViewById<View>(R.id.btnSidebarOpenApp).setOnClickListener {
            removeSidebar()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        populateRecentClips(view.findViewById(R.id.sidebarRecentClipsContainer))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or if (config.side == EdgeSide.RIGHT) Gravity.END else Gravity.START
            x = 18
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                removeSidebar()
                true
            } else {
                false
            }
        }

        runCatching {
            windowManager.addView(view, params)
        }.onFailure { err ->
            Toast.makeText(this, "Could not open sidebar: ${err.message}", Toast.LENGTH_SHORT).show()
            removeSidebar()
        }
    }

    private fun populateRecentClips(container: LinearLayout) {
        container.removeAllViews()
        val clips = prefs.getClips().take(4)
        if (clips.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No clips yet"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(12, 6, 12, 6)
            }
            container.addView(empty)
            return
        }

        clips.forEach { clip ->
            val tv = TextView(this).apply {
                text = clip.text.replace('\n', ' ').take(34)
                textSize = 12f
                setTextColor(getColor(R.color.text_primary))
                setPadding(12, 10, 12, 10)
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SnapEdge Clip", clip.text))
                    Toast.makeText(this@EdgeOverlayService, "Copied clip!", Toast.LENGTH_SHORT).show()
                    removeSidebar()
                }
            }
            container.addView(tv)
        }
    }

    private fun removeSidebar() {
        sidebarView?.let { runCatching { windowManager.removeView(it) } }
        sidebarView = null
        isSidebarOpen = false
        if (!isCaptureOverlayActive) {
            edgeHandleView?.visibility = View.VISIBLE
        }
    }

    private fun triggerCircleOcr() {
        if (!ScreenCaptureHolder.isReady()) {
            CapturePermissionActivity.launch(this, ACTION_CIRCLE_OCR)
            return
        }
        if (isCaptureOverlayActive) return
        isCaptureOverlayActive = true
        edgeHandleView?.visibility = View.GONE

        serviceScope.launch {
            delay(140)
            val screen = ScreenCaptureHolder.captureScreen()
            if (screen == null) {
                isCaptureOverlayActive = false
                edgeHandleView?.visibility = View.VISIBLE
                Toast.makeText(this@EdgeOverlayService, "Could not grab screen — try again", Toast.LENGTH_SHORT).show()
                return@launch
            }

            LassoCaptureOverlay(
                context = this@EdgeOverlayService,
                windowManager = windowManager,
                screenBitmap = screen,
                onDismissed = {
                    isCaptureOverlayActive = false
                    edgeHandleView?.visibility = View.VISIBLE
                }
            ).show()
        }
    }

    private fun triggerQuickFullShot() {
        if (!ScreenCaptureHolder.isReady()) {
            CapturePermissionActivity.launch(this, ACTION_QUICK_SHOT)
            return
        }
        isCaptureOverlayActive = true
        edgeHandleView?.visibility = View.GONE

        serviceScope.launch {
            delay(140)
            val screen = ScreenCaptureHolder.captureScreen()
            isCaptureOverlayActive = false
            edgeHandleView?.visibility = View.VISIBLE
            if (screen != null) {
                val path = saveTempBitmap(screen)
                screen.recycle()
                if (path != null) {
                    MarkupEditorActivity.launch(this@EdgeOverlayService, path)
                }
            }
        }
    }

    private fun pinCurrentClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val fallback = prefs.getClips().firstOrNull()?.text.orEmpty()
        val toPin = clipText.ifBlank { fallback }.ifBlank { "No clipboard text yet. Circle text on screen to pin!" }
        PinnedFloatingWindow(this, windowManager, toPin, null).show()
    }

    private fun saveTempBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "shot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun vibrateLight() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    companion object {
        const val ACTION_REFRESH_CONFIG = "com.snapedge.app.ACTION_REFRESH_CONFIG"
        const val ACTION_CIRCLE_OCR = "com.snapedge.app.ACTION_CIRCLE_OCR"
        const val ACTION_QUICK_SHOT = "com.snapedge.app.ACTION_QUICK_SHOT"
        const val ACTION_STOP = "com.snapedge.app.ACTION_STOP"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, EdgeOverlayService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
