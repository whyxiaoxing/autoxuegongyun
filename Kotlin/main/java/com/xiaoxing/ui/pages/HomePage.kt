package com.xiaoxing.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    onRequestStart: (workflowNames: List<String>) -> Unit,
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

    // 多选：列表顺序 = 执行顺序。ArrayList 经 listSaver 保存，旋转/切页后仍保留选择。
    var selectedNames by rememberSaveable(
        stateSaver = listSaver(
            save = { it },
            restore = { it },
        ),
    ) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(workflows) {
        val stillValid = selectedNames.filter { it in workflows }
        selectedNames = when {
            workflows.isEmpty() -> emptyList()
            stillValid.isEmpty() -> listOf(workflows.first())
            stillValid.size != selectedNames.size -> stillValid
            else -> selectedNames
        }
    }

    val selectedSummaries = summaries.filter { it.name in selectedNames }

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
            selectedNames = selectedNames,
            selectedSummaries = selectedSummaries,
            workflows = workflows,
            summaries = summaries,
            serverConfigured = server.isComplete,
            accessibilityEnabled = accessibilityEnabled,
            onSelect = { selectedNames = it },
            onStart = {
                PermissionMonitor.refresh(context)
                val error = when {
                    !server.isComplete -> "请先在设置中配置服务器 IP 与端口"
                    selectedNames.isEmpty() -> "请先选择要运行的流程"
                    isRunning -> "已有自动化在运行"
                    !PermissionMonitor.accessibilityEnabled.value -> "请先开启无障碍服务"
                    selectedSummaries.any { it.parseFailed } ->
                        "所选流程中有文件无法解析，请重新编辑保存"

                    selectedSummaries.any { it.stepCount == 0 } ->
                        "所选流程中有空流程，请先添加步骤"

                    else -> null
                }
                if (error != null) {
                    repo.showHint(error, HintLevel.WARNING)
                } else {
                    val target = if (selectedNames.size > 1) {
                        " ${selectedNames.size} 条流程"
                    } else {
                        "工作流"
                    }
                    repo.showHint("等待投屏授权…（将执行$target，捕获整个屏幕）", HintLevel.INFO)
                    onRequestStart(selectedNames)
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
    selectedNames: List<String>,
    selectedSummaries: List<WorkflowSummary>,
    workflows: List<String>,
    summaries: List<WorkflowSummary>,
    serverConfigured: Boolean,
    accessibilityEnabled: Boolean,
    onSelect: (List<String>) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workflowReady = selectedSummaries.isNotEmpty() &&
        selectedSummaries.all { it.stepCount > 0 && !it.parseFailed }
    val idleHint = when {
        !serverConfigured -> "下一步：在设置中填写服务器 IP 与端口"
        !workflowReady -> "下一步：选择一条包含步骤的流程"
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
                        when {
                            isRunning && selectedNames.size > 1 ->
                                "正在依次执行 ${selectedNames.size} 条流程，请保持投屏共享"

                            isRunning -> "任务正在执行，请保持投屏共享"
                            else -> "可勾选多条流程，按顺序依次执行"
                        },
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
                selectedNames = selectedNames,
                workflows = workflows,
                summaries = summaries,
                enabled = !isRunning,
                onSelect = onSelect,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    workflows.isEmpty() -> MetaChip("暂无工作流")
                    selectedSummaries.isEmpty() -> MetaChip("未选择流程")
                    else -> {
                        MetaChip("${selectedSummaries.size} 条流程")
                        MetaChip("共 ${selectedSummaries.sumOf { it.stepCount }} 个步骤")
                        if (selectedSummaries.any { it.parseFailed }) MetaChip("文件异常")
                    }
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
    selectedNames: List<String>,
    workflows: List<String>,
    summaries: List<WorkflowSummary>,
    enabled: Boolean,
    onSelect: (List<String>) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val selectedSteps = summaries.filter { it.name in selectedNames }.sumOf { it.stepCount }
    val subtitle = when {
        !enabled -> "运行中不可切换，请先停止"
        workflows.isEmpty() -> "还没有可运行的流程"
        selectedNames.isEmpty() -> "点按勾选要执行的流程"
        selectedNames.size == 1 -> "共 $selectedSteps 个步骤"
        else -> "${selectedNames.size} 条流程 · 按勾选顺序依次执行"
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "执行工作流",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (selectedNames.size > 1) {
                Text(
                    "按顺序执行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Surface(
            onClick = { if (enabled) showPicker = true },
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                if (showPicker) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
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
                        selectedNames.joinToString("、").ifBlank { "选择工作流" },
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
    }

    if (showPicker) {
        WorkflowPickerDialog(
            selected = selectedNames,
            workflows = workflows,
            summaries = summaries,
            onDismiss = { showPicker = false },
            onConfirm = {
                onSelect(it)
                showPicker = false
            },
        )
    }
}

/**
 * 多流程选择：勾选顺序即执行顺序（右侧序号）。
 * 选择结果在点「执行所选」时才写回，取消不会改动现有选择。
 */
@Composable
private fun WorkflowPickerDialog(
    selected: List<String>,
    workflows: List<String>,
    summaries: List<WorkflowSummary>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var draft by remember(selected) { mutableStateOf(selected) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 580.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择执行流程", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "勾选顺序即执行顺序 · 已选 ${draft.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { draft = workflows }) { Text("全选") }
                    TextButton(onClick = { draft = emptyList() }) { Text("清空") }
                }

                if (workflows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无工作流，请到「流程」页添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(workflows, key = { it }) { name ->
                            val order = draft.indexOf(name)
                            WorkflowPickRow(
                                name = name,
                                summary = summaries.firstOrNull { it.name == name },
                                order = order,
                                onClick = {
                                    draft = if (order >= 0) {
                                        draft - name
                                    } else {
                                        draft + name
                                    }
                                },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${draft.size} 条流程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onConfirm(draft) },
                        enabled = draft.isNotEmpty(),
                    ) { Text("执行所选") }
                }
            }
        }
    }
}

@Composable
private fun WorkflowPickRow(
    name: String,
    summary: WorkflowSummary?,
    order: Int,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val picked = order >= 0
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (picked) scheme.primary.copy(alpha = 0.10f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = picked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("${summary?.stepCount ?: 0} 个步骤")
                        if (summary?.parseFailed == true) append(" · 文件异常")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (picked) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${order + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimary,
                    )
                }
            }
        }
    }
}
