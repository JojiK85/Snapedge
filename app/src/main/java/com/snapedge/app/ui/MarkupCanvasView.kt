package com.snapedge.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Scroll-friendly image canvas supporting translucent highlight strokes, solid privacy redaction strokes,
 * and automatic sensitive-region blackout rectangles.
 */
class MarkupCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool {
        SCROLL, HIGHLIGHT, REDACT
    }

    private sealed class MarkupItem {
        data class Stroke(val path: Path, val isRedact: Boolean) : MarkupItem()
        data class AutoRedactBox(val rect: RectF) : MarkupItem()
    }

    var activeTool: Tool = Tool.SCROLL
    private var sourceBitmap: Bitmap? = null
    private val items = mutableListOf<MarkupItem>()
    private var activePath: Path? = null

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 250, 204, 21)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 38f
    }

    private val redactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 44f
    }

    private val autoRedactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    fun setBitmap(bitmap: Bitmap) {
        sourceBitmap = bitmap
        requestLayout()
        invalidate()
    }

    fun addAutoRedactRects(bitmapRects: List<Rect>) {
        val bmp = sourceBitmap ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / bmp.width
        val sy = height.toFloat() / bmp.height
        bitmapRects.forEach { r ->
            items.add(
                MarkupItem.AutoRedactBox(
                    RectF(r.left * sx - 4f, r.top * sy - 2f, r.right * sx + 4f, r.bottom * sy + 2f)
                )
            )
        }
        invalidate()
    }

    fun undoLast() {
        if (items.isNotEmpty()) {
            items.removeAt(items.lastIndex)
            invalidate()
        }
    }

    fun renderFinalBitmap(): Bitmap? {
        val bmp = sourceBitmap ?: return null
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val sx = bmp.width.toFloat() / width.coerceAtLeast(1)
        val sy = bmp.height.toFloat() / height.coerceAtLeast(1)
        canvas.scale(sx, sy)
        drawMarkups(canvas)
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        val bmp = sourceBitmap
        val desiredHeight = if (bmp != null && bmp.width > 0) {
            ((viewWidth.toFloat() / bmp.width) * bmp.height).toInt().coerceAtLeast(1)
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        setMeasuredDimension(viewWidth, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        drawMarkups(canvas)
    }

    private fun drawMarkups(canvas: Canvas) {
        items.forEach { item ->
            when (item) {
                is MarkupItem.Stroke -> {
                    canvas.drawPath(item.path, if (item.isRedact) redactPaint else highlightPaint)
                }
                is MarkupItem.AutoRedactBox -> {
                    canvas.drawRoundRect(item.rect, 8f, 8f, autoRedactPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeTool == Tool.SCROLL) return false

        parent?.requestDisallowInterceptTouchEvent(true)
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path().apply { moveTo(x, y) }
                activePath = path
                items.add(MarkupItem.Stroke(path, activeTool == Tool.REDACT))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                activePath?.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePath?.lineTo(x, y)
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
