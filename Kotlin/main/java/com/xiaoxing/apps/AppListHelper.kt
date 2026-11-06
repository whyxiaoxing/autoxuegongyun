package com.xiaoxing.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.xiaoxing.concurrent.AppExecutors
import kotlinx.coroutines.withContext

/** 已安装可拉起应用列表（Manifest <queries> 已声明 MAIN+LAUNCHER 过滤） */
object AppListHelper {

    data class AppEntry(val label: String, val packageName: String)

    /**
     * PackageManager 查询是跨进程 binder 调用 + 逐条 loadLabel，主线程同步执行会明显掉帧。
     * 全部下沉到工作线程池，调用方（应用选择器对话框）用加载态等待。
     */
    suspend fun launchableApps(context: Context): List<AppEntry> = withContext(AppExecutors.dispatcher) {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                AppEntry(label = label, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
