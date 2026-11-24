package com.snapedge.app.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.text.method.ScrollingMovementMethod
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.snapedge.app.R
import com.snapedge.app.ocr.SmartTextAnalyzer

/**
 * Draggable always-on-top mini window allowing users to keep extracted text or cropped images
 * visible while working in other apps.
 */
class PinnedFloatingWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val text: String,
    private val image: Bitmap? = null
) {
    private var rootView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_SnapEdge)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_pinned_window, null)
        rootView = view

        val dragHeader = view.findViewById<View>(R.id.pinnedDragHeader)
        val btnOpenLink = view.findViewById<ImageButton>(R.id.btnPinnedOpenLink)
        val btnCopy = view.findViewById<ImageButton>(R.id.btnPinnedCopy)
        val btnClose = view.findViewById<ImageButton>(R.id.btnPinnedClose)
        val ivPinned = view.findViewById<ImageView>(R.id.ivPinnedImage)
        val tvPinned = view.findViewById<TextView>(R.id.tvPinnedText)

        if (image != null) {
            ivPinned.visibility = View.VISIBLE
            ivPinned.setImageBitmap(image)
        }
        if (text.isNotBlank()) {
            tvPinned.visibility = View.VISIBLE
            tvPinned.text = text
            tvPinned.movementMethod = ScrollingMovementMethod()
        } else {
            tvPinned.visibility = View.GONE
            btnCopy.visibility = View.GONE
        }

        val url = SmartTextAnalyzer.extractFirstUrl(text)
        if (url != null) {
            btnOpenLink.visibility = View.VISIBLE
            btnOpenLink.setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Pinned Link", url))
                Toast.makeText(context, "Copied & opening link", Toast.LENGTH_SHORT).show()
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }

        btnCopy.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Pinned Clip", text))
            Toast.makeText(context, "Copied pinned text", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener { dismiss() }

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
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 220
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        dragHeader.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    fun dismiss() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        image?.recycle()
    }
}
