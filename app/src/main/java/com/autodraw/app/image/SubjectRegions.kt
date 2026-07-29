package com.autodraw.app.image

import android.graphics.PointF

/**
 * 识别到的"活物"区域集合(归一化坐标 [0,1],相对图片)。
 *
 * 由 [SubjectDetector] 通过 ML Kit 检测得到,供 [SketchExtractor] 做区域感知:
 *  - 在 [boxes] 覆盖的矩形内加密等值线、减小简化容差(加强细节)
 *  - 在 [boxes] 内使用更粗笔画/更高对比(突出强调)
 *  - 把人脸 [faceLandmarks] 转成结构引导笔画(眼/眉/鼻/嘴轮廓)
 *
 * [imageWidth]/[imageHeight] 为被检测位图的实际像素尺寸,用于换算归一化坐标。
 */
data class SubjectRegions(
    val boxes: List<SubjectBox>,
    val faceLandmarks: List<FaceLandmarks>,
    val imageWidth: Int,
    val imageHeight: Int
) {
    /** 是否识别到任何活物。 */
    val hasSubjects: Boolean get() = boxes.isNotEmpty() || faceLandmarks.isNotEmpty()

    companion object {
        val EMPTY = SubjectRegions(emptyList(), emptyList(), 0, 0)
    }
}

/** 一个活物矩形框(归一化坐标)。 */
data class SubjectBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,         // "Face" / "Person" / "Cat" / "Dog" ...
    val confidence: Float
) {
    fun contains(nx: Float, ny: Float): Boolean =
        nx in left..right && ny in top..bottom
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    /** 换算到指定尺寸的像素矩形(用于在位图坐标系下做区域过滤)。 */
    fun toPixels(w: Int, h: Int): IntRect = IntRect(
        (left * w).toInt().coerceIn(0, w),
        (top * h).toInt().coerceIn(0, h),
        (right * w).toInt().coerceIn(0, w),
        (bottom * h).toInt().coerceIn(0, h)
    )
}

/** 整数像素矩形,用于区域过滤。 */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(px: Float, py: Float): Boolean =
        px >= left && px < right && py >= top && py < bottom
}

/**
 * 一张人脸的关键点(归一化坐标)。null 表示该部位未检测到。
 * 用于生成五官结构引导笔画(避免眼/嘴等被等值线打碎成噪声)。
 */
data class FaceLandmarks(
    val leftEye: List<PointF> = emptyList(),
    val rightEye: List<PointF> = emptyList(),
    val leftBrow: List<PointF> = emptyList(),
    val rightBrow: List<PointF> = emptyList(),
    val noseBridge: List<PointF> = emptyList(),
    val upperLip: List<PointF> = emptyList(),
    val lowerLip: List<PointF> = emptyList(),
    val faceOval: List<PointF> = emptyList()
)
