package com.xiaoxing.workflow

import android.content.Context
import android.content.Intent
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.accessibility.AutomationAccessibilityService
import com.xiaoxing.capture.ScreenCaptureManager
import com.xiaoxing.capture.ScreenshotStore
import com.xiaoxing.concurrent.AppExecutors
import com.xiaoxing.config.IdentityInfo
import com.xiaoxing.config.IdentitySections
import com.xiaoxing.config.ReportTypes
import com.xiaoxing.network.AiTextClient
import com.xiaoxing.network.MatchOutcome
import com.xiaoxing.network.OcrClient
import com.xiaoxing.network.SeeClient
import com.xiaoxing.photo.LocalTemplateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 工作流执行引擎（运行于前台服务协程作用域）。
 *
 * 执行前先走 [initialize]：无障碍连接、帧池首帧预热、令牌/模板预取、本地图片模板、
 * 身份信息提示词、目标 App 可拉起校验，全部就绪后才执行步骤 —— 避免工作流快于
 * 帧池/令牌，跑到中途才暴露依赖缺失。
 *
 * 步骤失败语义：
 *  - match / photoMatch：timeoutMs 内按 intervalMs 轮询（截屏→识别→点击），超时未匹配 = 失败
 *  - 动作类（launch/tap/swipe/inputText/globalAction）：单次执行，失败由步骤级重试兜底
 *  - aiText：assistant 内容取自身份信息（单位/身份描述 + 报告类型），
 *    任务启动时即在 [AppExecutors.dispatcher] 预取 /AiText 结果，步骤执行时直接取用；
 *    未预取（如运行中改了身份信息）才现取，然后写入聚焦输入框
 *  - ocr：截屏 → /OCRPhoto → 命中文字后可选点击
 *  - if：条件分支，按代码语义执行嵌套步骤（详见 [IfStep]）；条件不成立不算失败
 *  - 失败后：按 [Workflow.failureRetries] 就地重试该步骤（首次执行不计入），
 *    重试仍失败时 optional = true 跳过，否则中止整个工作流（不再整条流程重放）。
 *    IF 块内的每个步骤同样各自重试；块内的非可选失败直接上抛中止整条流程。
 */
