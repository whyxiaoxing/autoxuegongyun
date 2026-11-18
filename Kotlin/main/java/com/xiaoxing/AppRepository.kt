package com.xiaoxing

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import com.xiaoxing.accessibility.ClickEffectOverlay
import com.xiaoxing.capture.ScreenCaptureManager
import com.xiaoxing.config.DEFAULT_MATCH_CONFIDENCE
import com.xiaoxing.config.FailureAlertSettings
import com.xiaoxing.config.IdentityInfo
import com.xiaoxing.config.MATCH_CONFIDENCE_RANGE
import com.xiaoxing.config.OssConfig
import com.xiaoxing.config.ServerConfig
import com.xiaoxing.config.SettingsStore
import com.xiaoxing.config.ThemeMode
import com.xiaoxing.concurrent.AppExecutors
import com.xiaoxing.log.AppLog
import com.xiaoxing.network.AiTextClient
import com.xiaoxing.network.OcrClient
import com.xiaoxing.network.SeeClient
import com.xiaoxing.network.TemplateNameClient
import com.xiaoxing.network.TokenManager
import com.xiaoxing.oss.OssResourceRepository
import com.xiaoxing.permissions.PermissionMonitor
import com.xiaoxing.service.KeepAliveWorker
import com.xiaoxing.workflow.TemplateRegistry
import com.xiaoxing.workflow.WorkflowRepository
import com.xiaoxing.workflow.WorkflowSummary
import com.xiaoxing.workflow.effectiveTargetPackage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏「第二层闸门」状态。
 *
 * Android 只给一层授权（MediaProjection 系统弹窗「开始录制/共享」）；部分厂商 ROM（MIUI/HyperOS 等）
 * 还会在通知栏再加一道「继续共享」，未点之前投屏画面会被隐私保护模糊成圆角卡片 —— 识别必然失败。
 * 该状态**没有公共 API 可查询**（MediaProjectionManager / MediaProjection.Callback 均无相关方法，
 * 只有授权的 OK/CANCEL 与 onCapturedContentResize/VisibilityChanged），所以由本应用补一道显式确认：
 * 页面弹窗 → 用户下拉通知栏点「继续共享」→ 回到本页点确认 → 才允许进入工作流。
 *
 * NONE      不在确认流程中
 * AWAITING  投屏已建立，等待用户确认（页面弹窗 + 实时预览）
 * CONFIRMED 用户已确认，可以进入工作流
 * CANCELLED 用户取消 / 投屏被撤销 / 超时
 */
enum class ProjectionGate { NONE, AWAITING, CONFIRMED, CANCELLED }

/** Last known server reachability. UNKNOWN only means "not checked this session". */
enum class ServerConnectionState { UNKNOWN, CHECKING, ONLINE, OFFLINE }

/** 顶层提示级别：决定弹窗颜色与是否自动消失（错误必须手动关闭） */
enum class HintLevel { INFO, SUCCESS, WARNING, ERROR }

/** 一条顶层提示。id 递增，保证同文案连续提示也能重新触发动画/计时。 */
@Immutable
data class AppHint(
    val id: Long,
    val message: String,
    val level: HintLevel,
)

/**
 * 全局状态仓库（单例）：页面与前台服务共享。
 * 页面可修改项（服务端 IP / 端口、OSS、目标包名、工作流）全部经此持久化到 DataStore / filesDir。
 */
object AppRepository {

    // ---- 页面可修改并持久化的状态 ----
    val serverConfig = MutableStateFlow(ServerConfig())
    val ossConfig = MutableStateFlow(OssConfig())
    val targetPackage = MutableStateFlow("")
    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val identityInfo = MutableStateFlow(IdentityInfo())

    // ---- 运行状态 / 展示 ----
    val workflows = MutableStateFlow<List<String>>(emptyList())
    val workflowSummaries = MutableStateFlow<List<WorkflowSummary>>(emptyList())
    val isRunning = MutableStateFlow(false)
    val engineStatus = MutableStateFlow("")
    val resourceVersion = MutableStateFlow("")
    val lastSyncText = MutableStateFlow("")
    val serverConnection = MutableStateFlow(ServerConnectionState.UNKNOWN)
    val serverConnectionDetail = MutableStateFlow("")

