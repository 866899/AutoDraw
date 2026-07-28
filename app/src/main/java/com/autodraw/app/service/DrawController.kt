package com.autodraw.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.autodraw.app.drawing.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 屏幕模拟绘画的核心编排器(单例)。
 *
 * - 持有待绘制的[Stroke]列表(坐标归一化到 [0,1],相对图片)。
 * - 由 [AutoDrawAccessibilityService] 在连接时注入自身引用,控制器通过它分发手势。
 * - 由悬浮窗服务调用 start/pause/resume/stop 及配置更新。
 * - 在协程中逐条(长笔触按段)用 [AccessibilityService.dispatchGesture] 下发
 *   [GestureDescription],并等待回调完成后继续下一段,实现连续滑动。
 */
object DrawController {

    enum class State { IDLE, PLAYING, PAUSED, STOPPED, DONE }

    /** 屏幕坐标变换参数。 */
    data class Transform(
        val scaleFactor: Float = 0.5f,   // 绘画区域占短边的比例
        val offsetXPx: Int = 0,           // 中心点水平偏移(像素)
        val offsetYPx: Int = 0,           // 中心点垂直偏移(像素)
        val pixelsPerSec: Float = 900f   // 滑动速度
    )

    // ---- 状态 ----
    @Volatile var service: AccessibilityService? = null
        private set
    @Volatile private var strokes: List<Stroke> = emptyList()
    @Volatile private var transform = Transform()
    @Volatile private var state: State = State.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playerJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 当前绘画目标包名(用于检测目标应用退出)。null 表示不限定。 */
    @Volatile var targetPackage: String? = null

