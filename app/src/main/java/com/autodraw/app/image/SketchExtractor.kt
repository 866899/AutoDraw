package com.autodraw.app.image

import android.graphics.Bitmap
import android.graphics.PointF
import com.autodraw.app.drawing.Stroke
import kotlin.math.max

/**
 * Converts a source photo into an ordered list of drawable [Stroke]s.
 *
 * Two families of strokes are produced:
 *  - **Outline strokes** traced from the Sobel edge-magnitude field at a few
 *    iso-levels (the higher the level, the stronger the edge → thicker stroke).
 *  - **Shading strokes** (optional) traced from the luminance field to suggest
 *    shadow regions, rendered thinner and lighter for a hatched look.
 *
 * All stroke points are normalized to [0,1]; the view re-applies aspect ratio.
 */
class SketchExtractor(private val config: SketchConfig = SketchConfig()) {

    data class Result(
        val strokes: List<Stroke>,
        val width: Int,
        val height: Int,
        /** 识别到的活物区域(可能为空)。供 UI 叠加显示。 */
        val subjects: SubjectRegions = SubjectRegions.EMPTY
    )

    /**
     * 活物增强模式:先由 [SubjectDetector] 识别活物,再在活物区域内加强细节、
     * 突出强调、追加五官结构引导笔画。传 [regions]=null 走普通模式。
     *
     * 设为 suspend 以便未来扩展异步子步骤;当前实现本身不挂起,但调用方应在
     * 协程中调用(识别由 [SubjectDetector.detect] 完成,在本函数之前)。
     */
    suspend fun extract(source: Bitmap, regions: SubjectRegions?): Result {
        val scaled = downscale(source, config.longSide)
        val w = scaled.width
        val h = scaled.height

        val gray = ImageProcessor.toGray(scaled)
        val blurred = ImageProcessor.gaussianBlur(gray, w, h, config.blurRadius, 1.4f)
        val mag = ImageProcessor.sobelMagnitude(blurred, w, h)

        // Optional dark shading contours (follow shadows). Traced on a
        // blurred luminance field so the hatch lines stay smooth.
        val shadingGray = ImageProcessor.gaussianBlur(gray, w, h, config.blurRadius, 2.0f)

        // 把检测到的归一化区域换算到当前 scaled 位图像素坐标
        val baseBoxes = regions?.boxes?.map { it.toPixels(w, h) } ?: emptyList()
        val useSubjects = config.subjectBoost && baseBoxes.isNotEmpty()
        // 在活物框基础上,叠加由人脸框推导出的"头发区域"(人脸上方扩展带),
        // 让发际线/发丝也获得加密细节。ML Kit 不提供头发轮廓,此为启发式区域。
        // 头发区域基于 faceOval 的人脸轮廓归一化坐标推导,不依赖 boxes 顺序。
        val pxBoxes = if (useSubjects && config.hairBoost && regions != null) {
            val hairBoxes = regions.faceLandmarks.mapNotNull { lm ->
                hairRegionFromLandmarks(lm, w, h)
            }
            (baseBoxes + hairBoxes).distinct()
        } else baseBoxes

        val inkStrokes = ArrayList<Stroke>()
        val pencilStrokes = ArrayList<Stroke>()

        // 1) Outlines from edge magnitude, one pass per threshold level.
        config.thresholds.forEachIndexed { level, t ->
            val widthPx = strokeWidthForLevel(level, config.thresholds.size)
            val contours = ContourExtractor.extract(mag, w, h, t)
            for (poly in contours) {
                val simplified = StrokeSimplifier.simplify(poly, config.simplifyTol)
                if (simplified.size < config.minStrokePts) continue
                val s = toStroke(simplified, w, h, widthPx, Stroke.Style.INK)
                if (keepStroke(s)) inkStrokes += s
            }
        }

        // 1b) 活物区域加密:在每个 box 内叠加额外的等值线层(更细的梯度分档)
        if (useSubjects) {
            val baseMin = config.thresholds.minOrNull() ?: 0.2f
            val baseMax = config.thresholds.maxOrNull() ?: 0.4f
            val extra = (1 until config.subjectExtraLevels + 1).map { i ->
                baseMin + (baseMax - baseMin) * (i.toFloat() / (config.subjectExtraLevels + 1))
            }
            for (t in extra) {
                val contours = ContourExtractor.extract(mag, w, h, t)
                for (poly in contours) {
                    // 仅保留质心落在任一活物框内的折线(区域过滤)
                    if (!centroidInAnyBox(poly, pxBoxes)) continue
                    // 区域内更小的简化容差,保留更多细节
                    val tol = config.simplifyTol * config.subjectSimplifyScale
                    val simplified = StrokeSimplifier.simplify(poly, tol)
                    if (simplified.size < config.minStrokePts) continue
                    // 区域内更粗笔画,突出强调
                    val widthPx = (strokeWidthForLevel(0, config.thresholds.size) *
                            config.subjectWidthScale)
                    val s = toStroke(simplified, w, h, widthPx, Stroke.Style.INK)
                    if (keepStroke(s)) inkStrokes += s
                }
            }
        }

        // 2) Shading from luminance (darker side of each level → hatch lines).
        for (t in config.shadingLevels) {
            val contours = ContourExtractor.extract(shadingGray, w, h, t)
            for (poly in contours) {
                val simplified = StrokeSimplifier.simplify(poly, config.simplifyTol * 1.5f)
                if (simplified.size < config.minStrokePts) continue
                val s = toStroke(simplified, w, h, 0.8f, Stroke.Style.PENCIL)
                if (keepStroke(s)) pencilStrokes += s
            }
        }

        // 2b) 突出主体:若开启 subjectOnlyShade,丢弃活物框外的阴影笔画
        val pencilsFinal = if (useSubjects && config.subjectOnlyShade) {
            pencilStrokes.filter { st ->
                val c = centroid(st.points)
                pxBoxes.any { it.contains(c.first, c.second) }
            }
        } else pencilStrokes

        // 3) 五官结构引导笔画(仅人脸):把 ML Kit 轮廓点直接转成 SKETCH 笔画,
        //    保证眼/眉/鼻/嘴的形状规整,不被等值线打碎
        val guideStrokes = if (useSubjects) {
            buildFaceGuideStrokes(regions!!, w, h)
        } else emptyList()

        // 4) Merge endpoint-adjacent strokes (same style only) to cut pen lifts,
        //    then order for natural hand travel and drop degenerate strokes.
        val merged = StrokeMerger.merge(inkStrokes + guideStrokes, config.mergeGap) +
                     StrokeMerger.merge(pencilsFinal, config.mergeGap)
        val ordered = StrokeSorter.sort(merged.filter { it.points.size >= 2 })
        return Result(ordered, w, h, regions ?: SubjectRegions.EMPTY)
    }

