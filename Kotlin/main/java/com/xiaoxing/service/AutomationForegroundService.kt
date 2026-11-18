package com.xiaoxing.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.MainActivity
import com.xiaoxing.ProjectionGate
import com.xiaoxing.R
import com.xiaoxing.accessibility.AutomationAccessibilityService
import com.xiaoxing.capture.ScreenCaptureManager
import com.xiaoxing.log.AppLog
import com.xiaoxing.network.SeeClient
import com.xiaoxing.workflow.WorkflowEngine
import com.xiaoxing.workflow.WorkflowRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动化前台服务（防墓碑核心）：
 *  - 常规运行挂 specialUse 类型 FGS（系统只冻结缓存进程，前台服务进程不进 Cached App Freezer）
 *  - 截屏阶段叠加 mediaProjection 类型（官方时序：授权 → startForeground 且通知上栏 → getMediaProjection）
 *  - 帧池创建前先"通知栏确认"（通知不上栏 = 通知权限被拒，帧池必不出帧，直接中止并提示）
 *  - 运行期间持有部分 WakeLock，防止 CPU 休眠（不申请亮屏锁：国内厂商 ROM 普遍拒绝，
 *    改为依赖系统自动熄屏策略，自动化步骤本身不依赖屏幕常亮）
 *  - START_STICKY + WorkManager 巡检兜底
 *
 * 不用 dataSync 类型：API 34+ 有 6 小时总时长上限，跑长工作流会被系统掐掉。
 */
class AutomationForegroundService : Service() {

