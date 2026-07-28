package com.autodraw.app.drawing

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Renders a [DrawingEngine]'s current state into any [Canvas]. Shared by the
 * on-screen [DrawingView], the image exporter and the video exporter so all
 * three produce identical visuals.
 */
class SceneRenderer {

    /** Multiplier applied on top of each stroke's base width. */
    var brushScale: Float = 1f

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF1A1A1A.toInt()
    }
    private val pencilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF4A4A4A.toInt()
        alpha = 150
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFEFEFE.toInt() }
    private val paperBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE2E2E2.toInt()
        strokeWidth = 1f
    }
    private val tmpPath = Path()

    fun render(
        engine: DrawingEngine,
        canvas: Canvas,
        viewW: Int,
        viewH: Int,
        drawBorder: Boolean = true
    ) {
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), bgPaint)
        if (engine.imgHeight == 0 || viewW == 0 || viewH == 0) return

        val a = engine.aspect
        val viewA = viewW.toFloat() / viewH
        val dstW: Float
        val dstH: Float
        val dstLeft: Float
        val dstTop: Float
        if (viewA > a) {
            dstH = viewH.toFloat()
            dstW = dstH * a
            dstLeft = (viewW - dstW) / 2f
            dstTop = 0f
        } else {
            dstW = viewW.toFloat()
            dstH = dstW / a
            dstLeft = 0f
            dstTop = (viewH - dstH) / 2f
        }
        if (drawBorder) {
            canvas.drawRect(RectF(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH), paperBorderPaint)
        }
        canvas.save()
        canvas.clipRect(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)
        canvas.translate(dstLeft, dstTop)

        val scaleFactor = minOf(dstW, dstH) * 0.0042f
        engine.visitVisible { stroke, frac ->
            drawStroke(canvas, stroke, frac, scaleFactor, dstW, dstH)
        }
        canvas.restore()
    }

    private fun drawStroke(
        canvas: Canvas, stroke: Stroke, frac: Float, scaleFactor: Float,
        dstW: Float, dstH: Float
    ) {
        val pts = stroke.points
        if (pts.isEmpty()) return
        val paint = when (stroke.style) {
            Stroke.Style.PENCIL, Stroke.Style.SKETCH -> pencilPaint
            Stroke.Style.INK -> inkPaint
        }
        paint.strokeWidth = stroke.width * scaleFactor * brushScale *
            if (stroke.style == Stroke.Style.PENCIL) 0.7f else 1f

        val mapX = { x: Float -> x * dstW }
        val mapY = { y: Float -> y * dstH }

        tmpPath.reset()
        if (pts.size == 1) {
            tmpPath.moveTo(mapX(pts[0].x), mapY(pts[0].y))
            tmpPath.lineTo(mapX(pts[0].x) + 0.5f, mapY(pts[0].y))
        } else if (frac >= 1f) {
            tmpPath.moveTo(mapX(pts[0].x), mapY(pts[0].y))
            for (i in 1 until pts.size) tmpPath.lineTo(mapX(pts[i].x), mapY(pts[i].y))
        } else {
            val n = pts.size
            val f = frac * (n - 1)
            val i = kotlin.math.floor(f).toInt().coerceIn(0, n - 2)
            val t = f - i
            tmpPath.moveTo(mapX(pts[0].x), mapY(pts[0].y))
            for (j in 1..i) tmpPath.lineTo(mapX(pts[j].x), mapY(pts[j].y))
            val p0 = pts[i]
            val p1 = pts[i + 1]
            tmpPath.lineTo(mapX(p0.x + (p1.x - p0.x) * t), mapY(p0.y + (p1.y - p0.y) * t))
        }
        canvas.drawPath(tmpPath, paint)
    }
}
