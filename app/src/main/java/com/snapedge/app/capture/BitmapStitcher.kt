package com.snapedge.app.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlin.math.abs

/**
 * Stitches sequential scrolled screen frames into a single seamless long screenshot.
 * Uses horizontal strip Sum-of-Absolute-Differences (SAD) matching while excluding fixed status/nav bars.
 */
object BitmapStitcher {

    private const val MAX_OUTPUT_HEIGHT = 18_000

    /**
     * Estimates how many pixels the viewport scrolled downward between [prev] and [curr].
     * Returns 0 if frames appear identical (bottom of scroll reached).
     */
    fun estimateScrollDelta(prev: Bitmap, curr: Bitmap): Int {
        if (prev.width != curr.width || prev.height != curr.height) return 0
        val w = prev.width
        val h = prev.height

        val topSafe = (h * 0.14f).toInt()
        val bottomSafe = (h * 0.84f).toInt()
        val stripHeight = 36
        if (bottomSafe - stripHeight <= topSafe) return 0

        // Take a template strip near the lower part of the usable viewport in [prev]
        val templateY = bottomSafe - stripHeight
        val xStart = (w * 0.15f).toInt()
        val xEnd = (w * 0.85f).toInt()
        val stepX = 6

        var bestY = templateY
        var bestScore = Long.MAX_VALUE

        // Search upward in [curr] for where that template strip moved
        for (candidateY in topSafe..templateY step 2) {
            var diffSum = 0L
            for (dy in 0 until stripHeight step 3) {
                val pY = templateY + dy
                val cY = candidateY + dy
                for (x in xStart until xEnd step stepX) {
                    val p1 = prev.getPixel(x, pY)
                    val p2 = curr.getPixel(x, cY)
                    diffSum += abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
                    diffSum += abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
                    diffSum += abs((p1 and 0xFF) - (p2 and 0xFF))
                }
                if (diffSum > bestScore) break
            }
            if (diffSum < bestScore) {
                bestScore = diffSum
                bestY = candidateY
            }
        }

        val delta = templateY - bestY
        return if (delta > 16) delta else 0
    }

    /**
     * Stitches [frames] vertically into one tall Bitmap, keeping the top header of the first frame
     * and the bottom bar of the last frame while cleanly appending newly scrolled middle content.
     */
    fun stitchFrames(frames: List<Bitmap>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first().copy(Bitmap.Config.ARGB_8888, false)

        val width = frames.first().width
        val frameHeight = frames.first().height
        val bottomSafe = (frameHeight * 0.84f).toInt()

        val deltas = mutableListOf<Int>()
        for (i in 0 until frames.size - 1) {
            val d = estimateScrollDelta(frames[i], frames[i + 1])
            deltas.add(if (d > 0) d else (frameHeight * 0.45f).toInt())
        }

        val totalHeight = (frameHeight + deltas.sum()).coerceAtMost(MAX_OUTPUT_HEIGHT)
        val result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw first frame up to bottomSafe
        canvas.drawBitmap(
            frames.first(),
            Rect(0, 0, width, bottomSafe),
            Rect(0, 0, width, bottomSafe),
            null
        )

        var currentY = bottomSafe
        for (i in 1 until frames.size) {
            val delta = deltas[i - 1]
            val srcTop = (bottomSafe - delta).coerceIn(0, frameHeight)
            val srcBottom = if (i == frames.size - 1) frameHeight else bottomSafe
            val sliceHeight = srcBottom - srcTop
            if (sliceHeight <= 0 || currentY >= totalHeight) continue

            val drawHeight = sliceHeight.coerceAtMost(totalHeight - currentY)
            canvas.drawBitmap(
                frames[i],
                Rect(0, srcTop, width, srcTop + drawHeight),
                Rect(0, currentY, width, currentY + drawHeight),
                null
            )
            currentY += drawHeight
        }

        if (currentY < totalHeight) {
            val trimmed = Bitmap.createBitmap(result, 0, 0, width, currentY.coerceAtLeast(1))
            result.recycle()
            return trimmed
        }
        return result
    }
}
