package com.xiaoxing.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import java.util.concurrent.TimeUnit

/**
 * 保活巡检：START_STICKY 重启时机不可控，这里周期检查
 * "页面期望在运行（isRunning）但前台服务已死"的状态并提示用户重新授权启动。
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (AppRepository.isRunning.value && !AutomationForegroundService.alive) {
            AppRepository.showHint(
                "自动化服务被系统终止（进程级回收无法恢复投屏授权），请重新启动",
                HintLevel.ERROR,
            )
            AppRepository.setRunning(false)
            AppRepository.engineStatus.value = "已被系统终止，请重新启动"
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto_gongxueyun_keep_alive"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
