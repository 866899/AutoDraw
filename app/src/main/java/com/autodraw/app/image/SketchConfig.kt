package com.autodraw.app.image

/**
 * Configuration that controls how a photo is converted into a sketch.
 *
 * @param longSide        process the bitmap downscaled so its longest side equals
 *                        this many pixels (performance vs. detail trade-off).
 * @param blurRadius       Gaussian blur radius applied before edge detection.
 *                        Larger value → smoother edges, fewer noise fragments.
 * @param thresholds       Normalized Sobel magnitude levels at which iso-contours
 *                        are traced. Multiple levels produce a layered, hatch-like
 *                        sketch. Values in (0, 1).
 * @param shadingLevels   Normalized luminance levels at which shadow hatch
 *                        contours are traced. Empty disables shading.
 * @param simplifyTol     Douglas–Peucker tolerance (normalized) for thinning
 *                        the extracted polylines into smoother strokes.
 * @param minStrokePts    Discard polylines shorter than this many points.
 * @param minStrokeLength Discard strokes whose total length (normalized) is
 *                        below this threshold — removes speckle/tiny fragments
 *                        that pass [minStrokePts] but are visually insignificant.
 *                        0 disables.
 * @param mergeGap        Max normalized endpoint distance at which two strokes
 *                        of the same style are merged into one continuous stroke
 *                        (repairs contour breaks, reduces pen lifts). 0 disables.
 * @param subjectBoost    活物区域增强开关。开启后对识别到的活物(人脸/动物)区域:
 *                        ①加密等值线层数(subjectExtraLevels 层);
 *                        ②减小简化容差(× subjectSimplifyScale);
 *                        ③更粗笔画(× subjectWidthScale);
 *                        ④追加五官结构引导笔画(眼/眉/鼻/嘴轮廓)。
 * @param subjectExtraLevels 活物区域在 [thresholds] 基础上额外叠加的等值线层数。
 * @param subjectSimplifyScale 活物区域内 RDP 容差缩放系数(<1 保留更多细节)。
 * @param subjectWidthScale   活物区域内笔画宽度缩放系数(>1 突出强调)。
 * @param subjectLabel   活物区域内阴影笔画是否启用(突出主体时通常关闭背景阴影,
 *                        仅在活物内保留)。true:仅活物内保留阴影。
 * @param hairBoost      头部增强开关。开启后,对由人脸框上方推导出的"头发区域"
 *                       (额头上方一定范围内)同样应用加密等值线 + 更小简化容差,
 *                       让发丝/发际线轮廓更清晰。ML Kit 不提供头发轮廓,此为
 *                       基于人脸框的启发式区域加权。
 * @param hairTopRatio   头发区域向上扩展占人脸高度的比例(0.6 表示再向上扩 60%)。
 * @param hairSideRatio  头发区域左右扩展占人脸宽度的比例(0.1 表示两侧各扩 10%)。
 */
data class SketchConfig(
    val longSide: Int = 384,
    val blurRadius: Int = 3,
    val thresholds: List<Float> = listOf(0.12f, 0.22f, 0.38f),
    val shadingLevels: List<Float> = listOf(0.32f, 0.55f),
    val simplifyTol: Float = 0.010f,
    val minStrokePts: Int = 3,
    val minStrokeLength: Float = 0.012f,
    val mergeGap: Float = 0.012f,
    val subjectBoost: Boolean = false,
    val subjectExtraLevels: Int = 2,
    val subjectSimplifyScale: Float = 0.5f,
    val subjectWidthScale: Float = 1.3f,
    val subjectOnlyShade: Boolean = true,
    val hairBoost: Boolean = true,
    val hairTopRatio: Float = 0.6f,
    val hairSideRatio: Float = 0.1f
)
