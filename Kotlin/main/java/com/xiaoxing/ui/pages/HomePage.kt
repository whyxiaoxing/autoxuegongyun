package com.xiaoxing.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.ServerConnectionState
import com.xiaoxing.permissions.PermissionMonitor
import com.xiaoxing.ui.components.AppPanel
import com.xiaoxing.ui.components.IconPlate
import com.xiaoxing.ui.components.MetaChip
import com.xiaoxing.ui.components.PermissionLightRow
import com.xiaoxing.ui.components.StatusDot
import com.xiaoxing.ui.components.StatusPill
import com.xiaoxing.ui.theme.AppTheme
import com.xiaoxing.workflow.WorkflowSummary
import kotlinx.coroutines.launch

/**
 * Home is deliberately reduced to two panels:
 *  1) a 35% status rail (server above, permission lights below);
 *  2) a 65% control surface (workflow selection, start/stop, live hint).
 */
@Composable
internal fun HomePage(
    onRequestStart: (workflowName: String) -> Unit,
    onStop: () -> Unit,
    onOpenServerSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val repo = AppRepository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val isRunning by repo.isRunning.collectAsStateWithLifecycle()
    val engineStatus by repo.engineStatus.collectAsStateWithLifecycle()
    val server by repo.serverConfig.collectAsStateWithLifecycle()
    val connection by repo.serverConnection.collectAsStateWithLifecycle()
    val connectionDetail by repo.serverConnectionDetail.collectAsStateWithLifecycle()
    val workflows by repo.workflows.collectAsStateWithLifecycle()
    val summaries by repo.workflowSummaries.collectAsStateWithLifecycle()
    val accessibilityEnabled by PermissionMonitor.accessibilityEnabled.collectAsStateWithLifecycle()
    val overlayGranted by PermissionMonitor.overlayGranted.collectAsStateWithLifecycle()
    val batteryWhitelisted by PermissionMonitor.batteryWhitelisted.collectAsStateWithLifecycle()

    var selectedName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(workflows) {
        if (selectedName.isBlank() || selectedName !in workflows) {
            selectedName = workflows.firstOrNull().orEmpty()
        }
    }

    val selectedSummary = summaries.firstOrNull { it.name == selectedName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(4f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerStatusBlock(
                modifier = Modifier.weight(0.9f),
                serverAddress = server.baseUrl,
                configured = server.isComplete,
                connection = connection,
                connectionDetail = connectionDetail,
                isRunning = isRunning,
                onCheck = {
                    if (!server.isComplete) {
                        onOpenServerSettings()
                    } else {
                        scope.launch {
                            repo.testConnection()
                                .onSuccess { repo.showHint(it, HintLevel.SUCCESS) }
                                .onFailure { repo.showHint("连接失败: ${it.message}", HintLevel.ERROR) }
                        }
                    }
                },
            )
            PermissionsBlock(
                modifier = Modifier.weight(1.15f),
                accessibilityEnabled = accessibilityEnabled,
                overlayGranted = overlayGranted,
                batteryWhitelisted = batteryWhitelisted,
                onOpenPermissions = onOpenPermissions,
            )
        }

        ControlBlock(
            modifier = Modifier.weight(6f),
            isRunning = isRunning,
            engineStatus = engineStatus,
            selectedName = selectedName,
            selectedSummary = selectedSummary,
            workflows = workflows,
            summaries = summaries,
            serverConfigured = server.isComplete,
            accessibilityEnabled = accessibilityEnabled,
            onSelect = { selectedName = it },
            onStart = {
                PermissionMonitor.refresh(context)
                val error = when {
                    !server.isComplete -> "请先在设置中配置服务器 IP 与端口"
                    selectedName.isBlank() -> "请先选择要运行的工作流"
                    isRunning -> "已有自动化在运行"
                    !PermissionMonitor.accessibilityEnabled.value -> "请先开启无障碍服务"
                    selectedSummary?.parseFailed == true -> "工作流文件无法解析，请重新编辑保存"
                    selectedSummary?.stepCount == 0 -> "该工作流还没有步骤，请先添加步骤"
                    else -> null
                }
                if (error != null) {
                    repo.showHint(error, HintLevel.WARNING)
                } else {
                    repo.showHint("等待投屏授权…（将捕获整个屏幕）", HintLevel.INFO)
                    onRequestStart(selectedName)
                }
            },
            onStop = {
                onStop()
                repo.showHint("已请求停止", HintLevel.INFO)
            },
        )
    }
}

