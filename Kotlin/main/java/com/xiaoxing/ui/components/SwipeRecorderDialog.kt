package com.xiaoxing.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 小于该像素位移视为误触，不写入步骤（避免把轻点当成滑动） */
private const val MIN_SWIPE_DISTANCE_PX = 24f

/** 手势时长夹取范围：太短在长距离上会丢点，太长会让目标应用判定为长按拖拽 */
private const val MIN_DURATION_MS = 50L
private const val MAX_DURATION_MS = 3000L

/**
 * 滑动手势录制层：全屏半透明，用户滑一次就拿到屏幕绝对坐标与真实时长。
 *
 * 为什么不用输入像素、也不用百分比换算：
 *  - 像素值要靠反复试错才能得到，百分比也只是把猜测换了个形式；
 *  - 无障碍 `dispatchGesture` 用的是屏幕坐标系，这里直接取 `MotionEvent.rawX/rawY`，
 *    与执行时完全一致（`event.x/y` 是局部坐标，不能用）。
 *
 * 为什么是应用内 Dialog 而不是 TYPE_APPLICATION_OVERLAY 悬浮窗：
 *  - 不需要悬浮窗权限，也不会在用户退出应用后留下录制窗口；
 *  - 全屏 Dialog 没有「外部」可点，`dismissOnClickOutside = false` 后手势全部归录制层所有；
 *  - `decorFitsSystemWindows = false` 让内容顶到屏幕边缘，与真实屏幕坐标一致。
 *
 * 限制：从屏幕最边缘起手时，返回手势 / 通知栏等系统手势优先，录制层收不到 DOWN，
 * 因此提示用户从屏幕中部起手。
 */
@Composable
internal fun SwipeRecorderDialog(
    onDismiss: () -> Unit,
    onRecorded: (x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) -> Unit,
) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }
    var downTime by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("在屏幕上滑一次，松手后自动填入起点、终点和时长") }
    var isError by remember { mutableStateOf(false) }
    // 回调后弹窗会被父级移除；这里再挡一道，避免同一次手势重复写入
    var done by remember { mutableStateOf(false) }

    val density = LocalDensity.current.density
    val labelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 0f, 1f * density, android.graphics.Color.BLACK)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 半透明遮罩：仍能看清底层界面，但触摸全部归录制层
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        if (done) return@pointerInteropFilter true
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                start = Offset(event.rawX, event.rawY)
                                current = start
                                downTime = event.eventTime
                                message = "滑到终点后松手"
                                isError = false
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (start != null) current = Offset(event.rawX, event.rawY)
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                val from = start
                                val to = Offset(event.rawX, event.rawY)
                                current = to
                                if (from == null) {
                                    true
                                } else if (hypot(to.x - from.x, to.y - from.y) < MIN_SWIPE_DISTANCE_PX) {
                                    start = null
                                    current = null
                                    message = "滑动距离太短，请再滑一次"
                                    isError = true
                                    true
                                } else {
                                    done = true
                                    val raw = (event.eventTime - downTime)
                                        .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
                                    onRecorded(
                                        from.x.roundToInt(),
                                        from.y.roundToInt(),
                                        to.x.roundToInt(),
                                        to.y.roundToInt(),
                                        // 取整到 10ms：录到的是用户手感，手抖的零头没有意义
                                        (raw + 5) / 10 * 10,
                                    )
                                    true
                                }
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                start = null
                                current = null
                                message = "手势被系统打断，请从屏幕中部重新滑一次"
                                isError = true
                                true
                            }

                            else -> true
                        }
                    },
            ) {
                val from = start
                val to = current
                if (from != null) {
                    val accent = Color(0xFFFF3B30)
                    val canvas = drawContext.canvas.nativeCanvas

                    if (to != null && (to.x != from.x || to.y != from.y)) {
                        drawLine(accent, from, to, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    }

                    drawCircle(Color.White.copy(alpha = 0.28f), 14.dp.toPx(), from)
                    drawCircle(accent, 7.dp.toPx(), from)
                    canvas.drawText(
                        "${from.x.roundToInt()}, ${from.y.roundToInt()}",
                        from.x + 16.dp.toPx(),
                        from.y - 16.dp.toPx(),
                        labelPaint,
                    )

                    if (to != null) {
                        drawCircle(Color.White.copy(alpha = 0.28f), 14.dp.toPx(), to)
                        drawCircle(Color.White, 7.dp.toPx(), to, style = Stroke(2.dp.toPx()))
                        canvas.drawText(
                            "${to.x.roundToInt()}, ${to.y.roundToInt()}",
                            to.x + 16.dp.toPx(),
                            to.y + 30.dp.toPx(),
                            labelPaint,
                        )
                    }
                }
            }

            // 说明卡片 / 取消按钮在绘制顺序上位于手势层之后，命中测试优先，
            // 因此按钮能正常点击，其余区域仍然全部由手势层接收。
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("录制滑动", style = MaterialTheme.typography.titleMedium)
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "从屏幕中部起手即可；贴着屏幕边缘会被系统返回手势/通知栏截走。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp,
            ) {
                TextButton(onClick = onDismiss) { Text("取消录制") }
            }
        }
    }
}
