package com.example.musicplayer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 前台 App 检测服务：识别抖音/快手等第三方 App 的进入与退出。
 *
 * 荣耀系统上：
 * 1. UsageStatsManager.queryEvents 拿不到第三方 App 的前台事件；
 * 2. 抖音极速版打开时不产生任何无障碍窗口事件（连 TYPE_WINDOWS_CHANGED 都没有）。
 *
 * 因此采用「事件回调 + 主动轮询」双保险：
 * - 事件回调：正常的窗口切换能触发；
 * - 主动轮询：每 800ms 查询 rootInActiveWindow 的包名，兜底检测抖音这类"隐形"窗口。
 */
class ForegroundAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_FOREGROUND_CHANGED =
            "com.example.musicplayer.ACTION_FOREGROUND_CHANGED"
        const val EXTRA_PACKAGE = "package"
        @Volatile
        var isRunning = false
            private set
    }

    private var lastPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pollTick = object : Runnable {
        override fun run() {
            checkForeground()
            handler.postDelayed(this, 800)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d("MusicPlayer", "无障碍服务已连接，启动轮询")
        // 启动主动轮询
        handler.post(pollTick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        // 优先用事件里的包名；拿不到时用当前活跃窗口的包名兜底
        var pkg = event.packageName?.toString()
        if (pkg.isNullOrEmpty()) {
            pkg = rootInActiveWindow?.packageName?.toString()
        }
        if (pkg.isNullOrEmpty()) return

        notifyChanged(pkg)
    }

    /** 主动查询当前活跃窗口包名，兜底检测不产生事件的第三方 App */
    private fun checkForeground() {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg.isNullOrEmpty()) return
        notifyChanged(pkg)
    }

    /** 前台包名变化时，通过广播通知播放服务 */
    private fun notifyChanged(pkg: String) {
        if (pkg == lastPackage) return
        lastPackage = pkg
        val intent = Intent(ACTION_FOREGROUND_CHANGED)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_PACKAGE, pkg)
        sendBroadcast(intent)
        Log.d("MusicPlayer", "前台切换 -> $pkg")
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        handler.removeCallbacks(pollTick)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(pollTick)
        super.onDestroy()
    }
}