@Composable
private fun ServerStatusBlock(
    serverAddress: String,
    configured: Boolean,
    connection: ServerConnectionState,
    connectionDetail: String,
    isRunning: Boolean,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extended = AppTheme.extended
    val (label, color) = when {
        !configured -> "未配置" to extended.warning
        connection == ServerConnectionState.CHECKING -> "检测中" to MaterialTheme.colorScheme.primary
        connection == ServerConnectionState.ONLINE || isRunning -> "已连接" to extended.success
        connection == ServerConnectionState.OFFLINE -> "连接失败" to MaterialTheme.colorScheme.error
        else -> "待连接" to extended.warning
    }
    val secondary = when {
        !configured -> "前往设置填写服务器地址"
        connection == ServerConnectionState.OFFLINE && connectionDetail.isNotBlank() -> connectionDetail
        else -> serverAddress
    }

    AppPanel(modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "服务器连接",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onCheck,
                enabled = connection != ServerConnectionState.CHECKING,
                modifier = Modifier.size(44.dp),
            ) {
                if (connection == ServerConnectionState.CHECKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = if (configured) "检测服务器连接" else "前往配置服务器",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsBlock(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    batteryWhitelisted: Boolean,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpenPermissions,
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "权限状态",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "管理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                PermissionLightRow("无障碍服务", accessibilityEnabled)
                PermissionLightRow("悬浮窗", overlayGranted)
                PermissionLightRow("电池白名单", batteryWhitelisted)
            }
        }
    }
}

@Composable
private fun ControlBlock(
    isRunning: Boolean,
    engineStatus: String,
    selectedName: String,
    selectedSummary: WorkflowSummary?,
    workflows: List<String>,
    summaries: List<WorkflowSummary>,
    serverConfigured: Boolean,
    accessibilityEnabled: Boolean,
    onSelect: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workflowReady = selectedSummary != null &&
        selectedSummary.stepCount > 0 &&
        !selectedSummary.parseFailed
    val idleHint = when {
        !serverConfigured -> "下一步：在设置中填写服务器 IP 与端口"
        !workflowReady -> "下一步：添加一条包含步骤的工作流"
        !accessibilityEnabled -> "下一步：在设置中开启无障碍服务"
        else -> "一切就绪，点击启动后按提示完成投屏授权"
    }

    AppPanel(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动化控制", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isRunning) "任务正在执行，请保持投屏共享" else "选择一条流程，然后开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(
                    text = if (isRunning) "运行中" else "待机",
                    color = if (isRunning) AppTheme.extended.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            WorkflowSelector(
                selectedName = selectedName,
                selectedSummary = selectedSummary,
                workflows = workflows,
                summaries = summaries,
                enabled = !isRunning,
                onSelect = onSelect,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedSummary == null) {
                    MetaChip("暂无工作流")
                } else {
                    MetaChip("${selectedSummary.stepCount} 个步骤")
                    MetaChip(
                        selectedSummary.targetPackage
                            .takeIf { it.isNotBlank() }
                            ?.substringAfterLast('.')
                            ?: "未指定目标应用",
                    )
                    if (selectedSummary.parseFailed) MetaChip("文件异常")
                }
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !isRunning && workflows.isNotEmpty(),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    ),
                    modifier = Modifier
                        .weight(1.45f)
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "运行中" else "启动", style = MaterialTheme.typography.titleSmall)
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = isRunning,
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isRunning) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止", style = MaterialTheme.typography.titleSmall)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (isRunning) engineStatus.ifBlank { "工作流运行中" } else idleHint,
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    !serverConfigured || !workflowReady || !accessibilityEnabled
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    AppTheme.extended.success
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WorkflowSelector(
    selectedName: String,
    selectedSummary: WorkflowSummary?,
    workflows: List<String>,
    summaries: List<WorkflowSummary>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val subtitle = when {
        !enabled -> "运行中不可切换，请先停止"
        selectedName.isBlank() -> "还没有可运行的流程"
        selectedSummary == null -> "正在读取流程信息…"
        selectedSummary.targetPackage.isBlank() -> "未指定目标应用 · ${selectedSummary.stepCount} 个步骤"
        else -> "${selectedSummary.targetPackage} · ${selectedSummary.stepCount} 个步骤"
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "执行工作流",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Surface(
                onClick = { expanded = true },
                enabled = enabled,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconPlate(
                        icon = Icons.AutoMirrored.Filled.List,
                        size = 38,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            selectedName.ifBlank { "选择工作流" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (workflows.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("暂无工作流，请到「流程」页添加") },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                }
                workflows.forEach { name ->
                    val summary = summaries.firstOrNull { it.name == name }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${summary?.stepCount ?: 0} 个步骤",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
