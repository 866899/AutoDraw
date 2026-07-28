package com.autodraw.app.image

import android.graphics.Bitmap
import android.graphics.PointF
import com.autodraw.app.drawing.Stroke
import kotlin.math.max

/**
 * Converts a source photo into an ordered list of drawable [Stroke]s.
 *
 * Two families of strokes are produced:
 *  - **Outline strokes** traced from the Sobel edge-magnitude field at a few
 *    iso-levels (the higher the level, the stronger the edge → thicker stroke).
 *  - **Shading strokes** (optional) traced from the luminance field to suggest
 *    shadow regions, rendered thinner and lighter for a hatched look.
 *
 * All stroke points are normalized to [0,1]; the view re-applies aspect ratio.
 */
class SketchExtractor(private val config: SketchConfig = SketchConfig()) {

    data class Result(
        val strokes: List<Stroke>,
        val width: Int,
        val height: Int
    )

    fun extract(source: Bitmap): Result {
        val scaled = downscale(source, config.longSide)
        val w = scaled.width
        val h = scaled.height

        val gray = ImageProcessor.toGray(scaled)
        val blurred = ImageProcessor.gaussianBlur(gray, w, h, config.blurRadius, 1.4f)
        val mag = ImageProcessor.sobelMagnitude(blurred, w, h)

        // Optional dark shading contours (follow shadows). Traced on a
        // blurred luminance field so the hatch lines stay smooth.
        val shadingGray = ImageProcessor.gaussianBlur(gray, w, h, config.blurRadius, 2.0f)

        val strokes = ArrayList<Stroke>()

        // 1) Outlines from edge magnitude, one pass per threshold level.
        config.thresholds.forEachIndexed { level, t ->
            val widthPx = strokeWidthForLevel(level, config.thresholds.size)
            val contours = ContourExtractor.extract(mag, w, h, t)
            for (poly in contours) {
                val simplified = StrokeSimplifier.simplify(poly, config.simplifyTol)
                if (simplified.size < config.minStrokePts) continue
                strokes += toStroke(simplified, w, h, widthPx, Stroke.Style.INK)
            }
        }

        // 2) Shading from luminance (darker side of each level → hatch lines).
        for (t in config.shadingLevels) {
            val contours = ContourExtractor.extract(shadingGray, w, h, t)
            for (poly in contours) {
                val simplified = StrokeSimplifier.simplify(poly, config.simplifyTol * 1.5f)
                if (simplified.size < config.minStrokePts) continue
                strokes += toStroke(simplified, w, h, 0.8f, Stroke.Style.PENCIL)
            }
        }

        // 3) Order naturally and drop degenerate single-point strokes.
        val ordered = StrokeSorter.sort(strokes.filter { it.points.size >= 2 })
        return Result(ordered, w, h)
    }

    private fun strokeWidthForLevel(level: Int, total: Int): Float {
        // Higher level (stronger edge, fewer/inner contours) → thicker line.
        val f = if (total <= 1) 1f else level.toFloat() / (total - 1)
        return 1.2f + f * 1.8f // 1.2 .. 3.0
    }

    private fun toStroke(
        poly: List<PointF>, w: Int, h: Int,
        widthPx: Float, style: Stroke.Style
    ): Stroke {
        val nx = 1f / max(w - 1, 1)
        val ny = 1f / max(h - 1, 1)
        val pts = ArrayList<PointF>(poly.size)
        for (p in poly) pts.add(PointF(p.x * nx, p.y * ny))
        return Stroke(points = pts, width = widthPx, style = style)
    }

    private fun downscale(src: Bitmap, longSide: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val longest = max(srcW, srcH)
        if (longest <= longSide) return src
        val scale = longSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (srcW * scale).toInt().coerceAtLeast(1),
            (srcH * scale).toInt().coerceAtLeast(1), true)
    }
}