    /**
     * 顶层提示弹窗的当前内容。用 StateFlow 而不是事件流：
     * 自动化在后台报错时用户可能不在页面上，状态保留到用户回到应用再展示，避免提示丢失。
     */
    val hint = MutableStateFlow<AppHint?>(null)

    private val hintSeq = AtomicLong(0L)

    /** 自动连接探测每会话最多一次；保存新地址后重置。 */
    private val autoCheckStarted = AtomicBoolean(false)

    /** 截图「保持帧」：屏幕静止不出新帧时复用上一帧（页面开关，默认开） */
    val holdFrame = MutableStateFlow(true)

    /** 截图「保存内容」：每次捕获的 PNG 经 MediaStore 存入相册 DCIM/AutoGongxueyun（页面开关，默认关） */
    val saveScreenshots = MutableStateFlow(false)

    /** 「点击位置效果」：自动化点击/滑动时在屏幕上标出实际坐标（页面开关，默认关） */
    val clickEffect = MutableStateFlow(false)

    /** 「跳过投屏二次确认」：不等待通知栏「继续共享」确认直接执行（页面开关，默认关） */
    val skipProjectionConfirm = MutableStateFlow(false)

    /** 失败提醒：震动/铃声开关与自定义铃声（「提醒」一级导航页可调） */
    val failureAlertSettings = MutableStateFlow(FailureAlertSettings())

    /** 识别模板名（服务端 /GetMatchName；刷新按钮走 /UpdataPhotoName） */
    val templateNames = MutableStateFlow<List<String>>(emptyList())
    val templateNamesLoading = MutableStateFlow(false)
    val templateNamesError = MutableStateFlow("")

    /** 识别置信度：随 /See 与 /See_Submit_Photo 请求发给服务端（默认 0.7） */
    val matchConfidence = MutableStateFlow(DEFAULT_MATCH_CONFIDENCE)

    /** 投屏二次确认状态：页面向下弹窗 ←→ 前台服务等待，二者共享（跨主线程/采屏线程读写） */
    val projectionGate = MutableStateFlow(ProjectionGate.NONE)

    /**
     * 二次确认期间的投屏预览缩略图（仅 [ProjectionGate.AWAITING] 时由采屏线程发布，约 1 张/秒）。
     * 用户据此自己判断厂商闸门是否已放行：预览糊 = 还没点「继续共享」。
     */
    val projectionPreview = MutableStateFlow<Bitmap?>(null)

    /** launch 步骤成功后通知 Activity 最小化自身窗口 */
    val minimizeRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val tokenManager = TokenManager { serverConfig.value.baseUrl }
    val seeClient by lazy {
        SeeClient(
            tokenManager = tokenManager,
            confidenceProvider = { matchConfidence.value },
        ) { serverConfig.value.baseUrl }
    }
    val aiTextClient by lazy { AiTextClient(tokenManager) { serverConfig.value.baseUrl } }
    val ocrClient by lazy { OcrClient(tokenManager) { serverConfig.value.baseUrl } }
    val templateNameClient by lazy {
        TemplateNameClient(tokenManager) { serverConfig.value.baseUrl }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        SettingsStore.init(context)
        if (appContext != null) return
        appContext = context.applicationContext
        val ctx = appContext!!

        PermissionMonitor.start(ctx)
        scope.launch {
            SettingsStore.serverConfig.collect {
                serverConfig.value = it
                // 一次会话最多自动探测一次；不再由 HomePage 的组合触发射。
                ensureServerConnectionChecked()
                ensureTemplateNamesLoaded()
            }
        }
        scope.launch {
            SettingsStore.ossConfig.collect {
                ossConfig.value = it
                resourceVersion.value = OssResourceRepository.cachedVersion(ctx) ?: ""
            }
        }
        scope.launch { SettingsStore.targetPackage.collect { targetPackage.value = it } }
        scope.launch { SettingsStore.holdFrame.collect { holdFrame.value = it } }
        scope.launch { SettingsStore.saveScreenshots.collect { saveScreenshots.value = it } }
        scope.launch { SettingsStore.clickEffect.collect { clickEffect.value = it } }
        scope.launch {
            SettingsStore.skipProjectionConfirm.collect { skipProjectionConfirm.value = it }
        }
        scope.launch {
            SettingsStore.failureAlertSettings.collect { failureAlertSettings.value = it }
        }
        scope.launch { SettingsStore.matchConfidence.collect { matchConfidence.value = it } }
        scope.launch { SettingsStore.themeMode.collect { themeMode.value = it } }
        scope.launch { SettingsStore.identityInfo.collect { identityInfo.value = it } }
        scope.launch { refreshWorkflows(ctx) }
        // WorkManager 首次 getInstance 会做磁盘初始化，不在 Application.onCreate 主线程上等
        scope.launch { KeepAliveWorker.schedule(ctx) }
    }

