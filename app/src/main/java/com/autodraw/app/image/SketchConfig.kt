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
 */
data class SketchConfig(
    val longSide: Int = 384,
    val blurRadius: Int = 3,
    val thresholds: List<Float> = listOf(0.12f, 0.22f, 0.38f),
    val shadingLevels: List<Float> = listOf(0.32f, 0.55f),
    val simplifyTol: Float = 0.010f,
    val minStrokePts: Int = 3,
    val minStrokeLength: Float = 0.012f,
    val mergeGap: Float = 0.012f
)
