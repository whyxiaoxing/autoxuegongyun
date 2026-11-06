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
 *  - 运行期间持有部分 WakeLock，防止 CPU 休眠
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
        const val EXTRA_WORKFLOW = "com.xiaoxing.extra.WORKFLOW_NAME"

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

        fun start(context: Context, projectionData: Intent, workflowName: String) {
            val intent = Intent(context, AutomationForegroundService::class.java).apply {
                putExtra(EXTRA_PROJECTION, projectionData)
                putExtra(EXTRA_WORKFLOW, workflowName)
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
        val workflowName = intent?.getStringExtra(EXTRA_WORKFLOW).orEmpty()

        goForeground(withProjection = projectionData != null)
        acquireWakeLock()

        if (engineJob?.isActive == true) {
            AppRepository.showHint("已有工作流在运行，请先停止再启动新的任务", HintLevel.WARNING)
            return START_STICKY
        }

        if (projectionData == null) {
            // 系统重启服务（START_STICKY）场景：投影令牌已随进程丢失，无法自行恢复截屏
            AppRepository.showHint(
                "自动化服务被系统重启，但投屏授权已丢失 —— 请回到页面重新启动",
                HintLevel.ERROR,
            )
            updateNotification("自动化中断：请回到页面重新启动（需重新授权投屏）")
            return START_STICKY
        }

        engineJob = scope.launch {
            runCatching {
                val workflow = WorkflowRepository.load(applicationContext, workflowName)
                    ?: throw IllegalStateException("工作流不存在或解析失败: $workflowName")

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
                    AppRepository.showHint("投屏已被停止（用户或系统撤销），自动化已中止", HintLevel.WARNING)
                    AppRepository.setProjectionGate(ProjectionGate.CANCELLED)
                    stopSelf()
                }
                manager.start(projectionData)

                // 第二层闸门：Android 只授权了「可以截屏」，部分厂商 ROM（MIUI/HyperOS 等）还会在
                // 通知栏再加一道「继续共享」，未点之前投屏画面会被隐私保护模糊成圆角卡片。
                // 该状态没有公共 API 可查，只能由页面弹窗让用户显式确认；页面同时显示实时预览，
                // 用户可以自己看出闸门是否已放行。确认前不进入任何工作流步骤。
                // 设置里开启「跳过投屏二次确认」后整道闸门都不再出现，授权完成即进入步骤。
                val skipConfirm = AppRepository.skipProjectionConfirm.value
                if (skipConfirm) {
                    AppRepository.setProjectionGate(ProjectionGate.NONE)
                    updateNotification("自动化运行中（已跳过投屏二次确认）")
                } else {
                    AppRepository.setProjectionGate(ProjectionGate.AWAITING)
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
                    identity = { AppRepository.identityInfo.value },
                )
                engine.onStatusChange = { AppRepository.engineStatus.value = it }
                engine.onMinimizeRequest = { AppRepository.requestMinimize() }

                AppRepository.setRunning(true)
                // 初始化与二次确认在引擎里并行；确认通过后才进入步骤
                val confirmGate: suspend (String) -> Boolean =
                    if (skipConfirm) { _ -> true } else ::awaitUserConfirm
                engine.run(workflow, AppRepository.targetPackage.value, confirmGate)
            }.onSuccess {
                AppRepository.showHint("工作流「$workflowName」已完成", HintLevel.SUCCESS)
            }.onFailure { e ->
                if (e is CancellationException) {
                    // 用户主动停止：首页已有「已请求停止」提示，不再重复报错
                } else if (
                    AppRepository.projectionGate.value == ProjectionGate.CANCELLED &&
                    !AppRepository.skipProjectionConfirm.value
                ) {
                    // 仅在真正等待过闸门时才用「取消确认」文案；跳过闸门时投屏被撤销走通用中止提示
                    AppRepository.showHint("已取消投屏确认，工作流未启动", HintLevel.WARNING)
                } else {
                    AppRepository.showHint(
                        e.message?.takeIf { it.isNotBlank() }?.let { "自动化中止: $it" }
                            ?: "自动化中止：未知错误",
                        HintLevel.ERROR,
                    )
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
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoGongxueyun:automation")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
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
    }
}
