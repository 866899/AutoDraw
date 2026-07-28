package com.autodraw.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autodraw.app.R
import kotlin.math.abs

/**
 * 悬浮窗控制面板(前台服务)。
 *
 * 提供按钮:开始 / 暂停 / 继续 / 停止 / 关闭。
 * 提供滑条:速度、轨迹缩放、X 偏移、Y 偏移。
 * 显示当前进度(已完成笔触数 / 总数 + 百分比)。
 *
 * 通过 [DrawController] 单例与无障碍服务通信,自身不持有服务引用。
 */
class FloatingControlService : Service(), DrawController.Listener {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null

    private lateinit var btnStart: View
    private lateinit var btnPause: View
    private lateinit var btnStop: View
    private lateinit var btnClose: View
    private lateinit var btnSelectRegion: View
    private lateinit var btnClearRegion: View
    private lateinit var tvState: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressSeek: SeekBar
    private lateinit var speedSeek: SeekBar
    private lateinit var scaleSeek: SeekBar
    private lateinit var offsetXSeek: SeekBar
    private lateinit var offsetYSeek: SeekBar

    private var dragging = false
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f

    /** 框选全屏 overlay 的根视图(含 OverlayRegionView + 底部确认/取消栏)。 */
    private var regionOverlay: View? = null
    private var regionView: OverlayRegionView? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showPanel()
        DrawController.listener = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 框选区域 overlay ----

