package com.snapedge.app.overlay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Google Circle-to-Search style interactive selection canvas over a frozen screen capture.
 * Renders a glowing gradient lasso while drawing, then dims outside the crop with crisp corner brackets.
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

    private val softAmbientPaint = Paint().apply {
        color = Color.argb(55, 8, 12, 26)
        style = Paint.Style.FILL
    }

    private val outsideCropDimPaint = Paint().apply {
        color = Color.argb(145, 5, 9, 20)
        style = Paint.Style.FILL
    }

    private val screenEdgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cropOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val activeBox = RectF()
    private var hasSelection = false
    private var isDrawing = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val gradient = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                Color.parseColor("#22D3EE"),
                Color.parseColor("#3B82F6"),
                Color.parseColor("#818CF8"),
                Color.parseColor("#E879F9"),
                Color.parseColor("#22D3EE")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        strokePaint.shader = gradient
        glowPaint.shader = gradient
        screenEdgeGlowPaint.shader = gradient
    }

    fun clearSelection() {
        strokePath.reset()
        activeBox.setEmpty()
        hasSelection = false
        isDrawing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!hasSelection) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), softAmbientPaint)
            canvas.drawRect(4f, 4f, width - 4f, height - 4f, screenEdgeGlowPaint)
            return
        }

        if (isDrawing) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), softAmbientPaint)
            if (mode == SelectionMode.BOX) {
                canvas.drawRoundRect(activeBox, 24f, 24f, glowPaint)
                canvas.drawRoundRect(activeBox, 24f, 24f, strokePaint)
            } else {
                canvas.drawPath(strokePath, glowPaint)
                canvas.drawPath(strokePath, strokePaint)
            }
        } else {
            // Google Circle-to-Search style highlight: keep selected crop bright, dim outside
            canvas.save()
            canvas.clipOutRect(activeBox)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), outsideCropDimPaint)
            canvas.restore()

            canvas.drawRoundRect(activeBox, 18f, 18f, cropOutlinePaint)
            drawCornerBrackets(canvas, activeBox)
        }
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF) {
        val len = min(36f, min(r.width(), r.height()) * 0.3f).coerceAtLeast(14f)
        // Top-left
        canvas.drawLine(r.left, r.top + len, r.left, r.top, cornerBracketPaint)
        canvas.drawLine(r.left, r.top, r.left + len, r.top, cornerBracketPaint)
        // Top-right
        canvas.drawLine(r.right - len, r.top, r.right, r.top, cornerBracketPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, cornerBracketPaint)
        // Bottom-left
        canvas.drawLine(r.left, r.bottom - len, r.left, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, cornerBracketPaint)
        // Bottom-right
        canvas.drawLine(r.right - len, r.bottom, r.right, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, cornerBracketPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hasSelection = true
                isDrawing = true
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
                isDrawing = false
                if (mode != SelectionMode.BOX) {
                    strokePath.lineTo(x, y)
                    activeBox.set(minX, minY, maxX, maxY)
                }

                // Expand tiny taps slightly (like tapping a word or icon in Circle to Search)
                if (activeBox.width() < 28f && activeBox.height() < 28f) {
                    val pad = 80f
                    activeBox.set(
                        (x - pad).coerceAtLeast(0f),
                        (y - pad).coerceAtLeast(0f),
                        (x + pad).coerceAtMost(width.toFloat()),
                        (y + pad).coerceAtMost(height.toFloat())
                    )
                }
                invalidate()
                onSelectionComplete?.invoke(RectF(activeBox))
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
