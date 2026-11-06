package com.xiaoxing.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.ProjectionGate

/**
 * 投屏二次确认弹窗（第二层闸门）。
 *
 * 为什么是应用内 Dialog 而不是 SYSTEM_ALERT_WINDOW 悬浮窗：
 *  悬浮窗（尤其 focusable 的）会抢焦点，并可能遮挡状态栏手势；本弹窗是普通应用窗口，
 *  状态栏照常可以下拉 —— 用户必须先下拉通知栏点「继续共享」，再回到这里点确认。
 *
 * 状态完全由 [AppRepository.projectionGate] 驱动：
 *  AWAITING → 显示；CONFIRMED / CANCELLED / NONE → 消失（服务端判定后统一改状态）。
 * 因此 Activity 被系统回收后重建、或用户从通知栏返回，弹窗都能自动恢复，不需要额外保存状态。
 */
@Composable
internal fun ProjectionConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val gate by AppRepository.projectionGate.collectAsStateWithLifecycle()
    if (gate != ProjectionGate.AWAITING) return
    val preview by AppRepository.projectionPreview.collectAsStateWithLifecycle()

    Dialog(
        // 闸门必须显式二选一：点外部 / 返回键都不关闭，避免服务空等两分钟
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("投屏二次确认", style = MaterialTheme.typography.titleLarge)

                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "系统已授权截屏 ✔。部分厂商（MIUI / HyperOS 等）还会在通知栏再加一道" +
                            "「继续共享」—— 在你点它之前，投屏画面会被隐私保护模糊，识别必然失败。",
                        fontSize = 13.sp,
                    )
                    Text(
                        "「继续共享」只在捕获目标应用时才出现，所以已自动切到目标应用（没切过去就手动切）。",
                        fontSize = 13.sp,
                    )
                    Text("① 在目标应用上下拉通知栏，点「继续共享」", fontSize = 13.sp)
                    Text("② 点本应用通知上的「已确认，开始」", fontSize = 13.sp)
                    Text("③ 或者回到本页，点下方按钮", fontSize = 13.sp)

                    Text(
                        "当前投屏画面预览",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val bitmap = preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "投屏实时预览",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text("预览准备中…", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "预览模糊 = 还没点「继续共享」；预览清晰即可确认。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    Button(onClick = onConfirm) { Text("已确认，开始工作流") }
                }
            }
        }
    }
}
