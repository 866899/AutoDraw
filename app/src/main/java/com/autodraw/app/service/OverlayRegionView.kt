package com.autodraw.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏覆盖的框选视图。
 *
 * 用户可:
 *  - 拖动四角:调整大小(锁定图片宽高比 [aspect])
 *  - 拖动中心区域:整体移动
 *  - 双指捏合暂不支持,保持单指简单可靠
 *
 * 确认后通过 [onConfirm] 回调返回屏幕像素坐标的 [RectF];取消则 [onCancel]。
 *
 * 视图本身 match_parent,触摸坐标即屏幕坐标(因为它是全屏 overlay)。
 */
class OverlayRegionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 图片宽高比(width/height)。调整大小时锁定此比例。 */
    var aspect: Float = 1f
        set(value) { field = value.coerceAtLeast(0.1f); invalidate() }

    /** 初始矩形比例占短边的多少(默认 0.6)。 */
    var initialFraction: Float = 0.6f

    private val region = RectF()

    /** 角/中心手柄半径(dp -> px 在 onSizeChanged 里算)。 */
    private val handleRadiusPx = dp(14f)

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        isFakeBoldText = true
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }

    var onConfirm: ((RectF) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var mode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && region.width() <= 0f) {
            // 用图片比例初始化居中矩形
            val base = minOf(w, h).toFloat() * initialFraction
            val rw: Float
            val rh: Float
            if (aspect >= 1f) {
                rw = base.coerceAtMost(w.toFloat() * 0.9f)
                rh = rw / aspect
            } else {
                rh = base.coerceAtMost(h.toFloat() * 0.9f)
                rw = rh * aspect
            }
            val cx = w / 2f
            val cy = h / 2f
            region.set(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 半透明遮罩:四块外部区域
        // 上
        canvas.drawRect(0f, 0f, w, region.top, maskPaint)
        // 下
        canvas.drawRect(0f, region.bottom, w, h, maskPaint)
        // 左
        canvas.drawRect(0f, region.top, region.left, region.bottom, maskPaint)
        // 右
        canvas.drawRect(region.right, region.top, w, region.bottom, maskPaint)

        // 边框
        canvas.drawRect(region, borderPaint)
        // 网格(三分线,辅助构图)
        val g1 = Paint(borderPaint).apply { strokeWidth = dp(0.8f); color = Color.argb(120, 255, 64, 129) }
        val x1 = region.left + region.width() / 3f
        val x2 = region.left + region.width() * 2f / 3f
        val y1 = region.top + region.height() / 3f
        val y2 = region.top + region.height() * 2f / 3f
        canvas.drawLine(x1, region.top, x1, region.bottom, g1)
        canvas.drawLine(x2, region.top, x2, region.bottom, g1)
        canvas.drawLine(region.left, y1, region.right, y1, g1)
        canvas.drawLine(region.left, y2, region.right, y2, g1)

        // 四角手柄
        for (corner in cornerList()) {
            canvas.drawCircle(corner.first, corner.second, handleRadiusPx, handlePaint)
            canvas.drawCircle(corner.first, corner.second, handleRadiusPx, handleStrokePaint)
        }

        // 中心提示文字
        val info = "${region.width().toInt()}×${region.height().toInt()}  比${"%.2f".format(region.width() / region.height())}"
        canvas.drawText(info, region.centerX() - textPaint.measureText(info) / 2f, region.centerY(), textPaint)
    }

    private fun cornerList(): List<Pair<Float, Float>> = listOf(
        region.left to region.top,
        region.right to region.top,
        region.left to region.bottom,
        region.right to region.bottom
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                mode = hitTest(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                when (mode) {
                    DragMode.MOVE -> {
                        region.offset(dx, dy)
                        clampRegion()
                        invalidate()
                    }
                    DragMode.RESIZE_TL -> resize(region.left + dx, region.top + dy, isTL = true)
                    DragMode.RESIZE_TR -> resize(region.right + dx, region.top + dy, isTL = false, isTR = true)
                    DragMode.RESIZE_BL -> resize(region.left + dx, region.bottom + dy, isTL = false, isBL = true)
                    DragMode.RESIZE_BR -> resize(region.right + dx, region.bottom + dy, isTL = false, isBR = true)
                    else -> {}
                }
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                mode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** 命中测试:优先四角,否则中心区域=移动。 */
    private fun hitTest(x: Float, y: Float): DragMode {
        for ((cx, cy) in cornerList()) {
            if (abs(x - cx) <= handleRadiusPx * 1.6f && abs(y - cy) <= handleRadiusPx * 1.6f) {
                return when {
                    cx == region.left && cy == region.top -> DragMode.RESIZE_TL
                    cx == region.right && cy == region.top -> DragMode.RESIZE_TR
                    cx == region.left && cy == region.bottom -> DragMode.RESIZE_BL
                    else -> DragMode.RESIZE_BR
                }
            }
        }
        return if (region.contains(x, y)) DragMode.MOVE else DragMode.NONE
    }

    /**
     * 调整大小:以拖动的角为锚点,按 [aspect] 锁定比例,对角固定。
     * 用对角点距离作为新尺寸基准。
     */
    private fun resize(newX: Float, newY: Float, isTL: Boolean = false,
                       isTR: Boolean = false, isBL: Boolean = false, isBR: Boolean = false) {
        // 锚点(对角固定点)
        val anchorX: Float
        val anchorY: Float
        when {
            isTL -> { anchorX = region.right; anchorY = region.bottom }
            isTR -> { anchorX = region.left;  anchorY = region.bottom }
            isBL -> { anchorX = region.right; anchorY = region.top }
            else -> { anchorX = region.left;  anchorY = region.top }
        }
        val dx = abs(newX - anchorX)
        val dy = abs(newY - anchorY)
        // 取 max 后按比例约束(保证不变形):以较大的拖动量为主
        var w = dx
        var h = dy
        if (w / h > aspect) {
            h = w / aspect
        } else {
            w = h * aspect
        }
        // 最小尺寸
        w = max(w, dp(80f))
        h = max(h, dp(80f) / aspect)

        // 根据锚点重建矩形
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (anchorX == region.left) {
            // 锚在左,右拖动
            left = anchorX; right = anchorX + w
        } else {
            right = anchorX; left = anchorX - w
        }
        if (anchorY == region.top) {
            top = anchorY; bottom = anchorY + h
        } else {
            bottom = anchorY; top = anchorY - h
        }
        region.set(left, top, right, bottom)
        clampRegion()
        invalidate()
    }

    /** 限制不超出屏幕。 */
    private fun clampRegion() {
        val w = width.toFloat()
        val h = height.toFloat()
        val rw = region.width()
        val rh = region.height()
        if (rw > w) {
            region.left = 0f; region.right = w
        } else {
            if (region.left < 0f) { region.right -= region.left; region.left = 0f }
            if (region.right > w) { region.left -= (region.right - w); region.right = w }
        }
        if (rh > h) {
            region.top = 0f; region.bottom = h
        } else {
            if (region.top < 0f) { region.bottom -= region.top; region.top = 0f }
            if (region.bottom > h) { region.top -= (region.bottom - h); region.bottom = h }
        }
    }

    /** 返回当前 region 的屏幕坐标拷贝。 */
    fun currentRect(): RectF = RectF(region)

    fun setRegion(rect: RectF) {
        region.set(rect)
        clampRegion()
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