    companion object {
        /** v2：旧通道是 IMPORTANCE_LOW，确认按钮藏在"静默通知"里不易发现，改为 DEFAULT 并换 id 生效 */
        const val CHANNEL_ID = "automation_channel_v2"
        private const val LEGACY_CHANNEL_ID = "automation_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_PROJECTION = "com.xiaoxing.extra.PROJECTION_RESULT"
        const val EXTRA_WORKFLOWS = "com.xiaoxing.extra.WORKFLOW_NAMES"

        /** 失败通知独立通道：声音/震动由 [FailureAlert] 显式触发，这里保持静音避免双响 */
        private const val FAILURE_CHANNEL_ID = "automation_failure_v1"
        private const val FAILURE_NOTIFICATION_ID = 2

        /** 通知栏确认按钮的动作（PendingIntent.getService → onStartCommand） */
        const val ACTION_CONFIRM = "com.xiaoxing.action.PROJECTION_CONFIRM"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 单次运行上限 2 小时

        /** 通知栏确认等待上限：startForeground 后通知应在数百 ms 内上栏 */
        private const val NOTIFICATION_CONFIRM_TIMEOUT_MS = 3000L

        /** 投屏二次确认等待上限：用户要下拉通知栏点「继续共享」再回来点确认，给足 2 分钟 */
        private const val CONFIRM_TIMEOUT_MS = 120_000L

        @Volatile
        var alive = false
            private set

        /** @param workflowNames 按执行顺序排列；多条流程共享同一次投屏授权与前台服务 */
        fun start(context: Context, projectionData: Intent, workflowNames: List<String>) {
            val intent = Intent(context, AutomationForegroundService::class.java).apply {
                putExtra(EXTRA_PROJECTION, projectionData)
                putStringArrayListExtra(EXTRA_WORKFLOWS, ArrayList(workflowNames))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var capture: ScreenCaptureManager? = null
    private var engineJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏「已确认」按钮：用户此刻通常停在目标 App 上（那里才会出现厂商的「继续共享」），
        // 够不到本应用的 Activity，所以确认入口就在通知里。直接放行，不重复走启动流程。
        if (intent?.action == ACTION_CONFIRM) {
            if (engineJob?.isActive == true) {
                AppRepository.setProjectionGate(ProjectionGate.CONFIRMED)
            } else {
                stopSelf() // 通知还在但流程已结束（竞态），别留一个空服务
            }
            return START_STICKY
        }

        val projectionData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_PROJECTION, Intent::class.java)
        }
        val workflowNames = intent?.getStringArrayListExtra(EXTRA_WORKFLOWS)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        goForeground(withProjection = projectionData != null)
        // 新一次运行开始：清掉上一轮遗留的失败通知
        getSystemService(NotificationManager::class.java).cancel(FAILURE_NOTIFICATION_ID)

        if (engineJob?.isActive == true) {
            AppRepository.showHint("已有工作流在运行，请先停止再启动新的任务", HintLevel.WARNING)
            return START_STICKY
        }

        if (projectionData == null) {
            // 系统重启服务（START_STICKY）场景：投影令牌已随进程丢失，无法自行恢复截屏
            val message = "自动化服务被系统重启，投屏授权已丢失 —— 请回到页面重新启动"
            AppRepository.showHint(message, HintLevel.ERROR)
            updateNotification("自动化中断：请回到页面重新启动（需重新授权投屏）")
            FailureAlert.alert(applicationContext, AppRepository.failureAlertSettings.value)
            postFailureNotification(message)
            return START_STICKY
        }

        if (workflowNames.isEmpty()) {
            AppRepository.showHint("没有选择要执行的工作流", HintLevel.WARNING)
            updateNotification("自动化未启动：未选择工作流")
            stopSelf()
            return START_STICKY
        }

        // 确认真的要跑起来之后再持锁：失败/空任务分支不持锁，避免白白耗电
        acquireWakeLocks()
        engineJob = scope.launch {
            try {
                runWorkflowBatch(projectionData, workflowNames)
            } catch (e: CancellationException) {
                // 用户主动停止：首页已有「已请求停止」提示，不再重复报错
            } catch (e: Exception) {
                if (
                    AppRepository.projectionGate.value == ProjectionGate.CANCELLED &&
                    !AppRepository.skipProjectionConfirm.value
                ) {
                    // 仅在真正等待过闸门时才用「取消确认」文案；跳过闸门时投屏被撤销走通用中止提示
                    AppRepository.showHint("已取消投屏确认，工作流未启动", HintLevel.WARNING)
                } else {
                    val message = e.message?.takeIf { it.isNotBlank() }
                        ?.let { "自动化中止: $it" }
                        ?: "自动化中止：未知错误"
                    AppRepository.showHint(message, HintLevel.ERROR)
                    FailureAlert.alert(applicationContext, AppRepository.failureAlertSettings.value)
                    postFailureNotification(message)
                }
            }
            AppRepository.setRunning(false)
            AppRepository.engineStatus.value = ""
            AppRepository.setProjectionGate(ProjectionGate.NONE)
            releaseCapture()
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * 按勾选顺序串行执行多条工作流，共享同一次投屏授权与前台服务。
     *
     * 失败语义：
     *  - 单条流程失败（含引擎内每个步骤的重试耗尽）不会中断后续流程，结束时汇总成功/失败数；
     *  - 用户主动停止、取消投屏确认、投屏被撤销会中止整批；
     *  - 每条流程最终失败时震动 + 响铃，并保留一条失败通知。
     */
    private suspend fun runWorkflowBatch(projectionData: Intent, workflowNames: List<String>) {
        AppLog.info(
            "自动化",
            "开始执行 ${workflowNames.size} 条流程：${workflowNames.joinToString()}",
        )
        // 官方时序（Android 14+）：用户授权 → mediaProjection FGS 通知真正上栏 → 才能建帧池。
        // 通知未上栏（典型：拒绝 POST_NOTIFICATIONS）就 getMediaProjection/createVirtualDisplay，
        // 投影不出帧，帧池永远为空 —— 所以此处先"通知栏确认"，失败则不创建帧池直接中止。
        if (!awaitNotificationInBar()) {
            throw IllegalStateException(
                "前台服务通知未出现在通知栏（多半是拒绝了通知权限），帧池未创建 —— 请授予通知权限后重新启动"
            )
        }

        val manager = ScreenCaptureManager(applicationContext)
            .also { this@AutomationForegroundService.capture = it }
        manager.onProjectionStopped = {
            AppLog.warn("投屏", "投屏已被停止（用户或系统撤销），自动化中止")
            AppRepository.showHint("投屏已被停止（用户或系统撤销），自动化已中止", HintLevel.WARNING)
            AppRepository.setProjectionGate(ProjectionGate.CANCELLED)
            stopSelf()
        }
        manager.start(projectionData)
        AppLog.info("投屏", "投屏已建立，开始等待首帧")

        // 第二层闸门：Android 只授权了「可以截屏」，部分厂商 ROM（MIUI/HyperOS 等）还会在
        // 通知栏再加一道「继续共享」，未点之前投屏画面会被隐私保护模糊成圆角卡片。
        // 该状态没有公共 API 可查，只能由页面弹窗让用户显式确认；页面同时显示实时预览，
        // 用户可以自己看出闸门是否已放行。确认前不进入任何工作流步骤。
        // 设置里开启「跳过投屏二次确认」后整道闸门都不再出现，授权完成即进入步骤。
        val skipConfirm = AppRepository.skipProjectionConfirm.value
        if (skipConfirm) {
            AppRepository.setProjectionGate(ProjectionGate.NONE)
            AppLog.info("投屏", "已跳过投屏二次确认，直接进入工作流")
            updateNotification("自动化运行中（已跳过投屏二次确认）")
        } else {
            AppRepository.setProjectionGate(ProjectionGate.AWAITING)
            AppLog.info("投屏", "等待用户确认投屏共享（通知栏「继续共享」→「已确认，开始」）")
            updateNotification(
                "等待确认：通知栏点「继续共享」，再点「已确认，开始」",
                awaitingConfirm = true,
            )
        }
        // 帧池预热（等待首帧）已移入 WorkflowEngine 初始化阶段，与二次确认并行

        val engine = WorkflowEngine(
            context = applicationContext,
            capture = manager,
            see = AppRepository.seeClient,
            aiText = AppRepository.aiTextClient,
            ocr = AppRepository.ocrClient,
            identity = { AppRepository.identityInfo.value },
        )
        engine.onMinimizeRequest = { AppRepository.requestMinimize() }

        AppRepository.setRunning(true)
        // 初始化与二次确认在引擎里并行；确认通过后才进入步骤。首次确认后闸门保持 CONFIRMED，
        // 后续流程直接放行，不再重复拉起目标应用。
        val confirmGate: suspend (String) -> Boolean =
            if (skipConfirm) { _ -> true } else ::awaitUserConfirm

        val total = workflowNames.size
        var succeeded = 0
        val failed = mutableListOf<String>()

        workflowNames.forEachIndexed { index, name ->
            val position = if (total > 1) "(${index + 1}/$total) " else ""
            engine.onStatusChange = { status ->
                AppRepository.engineStatus.value = position + status
                AppLog.info("工作流", position + status)
            }
            updateNotification(
                if (total > 1) "自动化运行中（${index + 1}/$total）：$name" else "自动化运行中：$name",
            )

            val workflow = WorkflowRepository.load(applicationContext, name)
            if (workflow == null) {
                failed += name
                val reason = "流程「$name」无法读取：文件不存在或解析失败"
                AppLog.error("工作流", reason)
                AppRepository.showHint(reason, HintLevel.ERROR)
                FailureAlert.alert(applicationContext, AppRepository.failureAlertSettings.value)
                return@forEachIndexed
            }

            AppLog.info(
                "工作流",
                "开始「$name」：${workflow.steps.size} 个步骤，每步失败重试 ${workflow.failureRetries} 次",
            )
            try {
                engine.run(workflow, AppRepository.targetPackage.value, confirmGate)
                succeeded++
                AppLog.info("工作流", "完成「$name」")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 闸门取消 = 用户明确中止：停止整批，交给外层按「取消」提示
                if (AppRepository.projectionGate.value == ProjectionGate.CANCELLED && !skipConfirm) {
                    throw e
                }
                failed += name
                val reason = "流程「$name」失败: " +
                    (e.message?.takeIf { it.isNotBlank() } ?: "未知错误")
                AppLog.error("工作流", reason)
                AppRepository.showHint(reason, HintLevel.ERROR)
                // 步骤级失败重试已在引擎内部消化；这里只对最终失败提醒一次
                FailureAlert.alert(applicationContext, AppRepository.failureAlertSettings.value)
            }
        }

        if (failed.isEmpty()) {
            val done = if (total > 1) "$total 条流程" else "工作流「${workflowNames.first()}」"
            AppLog.info("自动化", "$done 已完成")
            AppRepository.showHint("$done 已完成", HintLevel.SUCCESS)
            updateNotification("自动化完成（成功 $succeeded 条）")
        } else {
            val summary = "自动化结束：成功 $succeeded 条，失败 ${failed.size} 条" +
                "（${failed.joinToString()}）"
            AppLog.error("自动化", summary)
            AppRepository.showHint(summary, HintLevel.ERROR)
            postFailureNotification(summary)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉任务后若引擎仍在跑，安排巡检兜底提示
        if (engineJob?.isActive == true) {
            KeepAliveWorker.schedule(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        engineJob?.cancel()
        releaseCapture()
        releaseWakeLocks()
        alive = false
        AppRepository.setRunning(false)
        // 服务已结束：确认框不能残留（否则页面停在"等待确认"的死状态）
        AppRepository.setProjectionGate(ProjectionGate.NONE)
        scope.cancel()
        super.onDestroy()
    }

    private fun releaseCapture() {
        capture?.stop()
        capture = null
    }

    /**
     * 等待投屏二次确认（第二层闸门），≤[CONFIRM_TIMEOUT_MS]。
     *
     * 关键点：厂商（MIUI/HyperOS）的「继续共享」**只在被捕获的画面是目标 App 时才出现**，
     * 停在自动化应用自己身上时通知栏里根本没有那个按钮。所以进入等待前先把目标 App 拉到前台，
     * 让厂商闸门露出来；确认入口放在本应用的通知栏按钮上（此时用户够不到 Activity）。
     *
     * 生命周期：建帧池 → 置 AWAITING → 拉起目标 App → 用户点「继续共享」→ 点通知栏「已确认，开始」
     * → 置 CONFIRMED → 进入步骤。期间初始化已在并行进行，确认后不再额外等待。
     */
    private suspend fun awaitUserConfirm(targetPackage: String): Boolean {
        // 多流程串行：首次确认后闸门保持 CONFIRMED，后续流程直接放行，
        // 不再重复拉起目标应用（每个流程的目标可能不同，也不该在确认后跳走）。
        when (AppRepository.projectionGate.value) {
            ProjectionGate.CONFIRMED -> return true
            ProjectionGate.CANCELLED -> return false
            else -> Unit
        }
        launchTargetForGate(targetPackage)
        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (AppRepository.projectionGate.value) {
                ProjectionGate.CONFIRMED -> {
                    // 二次授权完成：确认入口通常在通知栏里（「继续共享」/「已确认，开始」），
                    // 通知栏此时往往还展开着，会盖住目标应用导致后续截图识别到通知栏本身。
                    // 顺手收起（需要无障碍服务，未连接时静默跳过），等收起动画结束再进入步骤。
                    AutomationAccessibilityService.dismissNotificationShade()
                    delay(250)
                    updateNotification("自动化运行中")
                    return true
                }

                ProjectionGate.CANCELLED -> return false

                else -> delay(100)
            }
        }
        return false
    }

    /**
     * 把目标 App 拉到前台，触发厂商的「继续共享」提示。
     * 拉不起来（包名为空/未安装）也不阻塞：用户在通知栏里手动切过去即可。
     */
    private fun launchTargetForGate(pkg: String) {
        if (pkg.isBlank()) return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /** 轮询通知栏，等待本服务的前台服务通知真正出现（"通知栏确认"，≤[NOTIFICATION_CONFIRM_TIMEOUT_MS]） */
    private suspend fun awaitNotificationInBar(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + NOTIFICATION_CONFIRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (nm.activeNotifications.any { it.id == NOTIFICATION_ID }) return true
            delay(50)
        }
        return nm.activeNotifications.any { it.id == NOTIFICATION_ID }
    }

    private fun goForeground(withProjection: Boolean) {
        val type = if (withProjection) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("自动化待运行"), type)
    }

    private fun buildNotification(text: String, awaitingConfirm: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)

        if (awaitingConfirm) {
            // 只有一个确认按钮：取消 = 点通知正文回应用里取消，或不操作等超时
            builder
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "① 已自动切到目标应用（没切过去就手动切一下）\n" +
                            "② 下拉通知栏，点系统提示里的「继续共享」\n" +
                            "③ 回到本通知，点下方「已确认，开始」"
                    )
                )
                .addAction(
                    android.R.drawable.checkbox_on_background,
                    "已确认，开始",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, AutomationForegroundService::class.java).setAction(ACTION_CONFIRM),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
        }

        return builder.build()
    }

    private fun updateNotification(text: String, awaitingConfirm: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, awaitingConfirm))
    }

    /**
     * 失败通知：前台服务结束后仍保留在通知栏，方便用户事后查看是哪条流程失败。
     * 通道本身静音（声音/震动由 [FailureAlert] 显式触发），避免一次失败响两遍。
     */
    private fun postFailureNotification(text: String) {
        runCatching {
            val contentIntent = PendingIntent.getActivity(
                this, 2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(this, FAILURE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("自动化失败")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            getSystemService(NotificationManager::class.java)
                .notify(FAILURE_NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoGongxueyun:automation")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // 通道重要性创建后不可改，旧通道直接删掉，新通道用 DEFAULT 保证确认按钮在通知栏里可见
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化运行",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "自动化引擎前台服务通知（含投屏确认按钮）"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        // 失败通道：高重要性保证弹窗可见；声音与震动交给 FailureAlert，这里静音避免双响
        val failureChannel = NotificationChannel(
            FAILURE_CHANNEL_ID,
            "自动化失败提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "工作流执行失败时的通知（同时震动并响铃）"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
        }
        nm.createNotificationChannel(failureChannel)
    }
}
