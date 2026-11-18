package com.xiaoxing.workflow

import com.xiaoxing.config.IdentitySections
import com.xiaoxing.config.ReportTypes
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

/** 单个步骤失败后的最大重试次数（首次执行不计入），编辑页与校验共用 */
const val MAX_FAILURE_RETRIES = 10

/** IF 条件类型：服务端模板匹配（/See） */
const val IF_CONDITION_MATCH = "match"

/** IF 条件类型：本地图片模板匹配（/See_Submit_Photo） */
const val IF_CONDITION_PHOTO = "photoMatch"

/** IF 条件类型：屏幕文字识别（/OCRPhoto） */
const val IF_CONDITION_OCR = "ocr"

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
 * AI 文案：把「身份信息」拆成 assistant 内容与 user 提示词，调用 `/AiText`，
 * 再把返回文案写入当前聚焦输入框。
 *
 *  - assistant：实习单位/身份描述（设置里可关闭默认发送）+ 报告类型「这是一份周报」；
 *  - 提示词：[sections] 选中的正文段（今日事件/日报/周报/月报）+ [instruction]。
 *
 * 只保存选择（sections）、报告类型与补充要求，实际内容实时读取设置页 —— 身份信息更新后无需改工作流。
 * 编辑器只保存 [com.xiaoxing.config.IdentitySections.prompt] 的 key；
 * 旧流程 sections 里残留的 unit/role 会被当作 assistant 身份段发送（以便平滑迁移）。
 */
@Serializable
@SerialName("aiText")
data class AiTextStep(
    val sections: List<String> = emptyList(),
    val instruction: String = "",
    /**
     * 报告类型：[com.xiaoxing.config.ReportTypes] 的 key（daily/weekly/monthly）；
     * 空串 = 不指定。选择后 assistant 内容以「这是一份日报/周报/月报」收尾。
     */
    val reportType: String = "",
    /** 生成后写入当前聚焦输入框（关闭时只生成不写入，用于调试/预览） */
    val inputResult: Boolean = true,
    override val optional: Boolean = false,
) : Step()

/**
 * 本地图片模板匹配：截屏后把「本机导入的模板图片」与截图一起提交到 `/See_Submit_Photo`，
 * 服务端不查 Photo/ 模板表，模板完全由用户在本机维护。
 *
 * @property offsetX 命中点横向偏移（像素，±200 内），用于微调模板左上角换算出的点击位置
 * @property offsetY 命中点纵向偏移（像素，±200 内）
 */
@Serializable
@SerialName("photoMatch")
data class PhotoMatchStep(
    val template: String,
    val tap: Boolean = true,
    val timeoutMs: Long = 10000,
    @SerialName("offsetX") val offsetX: Int = 0,
    @SerialName("offsetY") val offsetY: Int = 0,
    override val optional: Boolean = false,
) : Step()

/**
 * 条件分支（IF）：条件 = 「模板匹配」「图片匹配」或「文字识别」是否成功（只判断命中，不点击）。
 *
 * 语义与代码里的 `if` 一致：
 *  - [condition] = [IF_CONDITION_MATCH]：截屏 → `/See` 匹配服务端模板 [template]；
 *  - [condition] = [IF_CONDITION_PHOTO]：截屏 → `/See_Submit_Photo` 匹配本地图片模板 [template]；
 *  - [condition] = [IF_CONDITION_OCR]：截屏 → `/OCRPhoto` 查找 [keyword]（服务端过滤）；
 *  - timeoutMs 内命中 → 执行 [thenSteps]；超时未命中（或请求一直失败）→ 执行 [elseSteps]。
 *
 * [optional] 只作用于块内步骤：块内任一非可选步骤失败时，可选块会跳过剩余步骤并继续执行后续流程；
 * 非可选块则中止整条流程（块内步骤失败时已各自按「失败重复次数」重试过）。
 * 条件不成立只是走「否则分支」，不算失败。
 */
