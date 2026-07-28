package com.autodraw.app.image

import android.graphics.PointF

/**
 * Extracts pen-like polylines from a normalized edge-magnitude field.
 *
 * Pipeline:
 *  1. Marching squares isoline tracing at one or more threshold levels.
 *     Each cell yields 0, 1 or 2 line segments whose endpoints lie on the
 *     cell edges. Because two adjacent cells share an edge, the endpoint
 *     they each compute for that edge is produced by identical arithmetic
 *     and therefore compares **equal** — which lets us chain segments into
 *     continuous polylines by exact endpoint matching.
 *  2. Greedy chaining of segments → polylines (open chains or closed loops).
 */
internal object ContourExtractor {

    private const val TOP = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val LEFT = 3

    /**
     * Segment list per marching-squares case index
     * (bits: TL=1, TR=2, BR=4, BL=8, "inside" = magnitude >= threshold).
     * Each entry is a flat list of edge-id pairs.
     */
    private val CASE_SEGMENTS: Array<IntArray> = arrayOf(
        intArrayOf(),                  // 0  none
        intArrayOf(TOP, LEFT),         // 1  A
        intArrayOf(TOP, RIGHT),        // 2  B
        intArrayOf(LEFT, RIGHT),       // 3  A,B
        intArrayOf(RIGHT, BOTTOM),     // 4  C
        intArrayOf(TOP, RIGHT, BOTTOM, LEFT),  // 5  A,C (ambiguous)
        intArrayOf(TOP, BOTTOM),       // 6  B,C
        intArrayOf(LEFT, BOTTOM),      // 7  A,B,C
        intArrayOf(TOP, LEFT),         // 8  D
        intArrayOf(TOP, BOTTOM),      // 9  A,D
        intArrayOf(TOP, LEFT, RIGHT, BOTTOM),  // 10 B,D (ambiguous)
        intArrayOf(RIGHT, BOTTOM),     // 11 A,B,D
        intArrayOf(LEFT, RIGHT),       // 12 C,D
        intArrayOf(TOP, RIGHT),        // 13 A,C,D
        intArrayOf(TOP, LEFT),         // 14 B,C,D
        intArrayOf()                   // 15 all
    )

    /** A traceable line segment with mutable endpoints. */
    private class Seg(var ax: Float, var ay: Float, var bx: Float, var by: Float)

    fun extract(mag: FloatArray, w: Int, h: Int, threshold: Float): List<MutableList<PointF>> {
        val segs = buildSegments(mag, w, h, threshold)
        return chain(segs, w, h)
    }

    private fun buildSegments(mag: FloatArray, w: Int, h: Int, t: Float): List<Seg> {
        val segs = ArrayList<Seg>()
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val i = y * w + x
                val mA = mag[i]
                val mB = mag[i + 1]
                val mC = mag[i + w + 1]
                val mD = mag[i + w]
                var code = 0
                if (mA >= t) code += 1
                if (mB >= t) code += 2
                if (mC >= t) code += 4
                if (mD >= t) code += 8
                val edges = CASE_SEGMENTS[code]
                if (edges.isEmpty()) continue
                var k = 0
                while (k < edges.size) {
                    val e1 = edges[k]
                    val e2 = edges[k + 1]
                    val (x1, y1) = edgePoint(e1, x, y, mA, mB, mC, mD, t)
                    val (x2, y2) = edgePoint(e2, x, y, mA, mB, mC, mD, t)
                    segs.add(Seg(x1, y1, x2, y2))
                    k += 2
                }
            }
        }
        return segs
    }

    /** Interpolated crossing point of an edge of the cell (x,y). */
    private fun edgePoint(
        edge: Int, x: Int, y: Int,
        mA: Float, mB: Float, mC: Float, mD: Float, t: Float
    ): Pair<Float, Float> = when (edge) {
        TOP -> {
            val f = frac(mA, mB, t)
            Pair(x + f, y.toFloat())
        }
        RIGHT -> {
            val f = frac(mB, mC, t)
            Pair((x + 1).toFloat(), y + f)
        }
        BOTTOM -> {
            val f = frac(mD, mC, t)
            Pair(x + f, (y + 1).toFloat())
        }
        LEFT -> {
            val f = frac(mA, mD, t)
            Pair(x.toFloat(), y + f)
        }
        else -> Pair(x.toFloat(), y.toFloat())
    }

    private fun frac(a: Float, b: Float, t: Float): Float {
        val d = b - a
        if (kotlin.math.abs(d) < 1e-6f) return 0.5f
        return ((t - a) / d).coerceIn(0f, 1f)
    }

    /**
     * Chain segments that share identical endpoints into polylines.
     * Interior contour vertices have degree 2 (manifold), so greedy
     * endpoint matching yields clean open chains / closed loops.
     */
    private fun chain(segs: List<Seg>, w: Int, h: Int): List<MutableList<PointF>> {
        if (segs.isEmpty()) return emptyList()
        val used = BooleanArray(segs.size)
        val map = HashMap<Long, MutableList<Int>>(segs.size * 2)
        for (i in segs.indices) {
            map.getOrPut(key(segs[i].ax, segs[i].ay)) { ArrayList(2) }.add(i)
            map.getOrPut(key(segs[i].bx, segs[i].by)) { ArrayList(2) }.add(i)
        }

        val result = ArrayList<MutableList<PointF>>()
        for (start in segs.indices) {
            if (used[start]) continue
            used[start] = true
            val poly = ArrayList<PointF>()
            // Extend forward (b side)
            poly.add(PointF(segs[start].ax, segs[start].ay))
            poly.add(PointF(segs[start].bx, segs[start].by))
            extend(poly, true, segs, used, map)
            // Extend backward (a side): reverse, extend at "front"
            poly.reverse()
            extend(poly, true, segs, used, map)
            // restore orientation: leave as is (reversed already forward order)
            result.add(poly.toMutableList() as MutableList<PointF>)
        }
        return result
    }

    private fun extend(
        poly: MutableList<PointF>, fromTail: Boolean,
        segs: List<Seg>, used: BooleanArray,
        map: HashMap<Long, MutableList<Int>>
    ) {
        while (true) {
            val tail = poly.last()
            val k = key(tail.x, tail.y)
            val cands = map[k] ?: return
            val nextIdx = cands.firstOrNull { !used[it] } ?: return
            used[nextIdx] = true
            val s = segs[nextIdx]
            val aMatches = key(s.ax, s.ay) == k
            val np = if (aMatches) PointF(s.bx, s.by) else PointF(s.ax, s.ay)
            // Stop if we have closed a loop.
            if (ptsEqual(np, poly.first())) {
                poly.add(np) // close the loop visually
                return
            }
            poly.add(np)
        }
    }

    // Quantize a point to a 1/4-pixel grid key. Adjacent cells produce
    // identical floats for a shared edge, so even coarse quantization is safe.
    private fun key(x: Float, y: Float): Long {
        val qx = Math.round(x * 4f)
        val qy = Math.round(y * 4f)
        return (qx.toLong() shl 32) or (qy.toLong() and 0xFFFFFFFFL)
    }

    private fun ptsEqual(a: PointF, b: PointF): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy < 1e-4f
    }
}
