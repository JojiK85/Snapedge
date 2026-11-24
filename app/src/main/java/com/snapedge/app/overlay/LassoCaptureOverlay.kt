package com.snapedge.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Google Circle-to-Search style overlay: circle anything -> adjusts as a resizable square crop ->
 * extracts text & QR codes, auto-copies to default clipboard, and prompts to open links in browser.
 */
class LassoCaptureOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenBitmap: Bitmap,
    private val onDismissed: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val prefs = PreferencesManager(context)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SnapEdge)

    private var rootView: View? = null
    private var croppedBitmap: Bitmap? = null

    fun show() {
        val inflater = LayoutInflater.from(themedContext)
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
        val linkPromptRow = view.findViewById<LinearLayout>(R.id.linkPromptRow)
        val tvLinkPrompt = view.findViewById<TextView>(R.id.tvLinkPrompt)
        val btnOpenBrowser = view.findViewById<MaterialButton>(R.id.btnOpenBrowser)
        val scrollChips = view.findViewById<HorizontalScrollView>(R.id.scrollSmartChips)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupSmartActions)
        val etExtracted = view.findViewById<EditText>(R.id.etExtractedText)

        val btnDismissResult = view.findViewById<ImageButton>(R.id.btnDismissResult)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnCopyExtracted)
        val btnPin = view.findViewById<MaterialButton>(R.id.btnPinExtracted)
        val btnSearch = view.findViewById<MaterialButton>(R.id.btnSearchWeb)
        val btnSaveCrop = view.findViewById<MaterialButton>(R.id.btnSaveCropImage)
        val btnEditCrop = view.findViewById<MaterialButton>(R.id.btnEditCropImage)
        val btnShare = view.findViewById<MaterialButton>(R.id.btnShareExtracted)

        ivFrozen.setImageBitmap(screenBitmap)

        btnMode.setOnClickListener {
            if (lassoView.mode == LassoDrawingView.SelectionMode.FREEHAND) {
                lassoView.mode = LassoDrawingView.SelectionMode.BOX
                btnMode.text = "Circle"
            } else {
                lassoView.mode = LassoDrawingView.SelectionMode.FREEHAND
                btnMode.text = "Box Crop"
            }
            cardResult.visibility = View.GONE
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
                linkPromptRow = linkPromptRow,
                tvLinkPrompt = tvLinkPrompt,
                scrollChips = scrollChips,
                chipGroup = chipGroup,
                etExtracted = etExtracted,
                btnCopy = btnCopy
            )
        }

        etExtracted.doAfterTextChanged { editable ->
            val detectedUrl = SmartTextAnalyzer.extractFirstUrl(editable?.toString().orEmpty())
            linkPromptRow.visibility = if (detectedUrl != null) View.VISIBLE else View.GONE
            if (detectedUrl != null) {
                tvLinkPrompt.text = "Link found — Open with browser?"
            }
        }

        btnCopy.setOnClickListener {
            val text = etExtracted.text.toString().trim()
            if (text.isNotEmpty()) {
                copyToClipboard(text)
                prefs.addClip(text, "Circle OCR")
                btnCopy.text = "Copied ✓"
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                if (SmartTextAnalyzer.extractFirstUrl(text) == null) {
                    dismiss()
                }
            } else {
                Toast.makeText(context, "No text to copy", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenBrowser.setOnClickListener {
            val text = etExtracted.text.toString().trim()
            val url = SmartTextAnalyzer.extractFirstUrl(text)
            if (url != null) {
                copyToClipboard(url)
                prefs.addClip(url, "Link")
                openUrl(url)
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
                val directUrl = SmartTextAnalyzer.extractFirstUrl(query)
                if (directUrl != null) {
                    copyToClipboard(directUrl)
                    prefs.addClip(directUrl, "Link")
                    openUrl(directUrl)
                } else {
                    openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
                }
            }
        }

        btnSaveCrop.setOnClickListener {
            val crop = croppedBitmap ?: return@setOnClickListener
            scope.launch {
                val saved = withContext(Dispatchers.IO) { saveCropToGallery(crop) }
                Toast.makeText(
                    context,
                    if (saved) "Saved crop to Pictures/SnapEdge" else "Failed to save crop",
                    Toast.LENGTH_SHORT
                ).show()
                if (saved) dismiss()
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
                val chooser = Intent.createChooser(shareIntent, "Share crop").apply {
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
        linkPromptRow: LinearLayout,
        tvLinkPrompt: TextView,
        scrollChips: HorizontalScrollView,
        chipGroup: ChipGroup,
        etExtracted: EditText,
        btnCopy: MaterialButton
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
        tvStatus.text = "Scanning selection..."
        tvSummary.text = "Drag corners to resize • ${crop.width}×${crop.height}px"
        linkPromptRow.visibility = View.GONE
        etExtracted.setText("")
        btnCopy.text = "Copy"
        chipGroup.removeAllViews()
        scrollChips.visibility = View.GONE
        cardResult.visibility = View.VISIBLE

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                SmartTextAnalyzer.analyzeBitmap(crop)
            }

            val hasQr = result.qrPayloads.isNotEmpty()
            val qrFirstUrl = result.qrPayloads.firstNotNullOfOrNull { SmartTextAnalyzer.extractFirstUrl(it) }
            val textFirstUrl = SmartTextAnalyzer.extractFirstUrl(result.extractedText)
            val detectedUrl = qrFirstUrl ?: textFirstUrl

            if (result.extractedText.isNotEmpty()) {
                // Auto-copy to default Android clipboard
                copyToClipboard(result.extractedText)
                prefs.addClip(result.extractedText, if (hasQr) "QR Code" else "Circle OCR")
                etExtracted.setText(result.extractedText)
                btnCopy.text = "Copied ✓"

                if (hasQr) {
                    tvStatus.text = "QR Code Decoded"
                    tvSummary.text = if (qrFirstUrl != null) {
                        "QR link copied to clipboard"
                    } else {
                        "QR info copied to clipboard"
                    }
                } else {
                    tvStatus.text = "Text Extracted"
                    tvSummary.text = "Copied to clipboard ✓ • Drag corners to resize"
                }

                if (detectedUrl != null) {
                    linkPromptRow.visibility = View.VISIBLE
                    tvLinkPrompt.text = if (qrFirstUrl != null) {
                        "QR contains a link — Open with browser?"
                    } else {
                        "Link detected — Open with browser?"
                    }
                }
            } else {
                tvStatus.text = "Crop Selected"
                tvSummary.text = "No text found • Drag corners to resize"
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
        return Chip(themedContext).apply {
            text = entity.label
            isClickable = true
            setOnClickListener {
                when (entity.type) {
                    SmartEntityType.OTP, SmartEntityType.MATH, SmartEntityType.QR_CODE -> {
                        copyToClipboard(entity.value)
                        Toast.makeText(context, "Copied: ${entity.value}", Toast.LENGTH_SHORT).show()
                        val qrUrl = SmartTextAnalyzer.extractFirstUrl(entity.value)
                        if (entity.type == SmartEntityType.QR_CODE && qrUrl != null) {
                            openUrl(qrUrl)
                        }
                    }
                    SmartEntityType.URL -> {
                        copyToClipboard(entity.value)
                        prefs.addClip(entity.value, "Link")
                        openUrl(entity.value)
                    }
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
        cm.setPrimaryClip(ClipData.newPlainText("SnapEdge", text))
    }

    private fun saveCropToGallery(bitmap: Bitmap): Boolean {
        val filename = "SnapEdge_Crop_${System.currentTimeMillis()}.png"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SnapEdge")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: false
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "SnapEdge"
                ).apply { mkdirs() }
                val file = File(dir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                true
            }
        }.getOrDefault(false)
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
        scope.cancel()
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