    /** UI 回调(由悬浮窗注册)。 */
    var listener: Listener? = null

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onProgress(doneStrokes: Int, totalStrokes: Int, fraction: Float) {}
        fun onAutoStop(reason: String) {}
    }

    // ---- 服务生命周期 ----
    fun attachService(s: AccessibilityService) {
        service = s
    }

    fun detachService() {
        stop("无障碍服务已断开")
        service = null
    }

    // ---- 数据 / 配置 ----
    fun setStrokes(strokes: List<Stroke>) {
        if (state == State.PLAYING || state == State.PAUSED) {
            stop("重新设置轨迹")
        }
        this.strokes = strokes
        state = if (strokes.isEmpty()) State.IDLE else State.IDLE
        notifyState()
        listener?.onProgress(0, strokes.size, 0f)
    }

    fun setTransform(t: Transform) { transform = t }

    val currentState: State get() = state
    val hasStrokes: Boolean get() = strokes.isNotEmpty()

    // ---- 控制 ----
    fun start() {
        val s = service ?: run {
            listener?.onAutoStop("无障碍服务未开启")
            return
        }
        if (strokes.isEmpty()) {
            listener?.onAutoStop("没有可绘画的轨迹")
            return
        }
        if (state == State.PLAYING) return
        playerJob?.cancel()
        state = State.PLAYING
        notifyState()
        playerJob = scope.launch { play(s) }
    }

    fun pause() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        notifyState()
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        notifyState()
    }

    fun stop(reason: String = "用户停止") {
        playerJob?.cancel()
        playerJob = null
        if (state != State.IDLE) {
            state = State.STOPPED
            notifyState()
        }
    }

    fun reset() {
        stop("重置")
        state = State.IDLE
        notifyState()
        listener?.onProgress(0, strokes.size, 0f)
    }

    /** 外部触发自动停止(目标退出/灭屏/权限失效)。 */
    fun autoStop(reason: String) {
        if (state == State.PLAYING || state == State.PAUSED) {
            stop(reason)
            listener?.onAutoStop(reason)
        }
    }

    // ---- 播放循环 ----
    private suspend fun play(s: AccessibilityService) {
        val list = strokes
        val total = list.size
        try {
            for ((index, stroke) in list.withIndex()) {
                coroutineContext.ensureActive()
                // 暂停门:轮询直到非暂停态(50ms 粒度足够)
                while (state == State.PAUSED) {
                    coroutineContext.ensureActive()
                    delay(50)
                }
                if (state == State.STOPPED || state == State.IDLE) return

                if (!dispatchStroke(s, stroke)) {
                    // 手势被取消(权限失效/系统中断)
                    autoStop("手势被系统中断")
                    return
                }
                listener?.onProgress(index + 1, total, (index + 1) / total.toFloat())
                // 笔触间极短抬笔间隔,让下一条更自然
                if (state == State.PLAYING) delay(20)
            }
            state = State.DONE
            notifyState()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 正常取消,不改变外部设置的 STOPPED/IDLE
            throw e
        }
    }

    /**
     * 把一条 [stroke] 分段下发。每段最多 [SEG_MAX_POINTS] 个点,段间连续(前一段终点
     * 作为下一段起点),实现真实的手指连续滑动。返回 false 表示被系统取消。
     */
    private suspend fun dispatchStroke(s: AccessibilityService, stroke: Stroke): Boolean {
        val pts = stroke.points
        if (pts.size < 2) return true
        var idx = 0
        while (idx < pts.size - 1) {
            // 构造一段:起点 + 后续点,直到达到段上限
            val seg = ArrayList<PointF>(SEG_MAX_POINTS)
            seg.add(pts[idx])
            var j = idx + 1
            while (j < pts.size && seg.size < SEG_MAX_POINTS) {
                seg.add(pts[j])
                j++
            }
            if (seg.size < 2) break
            val path = buildPath(seg)
            val lenPx = pathLength(seg)
            val durationMs = (lenPx / transform.pixelsPerSec.coerceAtLeast(1f) * 1000f)
                .coerceIn(40f, 4000f).toLong()
            if (!dispatchOneGesture(s, path, durationMs)) return false
            idx = j - 1 // 下一段从本段最后一个点继续,保证连续
        }
        return true
    }

    private fun buildPath(seg: List<PointF>): Path {
        val path = Path()
        if (seg.isEmpty()) return path
        val first = mapToScreen(seg[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until seg.size) {
            val p = mapToScreen(seg[i])
            path.lineTo(p.x, p.y)
        }
        return path
    }

    private fun pathLength(seg: List<PointF>): Float {
        if (seg.size < 2) return 0f
        var len = 0f
        var prev = mapToScreen(seg[0])
        for (i in 1 until seg.size) {
            val cur = mapToScreen(seg[i])
            len += Stroke.distance(prev, cur)
            prev = cur
        }
        return len
    }

    /** 归一化坐标 -> 屏幕像素坐标(居中 + 用户缩放 + 用户偏移)。 */
    private fun mapToScreen(p: PointF): PointF {
        val s = service ?: return PointF(0f, 0f)
        val dm = s.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val base = minOf(screenW, screenH).toFloat()
        val drawScale = base * transform.scaleFactor
        val cx = screenW / 2f + transform.offsetXPx
        val cy = screenH / 2f + transform.offsetYPx
        return PointF(cx + (p.x - 0.5f) * drawScale, cy + (p.y - 0.5f) * drawScale)
    }

    private suspend fun dispatchOneGesture(
        s: AccessibilityService, path: Path, durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val strokeDesc = GestureDescription.StrokeDescription(
            path, 0L, durationMs, false
        )
        val gesture = GestureDescription.Builder().addStroke(strokeDesc).build()
        val dispatched = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, handler)
        if (!dispatched) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private fun notifyState() {
        handler.post { listener?.onStateChanged(state) }
    }

    /** 由 AccessibilityService 在事件中调用,检测目标窗口/包名变化。 */
    fun onAccessibilityEvent(event: AccessibilityEvent, activePackageName: String?) {
        // 目标应用退出:启动时记录的目标包名变了
        val target = targetPackage
        if (target != null && activePackageName != null && activePackageName != target) {
            autoStop("目标应用已退出")
        }
    }

    private const val SEG_MAX_POINTS = 100
}
