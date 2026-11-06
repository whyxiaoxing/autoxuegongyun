package com.xiaoxing.ui.pages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.ServerConnectionState
import com.xiaoxing.accessibility.AutomationAccessibilityService
import com.xiaoxing.config.IdentityInfo
import com.xiaoxing.config.IdentitySections
import com.xiaoxing.config.MATCH_CONFIDENCE_RANGE
import com.xiaoxing.config.ThemeMode
import com.xiaoxing.permissions.PermissionMonitor
import com.xiaoxing.photo.LocalTemplate
import com.xiaoxing.photo.LocalTemplateStore
import com.xiaoxing.ui.components.AppDivider
import com.xiaoxing.ui.components.AppPanel
import com.xiaoxing.ui.components.AppPanelColumn
import com.xiaoxing.ui.components.IconPlate
import com.xiaoxing.ui.components.MetaChip
import com.xiaoxing.ui.components.NumField
import com.xiaoxing.ui.components.StatusDot
import com.xiaoxing.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsDetailPage(
    destination: SettingsDestination,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    when (destination) {
        SettingsDestination.SERVER -> ServerSettingsDetail(onBack)
        SettingsDestination.OSS -> OssSettingsDetail(onBack)
        SettingsDestination.IDENTITY -> IdentitySettingsDetail(onBack)
        SettingsDestination.PHOTO -> PhotoTemplateSettingsDetail(onBack)
        SettingsDestination.PERMISSIONS -> PermissionsSettingsDetail(onBack)
        SettingsDestination.AUTOMATION -> AutomationSettingsDetail(onBack)
        SettingsDestination.APPEARANCE -> AppearanceSettingsDetail(onBack)
        SettingsDestination.ABOUT -> AboutSettingsDetail(onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

@Composable
private fun ServerSettingsDetail(onBack: () -> Unit) {
    val repo = AppRepository
    val scope = rememberCoroutineScope()
    val server by repo.serverConfig.collectAsStateWithLifecycle()
    val connection by repo.serverConnection.collectAsStateWithLifecycle()
    val connectionDetail by repo.serverConnectionDetail.collectAsStateWithLifecycle()

    var ipText by rememberSaveable(server.ip) { mutableStateOf(server.ip) }
    var portText by rememberSaveable(server.port) { mutableStateOf(server.port.toString()) }
    var busy by remember { mutableStateOf(false) }

    val statusColor = when {
        !server.isComplete -> AppTheme.extended.warning
        connection == ServerConnectionState.ONLINE -> AppTheme.extended.success
        connection == ServerConnectionState.OFFLINE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when {
        !server.isComplete -> "尚未配置"
        connection == ServerConnectionState.CHECKING -> "正在检测"
        connection == ServerConnectionState.ONLINE -> "连接正常"
        connection == ServerConnectionState.OFFLINE -> "连接失败"
        else -> "尚未检测"
    }

    DetailScaffold(
        title = "服务器连接",
        subtitle = "自动化服务地址与令牌",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(statusColor, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(statusLabel, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                !server.isComplete -> "填写 IP 与端口后即可检测连接"
                                connectionDetail.isNotBlank() -> connectionDetail
                                else -> server.baseUrl
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (connection == ServerConnectionState.CHECKING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            AppPanelColumn {
                Text("连接参数", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("服务端 IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                repo.saveServer(ipText, portText.toIntOrNull() ?: 0)
                                    .onSuccess {
                                        repo.showHint("服务端配置已保存", HintLevel.SUCCESS)
                                    }
                                    .onFailure { repo.showHint("保存失败: ${it.message}", HintLevel.ERROR) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                repo.saveServer(ipText, portText.toIntOrNull() ?: 0)
                                    .onFailure { repo.showHint("保存失败: ${it.message}", HintLevel.ERROR) }
                                repo.testConnection()
                                    .onSuccess { repo.showHint(it, HintLevel.SUCCESS) }
                                    .onFailure { repo.showHint("连接失败: ${it.message}", HintLevel.ERROR) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "检测中…" else "测试连接") }
                }
                Text(
                    "使用明文 HTTP。客户端会通过 /注册 获取令牌，并在 /See 中复用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// OSS
// ---------------------------------------------------------------------------

@Composable
private fun OssSettingsDetail(onBack: () -> Unit) {
    val repo = AppRepository
    val scope = rememberCoroutineScope()
    val oss by repo.ossConfig.collectAsStateWithLifecycle()
    val version by repo.resourceVersion.collectAsStateWithLifecycle()
    val lastSync by repo.lastSyncText.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }

    var endpoint by rememberSaveable(oss.endpoint) { mutableStateOf(oss.endpoint) }
    var bucket by rememberSaveable(oss.bucket) { mutableStateOf(oss.bucket) }
    var prefix by rememberSaveable(oss.prefix) { mutableStateOf(oss.prefix) }

    DetailScaffold(
        title = "资源同步",
        subtitle = "模板与工作流来自 OSS",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconPlate(Icons.Filled.Refresh, size = 42, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("当前资源", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "版本 ${version.ifBlank { "尚未同步" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (lastSync.isNotBlank()) {
                            Text(
                                lastSync,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MetaChip(if (oss.bucket.isBlank()) "未配置" else oss.bucket)
                }
            }

            AppPanelColumn {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it },
                    label = { Text("Bucket（公共读）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("对象前缀") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                repo.saveOss(endpoint, bucket, prefix)
                                    .onSuccess { repo.showHint("OSS 配置已保存", HintLevel.SUCCESS) }
                                    .onFailure { repo.showHint("保存失败: ${it.message}", HintLevel.ERROR) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                repo.saveOss(endpoint, bucket, prefix)
                                repo.syncResources()
                                    .onSuccess { repo.showHint("资源同步完成", HintLevel.SUCCESS) }
                                    .onFailure { repo.showHint("同步失败: ${it.message}", HintLevel.ERROR) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "同步中…" else "立即同步") }
                }
                Text(
                    "同步会校验 manifest 与 sha256，并把工作流写入本地引擎目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

@Composable
private fun PermissionsSettingsDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val accessibilityEnabled by PermissionMonitor.accessibilityEnabled.collectAsStateWithLifecycle()
    val overlayGranted by PermissionMonitor.overlayGranted.collectAsStateWithLifecycle()
    val batteryWhitelisted by PermissionMonitor.batteryWhitelisted.collectAsStateWithLifecycle()
    val notificationGranted = rememberNotificationGranted()

    DetailScaffold(
        title = "权限管理",
        subtitle = "运行自动化所需的系统入口",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Column {
                    PermissionSettingEntry(
                        icon = Icons.Filled.Build,
                        title = "无障碍服务",
                        description = "执行点击、滑动和全局动作",
                        granted = accessibilityEnabled,
                        onOpen = { AutomationAccessibilityService.openSettings(context) },
                    )
                    AppDivider(Modifier.padding(start = 68.dp))
                    PermissionSettingEntry(
                        icon = Icons.Filled.PlayArrow,
                        title = "悬浮窗",
                        description = "从后台拉起目标应用",
                        granted = overlayGranted,
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                    )
                    AppDivider(Modifier.padding(start = 68.dp))
                    PermissionSettingEntry(
                        icon = Icons.Filled.Lock,
                        title = "电池白名单",
                        description = "避免自动化期间被系统冻结",
                        granted = batteryWhitelisted,
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                    )
                    AppDivider(Modifier.padding(start = 68.dp))
                    PermissionSettingEntry(
                        icon = Icons.Filled.Notifications,
                        title = "通知权限",
                        description = "前台服务与投屏确认通知",
                        granted = notificationGranted,
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        },
                    )
                }
            }

            AppPanelColumn {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = AppTheme.extended.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("屏幕捕获", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "投屏授权每次启动都需要重新确认。部分厂商还会在通知栏追加「继续共享」，确认流程会在启动时引导。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "从系统设置返回后，本页状态会自动刷新。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun rememberNotificationGranted(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasNotificationPermission(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionSettingEntry(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    val color = if (granted) AppTheme.extended.success else AppTheme.extended.warning
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconPlate(icon = icon, tint = color, size = 40)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                StatusDot(color, Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (granted) "已开启" else "未开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) {
            Text(if (granted) "设置" else "去开启")
        }
    }
}

// ---------------------------------------------------------------------------
// Automation options
// ---------------------------------------------------------------------------

@Composable
private fun AutomationSettingsDetail(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val holdFrame by AppRepository.holdFrame.collectAsStateWithLifecycle()
    val saveScreenshots by AppRepository.saveScreenshots.collectAsStateWithLifecycle()
    val clickEffect by AppRepository.clickEffect.collectAsStateWithLifecycle()
    val skipProjectionConfirm by AppRepository.skipProjectionConfirm.collectAsStateWithLifecycle()
    val confidence by AppRepository.matchConfidence.collectAsStateWithLifecycle()

    // 滑杆拖动期间只改草稿，松手才落盘；避免每移动一像素写一次 DataStore
    var confidenceDraft by remember(confidence) { mutableStateOf(confidence) }

    DetailScaffold(
        title = "自动化选项",
        subtitle = "截图、投屏确认与识别参数",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Column {
                    OptionSwitchRow(
                        title = "保持帧",
                        description = "屏幕静止时复用上一帧截图，避免无谓上传。推荐开启。",
                        checked = holdFrame,
                        onCheckedChange = { scope.launch { AppRepository.setHoldFrame(it) } },
                    )
                    AppDivider(Modifier.padding(horizontal = 16.dp))
                    OptionSwitchRow(
                        title = "保存截图内容",
                        description = "每次捕获的 PNG 存入相册 DCIM/AutoGongxueyun，仅建议排查问题时开启。",
                        checked = saveScreenshots,
                        onCheckedChange = { scope.launch { AppRepository.setSaveScreenshots(it) } },
                    )
                    AppDivider(Modifier.padding(horizontal = 16.dp))
                    OptionSwitchRow(
                        title = "跳过投屏二次确认",
                        description = "不再等待通知栏「继续共享」确认，授权后直接执行工作流；厂商闸门未放行时识别会失败。",
                        checked = skipProjectionConfirm,
                        onCheckedChange = {
                            scope.launch { AppRepository.setSkipProjectionConfirm(it) }
                        },
                    )
                    AppDivider(Modifier.padding(horizontal = 16.dp))
                    OptionSwitchRow(
                        title = "点击位置效果",
                        description = "点击或滑动时在屏幕上标出实际坐标，便于排查命中偏移（需要悬浮窗权限）。",
                        checked = clickEffect,
                        onCheckedChange = { scope.launch { AppRepository.setClickEffect(it) } },
                    )
                }
            }

            AppPanelColumn {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("识别置信度", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "越高越严格，越低越容易命中；服务端默认 0.70。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    MetaChip("${(confidenceDraft * 100).roundToInt()}%")
                }
                Slider(
                    value = confidenceDraft,
                    onValueChange = { confidenceDraft = it },
                    onValueChangeFinished = {
                        scope.launch { AppRepository.setMatchConfidence(confidenceDraft) }
                    },
                    valueRange = MATCH_CONFIDENCE_RANGE,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${(MATCH_CONFIDENCE_RANGE.start * 100).roundToInt()}% · 宽松",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(MATCH_CONFIDENCE_RANGE.endInclusive * 100).roundToInt()}% · 严格",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "置信度随每次 /See 与 /See_Submit_Photo 请求一起发送；改动立即作用于后续识别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppPanelColumn {
                Text("启动顺序", style = MaterialTheme.typography.titleSmall)
                StepHint("1", "投屏授权与前台服务")
                StepHint("2", "通知栏二次确认（可跳过）")
                StepHint("3", "执行工作流步骤")
                Text(
                    "自动化运行期间会持有前台服务与部分 WakeLock，停止后自动释放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OptionSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepHint(index: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .then(
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

@Composable
private fun IdentitySettingsDetail(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val identity by AppRepository.identityInfo.collectAsStateWithLifecycle()

    var unit by rememberSaveable(identity.unit) { mutableStateOf(identity.unit) }
    var role by rememberSaveable(identity.role) { mutableStateOf(identity.role) }
    var todayEvents by rememberSaveable(identity.todayEvents) { mutableStateOf(identity.todayEvents) }
    var dailyReport by rememberSaveable(identity.dailyReport) { mutableStateOf(identity.dailyReport) }
    var weeklyReport by rememberSaveable(identity.weeklyReport) { mutableStateOf(identity.weeklyReport) }
    var monthlyReport by rememberSaveable(identity.monthlyReport) { mutableStateOf(identity.monthlyReport) }
    var saving by remember { mutableStateOf(false) }

    DetailScaffold(
        title = "身份信息",
        subtitle = "AI 文案步骤的提示词素材",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconPlate(Icons.Filled.Person, size = 42, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("提示词素材", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }

            AppPanelColumn {
                Text("基本身份", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("实习单位") },
                    placeholder = { Text(IdentitySections.all[0].placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("身份描述") },
                    placeholder = { Text(IdentitySections.all[1].placeholder) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AppPanelColumn {
                Text("内容素材", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = todayEvents,
                    onValueChange = { todayEvents = it },
                    label = { Text("今日事件") },
                    placeholder = { Text(IdentitySections.all[2].placeholder) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dailyReport,
                    onValueChange = { dailyReport = it },
                    label = { Text("日报") },
                    placeholder = { Text(IdentitySections.all[3].placeholder) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = weeklyReport,
                    onValueChange = { weeklyReport = it },
                    label = { Text("周报") },
                    placeholder = { Text(IdentitySections.all[4].placeholder) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = monthlyReport,
                    onValueChange = { monthlyReport = it },
                    label = { Text("月报") },
                    placeholder = { Text(IdentitySections.all[5].placeholder) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        AppRepository.saveIdentity(
                            IdentityInfo(
                                unit = unit,
                                role = role,
                                todayEvents = todayEvents,
                                dailyReport = dailyReport,
                                weeklyReport = weeklyReport,
                                monthlyReport = monthlyReport,
                            ),
                        )
                            .onSuccess {
                                AppRepository.showHint("身份信息已保存", HintLevel.SUCCESS)
                            }
                            .onFailure {
                                AppRepository.showHint("保存失败: ${it.message}", HintLevel.ERROR)
                            }
                        saving = false
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (saving) "保存中…" else "保存身份信息")
            }

            Text(
                "内容只保存在本机，并随提示词发送给自建服务端的 /AiText 路由。空白段在拼接时会自动跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Local photo templates
// ---------------------------------------------------------------------------

@Composable
private fun PhotoTemplateSettingsDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<LocalTemplate>>(emptyList()) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<LocalTemplate?>(null) }
    var toDelete by remember { mutableStateOf<LocalTemplate?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        templates = LocalTemplateStore.list(context)
    }

    LaunchedEffect(context) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingUri = uri
            nameDraft = LocalTemplateStore.suggestedName(queryDisplayName(context, uri))
        }
    }

    DetailScaffold(
        title = "本地模板",
        subtitle = "/See_Submit_Photo 的图片素材",
        onBack = onBack,
    ) {
        // 懒加载：只有可见行才解码缩略图。旧实现用 verticalScroll + 一次性 forEach，
        // 模板越多，峰值内存越高（每张缩略图都同时驻留）。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppPanel {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconPlate(Icons.Filled.Search, size = 42, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("本机图片模板", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "模板只保存在应用私有目录；匹配时与当前截图一起提交给服务端，不依赖服务端 Photo/ 目录或 OSS。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("从相册导入图片模板")
                }
            }

            if (templates.isEmpty()) {
                item {
                    AppPanel {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("还没有本地模板", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "导入一张裁剪好的按钮/图标截图，即可在工作流中添加「图片匹配」步骤。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(templates, key = { it.name }) { template ->
                    AppPanel {
                        TemplateRow(
                            context = context,
                            template = template,
                            onPreview = { preview = template },
                            onDelete = { toDelete = template },
                        )
                    }
                }
            }

            item {
                Text(
                    "建议使用从目标应用截取的原始像素图（不超过 ${LocalTemplateStore.MAX_EDGE}px），裁剪范围越精确，匹配越稳定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    val selectedUri = pendingUri
    if (selectedUri != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingUri = null },
            title = { Text("导入本地模板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("模板名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "名称不能重复；导入后可在工作流的「图片匹配」步骤中选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = nameDraft.trim()
                        if (name.isBlank()) {
                            AppRepository.showHint("请先填写模板名称", HintLevel.WARNING)
                            return@TextButton
                        }
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                LocalTemplateStore.importFromUri(context, name, selectedUri)
                            }
                            result
                                .onSuccess {
                                    pendingUri = null
                                    reload()
                                    AppRepository.showHint("已导入模板「${it.name}」", HintLevel.SUCCESS)
                                }
                                .onFailure {
                                    AppRepository.showHint("导入失败: ${it.message}", HintLevel.ERROR)
                                }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "导入中…" else "导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUri = null }, enabled = !busy) { Text("取消") }
            },
        )
    }

    preview?.let { template ->
        PhotoPreviewDialog(context = context, template = template, onDismiss = { preview = null })
    }

    toDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除本地模板？") },
            text = { Text("「${template.name}」将从本机删除，引用它的工作流步骤会匹配失败。") },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = LocalTemplateStore.delete(context, template.name)
                    toDelete = null
                    reload()
                    if (deleted) {
                        AppRepository.showHint("已删除模板「${template.name}」", HintLevel.SUCCESS)
                    } else {
                        AppRepository.showHint("删除失败：文件可能已被移除", HintLevel.ERROR)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TemplateRow(
    context: Context,
    template: LocalTemplate,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TemplateThumbnail(context = context, template = template)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                template.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatBytes(template.sizeBytes)} · 点击预览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除模板 ${template.name}",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TemplateThumbnail(context: Context, template: LocalTemplate) {
    // 按实际显示尺寸（52dp）解码一次，不再先压成 PNG 再解码
    val targetPx = with(LocalDensity.current) { 52.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(null, template.name, targetPx) {
        value = withContext(Dispatchers.IO) {
            LocalTemplateStore.decodeBitmap(context, template.name, targetPx)?.asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun PhotoPreviewDialog(context: Context, template: LocalTemplate, onDismiss: () -> Unit) {
    // 预览按屏幕宽度与 720px 的较小值解码，避免 1080px 大图常驻
    val maxEdge = minOf(720, context.resources.displayMetrics.widthPixels).coerceAtLeast(1)
    val image by produceState<ImageBitmap?>(null, template.name, maxEdge) {
        value = withContext(Dispatchers.IO) {
            LocalTemplateStore.decodeBitmap(context, template.name, maxEdge)?.asImageBitmap()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.name) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = image
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "模板预览 ${template.name}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
}.getOrNull()

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ---------------------------------------------------------------------------
// Appearance
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceSettingsDetail(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val themeMode by AppRepository.themeMode.collectAsStateWithLifecycle()

    DetailScaffold(
        title = "外观主题",
        subtitle = "浅色 / 深色 / 跟随系统",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Column {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        val description = when (mode) {
                            ThemeMode.SYSTEM -> "跟随手机系统的深色模式自动切换"
                            ThemeMode.LIGHT -> "始终使用浅色界面"
                            ThemeMode.DARK -> "始终使用深色界面"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (themeMode != mode) {
                                        scope.launch { AppRepository.setThemeMode(mode) }
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(mode.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (themeMode == mode) {
                                MetaChip("当前")
                            }
                        }
                        if (index < ThemeMode.entries.lastIndex) {
                            AppDivider(Modifier.padding(start = 62.dp))
                        }
                    }
                }
            }

            Text(
                "切换会立即应用到整个应用（含状态栏与导航栏图标），无需重启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

@Composable
private fun AboutSettingsDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    DetailScaffold(
        title = "关于",
        subtitle = "项目信息",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppPanel {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconPlate(Icons.Filled.Build, size = 58)
                    Spacer(Modifier.height(12.dp))
                    Text("工学云自动化", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "模板匹配驱动的 Android 自动化控制台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    MetaChip("版本 $version")
                }
            }

            AppPanelColumn {
                Text("应用信息", style = MaterialTheme.typography.titleSmall)
                InfoLine("包名", context.packageName)
                AppDivider()
                InfoLine("运行环境", "Android ${android.os.Build.VERSION.RELEASE}")
                AppDivider()
                InfoLine("自动化引擎", "MediaProjection + Accessibility + /See")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

