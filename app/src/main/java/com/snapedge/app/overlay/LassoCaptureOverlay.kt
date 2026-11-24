package com.snapedge.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.snapedge.app.R
import com.snapedge.app.data.PreferencesManager
import com.snapedge.app.model.SmartEntity
import com.snapedge.app.model.SmartEntityType
import com.snapedge.app.ocr.SmartTextAnalyzer
import com.snapedge.app.ui.MarkupEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Full-screen overlay showing the frozen screen capture where users can circle/lasso any region
 * to extract text, decode QR codes, compute math, copy OTPs, pin clips, or open the markup editor.
 */
class LassoCaptureOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenBitmap: Bitmap,
    private val onDismissed: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val prefs = PreferencesManager(context)

    private var rootView: View? = null
    private var croppedBitmap: Bitmap? = null

    fun show() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_lasso_capture, null)
        rootView = view

        val ivFrozen = view.findViewById<ImageView>(R.id.ivFrozenScreen)
        val lassoView = view.findViewById<LassoDrawingView>(R.id.lassoDrawingView)
        val btnMode = view.findViewById<MaterialButton>(R.id.btnToggleLassoMode)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseLasso)

        val cardResult = view.findViewById<MaterialCardView>(R.id.cardExtractionResult)
        val ivPreview = view.findViewById<ImageView>(R.id.ivCroppedPreview)
        val tvStatus = view.findViewById<TextView>(R.id.tvExtractionStatus)
        val tvSummary = view.findViewById<TextView>(R.id.tvSmartSummary)
        val scrollChips = view.findViewById<HorizontalScrollView>(R.id.scrollSmartChips)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupSmartActions)
        val etExtracted = view.findViewById<EditText>(R.id.etExtractedText)

        val btnDismissResult = view.findViewById<ImageButton>(R.id.btnDismissResult)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnCopyExtracted)
        val btnPin = view.findViewById<MaterialButton>(R.id.btnPinExtracted)
        val btnSearch = view.findViewById<MaterialButton>(R.id.btnSearchWeb)
        val btnEditCrop = view.findViewById<MaterialButton>(R.id.btnEditCropImage)
        val btnShare = view.findViewById<MaterialButton>(R.id.btnShareExtracted)

        ivFrozen.setImageBitmap(screenBitmap)

        btnMode.setOnClickListener {
            if (lassoView.mode == LassoDrawingView.SelectionMode.FREEHAND) {
                lassoView.mode = LassoDrawingView.SelectionMode.BOX
                btnMode.text = "Circle"
            } else {
                lassoView.mode = LassoDrawingView.SelectionMode.FREEHAND
                btnMode.text = "Box"
            }
            lassoView.clearSelection()
        }

        btnClose.setOnClickListener { dismiss() }
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }
        btnDismissResult.setOnClickListener {
            cardResult.visibility = View.GONE
            lassoView.clearSelection()
        }

        lassoView.onSelectionComplete = { viewRect ->
            analyzeRegion(
                lassoView = lassoView,
                viewRect = viewRect,
                cardResult = cardResult,
                ivPreview = ivPreview,
                tvStatus = tvStatus,
                tvSummary = tvSummary,
                scrollChips = scrollChips,
                chipGroup = chipGroup,
                etExtracted = etExtracted
            )
        }

        btnCopy.setOnClickListener {
            val text = etExtracted.text.toString().trim()
            if (text.isNotEmpty()) {
                copyToClipboard(text)
                prefs.addClip(text, "Circle OCR")
                Toast.makeText(context, "Copied & saved to Vault", Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(context, "No text to copy", Toast.LENGTH_SHORT).show()
            }
        }

        btnPin.setOnClickListener {
            val text = etExtracted.text.toString().trim()
            val crop = croppedBitmap
            if (text.isNotEmpty() || crop != null) {
                if (text.isNotEmpty()) prefs.addClip(text, "Pinned Clip")
                PinnedFloatingWindow(context, windowManager, text, crop?.copy(Bitmap.Config.ARGB_8888, false)).show()
                dismiss()
            }
        }

        btnSearch.setOnClickListener {
            val query = etExtracted.text.toString().trim()
            if (query.isNotEmpty()) {
                val url = "https://www.google.com/search?q=${Uri.encode(query)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                dismiss()
            }
        }

        btnEditCrop.setOnClickListener {
            val crop = croppedBitmap ?: return@setOnClickListener
            val path = saveTempBitmap(crop)
            if (path != null) {
                MarkupEditorActivity.launch(context, path)
                dismiss()
            }
        }

        btnShare.setOnClickListener {
            val text = etExtracted.text.toString().trim()
            if (text.isNotEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(shareIntent, "Share extracted text").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                dismiss()
            } else {
                val crop = croppedBitmap ?: return@setOnClickListener
                val path = saveTempBitmap(crop) ?: return@setOnClickListener
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Share circled crop").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                dismiss()
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(view, params)
    }

    private fun analyzeRegion(
        lassoView: View,
        viewRect: RectF,
        cardResult: MaterialCardView,
        ivPreview: ImageView,
        tvStatus: TextView,
        tvSummary: TextView,
        scrollChips: HorizontalScrollView,
        chipGroup: ChipGroup,
        etExtracted: EditText
    ) {
        val scaleX = screenBitmap.width.toFloat() / lassoView.width.coerceAtLeast(1)
        val scaleY = screenBitmap.height.toFloat() / lassoView.height.coerceAtLeast(1)

        val left = (viewRect.left * scaleX).toInt().coerceIn(0, screenBitmap.width - 1)
        val top = (viewRect.top * scaleY).toInt().coerceIn(0, screenBitmap.height - 1)
        val right = (viewRect.right * scaleX).toInt().coerceIn(left + 1, screenBitmap.width)
        val bottom = (viewRect.bottom * scaleY).toInt().coerceIn(top + 1, screenBitmap.height)

        val crop = Bitmap.createBitmap(screenBitmap, left, top, right - left, bottom - top)
        croppedBitmap?.recycle()
        croppedBitmap = crop

        ivPreview.setImageBitmap(crop)
        tvStatus.text = "Extracting text & QR..."
        tvSummary.text = "${crop.width}×${crop.height}px region"
        etExtracted.setText("")
        chipGroup.removeAllViews()
        scrollChips.visibility = View.GONE
        cardResult.visibility = View.VISIBLE

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                SmartTextAnalyzer.analyzeBitmap(crop)
            }

            if (result.extractedText.isNotEmpty()) {
                tvStatus.text = if (result.qrPayloads.isNotEmpty()) {
                    "Text + QR Code Extracted"
                } else {
                    "Text Extracted"
                }
                etExtracted.setText(result.extractedText)
                prefs.addClip(result.extractedText, "Circle OCR")
            } else {
                tvStatus.text = "Image Region Selected"
                etExtracted.setText("")
            }

            if (result.entities.isNotEmpty()) {
                scrollChips.visibility = View.VISIBLE
                result.entities.forEach { entity ->
                    chipGroup.addView(createEntityChip(entity))
                }
            }
        }
    }

    private fun createEntityChip(entity: SmartEntity): Chip {
        return Chip(context).apply {
            text = entity.label
            isClickable = true
            setOnClickListener {
                when (entity.type) {
                    SmartEntityType.OTP, SmartEntityType.MATH, SmartEntityType.QR_CODE -> {
                        copyToClipboard(entity.value)
                        Toast.makeText(context, "Copied: ${entity.value}", Toast.LENGTH_SHORT).show()
                        if (entity.type == SmartEntityType.QR_CODE &&
                            (entity.value.startsWith("http://") || entity.value.startsWith("https://"))
                        ) {
                            openUrl(entity.value)
                        }
                    }
                    SmartEntityType.URL -> openUrl(entity.value)
                    SmartEntityType.PHONE -> {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${entity.value}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        dismiss()
                    }
                    SmartEntityType.EMAIL -> {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${entity.value}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        dismiss()
                    }
                }
            }
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        dismiss()
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SnapEdge OCR", text))
    }

    private fun saveTempBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File(dir, "crop_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    fun dismiss() {
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        croppedBitmap?.recycle()
        croppedBitmap = null
        screenBitmap.recycle()
        onDismissed()
    }
}
