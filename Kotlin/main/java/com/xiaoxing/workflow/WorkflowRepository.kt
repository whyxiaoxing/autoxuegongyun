package com.xiaoxing.workflow

import android.content.Context
import java.io.File

/** 工作流本地存储：filesDir/workflows/<name>.json（OSS 同步的同名文件也落到此目录） */
object WorkflowRepository {

    private fun dir(context: Context): File = File(context.filesDir, "workflows").apply { mkdirs() }

    private fun file(context: Context, name: String): File {
        val safe = name.trim().ifBlank { error("工作流名为空") }
        require(!safe.contains('/') && !safe.contains('\\') && safe != "." && safe != "..") {
            "非法工作流名: $safe"
        }
        return File(dir(context), "$safe.json")
    }

    fun list(context: Context): List<String> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()

    fun rawJson(context: Context, name: String): String? =
        runCatching { file(context, name) }.getOrNull()
            ?.takeIf { it.isFile }
            ?.readText()

    fun load(context: Context, name: String): Workflow? = rawJson(context, name)?.let { raw ->
        runCatching { WorkflowCodec.decode(raw) }.getOrNull()
    }

    /** 保存前先解码校验；通过后按原文写入 */
    fun save(context: Context, name: String, jsonText: String): Result<Unit> = runCatching {
        val workflow = WorkflowCodec.decode(jsonText)
        val errors = WorkflowCodec.validate(workflow)
        if (errors.isNotEmpty()) error("校验失败:\n" + errors.joinToString("\n"))
        file(context, name.ifBlank { workflow.name }).writeText(jsonText)
    }

    fun delete(context: Context, name: String): Boolean =
        runCatching { file(context, name) }.getOrNull()?.delete() == true
}
