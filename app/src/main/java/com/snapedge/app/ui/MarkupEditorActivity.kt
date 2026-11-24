package com.snapedge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.snapedge.app.R
import com.snapedge.app.data.PreferencesManager
import com.snapedge.app.ocr.SmartTextAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Viewer and markup editor for Long Screenshots, Full Screenshots, and Circled Crops.
 * Supports Highlighting, Privacy Redact brush, 1-tap Auto-Redact, full-page OCR, Save, and Share.
 */
class MarkupEditorActivity : AppCompatActivity() {

    private lateinit var canvasView: MarkupCanvasView
    private lateinit var prefs: PreferencesManager
    private var loadedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_markup_editor)

        prefs = PreferencesManager(this)
        canvasView = findViewById(R.id.markupCanvasView)
        val tvTitle = findViewById<TextView>(R.id.tvEditorDimensions)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (imagePath.isNullOrEmpty() || !File(imagePath).exists()) {
            Toast.makeText(this, "Screenshot file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(imagePath)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadedBitmap = bitmap
        canvasView.setBitmap(bitmap)
        tvTitle.text = "Screenshot (${bitmap.width}×${bitmap.height})"

        val btnScroll = findViewById<MaterialButton>(R.id.btnToolScroll)
        val btnHighlight = findViewById<MaterialButton>(R.id.btnToolHighlight)
        val btnRedact = findViewById<MaterialButton>(R.id.btnToolRedact)

        fun updateToolSelection(tool: MarkupCanvasView.Tool) {
            canvasView.activeTool = tool
            btnScroll.alpha = if (tool == MarkupCanvasView.Tool.SCROLL) 1f else 0.55f
            btnHighlight.alpha = if (tool == MarkupCanvasView.Tool.HIGHLIGHT) 1f else 0.55f
            btnRedact.alpha = if (tool == MarkupCanvasView.Tool.REDACT) 1f else 0.55f
        }
        updateToolSelection(MarkupCanvasView.Tool.SCROLL)

        btnScroll.setOnClickListener { updateToolSelection(MarkupCanvasView.Tool.SCROLL) }
        btnHighlight.setOnClickListener { updateToolSelection(MarkupCanvasView.Tool.HIGHLIGHT) }
        btnRedact.setOnClickListener { updateToolSelection(MarkupCanvasView.Tool.REDACT) }
        findViewById<MaterialButton>(R.id.btnToolUndo).setOnClickListener { canvasView.undoLast() }

        findViewById<ImageButton>(R.id.btnEditorBack).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.btnEditorAutoRedact).setOnClickListener {
            runAutoRedact()
        }

        findViewById<MaterialButton>(R.id.btnToolOcrAll).setOnClickListener {
            extractAllText()
        }

        findViewById<ImageButton>(R.id.btnEditorSave).setOnClickListener {
            saveToGallery()
        }

        findViewById<ImageButton>(R.id.btnEditorShare).setOnClickListener {
            shareRenderedImage()
        }
    }

    private fun runAutoRedact() {
        val bmp = loadedBitmap ?: return
        Toast.makeText(this, "Scanning for emails & numbers...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                SmartTextAnalyzer.analyzeBitmap(bmp)
            }
            if (result.sensitiveRects.isEmpty()) {
                Toast.makeText(this@MarkupEditorActivity, "No emails/phones detected automatically", Toast.LENGTH_SHORT).show()
            } else {
                canvasView.addAutoRedactRects(result.sensitiveRects)
                Toast.makeText(
                    this@MarkupEditorActivity,
                    "Redacted ${result.sensitiveRects.size} sensitive region(s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun extractAllText() {
        val bmp = loadedBitmap ?: return
        Toast.makeText(this, "Extracting all text...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                SmartTextAnalyzer.analyzeBitmap(bmp)
            }
            if (result.extractedText.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SnapEdge OCR", result.extractedText))
                prefs.addClip(result.extractedText, "Full Shot OCR")
                Toast.makeText(this@MarkupEditorActivity, "Extracted & copied to clipboard!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MarkupEditorActivity, "No text found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToGallery() {
        lifecycleScope.launch {
            val rendered = canvasView.renderFinalBitmap() ?: return@launch
            val saved = withContext(Dispatchers.IO) {
                writeBitmapToMediaStore(rendered)
            }
            rendered.recycle()
            Toast.makeText(
                this@MarkupEditorActivity,
                if (saved) "Saved to Pictures/SnapEdge" else "Save failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareRenderedImage() {
        lifecycleScope.launch {
            val rendered = canvasView.renderFinalBitmap() ?: return@launch
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "captures").apply { mkdirs() }
                val out = File(dir, "share_${System.currentTimeMillis()}.png")
                FileOutputStream(out).use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out
            }
            rendered.recycle()

            val uri = FileProvider.getUriForFile(this@MarkupEditorActivity, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share SnapEdge Capture"))
        }
    }

    private fun writeBitmapToMediaStore(bitmap: Bitmap): Boolean {
        val filename = "SnapEdge_${System.currentTimeMillis()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SnapEdge")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
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
    }

    override fun onDestroy() {
        loadedBitmap?.recycle()
        loadedBitmap = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_IMAGE_PATH = "extra_image_path"

        fun launch(context: Context, imagePath: String) {
            val intent = Intent(context, MarkupEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_IMAGE_PATH, imagePath)
            }
            context.startActivity(intent)
        }
    }
}
