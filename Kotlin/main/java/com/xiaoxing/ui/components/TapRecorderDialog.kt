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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 小于该像素位移才视为点击；超过则可能是拖动，不写入坐标 */
private const val MAX_TAP_MOVEMENT_PX = 24f

/** 按下时长上限：再长目标应用可能识别成长按，录到的位置就没有意义了 */
private const val MAX_TAP_DURATION_MS = 800L

/**
 * 点击位置录制层：全屏半透明，用户点一下就把屏幕绝对坐标填入步骤。
 *
 * 与 [SwipeRecorderDialog] 同一套原理：直接取 `MotionEvent.rawX/rawY`
 * （无障碍 `dispatchGesture` 用的就是屏幕坐标系），避免像素试凑。
 *
 * 只接受「轻点」：位移超过 [MAX_TAP_MOVEMENT_PX] 或按住超过 [MAX_TAP_DURATION_MS]
 * 都提示重新点一次，防止把拖动/长按误录成点击。
 */
@Composable
internal fun TapRecorderDialog(
    onDismiss: () -> Unit,
    onRecorded: (x: Int, y: Int) -> Unit,
) {
    var point by remember { mutableStateOf<Offset?>(null) }
    var downTime by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf("在屏幕上点一下目标位置，松手后自动填入坐标") }
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
                                point = Offset(event.rawX, event.rawY)
                                downTime = event.eventTime
                                message = "松开手指确认这个位置"
                                isError = false
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                // 跟手显示当前位置，松手时再统一校验
                                if (point != null) point = Offset(event.rawX, event.rawY)
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                val from = point
                                val to = Offset(event.rawX, event.rawY)
                                point = to
                                val duration = event.eventTime - downTime
                                when {
                                    from == null -> true

                                    hypot(to.x - from.x, to.y - from.y) > MAX_TAP_MOVEMENT_PX -> {
                                        point = null
                                        message = "检测到拖动，请轻点一下目标位置"
                                        isError = true
                                        true
                                    }

                                    duration > MAX_TAP_DURATION_MS -> {
                                        point = null
                                        message = "按住时间过长（可能触发长按），请轻点一下"
                                        isError = true
                                        true
                                    }

                                    else -> {
                                        done = true
                                        // 以按下点为准：点击在目标应用里就是落在按下位置
                                        onRecorded(from.x.roundToInt(), from.y.roundToInt())
                                        true
                                    }
                                }
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                point = null
                                message = "手势被系统打断，请从屏幕中部重新点一次"
                                isError = true
                                true
                            }

                            else -> true
                        }
                    },
            ) {
                val at = point ?: return@Canvas
                val accent = Color(0xFFFF3B30)
                val canvas = drawContext.canvas.nativeCanvas

                // 十字准星 + 命中点：比单纯圆点更容易对准小控件
                drawLine(
                    Color.White.copy(alpha = 0.65f),
                    Offset(at.x - 18.dp.toPx(), at.y),
                    Offset(at.x + 18.dp.toPx(), at.y),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawLine(
                    Color.White.copy(alpha = 0.65f),
                    Offset(at.x, at.y - 18.dp.toPx()),
                    Offset(at.x, at.y + 18.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(Color.White.copy(alpha = 0.28f), 14.dp.toPx(), at)
                drawCircle(accent, 7.dp.toPx(), at)
                drawCircle(Color.White, 11.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
                canvas.drawText(
                    "${at.x.roundToInt()}, ${at.y.roundToInt()}",
                    at.x + 16.dp.toPx(),
                    at.y - 16.dp.toPx(),
                    labelPaint,
                )
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
                    Text("录制点击", style = MaterialTheme.typography.titleMedium)
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
                        "只接受轻点（不拖动、不长按）；贴着屏幕边缘会被系统返回手势/通知栏截走。",
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