    /**
     * 自动连接探测（幂等）：配置完整且本次会话尚未探测过时，后台跑一次 [testConnection]。
     * 由 DataStore 配置收集器触发，页面组合不再发起网络请求。
     */
    fun ensureServerConnectionChecked() {
        if (!serverConfig.value.isComplete) return
        if (serverConnection.value != ServerConnectionState.UNKNOWN) return
        if (!autoCheckStarted.compareAndSet(false, true)) return
        scope.launch { runCatching { testConnection() } }
    }

    /**
     * 模板名懒加载（幂等）：已加载或正在加载时直接返回。
     * 由服务端配置收集器与模板选择器共同触发，不阻塞页面。
     */
    fun ensureTemplateNamesLoaded() {
        if (!serverConfig.value.isComplete) return
        if (templateNames.value.isNotEmpty() || templateNamesLoading.value) return
        scope.launch { loadTemplateNames() }
    }

    /** 读取服务端缓存的模板名（/GetMatchName） */
    suspend fun loadTemplateNames(): Result<Unit> = fetchTemplateNames { templateNameClient.list() }

    /** 刷新按钮：让服务端重新扫描 Photo/ 目录（/UpdataPhotoName） */
    suspend fun refreshTemplateNames(): Result<Unit> =
        fetchTemplateNames { templateNameClient.refresh() }

    private suspend fun fetchTemplateNames(request: suspend () -> List<String>): Result<Unit> {
        if (templateNamesLoading.value) {
            return Result.failure(IllegalStateException("正在获取模板列表"))
        }
        templateNamesLoading.value = true
        templateNamesError.value = ""
        return runCatching {
            val names = request()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            TemplateRegistry.replace(names)
            templateNames.value = names
            templateNamesError.value = ""
        }.onFailure {
            templateNamesError.value = it.message ?: "获取模板列表失败"
        }.also {
            templateNamesLoading.value = false
        }
    }

    private fun clearTemplateNames() {
        TemplateRegistry.replace(emptyList())
        templateNames.value = emptyList()
        templateNamesError.value = ""
    }

    /**
     * 顶层提示：所有反馈（校验、保存结果、自动化错误）统一走这里。
     * 错误级别不会自动消失，必须由用户关闭，保证后台故障回到页面时仍然可见。
     */
    fun showHint(message: String, level: HintLevel = HintLevel.INFO) {
        val text = message.trim()
        if (text.isBlank()) return
        // 所有面向用户的反馈同时进运行日志，方便事后回看本次运行发生了什么
        when (level) {
            HintLevel.INFO, HintLevel.SUCCESS -> AppLog.info("提示", text)
            HintLevel.WARNING -> AppLog.warn("提示", text)
            HintLevel.ERROR -> AppLog.error("提示", text)
        }
        hint.value = AppHint(hintSeq.incrementAndGet(), text, level)
    }

