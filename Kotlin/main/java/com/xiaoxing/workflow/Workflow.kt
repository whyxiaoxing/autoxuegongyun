package com.xiaoxing.workflow

import com.xiaoxing.config.IdentitySections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 服务端 rustmatch 模板库。
 *
 * 模板名不再硬编码：由 `POST /GetMatchName` 从服务端 Photo/ 目录读取，
 * 刷新按钮调用 `POST /UpdataPhotoName` 重新扫描。
 * 尚未加载（网络不可达 / 未配置服务器）时 [known] 为空，校验会跳过「未知模板」检查，
 * 避免离线编辑旧工作流时被误判。
 */
object TemplateRegistry {
    @Volatile
    private var loadedNames: Set<String> = emptySet()

    /** 已加载的模板名（保序）；空 = 尚未从服务端加载 */
    val known: Set<String>
        get() = loadedNames

    val isLoaded: Boolean
        get() = loadedNames.isNotEmpty()

    fun replace(names: Collection<String>) {
        // toSet() 保序（LinkedHashSet），模板名顺序与服务端一致
        loadedNames = names.toSet()
    }
}

/** 模板匹配点击偏移的绝对值上限（像素），编辑页与校验共用 */
const val CLICK_OFFSET_LIMIT = 200

/** 工作流步骤：JSON 以 "type" 字段区分（classDiscriminator） */
@Serializable
sealed class Step {
    /** 可选步骤失败不中止工作流 */
    abstract val optional: Boolean
}

@Serializable
@SerialName("launch")
data class LaunchStep(
    /** 要启动的包名。编辑器要求必填：工作流不再保存默认目标应用 */
    @SerialName("package") val packageName: String = "",
    override val optional: Boolean = false,
) : Step()

@Serializable
@SerialName("wait")
data class WaitStep(
    val ms: Long = 1000,
    override val optional: Boolean = false,
) : Step()

/**
 * 截屏 → /See 模板匹配 → 可选点击；timeoutMs 内轮询，间隔 intervalMs。
 *
 * @property offsetX 命中点横向偏移（像素，±200 内），用于微调模板左上角换算出的点击位置
 * @property offsetY 命中点纵向偏移（像素，±200 内）
 */
@Serializable
@SerialName("match")
data class MatchStep(
    val template: String,
    val tap: Boolean = true,
    val timeoutMs: Long = 10000,
    @SerialName("offsetX") val offsetX: Int = 0,
    @SerialName("offsetY") val offsetY: Int = 0,
    override val optional: Boolean = false,
) : Step()

@Serializable
@SerialName("tap")
data class TapStep(
    val x: Int,
    val y: Int,
    override val optional: Boolean = false,
) : Step()

@Serializable
@SerialName("swipe")
data class SwipeStep(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long = 300,
    override val optional: Boolean = false,
) : Step()

@Serializable
@SerialName("inputText")
data class InputTextStep(
    val text: String,
    override val optional: Boolean = false,
) : Step()

/** @property action "back" / "home" */
@Serializable
@SerialName("globalAction")
data class GlobalActionStep(
    val action: String,
    override val optional: Boolean = false,
) : Step()

@Serializable
@SerialName("saveScreenshot")
data class SaveScreenshotStep(
    val tag: String = "凭证",
    override val optional: Boolean = false,
) : Step()

/**
 * AI 文案：把「身份信息」中选中的段拼成提示词，调用 `/AiText`，再把返回文案写入当前聚焦输入框。
 *
 * 只保存选择（sections）与补充要求，实际内容实时读取设置页 —— 身份信息更新后无需改工作流。
 * sections 取值为 [com.xiaoxing.config.IdentitySections] 的 key。
 */
@Serializable
@SerialName("aiText")
data class AiTextStep(
    val sections: List<String> = emptyList(),
    val instruction: String = "",
    /** 生成后写入当前聚焦输入框（关闭时只生成不写入，用于调试/预览） */
    val inputResult: Boolean = true,
    override val optional: Boolean = false,
) : Step()

