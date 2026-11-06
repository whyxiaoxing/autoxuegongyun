package com.xiaoxing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaoxing.ui.theme.AppTheme

/** Full-screen base wash: a calm gradient with two very soft brand pools. */
@Composable
internal fun AppBackground(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extended = AppTheme.extended
    val backgroundBrush = remember(scheme.background, scheme.surfaceContainerLowest) {
        Brush.verticalGradient(listOf(scheme.background, scheme.surfaceContainerLowest))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            // 渐变 Brush/Shader 只在尺寸或主题变化时创建一次。旧实现在 draw lambda 内
            // 每帧 new，一次切页动画就能产生几十个 Shader，是 GC 抖动的直接来源之一。
            .drawWithCache {
                val radius = size.minDimension * 0.85f
                val glowCenter = Offset(size.width * 0.96f, size.height * 0.02f)
                val successCenter = Offset(size.width * 0.02f, size.height * 0.76f)
                val glow = Brush.radialGradient(
                    colors = listOf(extended.glow.copy(alpha = 0.16f), Color.Transparent),
                    center = glowCenter,
                    radius = radius,
                )
                val success = Brush.radialGradient(
                    colors = listOf(extended.success.copy(alpha = 0.07f), Color.Transparent),
                    center = successCenter,
                    radius = radius * 0.8f,
                )
                onDrawBehind {
                    drawCircle(glow, radius, glowCenter)
                    drawCircle(success, radius * 0.8f, successCenter)
                }
            },
    ) {
        content()
    }
}

/** The single card treatment used across the app. */
@Composable
internal fun AppPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
        content = content,
    )
}

/** A rounded icon plate — one consistent visual language for every icon tile. */
@Composable
internal fun IconPlate(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Int = 40,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

/** Status light: soft halo + solid core, readable on both themes. */
@Composable
internal fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier.size(16.dp),
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(color.copy(alpha = 0.18f), CircleShape),
        )
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
internal fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(color, Modifier.size(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A compact permission line: status light + permission name + state. */
@Composable
internal fun PermissionLightRow(
    name: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
) {
    val statusColor = if (granted) AppTheme.extended.success else AppTheme.extended.warning
    val statusLabel = if (granted) "已开启" else "未开启"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name，$statusLabel"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(statusColor, Modifier.size(13.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
    }
}

@Composable
internal fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
internal fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/** Number field used across settings/editor sheets. */
@Composable
internal fun NumField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * 允许负数的整数字段（如模板匹配的点击偏移）。
 * 数字键盘多数不含负号，这里用 Ascii 键盘；输入过程中允许只输一个 "-"，提交时按 0 处理。
 */
@Composable
internal fun SignedNumField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val filtered = buildString {
                raw.forEachIndexed { index, char ->
                    when {
                        char.isDigit() -> append(char)
                        char == '-' && index == 0 -> append(char)
                    }
                }
            }
            onValueChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier,
    )
}

/** Discoverability helper for empty lists. */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconPlate(icon, size = 52)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

@Composable
internal fun AppPanelColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppPanel(modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
