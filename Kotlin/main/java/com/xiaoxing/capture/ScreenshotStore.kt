package com.xiaoxing.capture

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/**
 * 截图落盘：经 MediaStore 写入公共媒体库 DCIM/AutoGongxueyun（相册/文件管理直接可见）。
 *
 * 分区存储（API 29+，本项目 minSdk=34）规则：
 *  - 写「自己贡献」的媒体不需要任何存储权限（WRITE_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE 均不需要）；
 *  - RELATIVE_PATH 指定 DCIM 子目录，文件最终位于 /storage/emulated/0/DCIM/AutoGongxueyun/；
 *  - IS_PENDING=1 占位 → 写完转 0，媒体库即时索引，无需手动触发扫描。
 */
object ScreenshotStore {

    /** 相册内子目录：DCIM/AutoGongxueyun */
    private val RELATIVE_DIR = Environment.DIRECTORY_DCIM + "/AutoGongxueyun"

    /** 保存 PNG。失败抛异常（并清理 IS_PENDING 占位），由调用方决定提示/中止。 */
    fun savePng(context: Context, fileName: String, pngBytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore 拒绝写入 $RELATIVE_DIR")
        try {
            resolver.openOutputStream(uri)?.use { it.write(pngBytes) }
                ?: throw IllegalStateException("无法打开输出流: $uri")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) } // 清理失败的占位记录
            throw t
        }
    }
}
