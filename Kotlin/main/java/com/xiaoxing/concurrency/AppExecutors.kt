package com.xiaoxing.concurrent

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 全局工作线程池：ThreadPoolExecutor，核心 2 / 上限 4。
 *
 * 职责：网络请求、文件读写、PackageManager 查询、JSON 解析等一切可能阻塞的工作
 * 全部经 [dispatcher] 下沉到这里；主线程只做 UI 组合与绘制。
 *
 * 实现要点：
 *  - 队列必须有界（ArrayBlockingQueue）：无界队列会让线程数永远停在 corePoolSize，
 *    maximumPoolSize=4 形同虚设；
 *  - 队列打满后 CallerRunsPolicy 降级为调用者线程执行，保证任务不丢（本应用负载下不会触发）；
 *  - 空闲 30s 后核心线程也回收（allowCoreThreadTimeOut），后台常驻不占资源。
 */
object AppExecutors {

    private val SEQ = AtomicInteger(1)

    private val executor = ThreadPoolExecutor(
        2, 4,
        30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(128),
        { runnable ->
            Thread(runnable, "gxwork-${SEQ.getAndIncrement()}").apply {
                isDaemon = false
                priority = Thread.NORM_PRIORITY
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /** 协程侧统一入口：withContext(AppExecutors.dispatcher) { ... } */
    val dispatcher = executor.asCoroutineDispatcher()
}
