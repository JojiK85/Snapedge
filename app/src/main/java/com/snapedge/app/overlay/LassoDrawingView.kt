package com.snapedge.app.overlay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive freehand Circle/Lasso and Box selection canvas rendered over a frozen screen capture.
 */
class LassoDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class SelectionMode {
        FREEHAND, BOX
    }

    var mode: SelectionMode = SelectionMode.FREEHAND
    var onSelectionComplete: ((RectF) -> Unit)? = null

    private val strokePath = Path()
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f

    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = Float.MIN_VALUE
    private var maxY = Float.MIN_VALUE

    private val dimPaint = Paint().apply {
        color = Color.argb(95, 8, 12, 24)
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 34, 211, 238)
        style = Paint.Style.FILL
    }

    private val activeBox = RectF()
    private var hasSelection = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun clearSelection() {
        strokePath.reset()
        activeBox.setEmpty()
        hasSelection = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (!hasSelection) return

        if (mode == SelectionMode.BOX) {
            canvas.drawRoundRect(activeBox, 20f, 20f, boxFillPaint)
            canvas.drawRoundRect(activeBox, 20f, 20f, glowPaint)
            canvas.drawRoundRect(activeBox, 20f, 20f, strokePaint)
        } else {
            canvas.drawPath(strokePath, glowPaint)
            canvas.drawPath(strokePath, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hasSelection = true
                startX = x
                startY = y
                currentX = x
                currentY = y
                minX = x
                minY = y
                maxX = x
                maxY = y
                strokePath.reset()
                strokePath.moveTo(x, y)
                activeBox.set(x, y, x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == SelectionMode.BOX) {
                    activeBox.set(
                        min(startX, x),
                        min(startY, y),
                        max(startX, x),
                        max(startY, y)
                    )
                } else {
                    val midX = (currentX + x) / 2f
                    val midY = (currentY + y) / 2f
                    strokePath.quadTo(currentX, currentY, midX, midY)
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
                currentX = x
                currentY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val rect = if (mode == SelectionMode.BOX) {
                    RectF(activeBox)
                } else {
                    strokePath.lineTo(x, y)
                    RectF(minX, minY, maxX, maxY)
                }

                // Ignore accidental tiny taps (< 28px); expand small scribbles slightly
                if (rect.width() < 28f && rect.height() < 28f) {
                    val pad = 75f
                    rect.set(
                        (x - pad).coerceAtLeast(0f),
                        (y - pad).coerceAtLeast(0f),
                        (x + pad).coerceAtMost(width.toFloat()),
                        (y + pad).coerceAtMost(height.toFloat())
                    )
                }
                invalidate()
                onSelectionComplete?.invoke(rect)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