    /** 由人脸关键点轮廓生成结构引导笔画。 */
    private fun buildFaceGuideStrokes(
        regions: SubjectRegions, w: Int, h: Int
    ): List<Stroke> {
        val out = ArrayList<Stroke>()
        // 引导笔画用稍粗的笔触,SKETCH 风格以与 INK 视觉上区分
        val guideWidth = strokeWidthForLevel(0, config.thresholds.size) * 1.15f
        for (lm in regions.faceLandmarks) {
            // 单根轮廓:眼/鼻/嘴/脸型
            listOf(
                lm.leftEye, lm.rightEye,
                lm.noseBridge, lm.upperLip, lm.lowerLip, lm.faceOval
            ).forEach { pts ->
                out += guideStroke(pts, w, h, guideWidth)
            }
            // 眉毛:上缘 + 下缘配对,形成有厚度的眉(而非单线)
            out += pairedBrowStroke(lm.leftBrow, lm.leftBrowBottom, w, h, guideWidth)
            out += pairedBrowStroke(lm.rightBrow, lm.rightBrowBottom, w, h, guideWidth)
        }
        return out
    }

    /** 单条轮廓 → 简化后转 SKETCH 笔画。 */
    private fun guideStroke(
        pts: List<PointF>, w: Int, h: Int, widthPx: Float
    ): List<Stroke> {
        if (pts.size < 2) return emptyList()
        val scaled = pts.map { PointF(it.x * w, it.y * h) }
        val simplified = StrokeSimplifier.simplify(scaled, config.simplifyTol)
        return if (simplified.size >= 2)
            listOf(toStroke(simplified, w, h, widthPx, Stroke.Style.SKETCH))
        else emptyList()
    }

