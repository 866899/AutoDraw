package com.autodraw.app.drawing

/**
 * Drives the progressive drawing animation.
 *
 * Progress is measured in "points" (one per stroke vertex). The view asks the
 * engine, for a given progress, which strokes are fully drawn and which one is
 * currently being drawn (and at what fraction), then renders accordingly.
 *
 * Time advancement is decoupled from rendering: [advance] is called with the
 * elapsed seconds since the previous frame and simply bumps the progress by
 * `speed * dt`. The host (a Choreographer loop) decides when to call it.
 */
class DrawingEngine {

    /** One stroke + its cumulative point range. */
    private class Run(val stroke: Stroke, val start: Float, val end: Float) {
        val length: Float get() = end - start
    }

    private var runs: List<Run> = emptyList()
    var strokeCount: Int = 0
        private set
    var totalPoints: Float = 0f
        private set
    var imgWidth: Int = 1
        private set
    var imgHeight: Int = 1
        private set
    val aspect: Float get() = imgWidth.toFloat() / imgHeight

    /** Current drawn amount in points. */
    var progress: Float = 0f
        private set

    /** Points drawn per second. */
    var speed: Float = 700f

    @Volatile
    var isPlaying: Boolean = false
        private set

    var isComplete: Boolean = false
        private set

    var listener: Listener? = null

    interface Listener {
        fun onProgress(fraction: Float) {}
        fun onComplete() {}
    }

    fun setStrokes(strokes: List<Stroke>, imgWidth: Int, imgHeight: Int) {
        this.imgWidth = imgWidth
        this.imgHeight = imgHeight
        strokeCount = strokes.size
        val list = ArrayList<Run>(strokes.size)
        var acc = 0f
        for (s in strokes) {
            val len = s.points.size.toFloat()
            if (len < 1f) continue
            list.add(Run(s, acc, acc + len))
            acc += len
        }
        runs = list
        totalPoints = acc
        progress = 0f
        isComplete = false
        isPlaying = false
    }

    fun start() {
        if (runs.isEmpty()) return
        if (isComplete) progress = 0f
        isComplete = false
        isPlaying = true
    }

    fun pause() { isPlaying = false }
    fun resume() { isPlaying = runs.isNotEmpty() && !isComplete }

    fun reset() {
        isPlaying = false
        isComplete = false
        progress = 0f
        listener?.onProgress(0f)
    }

    fun seekTo(fraction: Float) {
        progress = (fraction.coerceIn(0f, 1f) * totalPoints)
        if (progress >= totalPoints) {
            progress = totalPoints
            isComplete = true
            isPlaying = false
        } else {
            isComplete = false
        }
    }

    fun advance(dtSec: Float) {
        if (!isPlaying || runs.isEmpty()) return
        progress += speed * dtSec
        if (progress >= totalPoints) {
            progress = totalPoints
            isPlaying = false
            isComplete = true
            listener?.onComplete()
        }
        listener?.onProgress(fraction)
    }

    val fraction: Float
        get() = if (totalPoints <= 0f) 0f else (progress / totalPoints).coerceIn(0f, 1f)

    val isEmpty: Boolean get() = runs.isEmpty()

    /**
     * Visits the strokes that should be rendered at the current progress.
     * Fully-drawn strokes are passed with fraction = 1f; the stroke currently
     * in motion is passed with its local fraction in [0,1].
     */
    fun visitVisible(block: (stroke: Stroke, fraction: Float) -> Unit) {
        var remaining = progress
        for (r in runs) {
            if (remaining <= 0f) return
            if (remaining >= r.length) {
                block(r.stroke, 1f)
                remaining -= r.length
            } else {
                block(r.stroke, (remaining / r.length).coerceIn(0f, 1f))
                return
            }
        }
    }
}
