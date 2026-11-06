package com.xiaoxing.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppHint
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * 顶层提示弹窗：取代原「运行日志」页与 Snackbar。
 *
 * - 位于页面内容之上、底部导航之上，任何页面（含二级页）都能看到；
 * - 容器不透明度 0.97，带描边/阴影，浅色与深色主题下都可读；
 * - INFO / SUCCESS / WARNING 自动消失，ERROR 常驻直到用户关闭 —— 后台报错回到页面也能看到；
 * - 状态来自 [AppRepository.hint]（StateFlow），进程内不会因页面未组合而丢失。
 */
@Composable
internal fun HintOverlay(modifier: Modifier = Modifier) {
    val hint by AppRepository.hint.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    var displayed by remember { mutableStateOf<AppHint?>(null) }

    LaunchedEffect(hint?.id) {
        val item = hint
        if (item == null) {
            visible = false
            return@LaunchedEffect
        }
        displayed = item
        visible = true
        // 错误必须由用户确认，保证可观测性；其余提示自动收起。
        if (item.level == HintLevel.ERROR) return@LaunchedEffect
        delay(
            when (item.level) {
                HintLevel.WARNING -> 4500L
                else -> 3200L
            },
        )
        if (AppRepository.hint.value?.id != item.id) return@LaunchedEffect
        visible = false
        delay(200)
        if (AppRepository.hint.value?.id == item.id) AppRepository.dismissHint()
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 3 },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            displayed?.let { item ->
                HintCard(
                    hint = item,
                    onDismiss = {
                        visible = false
                        AppRepository.dismissHint()
                    },
                )
            }
        }
    }
}

@Composable
private fun HintCard(hint: AppHint, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extended = AppTheme.extended
    val (accent, icon, label) = when (hint.level) {
        HintLevel.INFO -> Triple(scheme.primary, Icons.Filled.Info, "提示")
        HintLevel.SUCCESS -> Triple(extended.success, Icons.Filled.CheckCircle, "完成")
        HintLevel.WARNING -> Triple(extended.warning, Icons.Filled.Warning, "注意")
        HintLevel.ERROR -> Triple(scheme.error, Icons.Filled.Warning, "出错了")
    }
    val container = if (hint.level == HintLevel.ERROR) {
        scheme.errorContainer.copy(alpha = 0.97f)
    } else {
        scheme.surfaceContainerHighest.copy(alpha = 0.97f)
    }
    val contentColor = if (hint.level == HintLevel.ERROR) {
        scheme.onErrorContainer
    } else {
        scheme.onSurface
    }

    Surface(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(22.dp),
        color = container,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        shadowElevation = 14.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconPlate(icon = icon, tint = accent, size = 38)
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    hint.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭提示",
                    tint = contentColor.copy(alpha = 0.65f),
                )
            }
        }
    }
}
