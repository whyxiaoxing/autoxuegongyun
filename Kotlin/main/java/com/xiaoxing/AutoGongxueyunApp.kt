package com.xiaoxing

import android.app.Application

/**
 * 进程级初始化 + 内存压力回调。
 *
 * 放在 Application 而不是 Activity：工作流跑在前台服务时 Activity 可能已销毁，
 * onTrimMemory 仍然需要释放预览图、模板缩略图、截屏编码缓存。
 */
class AutoGongxueyunApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppRepository.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppRepository.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppRepository.onTrimMemory(0)
    }
}
