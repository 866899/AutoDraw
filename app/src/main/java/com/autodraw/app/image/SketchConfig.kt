package com.autodraw.app.image

/**
 * Configuration that controls how a photo is converted into a sketch.
 *
 * @param longSide      process the bitmap downscaled so its longest side equals
 *                      this many pixels (performance vs. detail trade-off).
 * @param blurRadius    Gaussian blur radius applied before edge detection.
 * @param thresholds    Normalized Sobel magnitude levels at which iso-contours
 *                      are traced. Multiple levels produce a layered, hatch-like
 *                      sketch. Values in (0, 1).
 * @param shadingLevels Normalized luminance levels at which shadow hatch
 *                      contours are traced. Empty disables shading.
 * @param simplifyTol   Douglas–Peucker tolerance (normalized) for thinning
 *                      the extracted polylines into smoother strokes.
 * @param minStrokePts  Discard polylines shorter than this many points.
 */
data class SketchConfig(
    val longSide: Int = 384,
    val blurRadius: Int = 2,
    val thresholds: List<Float> = listOf(0.12f, 0.22f, 0.38f),
    val shadingLevels: List<Float> = listOf(0.32f, 0.55f),
    val simplifyTol: Float = 0.006f,
    val minStrokePts: Int = 3
)
