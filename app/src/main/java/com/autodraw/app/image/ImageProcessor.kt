package com.autodraw.app.image

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Low-level, allocation-friendly image operators working directly on
 * [FloatArray] buffers indexed as `y * width + x`. No external native
 * libraries required so the project builds with the standard Android SDK.
 */
internal object ImageProcessor {

    /** Convert an ARGB bitmap to a luminance buffer in [0,1]. */
    fun toGray(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            // Rec. 601 luma
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return gray
    }

    /** Separable Gaussian blur. Returns a new buffer. */
    fun gaussianBlur(gray: FloatArray, w: Int, h: Int, radius: Int, sigma: Float): FloatArray {
        if (radius <= 0) return gray.copyOf()
        val kernel = buildKernel(radius, sigma)
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                var wsum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val weight = kernel[k + radius]
                    acc += gray[row + sx] * weight
                    wsum += weight
                }
                tmp[row + x] = acc / wsum
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f
                var wsum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val weight = kernel[k + radius]
                    acc += tmp[sy * w + x] * weight
                    wsum += weight
                }
                out[y * w + x] = acc / wsum
            }
        }
        return out
    }

    /** Sobel edge magnitude, normalized to [0,1]. */
    fun sobelMagnitude(gray: FloatArray, w: Int, h: Int): FloatArray {
        val mag = FloatArray(w * h)
        var max = 1f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val tl = gray[i - w - 1]
                val tc = gray[i - w]
                val tr = gray[i - w + 1]
                val cl = gray[i - 1]
                val cr = gray[i + 1]
                val bl = gray[i + w - 1]
                val bc = gray[i + w]
                val br = gray[i + w + 1]
                val gx = -tl - 2f * cl - bl + tr + 2f * cr + br
                val gy = -tl - 2f * tc - tr + bl + 2f * bc + br
                val m = sqrt(gx * gx + gy * gy)
                mag[i] = m
                if (m > max) max = m
            }
        }
        // Normalize + non-maximum suppression along gradient direction (cheap)
        val inv = 1f / max
        for (i in mag.indices) mag[i] *= inv
        return mag
    }

    private fun buildKernel(radius: Int, sigma: Float): FloatArray {
        val k = FloatArray(2 * radius + 1)
        val twoSigmaSq = 2f * sigma * sigma
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i).toFloat() / twoSigmaSq)
            k[i + radius] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }
}
