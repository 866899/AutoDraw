package com.autodraw.app.image

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 用 ML Kit 识别图片中的"活物":人脸(精确五官轮廓)+ 动物/人物标签。
 *
 * - [detect]:同时跑人脸检测与图像标注,合成 [SubjectRegions]。任一失败不影响另一个。
 *
 * 人脸由 ML Kit 直接给出精确矩形 + 五官轮廓(眼/眉/鼻/嘴/脸型)。
 * 动物/人物由图像标注给出标签(无位置),无人脸时用一个居中占位框覆盖主体。
 *
 * bundled 模型首次使用会异步下载(约几 MB),下载完成前返回 EMPTY。
 * 调用方应在 IO 线程调用。
 */
object SubjectDetector {

    /** 视为"活物"的标签关键词(小写匹配)。 */
    private val LIVING_KEYWORDS = listOf(
        "person", "man", "woman", "boy", "girl", "child", "people", "face",
        "cat", "dog", "bird", "fish", "horse", "rabbit", "animal",
        "hamster", "turtle", "lizard", "pet", "puppy", "kitten"
    )

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.10f)
                .build()
        )
    }

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.4f)
                .build()
        )
    }

    private data class FaceInfo(
        val box: SubjectBox,
        val landmarks: FaceLandmarks
    )

    /** 主入口:同时检测人脸与活物标签,合成 [SubjectRegions]。 */
    suspend fun detect(bitmap: Bitmap): SubjectRegions {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return SubjectRegions.EMPTY
        val image = InputImage.fromBitmap(bitmap, 0)

        val faces = runCatching { detectFaces(image, w, h) }.getOrDefault(emptyList())
        val labels = runCatching { detectLabels(image) }.getOrDefault(emptyList())

        // 动物/人物标签 → 占位框(仅当没有人脸时使用,避免与人脸框重复)
        val labelBoxes = if (faces.isEmpty()) labelsToBoxes(labels) else emptyList()

        val allBoxes = dedupe(faces.map { it.box } + labelBoxes)
        val landmarks = faces.map { it.landmarks }
        return SubjectRegions(allBoxes, landmarks, w, h)
    }

    private suspend fun detectFaces(image: InputImage, w: Int, h: Int): List<FaceInfo> =
        suspendCancellableCoroutine { cont ->
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    val results = faces.map { mapFace(it, w, h) }
                    if (cont.isActive) cont.resume(results)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    private fun mapFace(face: Face, w: Int, h: Int): FaceInfo {
        val b = face.getBoundingBox()
        val box = SubjectBox(
            left = b.left.toFloat() / w,
            top = b.top.toFloat() / h,
            right = b.right.toFloat() / w,
            bottom = b.bottom.toFloat() / h,
            label = "Face",
            confidence = 1f
        )
        val lm = FaceLandmarks(
            leftEye = contour(face, FaceContour.LEFT_EYE, w, h),
            rightEye = contour(face, FaceContour.RIGHT_EYE, w, h),
            leftBrow = contour(face, FaceContour.LEFT_EYEBROW_TOP, w, h),
            rightBrow = contour(face, FaceContour.RIGHT_EYEBROW_TOP, w, h),
            leftBrowBottom = contour(face, FaceContour.LEFT_EYEBROW_BOTTOM, w, h),
            rightBrowBottom = contour(face, FaceContour.RIGHT_EYEBROW_BOTTOM, w, h),
            noseBridge = contour(face, FaceContour.NOSE_BRIDGE, w, h),
            upperLip = contour(face, FaceContour.UPPER_LIP_TOP, w, h),
            lowerLip = contour(face, FaceContour.LOWER_LIP_BOTTOM, w, h),
            faceOval = contour(face, FaceContour.FACE, w, h)
        )
        return FaceInfo(box, lm)
    }

    private fun contour(face: Face, type: Int, w: Int, h: Int): List<PointF> {
        val c = face.getContour(type) ?: return emptyList()
        return c.getPoints().map { PointF(it.x / w, it.y / h) }
    }

    private suspend fun detectLabels(image: InputImage): List<ImageLabel> =
        suspendCancellableCoroutine { cont ->
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val living = labels.filter { lbl ->
                        val key = lbl.getText().lowercase()
                        LIVING_KEYWORDS.any { key.contains(it) || it.contains(key) }
                    }
                    if (cont.isActive) cont.resume(living)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    /**
     * 图像标注只给标签不给位置。这里取置信度最高的活物标签,生成一个居中占位框
     * (多数主体居中),供 [SketchExtractor] 做区域加权。用户可在 UI 预览中观察。
     */
    private fun labelsToBoxes(labels: List<ImageLabel>): List<SubjectBox> {
        if (labels.isEmpty()) return emptyList()
        val best = labels.maxByOrNull { it.getConfidence() } ?: return emptyList()
        return listOf(
            SubjectBox(
                left = 0.15f, top = 0.15f, right = 0.85f, bottom = 0.85f,
                label = best.getText().replaceFirstChar { c -> c.uppercase() },
                confidence = best.getConfidence()
            )
        )
    }

    private fun dedupe(boxes: List<SubjectBox>): List<SubjectBox> {
        val out = ArrayList<SubjectBox>()
        for (b in boxes) {
            if (out.none { iou(b, it) > 0.5f }) out.add(b)
        }
        return out
    }

    private fun iou(a: SubjectBox, b: SubjectBox): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bb = minOf(a.bottom, b.bottom)
        if (r <= l || bb <= t) return 0f
        val inter = (r - l) * (bb - t)
        val ua = a.width * a.height + b.width * b.height - inter
        return if (ua <= 0f) 0f else inter / ua
    }
}
