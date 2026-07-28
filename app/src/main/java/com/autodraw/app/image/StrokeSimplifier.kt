package com.autodraw.app.image

import android.graphics.PointF

/**
 * Ramer–Douglas–Peucker polyline simplification. Thins extracted contours
 * into smoother, lighter strokes while preserving shape. Operates in
 * normalized coordinates so [tolerance] is expressed relative to image size.
 */
internal object StrokeSimplifier {

    fun simplify(points: List<PointF>, tolerance: Float): List<PointF> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        rdp(points, 0, points.lastIndex, tolerance * tolerance, keep)
        val out = ArrayList<PointF>(points.size)
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    private fun rdp(
        pts: List<PointF>, start: Int, end: Int,
        tolSq: Float, keep: BooleanArray
    ) {
        if (end - start < 2) return
        var maxD = 0f
        var index = start
        val a = pts[start]
        val b = pts[end]
        for (i in start + 1 until end) {
            val d = perpDistSq(pts[i], a, b)
            if (d > maxD) {
                maxD = d
                index = i
            }
        }
        if (maxD > tolSq) {
            keep[index] = true
            rdp(pts, start, index, tolSq, keep)
            rdp(pts, index, end, tolSq, keep)
        }
    }

    /** Squared perpendicular distance from p to segment a-b. */
    private fun perpDistSq(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-10f) {
            val ex = p.x - a.x
            val ey = p.y - a.y
            return ex * ex + ey * ey
        }
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        val tc = t.coerceIn(0f, 1f)
        val px = a.x + tc * dx
        val py = a.y + tc * dy
        val ex = p.x - px
        val ey = p.y - py
        return ex * ex + ey * ey
    }
}
