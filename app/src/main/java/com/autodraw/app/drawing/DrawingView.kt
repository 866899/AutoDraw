package com.autodraw.app.drawing

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import android.view.View

/**
 * Renders the [DrawingEngine] progress as an animated, stroke-by-stroke
 * pen drawing. Owns its own Choreographer loop so it stays smooth and
 * independent of the UI thread's input handling.
 *
 * The actual pixel drawing lives in [SceneRenderer] so it can be reused by
 * the image and video exporters. This view additionally mirrors each frame
 * into an optional [Surface] (used while recording a video).
 */
class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val engine = DrawingEngine()
    private val renderer = SceneRenderer()

    @Volatile private var mirrorSurface: Surface? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 0f
                     else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = frameTimeNanos
            engine.advance(dt.coerceAtMost(0.05f))
            invalidate()
            if (engine.isPlaying || mirrorSurface != null) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                lastFrameNanos = 0L
            }
        }
    }
    private var lastFrameNanos = 0L

    fun startAnimation() { engine.start(); kickLoop() }
    fun pauseAnimation() = engine.pause()
    fun resumeAnimation() { engine.resume(); kickLoop() }
    fun resetAnimation() { engine.reset(); kickLoop(); invalidate() }
    fun setSpeed(pointsPerSec: Float) { engine.speed = pointsPerSec }
    fun setBrushScale(scale: Float) { renderer.brushScale = scale; invalidate() }

    fun beginMirroring(surface: Surface) {
        mirrorSurface = surface
        kickLoop()
    }

    fun stopMirroring() { mirrorSurface = null }

    /** Render the current engine state to an opaque bitmap (image export). */
    fun renderToBitmap(): android.graphics.Bitmap {
        val w = if (width > 0) width else 1080
        val h = if (height > 0) height else 1080
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        renderer.render(engine, canvas, w, h)
        return bmp
    }

    private fun kickLoop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.render(engine, canvas, width, height)
        mirrorSurface?.let { surf ->
            try {
                val c = surf.lockHardwareCanvas()
                renderer.render(engine, c, width, height)
                surf.unlockCanvasAndPost(c)
            } catch (_: Exception) {
                // surface may have been released between frames
            }
        }
    }
}