    /**
     * 眉毛上下缘配对:沿上缘画一笔,再从上缘末端连到下缘末端并反向画下缘,
     * 形成闭合的"眉形"带,描绘出眉毛的真实厚度。
     * 若下缘缺失,则退化为只画上缘。
     */
    private fun pairedBrowStroke(
        top: List<PointF>, bottom: List<PointF>,
        w: Int, h: Int, widthPx: Float
    ): List<Stroke> {
        if (top.size < 2) return emptyList()
        val tol = config.simplifyTol
        val topPx = top.map { PointF(it.x * w, it.y * h) }
        val topS = StrokeSimplifier.simplify(topPx, tol)
        if (topS.size < 2) return emptyList()
        if (bottom.size < 2) {
            return listOf(toStroke(topS, w, h, widthPx, Stroke.Style.SKETCH))
        }
        val bottomPx = bottom.map { PointF(it.x * w, it.y * h) }
        val bottomS = StrokeSimplifier.simplify(bottomPx, tol)
        if (bottomS.size < 2) {
            return listOf(toStroke(topS, w, h, widthPx, Stroke.Style.SKETCH))
        }
        // 闭合带:上缘正向 + 下缘反向 + 回到起点
        val band = ArrayList<PointF>(topS.size + bottomS.size + 1)
        band.addAll(topS)
        band.addAll(bottomS.reversed())
        band.add(PointF(topS[0].x, topS[0].y)) // 闭合
        val bandS = StrokeSimplifier.simplify(band, tol)
        return if (bandS.size >= 2)
            listOf(toStroke(bandS, w, h, widthPx, Stroke.Style.SKETCH))
        else listOf(toStroke(topS, w, h, widthPx, Stroke.Style.SKETCH))
    }

    /**
     * 由人脸轮廓 [FaceLandmarks.faceOval] 推导头发区域(归一化→像素):
     * 取人脸最上方点,向上扩展 [SketchConfig.hairTopRatio] 个人脸高度,
     * 左右各扩 [SketchConfig.hairSideRatio] 个人脸宽度。夹到画布内。
     * 返回 null 表示无法推导(无 faceOval)。
     */
    private fun hairRegionFromLandmarks(lm: FaceLandmarks, w: Int, h: Int): IntRect? {
        val oval = lm.faceOval
        if (oval.size < 3) return null
        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (p in oval) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val faceW = (maxX - minX).coerceAtLeast(0.01f)
        val faceH = (maxY - minY).coerceAtLeast(0.01f)
        // 头发区:从人脸顶部向上扩 faceH * hairTopRatio,左右扩 faceW * hairSideRatio
        val left = (minX - faceW * config.hairSideRatio).coerceIn(0f, 1f)
        val right = (maxX + faceW * config.hairSideRatio).coerceIn(0f, 1f)
        val top = (minY - faceH * config.hairTopRatio).coerceIn(0f, 1f)
        val bottom = maxY // 头发区底部 = 人脸底部(覆盖整个头部,含侧鬓)
        return IntRect(
            (left * w).toInt().coerceIn(0, w),
            (top * h).toInt().coerceIn(0, h),
            (right * w).toInt().coerceIn(0, w),
            (bottom * h).toInt().coerceIn(0, h)
        )
    }

    /** 折线质心是否落在任一活物像素框内。 */
    private fun centroidInAnyBox(poly: List<PointF>, boxes: List<IntRect>): Boolean {
        if (poly.isEmpty()) return false
        var sx = 0f; var sy = 0f
        for (p in poly) { sx += p.x; sy += p.y }
        val cx = sx / poly.size
        val cy = sy / poly.size
        return boxes.any { it.contains(cx, cy) }
    }

    private fun centroid(poly: List<PointF>): Pair<Float, Float> {
        if (poly.isEmpty()) return 0f to 0f
        var sx = 0f; var sy = 0f
        for (p in poly) { sx += p.x; sy += p.y }
        return (sx / poly.size) to (sy / poly.size)
    }

    /** 长度过滤:丢弃过短的碎片笔画(去噪)。 */
    private fun keepStroke(s: Stroke): Boolean {
        if (config.minStrokeLength <= 0f) return true
        return s.length >= config.minStrokeLength
    }

    private fun strokeWidthForLevel(level: Int, total: Int): Float {
        // Higher level (stronger edge, fewer/inner contours) → thicker line.
        val f = if (total <= 1) 1f else level.toFloat() / (total - 1)
        return 1.2f + f * 1.8f // 1.2 .. 3.0
    }

    private fun toStroke(
        poly: List<PointF>, w: Int, h: Int,
        widthPx: Float, style: Stroke.Style
    ): Stroke {
        val nx = 1f / max(w - 1, 1)
        val ny = 1f / max(h - 1, 1)
        val pts = ArrayList<PointF>(poly.size)
        for (p in poly) pts.add(PointF(p.x * nx, p.y * ny))
        return Stroke(points = pts, width = widthPx, style = style)
    }

    private fun downscale(src: Bitmap, longSide: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val longest = max(srcW, srcH)
        if (longest <= longSide) return src
        val scale = longSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (srcW * scale).toInt().coerceAtLeast(1),
            (srcH * scale).toInt().coerceAtLeast(1), true)
    }
}
