package com.xiaoxing.oss

import android.content.Context
import com.xiaoxing.concurrent.AppExecutors
import com.xiaoxing.config.OssConfig
import com.xiaoxing.network.ApiClient
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * 阿里云 OSS 资源同步（公共读 Bucket，虚拟主机式 URL，免签名）。
 *
 * 布局约定（见 TODO.md §5）：
 *   {prefix}/manifest.json                 → {version, files:[{key, sha256}]}
 *   {prefix}/templates/登录.png ...         → 与服务端 Photo/ 同名同内容
 *   {prefix}/workflows/每日签到.json ...     → 同步到 filesDir/workflows/
 *
 * 全量拉取 + sha256 校验；OSS 为唯一资源源，同名工作流以 OSS 为准覆盖。
 */
object OssResourceRepository {

    @Serializable
    data class ResourceFile(
        val key: String,
        @SerialName("sha256") val sha256: String = "",
    )

    @Serializable
    data class ResourceManifest(
        val version: String,
        val files: List<ResourceFile> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sync(context: Context, config: OssConfig): Result<String> = withContext(AppExecutors.dispatcher) {
        if (!config.isComplete) {
            return@withContext Result.failure(IllegalStateException("OSS 配置不完整（bucket/endpoint 必填）"))
        }
        runCatching {
            val root = File(context.filesDir, "resources").apply { mkdirs() }
            val manifest = json.decodeFromString(
                ResourceManifest.serializer(),
                download(config.url("manifest.json")).decodeToString(),
            )

            var updated = 0
            manifest.files.forEach { entry ->
                val bytes = download(config.url(entry.key))
                if (entry.sha256.isNotBlank()) {
                    val actual = sha256(bytes)
                    check(actual.equals(entry.sha256, ignoreCase = true)) {
                        "校验失败 ${entry.key}: 期望 ${entry.sha256.take(8)}… 实际 ${actual.take(8)}…"
                    }
                }
                val target = File(root, entry.key)
                check(target.canonicalPath.startsWith(root.canonicalPath)) { "非法资源路径: ${entry.key}" }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                updated++

                // 工作流同步到引擎读取目录（同名覆盖，OSS 为准）
                if (entry.key.startsWith("workflows/") && entry.key.endsWith(".json")) {
                    File(File(context.filesDir, "workflows").apply { mkdirs() }, entry.key.substringAfterLast('/'))
                        .writeBytes(bytes)
                }
            }
            "已同步版本 ${manifest.version}，共 ${updated} 个文件"
        }
    }

    /** 上次同步缓存中的版本号；无缓存返回 null */
    fun cachedVersion(context: Context): String? =
        File(File(context.filesDir, "resources"), "manifest.json")
            .takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(ResourceManifest.serializer(), it.readText()).version }.getOrNull() }

    private fun download(url: String): ByteArray =
        ApiClient.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "下载失败 HTTP ${response.code}: $url" }
            response.body?.bytes() ?: error("空响应体: $url")
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