    /** 显示全屏框选 overlay:暂停绘画,在屏幕上覆盖一个可拖拽调整的矩形框。 */
    private fun showRegionOverlay() {
        if (regionOverlay != null) return
        // 进入框选时先暂停,避免手势冲突
        DrawController.pause()

        val dm = resources.displayMetrics
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        // 1) 框选视图
        val region = OverlayRegionView(this).apply {
            aspect = DrawController.imageAspect.coerceAtLeast(0.1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        container.addView(region)
        regionView = region

        // 2) 底部按钮栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC1F2433"))
            setPadding(0, dp(8), 0, dp(8))
        }
        val hint = TextView(this).apply {
            text = getString(R.string.region_hint)
            setTextColor(Color.parseColor("#FFE082"))
            setPadding(dp(12), 0, dp(12), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val btnConfirm = Button(this).apply {
            text = getString(R.string.region_confirm)
            setBackgroundColor(Color.parseColor("#3F51B5"))
            setTextColor(Color.WHITE)
            setOnClickListener { confirmRegion() }
        }
        val btnCancel = Button(this).apply {
            text = getString(R.string.region_cancel)
            setBackgroundColor(Color.parseColor("#607D8B"))
            setTextColor(Color.WHITE)
            setOnClickListener { cancelRegion() }
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnConfirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        bar.addView(btnCancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        container.addView(bar)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        windowManager.addView(container, params)
        regionOverlay = container
    }

    /** 确认框选:把屏幕坐标的矩形传给 DrawController。 */
    private fun confirmRegion() {
        val view = regionView ?: return
        val rect = view.currentRect()
        DrawController.setRegion(
            DrawController.DrawRegion(rect.left, rect.top, rect.right, rect.bottom)
        )
        toast(getString(R.string.region_set, rect.width().toInt(), rect.height().toInt()))
        removeRegionOverlay()
    }

    private fun cancelRegion() {
        removeRegionOverlay()
    }

    private fun removeRegionOverlay() {
        regionOverlay?.let { runCatching { windowManager.removeView(it) } }
        regionOverlay = null
        regionView = null
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        DrawController.stop("悬浮窗已关闭")
        if (DrawController.listener === this) DrawController.listener = null
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
    }

    // ---- 悬浮窗 ----
    private fun showPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        val view = LayoutInflater.from(this).inflate(R.layout.floating_panel, null, false)
        bindViews(view)
        setupDrag(view, params)
        windowManager.addView(view, params)
        rootView = view
        syncButtons(DrawController.currentState)
    }

    private fun bindViews(v: View) {
        btnStart = v.findViewById(R.id.fab_start)
        btnPause = v.findViewById(R.id.fab_pause)
        btnStop = v.findViewById(R.id.fab_stop)
        btnClose = v.findViewById(R.id.fab_close)
        btnSelectRegion = v.findViewById(R.id.fab_select_region)
        btnClearRegion = v.findViewById(R.id.fab_clear_region)
        tvState = v.findViewById(R.id.tv_state)
        tvProgress = v.findViewById(R.id.tv_progress)
        progressSeek = v.findViewById(R.id.fab_progress)
        speedSeek = v.findViewById(R.id.fab_speed)
        scaleSeek = v.findViewById(R.id.fab_scale)
        offsetXSeek = v.findViewById(R.id.fab_offset_x)
        offsetYSeek = v.findViewById(R.id.fab_offset_y)

        btnStart.setOnClickListener {
            if (DrawController.hasStrokes) DrawController.start()
            else toast("没有可绘画的轨迹")
        }
        btnPause.setOnClickListener { DrawController.pause() }
        btnStop.setOnClickListener { DrawController.stop("用户停止") }
        btnClose.setOnClickListener { stopSelf() }
        btnSelectRegion.setOnClickListener { showRegionOverlay() }
        btnClearRegion.setOnClickListener {
            DrawController.setRegion(null)
            toast(getString(R.string.region_cleared))
        }

        progressSeek.isEnabled = false // 只读进度
        applyTransformFromSeekbars()

        speedSeek.setOnSeekBarChangeListener(simpleSeek { applyTransformFromSeekbars() })
        scaleSeek.setOnSeekBarChangeListener(simpleSeek { applyTransformFromSeekbars() })
        offsetXSeek.setOnSeekBarChangeListener(simpleSeek { applyTransformFromSeekbars() })
        offsetYSeek.setOnSeekBarChangeListener(simpleSeek { applyTransformFromSeekbars() })

        // 初始默认值
        speedSeek.progress = 50
        scaleSeek.progress = 50
        offsetXSeek.progress = 50
        offsetYSeek.progress = 50
    }

    private fun applyTransformFromSeekbars() {
        // 速度 0..100 -> 200..2400 px/s
        val pps = 200f + (speedSeek.progress / 100f) * (2400f - 200f)
        // 缩放 0..100 -> 0.15..0.95(占短边比例)
        val scale = 0.15f + (scaleSeek.progress / 100f) * (0.95f - 0.15f)
        // 偏移 0..100 -> -300..+300 px
        val ox = (offsetXSeek.progress - 50) * 6
        val oy = (offsetYSeek.progress - 50) * 6
        DrawController.setTransform(
            DrawController.Transform(
                scaleFactor = scale,
                offsetXPx = ox,
                offsetYPx = oy,
                pixelsPerSec = pps
            )
        )
    }

    private fun setupDrag(v: View, params: WindowManager.LayoutParams) {
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    initialX = params.x
                    initialY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (dragging || abs(dx) > 8 || abs(dy) > 8) {
                        dragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasDragging = dragging
                    dragging = false
                    // 若发生了拖动则消费事件,避免误触按钮
                    wasDragging
                }
                else -> false
            }
        }
    }

    // ---- DrawController.Listener ----
    override fun onStateChanged(state: DrawController.State) {
        runOnUiThread {
            syncButtons(state)
            tvState.text = stateLabel(state)
        }
    }

    override fun onProgress(doneStrokes: Int, totalStrokes: Int, fraction: Float) {
        runOnUiThread {
            val pct = (fraction * 100).toInt()
            tvProgress.text = "$doneStrokes / $totalStrokes  ($pct%)"
            progressSeek.max = totalStrokes.coerceAtLeast(1)
            progressSeek.progress = doneStrokes
        }
    }

    override fun onAutoStop(reason: String) {
        runOnUiThread {
            toast("已自动停止:$reason")
        }
    }

    private fun syncButtons(state: DrawController.State) {
        when (state) {
            DrawController.State.IDLE, DrawController.State.STOPPED, DrawController.State.DONE -> {
                btnStart.isEnabled = true
                btnPause.isEnabled = false
                btnStart.alpha = 1f
                btnPause.alpha = 0.4f
            }
            DrawController.State.PLAYING -> {
                btnStart.isEnabled = false
                btnPause.isEnabled = true
                btnStart.alpha = 0.4f
                btnPause.alpha = 1f
            }
            DrawController.State.PAUSED -> {
                btnStart.isEnabled = true
                btnPause.isEnabled = false
                btnStart.alpha = 1f
                btnPause.alpha = 0.4f
            }
        }
    }

    private fun stateLabel(state: DrawController.State): String = when (state) {
        DrawController.State.IDLE -> "就绪"
        DrawController.State.PLAYING -> "绘画中…"
        DrawController.State.PAUSED -> "已暂停"
        DrawController.State.STOPPED -> "已停止"
        DrawController.State.DONE -> "完成"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun simpleSeek(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onProgress(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    // ---- 前台通知 ----
    private fun startForeground() {
        val channelId = "autodraw_floating"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "悬浮控制", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoDraw 屏幕绘画")
            .setContentText("悬浮控制面板运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private companion object {
        const val NOTIF_ID = 4242
    }
}
