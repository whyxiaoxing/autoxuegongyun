package com.xiaoxing.permissions

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.xiaoxing.accessibility.AutomationAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 权限状态实时监控（页面只读 StateFlow，不再各自 remember 一次性读取）：
 *  - 无障碍开关：ContentObserver 监听 ENABLED_ACCESSIBILITY_SERVICES，
 *    用户在系统设置里一开/一关，本进程存活期间即时收到推送；
 *  - 悬浮窗（SYSTEM_ALERT_WINDOW）：后台拉起其他 App 的标准豁免，`Settings.canDrawOverlays` 可直接查询；
 *  - 电池白名单：系统无公开广播，由页面 ON_RESUME（从系统设置返回即触发）调用 [refresh]。
 */
object PermissionMonitor {

    val accessibilityEnabled = MutableStateFlow(false)
    val overlayGranted = MutableStateFlow(false)
    val batteryWhitelisted = MutableStateFlow(false)

    @Volatile
    private var appContext: Context? = null

    /** 权限查询走 binder IPC：放到后台线程，Application.onCreate 不在主线程做系统调用。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var registered = false

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val ctx = appContext ?: return
            scope.launch {
                accessibilityEnabled.value = AutomationAccessibilityService.isEnabled(ctx)
            }
        }
    }

    /** Application 级注册一次（AppRepository.init 调用） */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!registered) {
            registered = true
            scope.launch { refresh(ctx) }
            val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ctx.contentResolver.registerContentObserver(uri, /* notifyForDescendants = */ false, settingsObserver)
        }
    }

    /** ON_RESUME / 启动校验前手动刷新（覆盖电池白名单、悬浮窗等无广播的项） */
    fun refresh(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        accessibilityEnabled.value = AutomationAccessibilityService.isEnabled(ctx)
        overlayGranted.value = Settings.canDrawOverlays(ctx)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryWhitelisted.value = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