/**
 * 本地图片模板匹配：截屏后把「本机导入的模板图片」与截图一起提交到 `/See_Submit_Photo`，
 * 服务端不查 Photo/ 模板表，模板完全由用户在本机维护。
 */
@Serializable
@SerialName("photoMatch")
data class PhotoMatchStep(
    val template: String,
    val tap: Boolean = true,
    val timeoutMs: Long = 10000,
    override val optional: Boolean = false,
) : Step()

@Serializable
data class Workflow(
    val name: String,
    /**
     * 旧版的工作流默认目标。新版编辑器不再写入（目标应用随「启动应用」步骤保存），
     * 保留字段是为了兼容已有 JSON；运行时由 [effectiveTargetPackage] 回退到启动步骤。
     */
    @SerialName("targetPackage") val targetPackage: String = "",
    /** match 步骤轮询间隔（在「启动应用」步骤面板里编辑） */
    @SerialName("intervalMs") val intervalMs: Long = 1000,
    /** 动作类步骤（tap/swipe/input/global/launch）重试次数（在「启动应用」步骤面板里编辑） */
    @SerialName("maxRetries") val maxRetries: Int = 3,
    val steps: List<Step> = emptyList(),
)

/** 旧版新建骨架曾把示例占位包名烘焙进 targetPackage，视为未设置 */
const val SKELETON_PLACEHOLDER_PKG = "com.example.target"

/**
 * 目标包名：工作流默认目标优先，其次取第一个指定了包名的「启动应用」步骤。
 * 全局兜底由调用方（执行引擎/页面）处理。
 */
val Workflow.effectiveTargetPackage: String
    get() {
        val raw = targetPackage.trim()
        val workflowTarget = if (raw == SKELETON_PLACEHOLDER_PKG) "" else raw
        return workflowTarget.ifBlank {
            steps.filterIsInstance<LaunchStep>()
                .firstOrNull { it.packageName.isNotBlank() }
                ?.packageName
                ?.trim()
                .orEmpty()
        }
    }

object WorkflowCodec {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 编辑器内存草稿用紧凑 JSON；磁盘仍保持 pretty 格式。 */
    private val compactJson: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(workflow: Workflow): String = json.encodeToString(Workflow.serializer(), workflow)

    fun encodeCompact(workflow: Workflow): String =
        compactJson.encodeToString(Workflow.serializer(), workflow)

    fun decode(text: String): Workflow = json.decodeFromString(Workflow.serializer(), text)

    /** 返回错误列表；空列表 = 校验通过 */
    fun validate(workflow: Workflow): List<String> = buildList {
        if (workflow.name.isBlank()) add("name 不能为空")
        if (workflow.steps.isEmpty()) add("steps 不能为空")
        workflow.steps.forEachIndexed { index, step ->
            when (step) {
                is MatchStep -> {
                    if (step.template.isBlank()) add("步骤#$index: 模板名为空")
                    // 只在已从服务端取到模板列表时校验，离线时不误伤已有工作流
                    if (TemplateRegistry.isLoaded && step.template !in TemplateRegistry.known) {
                        add("步骤#$index: 未知模板「${step.template}」（服务端未注册）")
                    }
                    if (step.offsetX !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT ||
                        step.offsetY !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT
                    ) {
                        add("步骤#$index: 点击偏移需在 ±$CLICK_OFFSET_LIMIT 像素以内")
                    }
                }

                is GlobalActionStep ->
                    if (step.action.lowercase() !in setOf("back", "home")) add("步骤#$index: 未知全局动作「${step.action}」")

                is AiTextStep -> {
                    if (step.sections.isEmpty() && step.instruction.isBlank()) {
                        add("步骤#$index: AI 文案至少选择一段身份信息或填写补充要求")
                    }
                    val unknown = step.sections.filter { IdentitySections.find(it) == null }
                    if (unknown.isNotEmpty()) add("步骤#$index: 未知身份信息段 ${unknown.joinToString()}")
                }

                is PhotoMatchStep ->
                    if (step.template.isBlank()) add("步骤#$index: 本地图片模板不能为空")

                else -> Unit
            }
        }
    }
}
