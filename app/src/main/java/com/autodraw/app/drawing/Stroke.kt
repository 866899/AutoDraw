package com.autodraw.app.drawing

import android.graphics.PointF

/**
 * A single drawable stroke (one continuous pen motion).
 * Points are stored in normalized image coordinates [0,1] so the stroke
 * can be rendered at any target size/aspect without re-processing.
 */
data class Stroke(
    val points: List<PointF>,
    val width: Float,
    val style: Style
) {
    enum class Style { PENCIL, INK, SKETCH }

    val length: Float by lazy {
        if (points.size < 2) 0f
        else {
            var s = 0f
            for (i in 1 until points.size) {
                s += distance(points[i - 1], points[i])
            }
            s
        }
    }

    companion object {
        fun distance(a: PointF, b: PointF): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }
}
