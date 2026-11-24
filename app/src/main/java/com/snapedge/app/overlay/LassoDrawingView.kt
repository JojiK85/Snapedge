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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Google Circle-to-Search style selection canvas.
 * Users circle anything on screen; on release it snaps to a resizable square/rectangle crop box
 * with 4 draggable corner handles and drag-to-move support.
 */
class LassoDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class SelectionMode {
        FREEHAND, BOX
    }

    private enum class DragHandle {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE_BOX
    }

    var mode: SelectionMode = SelectionMode.FREEHAND
    var onSelectionComplete: ((RectF) -> Unit)? = null

    private val strokePath = Path()
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

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

    private val handleDotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleDotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val cropOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val activeBox = RectF()
    private var hasSelection = false
    private var isDrawingNew = false
    private var activeHandle = DragHandle.NONE

    private val handleTouchRadiusPx = 56f
    private val minBoxSizePx = 56f

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
        isDrawingNew = false
        activeHandle = DragHandle.NONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!hasSelection) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), softAmbientPaint)
            canvas.drawRect(4f, 4f, width - 4f, height - 4f, screenEdgeGlowPaint)
            return
        }

        if (isDrawingNew) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), softAmbientPaint)
            if (mode == SelectionMode.BOX) {
                canvas.drawRoundRect(activeBox, 20f, 20f, glowPaint)
                canvas.drawRoundRect(activeBox, 20f, 20f, strokePaint)
            } else {
                canvas.drawPath(strokePath, glowPaint)
                canvas.drawPath(strokePath, strokePaint)
            }
        } else {
            canvas.save()
            canvas.clipOutRect(activeBox)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), outsideCropDimPaint)
            canvas.restore()

            canvas.drawRect(activeBox, cropOutlinePaint)
            drawCornerBracketsAndHandles(canvas, activeBox)
        }
    }

    private fun drawCornerBracketsAndHandles(canvas: Canvas, r: RectF) {
        val len = min(32f, min(r.width(), r.height()) * 0.28f).coerceAtLeast(12f)
        // Corner brackets
        canvas.drawLine(r.left, r.top + len, r.left, r.top, cornerBracketPaint)
        canvas.drawLine(r.left, r.top, r.left + len, r.top, cornerBracketPaint)
        canvas.drawLine(r.right - len, r.top, r.right, r.top, cornerBracketPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, cornerBracketPaint)
        canvas.drawLine(r.left, r.bottom - len, r.left, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.right - len, r.bottom, r.right, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, cornerBracketPaint)

        // Draggable resize dots at all 4 corners
        val dotRadius = 11f
        val corners = arrayOf(
            r.left to r.top,
            r.right to r.top,
            r.left to r.bottom,
            r.right to r.bottom
        )
        for ((cx, cy) in corners) {
            canvas.drawCircle(cx, cy, dotRadius, handleDotFillPaint)
            canvas.drawCircle(cx, cy, dotRadius, handleDotStrokePaint)
        }
    }

    private fun hitTestHandle(x: Float, y: Float): DragHandle {
        if (!hasSelection || isDrawingNew) return DragHandle.NONE
        val corners = listOf(
            DragHandle.TOP_LEFT to (activeBox.left to activeBox.top),
            DragHandle.TOP_RIGHT to (activeBox.right to activeBox.top),
            DragHandle.BOTTOM_LEFT to (activeBox.left to activeBox.bottom),
            DragHandle.BOTTOM_RIGHT to (activeBox.right to activeBox.bottom)
        )
        for ((handle, pt) in corners) {
            if (hypot((x - pt.first).toDouble(), (y - pt.second).toDouble()) <= handleTouchRadiusPx) {
                return handle
            }
        }
        if (activeBox.contains(x, y)) return DragHandle.MOVE_BOX
        return DragHandle.NONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTestHandle(x, y)
                if (hit != DragHandle.NONE) {
                    activeHandle = hit
                    lastTouchX = x
                    lastTouchY = y
                    return true
                }

                // Start a fresh circle / box selection
                activeHandle = DragHandle.NONE
                hasSelection = true
                isDrawingNew = true
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
                if (activeHandle != DragHandle.NONE) {
                    applyHandleDrag(x, y)
                    invalidate()
                    return true
                }

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
                if (activeHandle != DragHandle.NONE) {
                    applyHandleDrag(x, y)
                    activeHandle = DragHandle.NONE
                    invalidate()
                    onSelectionComplete?.invoke(RectF(activeBox))
                    return true
                }

                isDrawingNew = false
                if (mode != SelectionMode.BOX) {
                    strokePath.lineTo(x, y)
                    activeBox.set(minX, minY, maxX, maxY)
                }

                // Expand tiny taps into a neat square crop
                if (activeBox.width() < 28f && activeBox.height() < 28f) {
                    val pad = 90f
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

    private fun applyHandleDrag(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (activeHandle) {
            DragHandle.TOP_LEFT -> {
                activeBox.left = x.coerceIn(0f, activeBox.right - minBoxSizePx)
                activeBox.top = y.coerceIn(0f, activeBox.bottom - minBoxSizePx)
            }
            DragHandle.TOP_RIGHT -> {
                activeBox.right = x.coerceIn(activeBox.left + minBoxSizePx, w)
                activeBox.top = y.coerceIn(0f, activeBox.bottom - minBoxSizePx)
            }
            DragHandle.BOTTOM_LEFT -> {
                activeBox.left = x.coerceIn(0f, activeBox.right - minBoxSizePx)
                activeBox.bottom = y.coerceIn(activeBox.top + minBoxSizePx, h)
            }
            DragHandle.BOTTOM_RIGHT -> {
                activeBox.right = x.coerceIn(activeBox.left + minBoxSizePx, w)
                activeBox.bottom = y.coerceIn(activeBox.top + minBoxSizePx, h)
            }
            DragHandle.MOVE_BOX -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                val boxW = activeBox.width()
                val boxH = activeBox.height()
                val newLeft = (activeBox.left + dx).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
                val newTop = (activeBox.top + dy).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
                activeBox.set(newLeft, newTop, newLeft + boxW, newTop + boxH)
                lastTouchX = x
                lastTouchY = y
            }
            DragHandle.NONE -> Unit
        }
    }
}
