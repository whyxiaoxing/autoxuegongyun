package com.xiaoxing.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.ServerConnectionState
import com.xiaoxing.permissions.PermissionMonitor
import com.xiaoxing.ui.components.AppDivider
import com.xiaoxing.ui.components.AppPanel
import com.xiaoxing.ui.components.IconPlate
import com.xiaoxing.ui.components.SectionEyebrow
import com.xiaoxing.ui.theme.AppTheme
import kotlin.math.roundToInt

/** Settings is a traditional two-level hierarchy: list → one detail page. */
internal enum class SettingsDestination(val key: String, val title: String) {
    SERVER("server", "服务器连接"),
    OSS("oss", "资源同步"),
    IDENTITY("identity", "身份信息"),
    PHOTO("photo", "本地模板"),
    PERMISSIONS("permissions", "权限管理"),
    AUTOMATION("automation", "自动化选项"),
    APPEARANCE("appearance", "外观主题"),
    ABOUT("about", "关于"),
}

@Composable
internal fun SettingsPage(onOpen: (SettingsDestination) -> Unit) {
    val server by AppRepository.serverConfig.collectAsStateWithLifecycle()
    val connection by AppRepository.serverConnection.collectAsStateWithLifecycle()
    val accessibilityEnabled by PermissionMonitor.accessibilityEnabled.collectAsStateWithLifecycle()
    val overlayGranted by PermissionMonitor.overlayGranted.collectAsStateWithLifecycle()
    val batteryWhitelisted by PermissionMonitor.batteryWhitelisted.collectAsStateWithLifecycle()
    val confidence by AppRepository.matchConfidence.collectAsStateWithLifecycle()
    val identity by AppRepository.identityInfo.collectAsStateWithLifecycle()
    val themeMode by AppRepository.themeMode.collectAsStateWithLifecycle()

    val grantedCount = listOf(accessibilityEnabled, overlayGranted, batteryWhitelisted).count { it }
    val serverStatus = when {
        !server.isComplete -> "未配置"
        connection == ServerConnectionState.ONLINE -> "已连接"
        connection == ServerConnectionState.OFFLINE -> "连接失败"
        else -> "待检测"
    }
    val serverColor = when {
        !server.isComplete -> AppTheme.extended.warning
        connection == ServerConnectionState.ONLINE -> AppTheme.extended.success
        connection == ServerConnectionState.OFFLINE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SectionEyebrow("连接")
                AppPanel {
                    Column {
                        SettingsEntry(
                            icon = Icons.Filled.Refresh,
                            title = "服务器连接",
                            subtitle = if (server.isComplete) {
                                "${server.ip}:${server.port}"
                            } else {
                                "填写 IP 与端口后测试连接"
                            },
                            accent = MaterialTheme.colorScheme.primary,
                            trailingText = serverStatus,
                            trailingColor = serverColor,
                            onClick = { onOpen(SettingsDestination.SERVER) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SectionEyebrow("运行")
                AppPanel {
                    Column {
                        SettingsEntry(
                            icon = Icons.Filled.Lock,
                            title = "权限管理",
                            subtitle = "无障碍、悬浮窗、电池白名单",
                            accent = AppTheme.extended.warning,
                            trailingText = "$grantedCount/3",
                            trailingColor = if (grantedCount == 3) {
                                AppTheme.extended.success
                            } else {
                                AppTheme.extended.warning
                            },
                            onClick = { onOpen(SettingsDestination.PERMISSIONS) },
                        )
                        AppDivider(Modifier.padding(start = 68.dp))
                        SettingsEntry(
                            icon = Icons.Filled.Build,
                            title = "自动化选项",
                            subtitle = "截图、投屏确认与识别置信度",
                            accent = MaterialTheme.colorScheme.secondary,
                            trailingText = "置信度 ${(confidence * 100).roundToInt()}%",
                            onClick = { onOpen(SettingsDestination.AUTOMATION) },
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SectionEyebrow("内容")
                AppPanel {
                    Column {
                        SettingsEntry(
                            icon = Icons.Filled.Person,
                            title = "身份信息",
                            subtitle = "实习单位、身份描述、今日事件与报告，作为 /AiText 提示词",
                            accent = MaterialTheme.colorScheme.tertiary,
                            trailingText = "已填 ${identity.filledCount}/6",
                            trailingColor = if (identity.filledCount == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                AppTheme.extended.success
                            },
                            onClick = { onOpen(SettingsDestination.IDENTITY) },
                        )
                        AppDivider(Modifier.padding(start = 68.dp))
                        SettingsEntry(
                            icon = Icons.Filled.Search,
                            title = "本地模板",
                            subtitle = "导入本机图片，作为 /See_Submit_Photo 的识别模板",
                            accent = MaterialTheme.colorScheme.secondary,
                            onClick = { onOpen(SettingsDestination.PHOTO) },
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SectionEyebrow("通用")
                AppPanel {
                    Column {
                        SettingsEntry(
                            icon = Icons.Filled.Star,
                            title = "外观主题",
                            subtitle = "跟随系统，或强制浅色 / 深色界面",
                            accent = MaterialTheme.colorScheme.primary,
                            trailingText = themeMode.label,
                            onClick = { onOpen(SettingsDestination.APPEARANCE) },
                        )
                        AppDivider(Modifier.padding(start = 68.dp))
                        SettingsEntry(
                            icon = Icons.Filled.Info,
                            title = "关于",
                            subtitle = "版本信息与项目说明",
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onOpen(SettingsDestination.ABOUT) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
internal fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    trailingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconPlate(icon = icon, tint = accent, size = 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailingText != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = trailingColor,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