@Serializable
@SerialName("if")
data class IfStep(
    /** 条件类型：[IF_CONDITION_MATCH]（服务端模板）/ [IF_CONDITION_PHOTO]（本地图片模板）/ [IF_CONDITION_OCR]（文字识别） */
    val condition: String = IF_CONDITION_MATCH,
    /** 条件模板名（服务端 Photo/ 名称或本机模板名，随 [condition] 而定；OCR 条件不使用） */
    val template: String = "",
    /** OCR 条件要查找的文字（由服务端 /OCRPhoto 按目标文字过滤；仅 [IF_CONDITION_OCR] 使用） */
    val keyword: String = "",
    val timeoutMs: Long = 10000,
    /** 条件成立时执行的步骤 */
    @SerialName("thenSteps") val thenSteps: List<Step> = emptyList(),
    /** 条件不成立时执行的步骤 */
    @SerialName("elseSteps") val elseSteps: List<Step> = emptyList(),
    override val optional: Boolean = false,
) : Step()

/**
 * 屏幕文字识别：截屏 → `POST /OCRPhoto`（目标文字随请求提交）返回 `{文字: [centerX, centerY]}`。
 *
 * 在 timeoutMs 内轮询，服务端返回命中 [keyword] 的文字后（默认）点击该文字中心坐标。
 *
 * @property offsetX 命中点横向偏移（像素，±200 内），用于微调文字中心换算出的点击位置
 * @property offsetY 命中点纵向偏移（像素，±200 内）
 */
@Serializable
@SerialName("ocr")
data class OcrStep(
    /** 要查找的文字（随 /OCRPhoto 提交，由服务端按目标文字过滤） */
    val keyword: String,
    val tap: Boolean = true,
    val timeoutMs: Long = 10000,
    @SerialName("offsetX") val offsetX: Int = 0,
    @SerialName("offsetY") val offsetY: Int = 0,
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
    /**
     * 每个功能失败后的重试次数（在功能库「流程控制」里编辑）。
     * 某一步失败时就地重试该步骤（模板匹配超时、动作失败等统统一视同仁），
     * 不再是整条流程从头重放；0 = 失败即停止（不重试）。
     *
     * 旧版 JSON 里的 `maxRetries` 会被忽略（ignoreUnknownKeys）。
     */
    @SerialName("failureRetries") val failureRetries: Int = 0,
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
        return workflowTarget.ifBlank { steps.firstLaunchPackage() }
    }

/**
 * 深度优先查找第一个指定了包名的「启动应用」步骤，顺序与 [flattenSteps] 一致。
 * 直接递归查找，不再先展开整条流程再过滤（列表刷新/首页摘要都会走到这里）。
 */
private fun List<Step>.firstLaunchPackage(): String =
    firstNotNullOfOrNull { step ->
        when (step) {
            is LaunchStep -> step.packageName.trim().takeIf { it.isNotEmpty() }

            is IfStep -> step.thenSteps.firstLaunchPackage()
                .ifEmpty { step.elseSteps.firstLaunchPackage() }
                .takeIf { it.isNotEmpty() }

            else -> null
        }
    }.orEmpty()

/**
 * 深度展开步骤：IF 的两个分支会展开到同一列表（块本身保留在最前）。
 *
 * 初始化预检（模板/截屏/无障碍/目标 App）和校验都基于展开后的列表，
 * 避免嵌套在控制流里的步骤绕过依赖检查。
 *
 * 用 [buildList] 单次分配构建（旧实现的 flatMap + listOf + `+` 会在每一层产生
 * 多份中间列表，深层/多分支流程的预处理开销与 GC 压力都会被放大）。
 */