    fun dismissHint() {
        hint.value = null
    }

    fun requestMinimize() {
        minimizeRequests.tryEmit(Unit)
    }

    /**
     * 切换投屏二次确认状态。
     * 离开 AWAITING 时顺手丢掉预览图，避免确认框关掉后仍长期持有一张大 Bitmap。
     */
    fun setProjectionGate(state: ProjectionGate) {
        projectionGate.value = state
        if (state != ProjectionGate.AWAITING) projectionPreview.value = null
    }

    fun setRunning(value: Boolean) {
        isRunning.value = value
    }

    /**
     * 进程级内存压力回调（Application.onTrimMemory / onLowMemory）。
     * 只释放可重建的缓存；不清工作流、设置、提示等业务状态。
     */
    @Suppress("UNUSED_PARAMETER")
    fun onTrimMemory(level: Int) {
        // Android 14+ 不再可靠地区分具体 level；收到回调就释放可重建缓存。
        // AWAITING 时预览仍用于投屏二次确认，不能提前清掉。
        if (projectionGate.value != ProjectionGate.AWAITING) {
            projectionPreview.value = null
        }
        ClickEffectOverlay.clearActive()
        ScreenCaptureManager.trimActive()
    }

    suspend fun refreshWorkflows(context: Context = appContext ?: error("未初始化")) {
        val (names, summaries) = withContext(AppExecutors.dispatcher) {
            val files = WorkflowRepository.list(context)
            files to files.map { name ->
                val workflow = WorkflowRepository.load(context, name)
                WorkflowSummary(
                    name = name,
                    targetPackage = workflow?.effectiveTargetPackage.orEmpty(),
                    stepCount = workflow?.steps?.size ?: 0,
                    parseFailed = workflow == null,
                )
            }
        }
        workflows.value = names
        workflowSummaries.value = summaries
    }

    // ---- 页面操作 ----

    suspend fun saveServer(ip: String, port: Int): Result<Unit> =
        runCatching {
            val config = ServerConfig(ip = ip.trim(), port = port)
            check(config.isComplete) { "IP 不能为空，端口需在 1-65535" }
            // 允许新地址在下一次 DataStore 发射时再自动探测一次（先重置，避免与收集器竞态）
            autoCheckStarted.set(false)
            SettingsStore.saveServerConfig(config)
            serverConnection.value = ServerConnectionState.UNKNOWN
            serverConnectionDetail.value = ""
            // 换了服务器：旧模板名不再可信，等新的配置收集器重新拉取
            clearTemplateNames()
        }

    suspend fun saveOss(endpoint: String, bucket: String, prefix: String): Result<Unit> =
        runCatching {
            SettingsStore.saveOssConfig(OssConfig(endpoint.trim(), bucket.trim(), prefix.trim()))
        }

    suspend fun saveTargetPackage(pkg: String): Result<Unit> =
        runCatching { SettingsStore.saveTargetPackage(pkg) }

    suspend fun setHoldFrame(value: Boolean) {
        SettingsStore.saveHoldFrame(value)
    }

    suspend fun setSaveScreenshots(value: Boolean) {
        SettingsStore.saveSaveScreenshots(value)
    }

    suspend fun setClickEffect(value: Boolean) {
        SettingsStore.saveClickEffect(value)
        clickEffect.value = value
        if (!value) ClickEffectOverlay.clearActive()
    }

    suspend fun setSkipProjectionConfirm(value: Boolean) {
        SettingsStore.saveSkipProjectionConfirm(value)
        skipProjectionConfirm.value = value
    }

    suspend fun setFailureVibration(value: Boolean) {
        SettingsStore.saveFailureVibration(value)
        failureAlertSettings.value = failureAlertSettings.value.copy(vibrationEnabled = value)
    }

    suspend fun setFailureRingtone(value: Boolean) {
        SettingsStore.saveFailureRingtone(value)
        failureAlertSettings.value = failureAlertSettings.value.copy(ringtoneEnabled = value)
    }