class WorkflowEngine(
    private val context: Context,
    private val capture: ScreenCaptureManager,
    private val see: SeeClient,
    private val aiText: AiTextClient,
    private val ocr: OcrClient,
    private val identity: () -> IdentityInfo,
) {

    /** 非可选步骤失败时抛出，携带中止原因 */
    open class AbortException(message: String) : Exception(message)

    /** launch 步骤成功后回调（用于最小化自身窗口） */
    var onMinimizeRequest: (() -> Unit)? = null

    /** 当前执行状态描述（面向页面展示） */
    var onStatusChange: ((String) -> Unit)? = null

    /**
     * 本次运行的 AI 文案预取：提示词 + assistant 内容 → 线程池里的生成任务。
     * [run] 一开始就发起，步骤执行到 AI 文案时直接 await 结果，不再从零等待生成。
     */
    @Volatile
    private var aiPrefetches: Map<AiPrompt, Deferred<Result<String>>> = emptyMap()

    /** AI 文案请求的两段内容；相同组合只请求一次 */
    private data class AiPrompt(val prompt: String, val assistant: String)

    /**
     * @param awaitUserConfirm 投屏二次确认闸门（第二层闸门），入参是解析后的目标包名。
     *   返回 false = 用户取消/超时，不进入工作流。
     *   与 [initialize] 并行执行：用户下拉通知栏点「继续共享」的时间正好覆盖初始化耗时。
     */
    suspend fun run(
        workflow: Workflow,
        fallbackPackage: String,
        awaitUserConfirm: suspend (targetPackage: String) -> Boolean = { _ -> true },
    ) {
        val target = resolveTarget(workflow, fallbackPackage)
        // 整条流程只展开一次：预取与初始化预检共用同一份步骤列表。
        val allSteps = workflow.steps.flattenSteps()
        onStatusChange?.invoke("初始化: ${workflow.name}")

        // AI 文案预取：一启动就把它丢进线程池（生成可能耗时数分钟），与初始化、投屏二次确认并行。
        // 流程走到 AI 步骤时通常已经生成完毕，只等待剩余时间；生成任务随本次运行结束一起取消。
        val prefetchScope = CoroutineScope(SupervisorJob() + AppExecutors.dispatcher)
        aiPrefetches = startAiPrefetch(allSteps, prefetchScope)
        try {
            runInternal(workflow, allSteps, target, awaitUserConfirm)
        } finally {
            prefetchScope.cancel()
            aiPrefetches = emptyMap()
        }
    }

    private suspend fun runInternal(
        workflow: Workflow,
        allSteps: List<Step>,
        target: String,
        awaitUserConfirm: suspend (targetPackage: String) -> Boolean,
    ) {
        // 初始化与「投屏二次确认」互不依赖，并行跑：总耗时 ≈ max(初始化, 用户确认)。
        // 用户取消时取消初始化（注意：内部阻塞中的 OkHttp 调用不可中断，会等它返回后立即抛出取消）。
        val (confirmed, initResult) = coroutineScope {
            val initJob = async { initialize(allSteps, target) }
            val confirmed = awaitUserConfirm(target)
            if (!confirmed) {
                initJob.cancel()
                false to InitResult(false, emptyList())
            } else {
                confirmed to initJob.await()
            }
        }

        if (!confirmed) {
            onStatusChange?.invoke("已取消: ${workflow.name}")
            throw AbortException("投屏二次确认未通过（取消/超时），工作流未启动")
        }
        if (!initResult.ok) {
            onStatusChange?.invoke("初始化失败: ${workflow.name}")
            throw AbortException(
                "初始化失败: " + initResult.problems.ifEmpty { listOf("运行依赖未就绪") }
                    .joinToString("；")
            )
        }

        // 「失败重复次数」是功能库内的步骤级重试（在 executeSteps 里消化），
        // 这里不再有整条流程从头重放的第二层循环。
        executeSteps(workflow.steps, target, workflow)

        onStatusChange?.invoke("完成: ${workflow.name}")
    }

    /**
     * 按顺序执行一组步骤。每个步骤失败后就地重试，次数 = [Workflow.failureRetries]；
     * 重试耗尽仍失败时，非可选步骤抛出 [AbortException]，可选步骤跳过继续。
     *
     * @param prefix 嵌套块的状态前缀（缩进 + 分支名），顶层为空
     */
    private suspend fun executeSteps(
        steps: List<Step>,
        target: String,
        workflow: Workflow,
        prefix: String = "",
    ) {
        val retries = workflow.failureRetries.coerceIn(0, MAX_FAILURE_RETRIES)
        steps.forEachIndexed { index, step ->
            val desc = describe(step)
            var lastDetail: String? = null
            var succeeded = false
            var attempt = 0

            while (!succeeded) {
                attempt++
                onStatusChange?.invoke(
                    buildString {
                        append("$prefix[${index + 1}/${steps.size}] $desc")
                        if (attempt > 1) append("（重试 ${attempt - 1}/$retries）")
                    },
                )

                val result = runCatching { execute(step, target, workflow, prefix) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        // 控制流块内的中止原因必须原样上抛：块内步骤已经各自重试过，
                        // 这里不再对 IF 本身重试
                        if (it is AbortException) throw it
                    }
                    .getOrElse { e -> StepResult(false, "异常: ${e.message}") }

                if (result.ok) {
                    succeeded = true
                } else {
                    lastDetail = result.detail
                    // 共执行 1 + retries 次
                    if (attempt > retries) break
                    delay(RETRY_DELAY_MS)
                }
            }

            if (!succeeded && !step.optional) {
                onStatusChange?.invoke("${prefix}中止: $desc")
                val reason = lastDetail?.let { "（$it）" }.orEmpty()
                throw AbortException("步骤失败，工作流中止: $desc$reason")
            }
            // 最后一步之后不再空等：顶层直接结束，IF 块边界由外层 IF 步骤自身的停顿接管。
            if (index != steps.lastIndex) delay(workflow.intervalMs.coerceAtLeast(0))
        }
    }

    /**
     * 初始化：按工作流实际用到的能力预取依赖，任何一项不满足都立即中止（不再跑到中途才失败）。
     *  - 无障碍服务连接（点击/滑动/输入/全局动作/AI 写入）
     *  - 帧池首帧预热（match / photoMatch / saveScreenshot）
     *  - 服务端令牌与模板注册状态（match / photoMatch / aiText）
     *  - 本地图片模板存在（photoMatch）
     *  - 身份信息提示词非空（aiText）
     *  - 目标 App 可拉起（launch）
     */
    private suspend fun initialize(
        allSteps: List<Step>,
        target: String,
    ): InitResult {
        // 单次遍历收集依赖（旧实现对展开列表反复 filterIsInstance/map/any，
        // 每项检查都会再遍历并分配一次中间列表）。
        val templates = LinkedHashSet<String>()
        val photoTemplates = LinkedHashSet<String>()
        val aiSteps = ArrayList<AiTextStep>()
        var needsServer = false
        var needsCapture = false
        var needsGesture = false

        for (step in allSteps) {
            when (step) {
                is MatchStep -> {
                    if (step.template.isNotBlank()) {
                        templates += step.template
                        needsServer = true
                    }
                    needsCapture = true
                    if (step.tap) needsGesture = true
                }

                is PhotoMatchStep -> {
                    if (step.template.isNotBlank()) {
                        photoTemplates += step.template
                        needsServer = true
                    }
                    needsCapture = true
                    if (step.tap) needsGesture = true
                }

                is AiTextStep -> {
                    aiSteps += step
                    needsServer = true
                    if (step.inputResult) needsGesture = true
                }

                is OcrStep -> {
                    needsServer = true
                    needsCapture = true
                    if (step.tap) needsGesture = true
                }

                is IfStep -> {
                    // IF 的条件判定本身也要截屏
                    needsCapture = true
                    when (step.condition) {
                        // 服务端模板条件同样走 /See
                        IF_CONDITION_MATCH -> if (step.template.isNotBlank()) {
                            templates += step.template
                            needsServer = true
                        }

                        // 本地图片模板条件走 /See_Submit_Photo
                        IF_CONDITION_PHOTO -> if (step.template.isNotBlank()) {
                            photoTemplates += step.template
                            needsServer = true
                        }

                        // 文字条件走 /OCRPhoto
                        IF_CONDITION_OCR -> needsServer = true
                    }
                }

                is SaveScreenshotStep -> needsCapture = true

                is TapStep, is SwipeStep, is InputTextStep, is GlobalActionStep ->
                    needsGesture = true

                is LaunchStep, is WaitStep -> Unit
            }
        }

        // 各检查互不依赖，各自在独立协程里跑（总耗时 ≈ 最慢一项，而不是总和）。
        val results = coroutineScope {
            listOf(
                async { checkAccessibility(needsGesture) },
                async { checkCapture(needsCapture) },
                async { checkServer(templates.toList(), needsServer) },
                async { checkAiPrompts(aiSteps) },
                async(AppExecutors.dispatcher) { checkLaunchTargets(allSteps, target) },
                async(AppExecutors.dispatcher) { checkPhotoTemplates(photoTemplates.toList()) },
            ).awaitAll()
        }
        val problems = results.flatMap { it.problems }
        return InitResult(results.all { it.ok }, problems)
    }

    /** 单项预检结果：是否通过 + 失败原因（成功项不产生文案） */
    private class CheckResult(val ok: Boolean, val problems: List<String> = emptyList())

    private class InitResult(val ok: Boolean, val problems: List<String>)

    private class StepResult(val ok: Boolean, val detail: String? = null)

    private fun checkAccessibility(needsGesture: Boolean): CheckResult = when {
        !needsGesture -> CheckResult(true)
        AutomationAccessibilityService.isConnected -> CheckResult(true)
        else -> CheckResult(false, listOf("无障碍服务未连接（请到系统设置重新开启）"))
    }

    private suspend fun checkCapture(needsCapture: Boolean): CheckResult = when {
        !needsCapture -> CheckResult(true)
        !capture.isReady -> CheckResult(false, listOf("截屏链路未就绪（投屏授权/前台服务异常）"))
        capture.awaitFirstFrame(FIRST_FRAME_TIMEOUT_MS) -> CheckResult(true)
        else -> CheckResult(
            false,
            listOf("帧池 ${FIRST_FRAME_TIMEOUT_MS}ms 内未产出首帧（投屏可能未生效）"),
        )
    }

    private suspend fun checkServer(templates: List<String>, needed: Boolean): CheckResult {
        if (!needed) return CheckResult(true)
        val unknown = if (TemplateRegistry.isLoaded) {
            templates.filter { it !in TemplateRegistry.known }
        } else {
            emptyList()
        }
        if (unknown.isNotEmpty()) {
            return CheckResult(false, listOf("服务端未注册模板: ${unknown.joinToString()}"))
        }
        return try {
            see.prepare()
            CheckResult(true)
        } catch (e: CancellationException) {
            throw e // 协程取消必须透传，不能当普通失败吞掉
        } catch (e: Exception) {
            CheckResult(false, listOf("令牌获取失败: ${e.message}"))
        }
    }

    private fun checkAiPrompts(aiSteps: List<AiTextStep>): CheckResult {
        if (aiSteps.isEmpty()) return CheckResult(true)
        val info = identity()
        val problems = aiSteps.mapNotNull { step ->
            when {
                step.sections.any { IdentitySections.find(it) == null } ->
                    "AI 文案步骤包含未知身份信息段"

                info.buildPrompt(step.sections, step.instruction).isBlank() ->
                    "AI 文案提示词为空：请选择提示词内容或填写补充要求，并在 设置 → 身份信息 中填写对应内容"

                else -> null
            }
        }
        return CheckResult(problems.isEmpty(), problems)
    }

    private fun checkPhotoTemplates(templates: List<String>): CheckResult {
        val missing = templates.filterNot { LocalTemplateStore.exists(context, it) }
        return if (missing.isEmpty()) {
            CheckResult(true)
        } else {
            CheckResult(
                false,
                listOf("本地图片模板不存在: ${missing.joinToString()}（设置 → 本地模板）"),
            )
        }
    }

    private fun checkLaunchTargets(allSteps: List<Step>, target: String): CheckResult = try {
        val problems = mutableListOf<String>()
        allSteps.filterIsInstance<LaunchStep>().forEach { step ->
            val pkg = step.packageName.ifBlank { target }
            val launchable = pkg.isNotBlank() && context.packageManager.getLaunchIntentForPackage(pkg) != null
            if (!launchable && !step.optional) {
                problems += "目标 App 不可拉起: ${pkg.ifBlank { "<未指定包名>" }}（检查包名/安装状态）"
            }
        }
        CheckResult(problems.isEmpty(), problems)
    } catch (e: Exception) {
        // 单项检查抛异常不能让整个 coroutineScope 连带取消「二次确认」的等待（否则弹窗会莫名消失）
        CheckResult(false, listOf("目标 App 检查异常: ${e.message}"))
    }

    private suspend fun execute(
        step: Step,
        fallbackPackage: String,
        workflow: Workflow,
        prefix: String,
    ): StepResult =
        when (step) {
            is MatchStep -> runMatch(step, workflow.intervalMs)
            is PhotoMatchStep -> runPhotoMatch(step, workflow.intervalMs)
            is AiTextStep -> runAiText(step)
            is OcrStep -> runOcr(step, workflow.intervalMs)
            is IfStep -> runIf(step, fallbackPackage, workflow, prefix)
            is LaunchStep -> runLaunch(step, fallbackPackage)
            is WaitStep -> {
                delay(step.ms.coerceAtLeast(0))
                StepResult(true)
            }

            // 动作类单次执行；失败后由 executeSteps 按「失败重复次数」重试该步骤
            is TapStep -> if (AutomationAccessibilityService.tap(step.x, step.y)) {
                StepResult(true)
            } else {
                StepResult(false, "无障碍点击失败（请检查服务是否开启）")
            }

            is SwipeStep -> if (
                AutomationAccessibilityService.swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs)
            ) {
                StepResult(true)
            } else {
                StepResult(false, "无障碍滑动失败（请检查服务是否开启）")
            }

            is InputTextStep -> if (AutomationAccessibilityService.inputText(step.text)) {
                StepResult(true)
            } else {
                StepResult(false, "输入失败：没有可写入的输入框或无障碍未开启")
            }

            is GlobalActionStep -> if (AutomationAccessibilityService.globalAction(step.action)) {
                StepResult(true)
            } else {
                StepResult(false, "全局动作失败（请检查服务是否开启）")
            }

            is SaveScreenshotStep -> runSaveScreenshot(step.tag)
        }

    private suspend fun runMatch(step: MatchStep, intervalMs: Long): StepResult {
        val interval = intervalMs.coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceAtLeast(0)
        var attempts = 0
        var lastDetail: String? = null
        // 保持帧开启时，画面未变化（帧号相同）就没必要把同一张图再发给服务端：
        // 同一帧的识别结果必然相同，直接复用上一轮结果，省掉一次大 JSON 请求。
        var lastFrameId = -1L
        var cachedOutcome: MatchOutcome? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val frameId = capture.currentFrameId
            val outcome: MatchOutcome = if (
                frameId > 0L && frameId == lastFrameId && cachedOutcome != null
            ) {
                cachedOutcome!!
            } else {
                attempts++
                val fresh = try {
                    see.match(capture.capturePngBase64(), step.template)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastDetail = "请求异常: ${e.message}"
                    delay(interval)
                    continue
                }
                if (frameId > 0L) {
                    lastFrameId = frameId
                    cachedOutcome = fresh
                }
                fresh
            }
            when (outcome) {
                is MatchOutcome.Found -> {
                    // 点击位置 = 模板命中点 + 用户配置的偏移量（±200），便于避开图标边缘/命中到相邻控件
                    val tapX = outcome.x + step.offsetX
                    val tapY = outcome.y + step.offsetY
                    if (!step.tap || AutomationAccessibilityService.tap(tapX, tapY)) {
                        return StepResult(true)
                    }
                    return StepResult(false, "命中 ($tapX, $tapY) 但无障碍点击失败")
                }

                is MatchOutcome.NotFound -> {
                    lastDetail = outcome.detail
                    delay(interval)
                }
            }
        }
        return StepResult(
            false,
            "「${step.template}」超时未匹配（尝试 $attempts 次）" +
                (lastDetail?.let { "；最后原因: $it" } ?: ""),
        )
    }

    private suspend fun runPhotoMatch(step: PhotoMatchStep, intervalMs: Long): StepResult {
        val templateBase64 = withContext(AppExecutors.dispatcher) {
            LocalTemplateStore.readBase64(context, step.template)
        } ?: return StepResult(false, "本地模板不存在: ${step.template}")
        val interval = intervalMs.coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceAtLeast(0)
        var attempts = 0
        var lastDetail: String? = null
        var lastFrameId = -1L
        var cachedOutcome: MatchOutcome? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val frameId = capture.currentFrameId
            val outcome: MatchOutcome = if (
                frameId > 0L && frameId == lastFrameId && cachedOutcome != null
            ) {
                cachedOutcome!!
            } else {
                attempts++
                val fresh = try {
                    see.matchPhoto(templateBase64, capture.capturePngBase64())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastDetail = "请求异常: ${e.message}"
                    delay(interval)
                    continue
                }
                if (frameId > 0L) {
                    lastFrameId = frameId
                    cachedOutcome = fresh
                }
                fresh
            }
            when (outcome) {
                is MatchOutcome.Found -> {
                    // 点击位置 = 模板命中点 + 用户配置的偏移量（±200），便于避开图标边缘/命中到相邻控件
                    val tapX = outcome.x + step.offsetX
                    val tapY = outcome.y + step.offsetY
                    if (!step.tap || AutomationAccessibilityService.tap(tapX, tapY)) {
                        return StepResult(true)
                    }
                    return StepResult(false, "命中 ($tapX, $tapY) 但无障碍点击失败")
                }

                is MatchOutcome.NotFound -> {
                    lastDetail = outcome.detail
                    delay(interval)
                }
            }
        }
        return StepResult(
            false,
            "图片模板「${step.template}」超时未匹配（尝试 $attempts 次）" +
                (lastDetail?.let { "；最后原因: $it" } ?: ""),
        )
    }

    /**
     * OCR：截屏 → /OCRPhoto → 服务端按目标文字过滤，命中后（默认）点击其文字中心 + 偏移量。
     * 与模板匹配一样在 timeoutMs 内轮询，请求异常只记录原因并继续尝试。
     */
    private suspend fun runOcr(step: OcrStep, intervalMs: Long): StepResult {
        val keyword = step.keyword.trim()
        if (keyword.isEmpty()) return StepResult(false, "OCR 识别文字为空")
        val interval = intervalMs.coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceAtLeast(0)
        var attempts = 0
        var lastDetail: String? = null
        var lastFrameId = -1L
        var cachedResults: Map<String, List<Int>>? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val frameId = capture.currentFrameId
            val results = if (frameId > 0L && frameId == lastFrameId && cachedResults != null) {
                cachedResults!!
            } else {
                attempts++
                val fresh = try {
                    ocr.recognize(capture.capturePngBase64(), keyword)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastDetail = "请求异常: ${e.message}"
                    delay(interval)
                    continue
                }
                if (frameId > 0L) {
                    lastFrameId = frameId
                    cachedResults = fresh
                }
                fresh
            }
            // 服务端已按目标文字过滤（含易混字符映射），返回的每个 key 都是命中项；
            // 这里只取第一个带坐标的结果，不能再按 keyword 做包含判断。
            val hit = results.entries.firstOrNull { it.value.size >= 2 }
            if (hit != null) {
                // 点击位置 = 文字中心（服务端返回 bbox 中心）+ 用户配置的偏移量（±200）
                val tapX = hit.value[0] + step.offsetX
                val tapY = hit.value[1] + step.offsetY
                if (!step.tap || AutomationAccessibilityService.tap(tapX, tapY)) {
                    return StepResult(true)
                }
                return StepResult(false, "识别到「${hit.key}」($tapX, $tapY) 但无障碍点击失败")
            }
            lastDetail = if (results.isEmpty()) {
                "本次未识别到「$keyword」"
            } else {
                "识别结果缺少坐标: ${results.keys.take(3).joinToString()}"
            }
            delay(interval)
        }
        return StepResult(
            false,
            "文字「$keyword」超时未识别（尝试 $attempts 次）" +
                (lastDetail?.let { "；最后原因: $it" } ?: ""),
        )
    }

    /**
     * IF：条件（模板匹配 / 图片匹配 / 文字识别）在超时内成功 → 成立分支，否则 → 否则分支。
     * 条件本身不产生失败（不成立只是走否则分支）；分支内步骤各自按「失败重复次数」重试，
     * 非可选步骤重试耗尽后中止整条流程。
     */
    private suspend fun runIf(
        step: IfStep,
        target: String,
        workflow: Workflow,
        prefix: String,
    ): StepResult {
        val matched = awaitCondition(step, workflow.intervalMs)
        val branch = if (matched) step.thenSteps else step.elseSteps
        val branchLabel = if (matched) "IF 成立" else "IF 否则"
        onStatusChange?.invoke("$prefix→ $branchLabel（${branch.size} 步）")
        try {
            executeSteps(branch, target, workflow, "$prefix  ")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AbortException) {
            // 可选块：跳过剩余步骤并继续；非可选块：原样上抛（块内步骤已经重试过）
            if (!step.optional) throw e
            onStatusChange?.invoke("$branchLabel 内步骤失败，可选块已跳过")
        }
        return StepResult(true)
    }

    /**
     * IF 条件：按 [IfStep.condition] 轮询对应的识别接口
     * （服务端模板 / 本机图片模板 / OCR 文字）。
     * timeoutMs 内命中返回 true；超时或请求一直异常返回 false（条件判定本身不产生失败）。
     */
    private suspend fun awaitCondition(step: IfStep, intervalMs: Long): Boolean {
        when (step.condition) {
            IF_CONDITION_MATCH ->
                if (step.template.isBlank()) return false

            IF_CONDITION_PHOTO ->
                if (step.template.isBlank()) return false

            IF_CONDITION_OCR ->
                if (step.keyword.isBlank()) return false

            else -> return false // 未知条件类型按不成立处理
        }
        val template = step.template
        val keyword = step.keyword.trim()
        // 图片条件先取一次本机模板；读不到直接判为不成立（磁盘读取下沉到工作线程）
        val photoBase64 = if (step.condition == IF_CONDITION_PHOTO) {
            withContext(AppExecutors.dispatcher) {
                LocalTemplateStore.readBase64(context, template)
            } ?: return false
        } else {
            null
        }
        val interval = intervalMs.coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceAtLeast(0)
        // 同 runMatch：画面未更新时复用上一轮条件结果，不重复上传同一张截图。
        var lastFrameId = -1L
        var cachedHit: Boolean? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val frameId = capture.currentFrameId
            val hit = if (frameId > 0L && frameId == lastFrameId && cachedHit != null) {
                cachedHit!!
            } else {
                val fresh = try {
                    when {
                        photoBase64 != null ->
                            see.matchPhoto(photoBase64, capture.capturePngBase64()) is MatchOutcome.Found

                        step.condition == IF_CONDITION_MATCH ->
                            see.match(capture.capturePngBase64(), template) is MatchOutcome.Found

                        step.condition == IF_CONDITION_OCR ->
                            // 服务端只返回命中目标文字的条目，有带坐标的结果即条件成立
                            ocr.recognize(capture.capturePngBase64(), keyword)
                                .values.any { it.size >= 2 }

                        else -> false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(interval)
                    continue
                }
                if (frameId > 0L) {
                    lastFrameId = frameId
                    cachedHit = fresh
                }
                fresh
            }
            if (hit) return true
            delay(interval)
        }
        return false
    }

    /**
     * 预取 AI 文案：把工作流里（含 IF 分支）所有 AI 文案步骤的
     * 「提示词 + assistant 内容」组合去重后，立即交给线程池生成。
     *
     * 相同组合只请求一次；步骤失败重试时也直接复用已完成的结果。
     * 生成是阻塞的长请求（最长 10 分钟），用信号量限流，避免吃满线程池影响令牌刷新等其它请求。
     */
    private fun startAiPrefetch(
        allSteps: List<Step>,
        scope: CoroutineScope,
    ): Map<AiPrompt, Deferred<Result<String>>> {
        val info = identity()
        val prompts = allSteps
            .filterIsInstance<AiTextStep>()
            .map {
                AiPrompt(
                    prompt = info.buildPrompt(it.sections, it.instruction),
                    assistant = info.buildAssistant(it.sections, it.reportType),
                )
            }
            .filter { it.prompt.isNotBlank() }
            .distinct()
        if (prompts.isEmpty()) return emptyMap()
        val permits = Semaphore(AI_PREFETCH_CONCURRENCY)
        return prompts.associateWith { request ->
            scope.async {
                permits.withPermit {
                    try {
                        Result.success(aiText.generate(request.prompt, request.assistant))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }
        }
    }

    private suspend fun runAiText(step: AiTextStep): StepResult {
        val info = identity()
        val prompt = info.buildPrompt(step.sections, step.instruction)
        if (prompt.isBlank()) {
            return StepResult(false, "提示词为空：请先在 设置 → 身份信息 中填写内容")
        }
        val request = AiPrompt(
            prompt = prompt,
            assistant = info.buildAssistant(step.sections, step.reportType),
        )
        val content = try {
            // 优先取任务启动时的预取结果；相同组合只请求过一次，重试也直接复用。
            aiPrefetches[request]?.await()?.getOrThrow()
                ?: aiText.generate(request.prompt, request.assistant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StepResult(false, "AI 生成失败: ${e.message}")
        }
        if (!step.inputResult) {
            AppRepository.showHint("AI 文案已生成（${content.length} 字，未写入输入框）", HintLevel.SUCCESS)
            return StepResult(true)
        }
        val written = AutomationAccessibilityService.inputText(content)
        return if (written) {
            AppRepository.showHint("AI 文案已生成并写入（${content.length} 字）", HintLevel.SUCCESS)
            StepResult(true)
        } else {
            StepResult(false, "AI 文案已生成，但写入失败：请确认输入框已聚焦且无障碍服务已开启")
        }
    }

    private fun runLaunch(step: LaunchStep, fallbackPackage: String): StepResult {
        val pkg = step.packageName.ifBlank { fallbackPackage }
        if (pkg.isBlank()) {
            return StepResult(false, "未指定目标包名（launch 步骤与全局目标均为空）")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return StepResult(
                false,
                "无法拉起 $pkg：未安装或被禁用（工作流内目标包名留空 = 跟随全局目标）",
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onMinimizeRequest?.invoke()
        return StepResult(true)
    }

    private suspend fun runSaveScreenshot(tag: String): StepResult = try {
        // 直接写 PNG 字节，避免 base64 编码后再 decode 的往返
        val pngBytes = capture.capturePngBytes()
        // 经 MediaStore 存入相册 DCIM/AutoGongxueyun（相册/文件管理直接可见，无需存储权限）
        withContext(AppExecutors.dispatcher) {
            ScreenshotStore.savePng(
                context,
                "${System.currentTimeMillis()}_$tag.png",
                pngBytes,
            )
        }
        StepResult(true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StepResult(false, "截图存证失败: ${e.message}")
    }

    private fun describe(step: Step): String = when (step) {
        is LaunchStep -> "拉起 ${step.packageName.ifBlank { "<全局目标>" }}"
        is WaitStep -> "等待 ${step.ms}ms"
        is MatchStep -> "匹配「${step.template}」${if (step.tap) " + 点击" else ""}"
        is PhotoMatchStep -> "图片匹配「${step.template}」${if (step.tap) " + 点击" else ""}"
        is AiTextStep -> buildString {
            append("AI 文案")
            ReportTypes.label(step.reportType).takeIf { it.isNotBlank() }?.let { append(" · $it") }
            append("（${step.sections.size} 段提示词内容）")
        }
        is OcrStep -> "识别文字「${step.keyword}」${if (step.tap) " + 点击" else ""}"
        is IfStep -> buildString {
            val kind = when (step.condition) {
                IF_CONDITION_PHOTO -> "图片条件"
                IF_CONDITION_OCR -> "文字条件"
                else -> "模板条件"
            }
            val target = if (step.condition == IF_CONDITION_OCR) {
                step.keyword.ifBlank { "<未填写文字>" }
            } else {
                step.template.ifBlank { "<未选择模板>" }
            }
            append("$kind「$target」")
            append(" · 成立 ${step.thenSteps.size} 步")
            if (step.elseSteps.isNotEmpty()) append(" / 否则 ${step.elseSteps.size} 步")
        }
        is TapStep -> "点击 (${step.x}, ${step.y})"
        is SwipeStep -> "滑动 (${step.x1}, ${step.y1}) → (${step.x2}, ${step.y2})"
        is InputTextStep -> "输入文本「${step.text.take(12)}」"
        is GlobalActionStep -> "全局动作 ${step.action}"
        is SaveScreenshotStep -> "截图存证 ${step.tag}"
    }

    /**
     * 目标包名解析优先级：[effectiveTargetPackage]（工作流默认目标 > 第一个「启动应用」步骤）> 全局目标。
     *
     * 新版编辑器把目标应用下放到启动步骤、工作流不再保存默认目标，所以启动步骤是主要来源。
     */
    private fun resolveTarget(workflow: Workflow, fallbackPackage: String): String =
        workflow.effectiveTargetPackage.ifBlank { fallbackPackage.trim() }

    private companion object {
        /** 步骤级重试前的缓冲；给目标应用/界面一点时间恢复到可操作状态 */
        const val RETRY_DELAY_MS = 500L

        /** 初始化帧池预热上限：等待投屏首帧到达 */
        const val FIRST_FRAME_TIMEOUT_MS = ScreenCaptureManager.FIRST_FRAME_TIMEOUT_MS

        /** AI 文案预取的并发上限：生成长请求会阻塞线程，留出线程给令牌/其它接口 */
        const val AI_PREFETCH_CONCURRENCY = 2
    }
}