fun List<Step>.flattenSteps(): List<Step> = buildList {
    fun addDeep(steps: List<Step>) {
        for (step in steps) {
            add(step)
            if (step is IfStep) {
                addDeep(step.thenSteps)
                addDeep(step.elseSteps)
            }
        }
    }
    addDeep(this@flattenSteps)
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
        if (workflow.failureRetries !in 0..MAX_FAILURE_RETRIES) {
            add("failureRetries 需在 0..$MAX_FAILURE_RETRIES 之间（每个步骤失败后的重试次数）")
        }
        validateSteps(workflow.steps, "步骤")
    }

    /** 递归校验（含 IF 块内步骤）；[path] 用于在错误文案里标出嵌套位置 */
    private fun MutableList<String>.validateSteps(steps: List<Step>, path: String) {
        steps.forEachIndexed { index, step ->
            val label = "$path#$index"
            when (step) {
                is MatchStep -> {
                    if (step.template.isBlank()) add("$label: 模板名为空")
                    // 只在已从服务端取到模板列表时校验，离线时不误伤已有工作流
                    if (TemplateRegistry.isLoaded && step.template !in TemplateRegistry.known) {
                        add("$label: 未知模板「${step.template}」（服务端未注册）")
                    }
                    if (step.offsetX !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT ||
                        step.offsetY !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT
                    ) {
                        add("$label: 点击偏移需在 ±$CLICK_OFFSET_LIMIT 像素以内")
                    }
                }

                is GlobalActionStep ->
                    if (
                        !step.action.equals("back", ignoreCase = true) &&
                        !step.action.equals("home", ignoreCase = true)
                    ) {
                        add("$label: 未知全局动作「${step.action}」")
                    }

                is AiTextStep -> {
                    if (step.sections.isEmpty() && step.instruction.isBlank()) {
                        add("$label: AI 文案至少选择一段提示词内容或填写补充要求")
                    }
                    val unknown = step.sections.filter { IdentitySections.find(it) == null }
                    if (unknown.isNotEmpty()) add("$label: 未知身份信息段 ${unknown.joinToString()}")
                    if (step.reportType.isNotBlank() && ReportTypes.find(step.reportType) == null) {
                        add("$label: 未知报告类型「${step.reportType}」")
                    }
                }

                is PhotoMatchStep -> {
                    if (step.template.isBlank()) add("$label: 本地图片模板不能为空")
                    if (step.offsetX !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT ||
                        step.offsetY !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT
                    ) {
                        add("$label: 点击偏移需在 ±$CLICK_OFFSET_LIMIT 像素以内")
                    }
                }

                is OcrStep -> {
                    if (step.keyword.isBlank()) add("$label: OCR 识别文字不能为空")
                    if (step.offsetX !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT ||
                        step.offsetY !in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT
                    ) {
                        add("$label: 点击偏移需在 ±$CLICK_OFFSET_LIMIT 像素以内")
                    }
                }

                is IfStep -> {
                    when (step.condition) {
                        IF_CONDITION_MATCH, IF_CONDITION_PHOTO, IF_CONDITION_OCR -> Unit
                        else -> add("$label: 未知 IF 条件类型「${step.condition}」")
                    }
                    when (step.condition) {
                        IF_CONDITION_PHOTO ->
                            if (step.template.isBlank()) add("$label: IF 条件图片模板不能为空")

                        IF_CONDITION_MATCH ->
                            if (step.template.isBlank()) add("$label: IF 条件模板不能为空")

                        IF_CONDITION_OCR ->
                            if (step.keyword.isBlank()) add("$label: IF 条件识别文字不能为空")

                        else -> Unit
                    }
                    if (
                        step.condition == IF_CONDITION_MATCH &&
                        TemplateRegistry.isLoaded &&
                        step.template.isNotBlank() &&
                        step.template !in TemplateRegistry.known
                    ) {
                        add("$label: 未知模板「${step.template}」（服务端未注册）")
                    }
                    validateSteps(step.thenSteps, "$label IF 成立分支")
                    validateSteps(step.elseSteps, "$label IF 否则分支")
                }

                is LaunchStep, is WaitStep, is TapStep, is SwipeStep, is InputTextStep,
                is GlobalActionStep, is SaveScreenshotStep,
                -> Unit
            }
        }
    }
}
