package com.autodraw.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

/**
 * 屏幕模拟绘画的无障碍服务。
 *
 * 职责:
 *  - 连接时把自己注入 [DrawController],使其能下发手势。
 *  - 监听无障碍事件:目标应用窗口/包名变化 → 自动停止绘画。
 *  - 监听系统灭屏广播(ACTION_SCREEN_OFF) → 自动停止绘画。
 *  - 断开(权限被关闭)时通知控制器停止。
 */
class AutoDrawAccessibilityService : AccessibilityService() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                DrawController.autoStop("屏幕已关闭")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DrawController.attachService(this)
        // 注册灭屏广播(动态注册,服务断开时反注册)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 仅在绘画期间关心事件
        if (DrawController.currentState == DrawController.State.PLAYING ||
            DrawController.currentState == DrawController.State.PAUSED
        ) {
            val pkg = event.packageName?.toString()
            DrawController.onAccessibilityEvent(event, pkg)
        }
    }

    override fun onInterrupt() {
        DrawController.autoStop("无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        DrawController.detachService()
        return super.onUnbind(intent)
    }
}