    suspend fun setFailureRingtoneUri(uri: String) {
        val trimmed = uri.trim()
        SettingsStore.saveFailureRingtoneUri(trimmed)
        failureAlertSettings.value = failureAlertSettings.value.copy(ringtoneUri = trimmed)
    }

    suspend fun setMatchConfidence(value: Float) {
        val clamped = value.coerceIn(MATCH_CONFIDENCE_RANGE)
        SettingsStore.saveMatchConfidence(clamped)
        matchConfidence.value = clamped
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        // 先落盘再更新状态：DataStore 的旧值不会在切换瞬间把界面闪回去
        SettingsStore.saveThemeMode(mode)
        themeMode.value = mode
    }

    suspend fun saveIdentity(info: IdentityInfo): Result<Unit> {
        val trimmed = info.copy(
            unit = info.unit.trim(),
            role = info.role.trim(),
            todayEvents = info.todayEvents.trim(),
            dailyReport = info.dailyReport.trim(),
            weeklyReport = info.weeklyReport.trim(),
            monthlyReport = info.monthlyReport.trim(),
        )
        return runCatching { SettingsStore.saveIdentity(trimmed) }
            .onSuccess { identityInfo.value = trimmed }
    }

    /** 连通性测试：实际走一次 POST /注册，并同步首页连接状态。 */
    suspend fun testConnection(): Result<String> {
        serverConnection.value = ServerConnectionState.CHECKING
        serverConnectionDetail.value = "正在申请令牌…"
        return runCatching {
            check(serverConfig.value.isComplete) { "请先填写服务端 IP 与端口并保存" }
            tokenManager.refresh()
            "连接成功，令牌已获取（1300 秒有效期，已自动提前刷新）"
        }.onSuccess { message ->
            serverConnection.value = ServerConnectionState.ONLINE
            serverConnectionDetail.value = message
        }.onFailure { error ->
            serverConnection.value = ServerConnectionState.OFFLINE
            serverConnectionDetail.value = error.message ?: "无法连接服务器"
        }
    }

    suspend fun syncResources(): Result<Unit> {
        val ctx = appContext ?: error("未初始化")
        return OssResourceRepository.sync(ctx, ossConfig.value).map { message ->
            lastSyncText.value = message
            resourceVersion.value = withContext(AppExecutors.dispatcher) {
                OssResourceRepository.cachedVersion(ctx)
            } ?: ""
            refreshWorkflows(ctx)
        }
    }

    suspend fun saveWorkflow(name: String, jsonText: String): Result<Unit> {
        val ctx = appContext ?: error("未初始化")
        return withContext(AppExecutors.dispatcher) { WorkflowRepository.save(ctx, name, jsonText) }
            .onSuccess { refreshWorkflows(ctx) }
    }

    suspend fun deleteWorkflow(name: String) {
        val ctx = appContext ?: return
        val deleted = withContext(AppExecutors.dispatcher) { WorkflowRepository.delete(ctx, name) }
        if (deleted) refreshWorkflows(ctx)
    }

    suspend fun loadWorkflowJson(name: String): String? {
        val ctx = appContext ?: return null
        return withContext(AppExecutors.dispatcher) { WorkflowRepository.rawJson(ctx, name) }
    }

    /** 新建工作流的默认骨架（targetPackage 留空 → 运行时跟随页面全局目标，不烘焙占位包名） */
    fun defaultWorkflowJson(name: String): String {
        return """
            {
              "name": "$name",
              "targetPackage": "",
              "intervalMs": 1000,
              "steps": [
                { "type": "launch" },
                { "type": "wait", "ms": 3000 },
                { "type": "match", "template": "登录", "tap": true, "timeoutMs": 10000 },
                { "type": "match", "template": "同意圆圈", "tap": true, "optional": true, "timeoutMs": 4000 },
                { "type": "globalAction", "action": "back" },
                { "type": "saveScreenshot", "tag": "凭证" }
              ]
            }
        """.trimIndent()
    }
}
