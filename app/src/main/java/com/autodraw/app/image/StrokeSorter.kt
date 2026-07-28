package com.autodraw.app.image

import android.graphics.PointF
import com.autodraw.app.drawing.Stroke

/**
 * Orders strokes so the simulated pen travels naturally between neighbouring
 * strokes (greedy nearest-neighbour), optionally flipping a stroke's
 * direction so its start point is the closest end to the current pen
 * position. This makes the drawing animation look like a human hand
 * moving smoothly rather than teleporting across the canvas.
 */
internal object StrokeSorter {

    fun sort(strokes: List<Stroke>): List<Stroke> {
        if (strokes.size < 2) return strokes
        val remaining = strokes.toMutableList()
        val result = ArrayList<Stroke>(strokes.size)
        var pen = PointF(0f, 0f) // start from top-left
        while (remaining.isNotEmpty()) {
            var bestIdx = 0
            var bestFlip = false
            var bestDist = Float.MAX_VALUE
            for (i in remaining.indices) {
                val s = remaining[i]
                val p = s.points
                if (p.isEmpty()) continue
                val dHead = Stroke.distance(pen, p.first())
                val dTail = Stroke.distance(pen, p.last())
                val (d, flip) = if (dHead <= dTail) dHead to false else dTail to true
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                    bestFlip = flip
                }
            }
            val picked = remaining.removeAt(bestIdx)
            val oriented = if (bestFlip) picked.copy(points = picked.points.asReversed()) else picked
            result.add(oriented)
            pen = oriented.points.last()
        }
        return result
    }
}
