package com.xiaoxing.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File

/** 本地模板条目：name 为去扩展名后的文件名，file 指向 filesDir/photo_templates/<name>.img */
data class LocalTemplate(
    val name: String,
    val file: File,
) {
    val sizeBytes: Long get() = file.length()
}

/**
 * 本地图片模板库（「SeeSubmitPhoto」链路）。
 *
 * 模板图片由用户从系统相册选择后复制到应用私有目录，匹配时以 Base64 直接提交给
 * `/See_Submit_Photo`，不依赖服务端 Photo/ 目录、也不经过 OSS —— 模板始终由本机可控。
 *
 * 文件保留原始字节（PNG/JPEG/WebP 均可），解码交给 BitmapFactory / 服务端 image crate；
 * 文件名统一用 .img，重编码不会改变模板像素。
 */
object LocalTemplateStore {

    private const val DIR_NAME = "photo_templates"
    private const val SUFFIX = ".img"

    /** 文件名非法字符：只编译一次（suggestedName/sanitize 会被列表与导入反复调用）。 */
    private val INVALID_NAME_CHARS = Regex("""[\\/:*?"<>|]""")

    /** 模板图片最长边上限：超过通常是整张相册照片，既不适合做模板也容易 OOM */
    const val MAX_EDGE = 4096

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun file(context: Context, name: String): File =
        File(dir(context), "${sanitize(name)}$SUFFIX")

    fun sanitize(name: String): String =
        name.trim()
            .replace(INVALID_NAME_CHARS, "_")
            .trim('.', ' ')
            .ifBlank { "模板_${System.currentTimeMillis()}" }

    fun list(context: Context): List<LocalTemplate> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.map { LocalTemplate(it.name.removeSuffix(SUFFIX), it) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun exists(context: Context, name: String): Boolean = file(context, name).isFile

    fun isNameTaken(context: Context, name: String): Boolean = exists(context, name)

    /** 读取模板为纯 Base64（无 data: 前缀）；不存在返回 null */
    fun readBase64(context: Context, name: String): String? {
        val target = file(context, name)
        if (!target.isFile) return null
        return runCatching {
            Base64.encodeToString(target.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 从系统相册 Uri 导入。重名直接失败，由页面提示用户换一个名称。 */
    fun importFromUri(context: Context, name: String, uri: Uri): Result<LocalTemplate> = runCatching {
        val cleanName = sanitize(name)
        check(!isNameTaken(context, cleanName)) { "已存在同名模板「$cleanName」" }

        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可识别的图片" }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        check(longest <= MAX_EDGE) {
            "图片边长 ${bounds.outWidth}x${bounds.outHeight} 过大，请使用不超过 ${MAX_EDGE}px 的裁剪模板"
        }

        val target = file(context, cleanName)
        target.writeBytes(bytes)
        LocalTemplate(cleanName, target)
    }

    /** 直接保存已解码的 PNG 字节（供导入/测试共用） */
    fun savePng(context: Context, name: String, pngBytes: ByteArray): Result<LocalTemplate> = runCatching {
        val cleanName = sanitize(name)
        check(!isNameTaken(context, cleanName)) { "已存在同名模板「$cleanName」" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        val target = file(context, cleanName)
        target.writeBytes(pngBytes)
        LocalTemplate(cleanName, target)
    }

    fun delete(context: Context, name: String): Boolean =
        runCatching { file(context, name).delete() }.getOrDefault(false)

    /** 相册选择器的默认名称：去掉扩展名，替换非法字符 */
    fun suggestedName(rawDisplayName: String?): String {
        val base = rawDisplayName.orEmpty()
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "模板_${System.currentTimeMillis()}" }
        return sanitize(base)
    }

    /**
     * 单次解码到目标尺寸（长边按 2 的幂降到 maxEdge 附近）。
     * 缩略图/预览直接使用，不再「解码 → PNG 压缩 → 再解码」往返。
     */
    fun decodeBitmap(context: Context, name: String, maxEdge: Int): Bitmap? {
        val target = file(context, name)
        if (!target.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val limit = maxEdge.coerceAtLeast(1)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(target.path, opts)
        }.getOrNull()
    }
}
