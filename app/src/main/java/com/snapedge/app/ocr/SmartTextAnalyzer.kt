package com.snapedge.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Patterns
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.snapedge.app.model.SmartAnalysisResult
import com.snapedge.app.model.SmartEntity
import com.snapedge.app.model.SmartEntityType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.DecimalFormat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs on-device ML Kit OCR and Barcode/QR recognition, then extracts smart actionable entities.
 */
object SmartTextAnalyzer {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val barcodeScanner by lazy {
        BarcodeScanning.getClient()
    }

    suspend fun analyzeBitmap(bitmap: Bitmap): SmartAnalysisResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = runCatching { textRecognizer.process(image).await() }.getOrNull()
        val barcodes = runCatching { barcodeScanner.process(image).await() }.getOrNull().orEmpty()

        val qrPayloads = barcodes.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }.distinct()
        val ocrText = visionText?.text?.trim().orEmpty()

        val combinedText = buildString {
            if (ocrText.isNotEmpty()) append(ocrText)
            if (qrPayloads.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(qrPayloads.joinToString("\n"))
            }
        }

        val entities = extractSmartEntities(ocrText, qrPayloads)
        val sensitiveRects = if (visionText != null) findSensitiveRects(visionText) else emptyList()

        return SmartAnalysisResult(
            extractedText = combinedText,
            qrPayloads = qrPayloads,
            entities = entities,
            sensitiveRects = sensitiveRects
        )
    }

    private fun extractSmartEntities(text: String, qrPayloads: List<String>): List<SmartEntity> {
        val entities = mutableListOf<SmartEntity>()

        qrPayloads.forEach { payload ->
            entities.add(SmartEntity(SmartEntityType.QR_CODE, "QR: ${payload.take(24)}", payload))
        }

        if (text.isBlank()) return entities

        // Inline math calculation
        val mathRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/×÷])\\s*(\\d+(?:\\.\\d+)?)")
        mathRegex.find(text)?.let { match ->
            val a = match.groupValues[1].toDoubleOrNull()
            val op = match.groupValues[2]
            val b = match.groupValues[3].toDoubleOrNull()
            if (a != null && b != null) {
                val res = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*", "×" -> a * b
                    "/", "÷" -> if (b != 0.0) a / b else null
                    else -> null
                }
                if (res != null) {
                    val formatted = DecimalFormat("#.####").format(res)
                    entities.add(SmartEntity(SmartEntityType.MATH, "= $formatted", formatted))
                }
            }
        }

        // OTP detection
        val lower = text.lowercase()
        if (lower.contains("otp") || lower.contains("code") || lower.contains("pin") || lower.contains("verify")) {
            Regex("\\b\\d{4,8}\\b").find(text)?.let { match ->
                entities.add(SmartEntity(SmartEntityType.OTP, "Copy OTP ${match.value}", match.value))
            }
        }

        // URLs (avoid matching email domains as standalone URLs)
        val urlMatcher = Patterns.WEB_URL.matcher(text)
        while (urlMatcher.find()) {
            val start = urlMatcher.start()
            if (start > 0 && text[start - 1] == '@') continue
            val rawUrl = urlMatcher.group()
            val normalized = normalizeUrl(rawUrl)
            entities.add(SmartEntity(SmartEntityType.URL, "Open ${rawUrl.take(22)}", normalized))
        }

        // Emails
        val emailMatcher = Patterns.EMAIL_ADDRESS.matcher(text)
        if (emailMatcher.find()) {
            val email = emailMatcher.group()
            entities.add(SmartEntity(SmartEntityType.EMAIL, email, email))
        }

        // Phone numbers
        Regex("\\+?\\d[\\d\\s\\-()]{7,15}\\d").find(text)?.let { match ->
            val cleaned = match.value.trim()
            if (!cleaned.contains(".")) {
                entities.add(SmartEntity(SmartEntityType.PHONE, "Call $cleaned", cleaned))
            }
        }

        return entities.distinctBy { "${it.type}:${it.value}" }
    }

    private fun findSensitiveRects(visionText: Text): List<Rect> {
        val rects = mutableListOf<Rect>()
        val phoneOrCardRegex = Regex(".*(?:\\d{4}[\\s-]?\\d{4}|\\+?\\d[\\d\\s-]{7,}\\d).*")
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val content = line.text
                val hasEmail = Patterns.EMAIL_ADDRESS.matcher(content).find()
                val hasNumber = phoneOrCardRegex.matches(content)
                if (hasEmail || hasNumber) {
                    line.boundingBox?.let { rects.add(Rect(it)) }
                }
            }
        }
        return rects
    }

    fun extractFirstUrl(text: String): String? {
        if (text.isBlank()) return null
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            if (start > 0 && text[start - 1] == '@') continue
            return normalizeUrl(matcher.group())
        }
        return null
    }

    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
    }
}
