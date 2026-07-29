package com.autodraw.app.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * 在画布上叠加显示识别到的活物:矩形框 + 标签 + 五官点。
 * 用于 [MainActivity] 在生成笔画前让用户预览识别结果。
 *
 * 坐标系:[SubjectRegions] 用归一化坐标 [0,1],本 View 按自身宽高映射到像素。
 * 图片宽高比由调用方通过 [setAspect] 传入,本 View 把图片居中并留 letterbox,
 * 使叠加框与 [DrawingView] 的渲染对齐。
 */
class SubjectOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var subjects: SubjectRegions = SubjectRegions.EMPTY
    private var imgAspect: Float = 1f  // width/height

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF5722")
        strokeWidth = 4f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CCFF5722")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CAF50")
    }
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4CAF50")
        strokeWidth = 2f
    }

    fun setSubjects(regions: SubjectRegions, aspect: Float) {
        subjects = regions
        imgAspect = if (aspect > 0f) aspect else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!subjects.hasSubjects) return
        val (ox, oy, iw, ih) = imageFrame()
        if (iw <= 0 || ih <= 0) return
        val sx = iw
        val sy = ih

        for (box in subjects.boxes) {
            val l = ox + box.left * sx
            val t = oy + box.top * sy
            val r = ox + box.right * sx
            val b = oy + box.bottom * sy
            canvas.drawRect(l, t, r, b, boxPaint)
            // 标签背景
            val label = "${box.label} ${(box.confidence * 100).toInt()}%"
            val lw = labelTextPaint.measureText(label)
            canvas.drawRect(l, t - 30, l + lw + 16, t, labelBgPaint)
            canvas.drawText(label, l + 8, t - 8, labelTextPaint)
        }

        for (lm in subjects.faceLandmarks) {
            listOf(
                lm.leftEye, lm.rightEye,
                lm.leftBrow, lm.rightBrow,
                lm.leftBrowBottom, lm.rightBrowBottom,  // 眉毛下缘(厚度)
                lm.noseBridge, lm.upperLip, lm.lowerLip, lm.faceOval
            ).forEach { pts ->
                drawContour(canvas, pts, ox, oy, sx, sy)
            }
        }
    }

    private fun drawContour(
        canvas: Canvas, pts: List<PointF>,
        ox: Float, oy: Float, sx: Float, sy: Float
    ) {
        if (pts.isEmpty()) return
        for (p in pts) {
            canvas.drawCircle(ox + p.x * sx, oy + p.y * sy, 3f, dotPaint)
        }
        if (pts.size >= 2) {
            val path = android.graphics.Path()
            path.moveTo(ox + pts[0].x * sx, oy + pts[0].y * sy)
            for (i in 1 until pts.size) {
                path.lineTo(ox + pts[i].x * sx, oy + pts[i].y * sy)
            }
            canvas.drawPath(path, contourPaint)
        }
    }

    /** 计算图片居中显示时的 frame(原图比例的 letterbox)。 */
    private fun imageFrame(): FloatArray {
        if (width == 0 || height == 0) return FloatArray(4)
        val viewAspect = width.toFloat() / height
        val iw: Float
        val ih: Float
        if (imgAspect >= viewAspect) {
            iw = width.toFloat()
            ih = iw / imgAspect
        } else {
            ih = height.toFloat()
            iw = ih * imgAspect
        }
        val ox = (width - iw) / 2f
        val oy = (height - ih) / 2f
        return floatArrayOf(ox, oy, iw, ih)
    }
}
