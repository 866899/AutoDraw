package com.autodraw.app.service

import android.graphics.PointF
import com.autodraw.app.drawing.Stroke

/**
 * 预设绘画路径(无需图片即可演示屏幕绘画)。
 *
 * 当前提供:一个居中、归一化坐标 [0,1] 的心形闭合曲线。
 * 也可作为"读取预设绘画路径"功能的示例入口。
 */
object PresetPaths {

    /** 生成一个心形(归一化坐标,中心约 (0.5,0.5))。 */
    fun heart(steps: Int = 120, style: Stroke.Style = Stroke.Style.INK): List<Stroke> {
        val pts = ArrayList<PointF>(steps)
        for (i in 0..steps) {
            val t = (i / steps.toDouble()) * 2 * Math.PI
            // 经典心形参数方程
            val x = 16.0 * Math.sin(t).pow3()
            val y = 13.0 * Math.cos(t) -
                    5.0 * Math.cos(2 * t) -
                    2.0 * Math.cos(3 * t) -
                    Math.cos(4 * t)
            // 归一化到 [0.15, 0.85] 并翻转 Y(屏幕坐标向下)
            val nx = ((x / 17.0) * 0.35f + 0.5f).toFloat()
            val ny = (1f - ((y / 17.0) * 0.35f + 0.5f)).toFloat()
            pts.add(PointF(nx, ny))
        }
        return listOf(Stroke(pts, width = 2.0f, style = style))
    }

    private fun Double.pow3(): Double = this * this * this
}
