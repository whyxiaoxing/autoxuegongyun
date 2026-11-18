package com.xiaoxing.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：为自动化提供点击 / 滑动手势与全局动作。
 * 需要用户在系统设置中手动开启；未开启时页面会引导跳转。
 */
class AutomationAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
    }

    override fun onDestroy() {
        live = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        // 开关打开且已授予悬浮窗权限时，在屏幕上标出实际点击坐标（未授权时静默跳过）
        if (dispatched) ClickEffectOverlay.showTap(this, x, y)
        return dispatched
    }

    private fun dispatchSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (dispatched) ClickEffectOverlay.showSwipe(this, x1, y1, x2, y2)
        return dispatched
    }

    private fun dispatchInputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            target = findEditable(root)
        }
        target = target ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditable(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAP_DURATION_MS = 60L

        @Volatile
        private var live: AutomationAccessibilityService? = null

        /** 服务是否已连接（手势/全局动作可用的前提） */
        val isConnected: Boolean get() = live != null

        /** 系统设置中是否已启用本服务 */
        fun isEnabled(context: Context): Boolean {
            val setting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return setting.split(':').any {
                it.contains(context.packageName) && it.contains(AutomationAccessibilityService::class.java.simpleName)
            }
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun tap(x: Int, y: Int): Boolean = live?.dispatchTap(x.toFloat(), y.toFloat()) ?: false

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
            live?.dispatchSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs) ?: false

        fun inputText(text: String): Boolean = live?.dispatchInputText(text) ?: false

        /**
         * 收起通知栏。第二层闸门确认后，用户的通知栏通常仍处于展开状态，会遮住目标应用，
         * 导致后续截图识别到的是通知栏而不是目标界面。无障碍服务未连接时返回 false（静默跳过）。
         */
        fun dismissNotificationShade(): Boolean =
            live?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                ?: false

        /** @param action "back" / "home" */
        fun globalAction(action: String): Boolean {
            val svc = live ?: return false
            // equals(ignoreCase) 避免每次动作都新建一个 lowercase 字符串
            return when {
                action.equals("back", ignoreCase = true) ->
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

                action.equals("home", ignoreCase = true) ->
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

                else -> false
            }
        }
    }
}
