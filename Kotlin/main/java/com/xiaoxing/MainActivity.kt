package com.xiaoxing

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.xiaoxing.capture.ScreenCaptureManager
import com.xiaoxing.config.ThemeMode
import com.xiaoxing.permissions.PermissionMonitor
import com.xiaoxing.service.AutomationForegroundService
import com.xiaoxing.ui.MainScreen
import com.xiaoxing.ui.theme.GongXueYunAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    /** 待执行的流程（按勾选顺序）；投屏授权通过后整批交给前台服务串行执行 */
    private var pendingWorkflowNames: List<String> = emptyList()

    /**
     * 通知权限是启动链路第一关：POST_NOTIFICATIONS 被拒 → 前台服务通知不上栏
     * → mediaProjection FGS 未被通知栏确认 → 帧池创建后永远取不到帧。
     * 因此先拿通知权限，再发起投屏授权。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchProjectionConsent()
            } else {
                AppRepository.showHint(
                    "未授予通知权限：前台服务通知无法出现在通知栏，帧池将无法出帧 —— 已取消启动",
                    HintLevel.ERROR,
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRepository.init(this)
        enableEdgeToEdge()

        // 投屏授权（MediaProjection 每次会话都需要用户同意）。
        // 只有拿到 RESULT_OK 的授权 data 后才启动服务、开始截图，未授权不会进入截屏流程。
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                AutomationForegroundService.start(this, data, pendingWorkflowNames)
            } else {
                AppRepository.showHint("投屏授权被拒绝，无法启动自动化", HintLevel.ERROR)
            }
        }

        setContent {
            val themeMode by AppRepository.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            GongXueYunAppTheme(darkTheme = darkTheme) {
                MainScreen(
                    onRequestStart = ::requestStart,
                    onStop = ::stopAutomation,
                    onConfirmProjection = ::confirmProjection,
                    onCancelProjection = ::cancelProjection,
                )
            }
        }

        // 自动化引擎发出 launch 事件后，把自身窗口最小化（不 finish，FGS 与状态页均保留）
        lifecycleScope.launch {
            AppRepository.minimizeRequests.collect {
                runCatching { moveTaskToBack(true) }
            }
        }
    }

    /**
     * UI 启动入口（官方时序第一环）：
     * POST_NOTIFICATIONS 已授予 → 投屏授权弹窗 → 前台服务（通知上栏）→ 帧池。
     * 未授予则先申请，拒绝即终止 —— 没有通知栏确认就不该建帧池。
     */
    private fun requestStart(workflowNames: List<String>) {
        pendingWorkflowNames = workflowNames
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchProjectionConsent()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun launchProjectionConsent() {
        projectionLauncher.launch(ScreenCaptureManager.createScreenCaptureIntent(this))
    }

    /** 停止按钮入口（供 UI 调用） */
    fun stopAutomation() {
        AutomationForegroundService.stop(this)
    }

    /** 二次确认通过：放行工作流（前台服务在 awaitUserConfirm 里看到 CONFIRMED 即进入步骤） */
    private fun confirmProjection() {
        AppRepository.setProjectionGate(ProjectionGate.CONFIRMED)
    }

    /** 二次确认取消：关掉前台服务与投屏，工作流不启动 */
    private fun cancelProjection() {
        AppRepository.setProjectionGate(ProjectionGate.CANCELLED)
        AutomationForegroundService.stop(this)
    }

    /**
     * 权限状态只在「回到前台 / 从系统设置返回」时刷新。
     *
     * 旧实现由各页面在组合时注册 LifecycleObserver；由于注册时 Activity 已是 RESUMED，
     * addObserver 会立即同步补发 ON_RESUME —— 每次切 Tab 都会在主线程做 3 次 binder IPC。
     * 现在统一放到 Activity 级，并下沉到后台线程，切页帧不再有系统调用。
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.Default) {
            PermissionMonitor.refresh(applicationContext)
        }
    }

    override fun onDestroy() {
        // 页面被真正关闭（不是旋转重建）时别让前台服务空等到超时；
        // 下拉通知栏只会让 Activity 走 onPause/onStop，不会触发这里。
        if (isFinishing && AppRepository.projectionGate.value == ProjectionGate.AWAITING) {
            AppRepository.setProjectionGate(ProjectionGate.CANCELLED)
        }
        if (isFinishing) AppRepository.projectionPreview.value = null
        super.onDestroy()
    }
}
