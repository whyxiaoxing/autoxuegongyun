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
import com.xiaoxing.network.AiTextClient
import com.xiaoxing.network.MatchOutcome
import com.xiaoxing.network.SeeClient
import com.xiaoxing.photo.LocalTemplateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 工作流执行引擎（运行于前台服务协程作用域）。
 *
 * 执行前先走 [initialize]：无障碍连接、帧池首帧预热、令牌/模板预取、本地图片模板、
 * 身份信息提示词、目标 App 可拉起校验，全部就绪后才执行步骤 —— 避免工作流快于
 * 帧池/令牌，跑到中途才暴露依赖缺失。
 *
 * 步骤失败语义：
 *  - match / photoMatch：timeoutMs 内按 intervalMs 轮询（截屏→识别→点击），超时未匹配 = 失败
 *  - 动作类（launch/tap/swipe/inputText/globalAction）：按 maxRetries 重试
 *  - aiText：拼身份信息 → /AiText → 写入聚焦输入框
 *  - 失败后：optional = true 跳过，否则中止整个工作流
 */
class WorkflowEngine(
    private val context: Context,
    private val capture: ScreenCaptureManager,
    private val see: SeeClient,
    private val aiText: AiTextClient,
    private val identity: () -> IdentityInfo,
) {

    /** 非可选步骤失败时抛出，携带中止原因 */
    class AbortException(message: String) : Exception(message)

    /** launch 步骤成功后回调（用于最小化自身窗口） */
    var onMinimizeRequest: (() -> Unit)? = null

    /** 当前执行状态描述（面向页面展示） */
    var onStatusChange: ((String) -> Unit)? = null

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
        onStatusChange?.invoke("初始化: ${workflow.name}")

        // 初始化与「投屏二次确认」互不依赖，并行跑：总耗时 ≈ max(初始化, 用户确认)。
        // 用户取消时取消初始化（注意：内部阻塞中的 OkHttp 调用不可中断，会等它返回后立即抛出取消）。
        val (confirmed, initResult) = coroutineScope {
            val initJob = async { initialize(workflow, target) }
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

        workflow.steps.forEachIndexed { index, step ->
            val desc = describe(step)
            onStatusChange?.invoke("[${index + 1}/${workflow.steps.size}] $desc")

            val result = runCatching { execute(step, target, workflow) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse { e -> StepResult(false, "异常: ${e.message}") }

            if (!result.ok) {
                if (step.optional) {
                    // 可选步骤失败不打断流程；状态会继续推进到下一步
                } else {
                    onStatusChange?.invoke("中止: $desc")
                    val reason = result.detail?.let { "（$it）" }.orEmpty()
                    throw AbortException("步骤失败，工作流中止: $desc$reason")
                }
            }
            delay(workflow.intervalMs.coerceAtLeast(0))
        }

        onStatusChange?.invoke("完成: ${workflow.name}")
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
    private suspend fun initialize(workflow: Workflow, target: String): InitResult {
        val templates = workflow.steps.filterIsInstance<MatchStep>().map { it.template }.distinct()
        val photoTemplates = workflow.steps.filterIsInstance<PhotoMatchStep>().map { it.template }.distinct()
        val needsServer = templates.isNotEmpty() ||
            photoTemplates.isNotEmpty() ||
            workflow.steps.any { it is AiTextStep }
        val needsCapture = workflow.steps.any {
            it is MatchStep || it is PhotoMatchStep || it is SaveScreenshotStep
        }
        val needsGesture = workflow.steps.any {
            when (it) {
                is MatchStep -> it.tap
                is PhotoMatchStep -> it.tap
                is AiTextStep -> it.inputResult
                is TapStep, is SwipeStep, is InputTextStep, is GlobalActionStep -> true
                else -> false
            }
        }

        // 各检查互不依赖，各自在独立协程里跑（总耗时 ≈ 最慢一项，而不是总和）。
        val results = coroutineScope {
            listOf(
                async { checkAccessibility(needsGesture) },
                async { checkCapture(needsCapture) },
                async { checkServer(templates, needsServer) },
                async { checkAiPrompts(workflow) },
                async(AppExecutors.dispatcher) { checkLaunchTargets(workflow, target) },
                async(AppExecutors.dispatcher) { checkPhotoTemplates(photoTemplates) },
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

    private fun checkAiPrompts(workflow: Workflow): CheckResult {
        val problems = workflow.steps.filterIsInstance<AiTextStep>().mapNotNull { step ->
            when {
                step.sections.any { IdentitySections.find(it) == null } ->
                    "AI 文案步骤包含未知身份信息段"

                identity().buildPrompt(step.sections, step.instruction).isBlank() ->
                    "AI 文案提示词为空：请先在 设置 → 身份信息 中填写内容"

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

    private fun checkLaunchTargets(workflow: Workflow, target: String): CheckResult = try {
        val problems = mutableListOf<String>()
        workflow.steps.filterIsInstance<LaunchStep>().forEach { step ->
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

    private suspend fun execute(step: Step, fallbackPackage: String, workflow: Workflow): StepResult =
        when (step) {
            is MatchStep -> runMatch(step, workflow.intervalMs)
            is PhotoMatchStep -> runPhotoMatch(step, workflow.intervalMs)
            is AiTextStep -> runAiText(step, workflow.maxRetries)
            is LaunchStep -> runLaunch(step, fallbackPackage)
            is WaitStep -> {
                delay(step.ms.coerceAtLeast(0))
                StepResult(true)
            }

            is TapStep -> if (retryable(workflow.maxRetries) {
                    AutomationAccessibilityService.tap(step.x, step.y)
                }
            ) StepResult(true) else StepResult(false, "无障碍点击失败（请检查服务是否开启）")

            is SwipeStep -> if (retryable(workflow.maxRetries) {
                    AutomationAccessibilityService.swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs)
                }
            ) StepResult(true) else StepResult(false, "无障碍滑动失败（请检查服务是否开启）")

            is InputTextStep -> if (retryable(workflow.maxRetries) {
                    AutomationAccessibilityService.inputText(step.text)
                }
            ) StepResult(true) else StepResult(false, "输入失败：没有可写入的输入框或无障碍未开启")

            is GlobalActionStep -> if (retryable(workflow.maxRetries) {
                    AutomationAccessibilityService.globalAction(step.action)
                }
            ) StepResult(true) else StepResult(false, "全局动作失败（请检查服务是否开启）")

            is SaveScreenshotStep -> runSaveScreenshot(step.tag)
        }

    private suspend fun runMatch(step: MatchStep, intervalMs: Long): StepResult {
        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceAtLeast(0)
        var attempts = 0
        var lastDetail: String? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            attempts++
            val outcome = try {
                see.match(capture.capturePngBase64(), step.template)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastDetail = "请求异常: ${e.message}"
                delay(intervalMs.coerceAtLeast(0))
                continue
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
                    delay(intervalMs.coerceAtLeast(0))
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
        val templateBase64 = LocalTemplateStore.readBase64(context, step.template)
            ?: return StepResult(false, "本地模板不存在: ${step.template}")
        val deadline = System.currentTimeMillis() + step.timeoutMs.coerceAtLeast(0)
        var attempts = 0
        var lastDetail: String? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            attempts++
            val outcome = try {
                see.matchPhoto(templateBase64, capture.capturePngBase64())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastDetail = "请求异常: ${e.message}"
                delay(intervalMs.coerceAtLeast(0))
                continue
            }
            when (outcome) {
                is MatchOutcome.Found -> {
                    if (!step.tap || AutomationAccessibilityService.tap(outcome.x, outcome.y)) {
                        return StepResult(true)
                    }
                    return StepResult(false, "命中 (${outcome.x}, ${outcome.y}) 但无障碍点击失败")
                }

                is MatchOutcome.NotFound -> {
                    lastDetail = outcome.detail
                    delay(intervalMs.coerceAtLeast(0))
                }
            }
        }
        return StepResult(
            false,
            "图片模板「${step.template}」超时未匹配（尝试 $attempts 次）" +
                (lastDetail?.let { "；最后原因: $it" } ?: ""),
        )
    }

    private suspend fun runAiText(step: AiTextStep, maxRetries: Int): StepResult {
        val prompt = identity().buildPrompt(step.sections, step.instruction)
        if (prompt.isBlank()) {
            return StepResult(false, "提示词为空：请先在 设置 → 身份信息 中填写内容")
        }
        val content = try {
            aiText.generate(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return StepResult(false, "AI 生成失败: ${e.message}")
        }
        if (!step.inputResult) {
            AppRepository.showHint("AI 文案已生成（${content.length} 字，未写入输入框）", HintLevel.SUCCESS)
            return StepResult(true)
        }
        val written = retryable(maxRetries) { AutomationAccessibilityService.inputText(content) }
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
        ScreenshotStore.savePng(
            context,
            "${System.currentTimeMillis()}_$tag.png",
            pngBytes,
        )
        StepResult(true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StepResult(false, "截图存证失败: ${e.message}")
    }

    private suspend fun retryable(times: Int, block: suspend () -> Boolean): Boolean {
        val total = times.coerceAtLeast(1)
        repeat(total) { attempt ->
            currentCoroutineContext().ensureActive()
            if (block()) return true
            if (attempt < total - 1) delay(RETRY_DELAY_MS)
        }
        return false
    }

    private fun describe(step: Step): String = when (step) {
        is LaunchStep -> "拉起 ${step.packageName.ifBlank { "<全局目标>" }}"
        is WaitStep -> "等待 ${step.ms}ms"
        is MatchStep -> "匹配「${step.template}」${if (step.tap) " + 点击" else ""}"
        is PhotoMatchStep -> "图片匹配「${step.template}」${if (step.tap) " + 点击" else ""}"
        is AiTextStep -> "AI 文案（${step.sections.size} 段身份信息）"
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
        const val RETRY_DELAY_MS = 500L

        /** 初始化帧池预热上限：等待投屏首帧到达 */
        const val FIRST_FRAME_TIMEOUT_MS = ScreenCaptureManager.FIRST_FRAME_TIMEOUT_MS
    }
}
