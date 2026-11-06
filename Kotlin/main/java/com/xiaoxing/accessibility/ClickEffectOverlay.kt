package com.xiaoxing.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.xiaoxing.AppRepository
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「点击位置效果」：自动化执行点击 / 滑动时，在屏幕对应位置短暂绘制命中圈与坐标文字。
 *
 * 为什么走悬浮窗（TYPE_APPLICATION_OVERLAY）而不是无障碍浮层：
 *  - 无障碍浮层只能挂在无障碍服务的窗口里，无法覆盖到目标应用之上；
 *  - 悬浮窗可以直接盖在目标 App 上，且本应用已申请 SYSTEM_ALERT_WINDOW（拉起后台 App 也需要它）。
 *
 * 约束：
 *  - 开关关闭或未授予悬浮窗权限时静默跳过，绝不影响自动化本身；
 *  - 窗口不可触摸、不可聚焦，不会改变用户的点击行为；
 *  - 每次事件独立建窗、动画结束即移除，连续点击不会互相干扰。
 */
object ClickEffectOverlay {

    private const val DURATION_MS = 650L

    private val mainHandler = Handler(Looper.getMainLooper())

    private class ActiveEffect(
        val windowManager: WindowManager,
        val view: View,
        val animator: ValueAnimator,
    )

    /** 当前仍在动画中的全屏浮层；异常/关闭开关/内存压力时统一清理。 */
    private val active = CopyOnWriteArrayList<ActiveEffect>()

    /** 取消所有进行中的点击效果并移除窗口（关闭开关、onTrimMemory、onLowMemory）。 */
    fun clearActive() {
        mainHandler.post {
            active.forEach { effect ->
                runCatching { effect.animator.cancel() }
                runCatching { effect.windowManager.removeView(effect.view) }
            }
            active.clear()
        }
    }

    fun showTap(context: Context, x: Float, y: Float) {
        if (!AppRepository.clickEffect.value) return
        show(context, x, y, null, null)
    }

    fun showSwipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!AppRepository.clickEffect.value) return
        show(context, x1, y1, x2, y2)
    }

    private fun show(
        context: Context,
        startX: Float,
        startY: Float,
        endX: Float?,
        endY: Float?,
    ) {
        val appContext = context.applicationContext
        // 未授予悬浮窗权限时直接跳过（设置页的「点击位置效果」说明里已注明这一前提）
        if (!Settings.canDrawOverlays(appContext)) return
        mainHandler.post {
            runCatching {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = EffectView(appContext, startX, startY, endX, endY)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                wm.addView(view, params)
                val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = DURATION_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animation ->
                        view.progress = animation.animatedValue as Float
                        view.invalidate()
                    }
                }
                val effect = ActiveEffect(wm, view, animator)
                active.add(effect)
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runCatching { wm.removeView(view) }
                        active.remove(effect)
                    }
                })
                animator.start()
            }
        }
    }

    /** 全屏透明画布：坐标与无障碍手势/截图像素一致（均为屏幕左上角原点）。 */
    private class EffectView(
        context: Context,
        private val startX: Float,
        private val startY: Float,
        private val endX: Float?,
        private val endY: Float?,
    ) : View(context) {

        var progress: Float = 0f

        private val density = resources.displayMetrics.density
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 0f, 1f * density, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val alpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
            val accent = Color.rgb(255, 59, 48)
            val radius = (9f + progress * 18f) * density

            ringPaint.color = accent
            ringPaint.alpha = alpha
            fillPaint.color = accent
            fillPaint.alpha = (alpha * 0.22f).toInt().coerceIn(0, 255)

            canvas.drawCircle(startX, startY, radius, fillPaint)
            canvas.drawCircle(startX, startY, radius, ringPaint)

            if (endX != null && endY != null) {
                canvas.drawLine(startX, startY, endX, endY, ringPaint)
                canvas.drawCircle(endX, endY, radius * 0.7f, fillPaint)
                canvas.drawCircle(endX, endY, radius * 0.7f, ringPaint)
            }

            val label = if (endX != null && endY != null) {
                "${startX.toInt()}, ${startY.toInt()} → ${endX.toInt()}, ${endY.toInt()}"
            } else {
                "${startX.toInt()}, ${startY.toInt()}"
            }
            textPaint.alpha = alpha
            canvas.drawText(label, startX + 14f * density, startY - 12f * density, textPaint)
        }
    }
}
