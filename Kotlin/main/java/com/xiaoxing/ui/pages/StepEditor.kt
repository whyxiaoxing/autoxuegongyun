package com.xiaoxing.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.config.IdentitySections
import com.xiaoxing.photo.LocalTemplateStore
import com.xiaoxing.ui.components.AppPickerDialog
import com.xiaoxing.ui.components.AppSelectorField
import com.xiaoxing.ui.components.IconPlate
import com.xiaoxing.ui.components.NumField
import com.xiaoxing.ui.components.SignedNumField
import com.xiaoxing.workflow.AiTextStep
import com.xiaoxing.workflow.CLICK_OFFSET_LIMIT
import com.xiaoxing.workflow.GlobalActionStep
import com.xiaoxing.workflow.InputTextStep
import com.xiaoxing.workflow.LaunchStep
import com.xiaoxing.workflow.MatchStep
import com.xiaoxing.workflow.PhotoMatchStep
import com.xiaoxing.workflow.SaveScreenshotStep
import com.xiaoxing.workflow.Step
import com.xiaoxing.workflow.SwipeStep
import com.xiaoxing.workflow.TapStep
import com.xiaoxing.workflow.WaitStep
import kotlinx.coroutines.launch

/** UI-side step catalogue. Serialization contract stays in workflow/Workflow.kt. */
internal enum class StepType(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    LAUNCH("启动应用", "拉起目标 App", Icons.Filled.PlayArrow),
    WAIT("等待", "固定等待 N 毫秒", Icons.Filled.Refresh),
    MATCH("模板匹配", "截屏后识别并点击", Icons.Filled.Search),
    TAP("坐标点击", "点击固定坐标", Icons.Filled.Place),
    SWIPE("滑动", "两点之间滑动手势", Icons.AutoMirrored.Filled.ArrowForward),
    INPUT("输入文本", "写入当前输入框", Icons.Filled.Edit),
    GLOBAL("全局动作", "返回或回到主屏", Icons.Filled.Home),
    SCREENSHOT("保存凭证", "截图存入相册", Icons.Filled.CheckCircle),
    AI_TEXT("AI 文案", "拼接身份信息并写入", Icons.Filled.Person),
    PHOTO_MATCH("图片匹配", "本地图片模板匹配", Icons.Filled.Search),
}

internal fun Step.toStepType(): StepType = when (this) {
    is LaunchStep -> StepType.LAUNCH
    is WaitStep -> StepType.WAIT
    is MatchStep -> StepType.MATCH
    is TapStep -> StepType.TAP
    is SwipeStep -> StepType.SWIPE
    is InputTextStep -> StepType.INPUT
    is GlobalActionStep -> StepType.GLOBAL
    is SaveScreenshotStep -> StepType.SCREENSHOT
    is AiTextStep -> StepType.AI_TEXT
    is PhotoMatchStep -> StepType.PHOTO_MATCH
}

internal fun Step.summary(): String = when (this) {
    is LaunchStep -> if (packageName.isBlank()) "未选择目标应用" else "拉起 $packageName"
    is WaitStep -> "等待 ${ms}ms"
    is MatchStep -> buildString {
        append(template)
        if (tap) append(" · 命中点击")
        if (offsetX != 0 || offsetY != 0) append(" · 偏移 (${offsetX}, ${offsetY})")
        if (optional) append(" · 可选")
        append(" · 超时 ${timeoutMs}ms")
    }
    is TapStep -> "点击 ($x, $y)"
    is SwipeStep -> "($x1, $y1) → ($x2, $y2) · ${durationMs}ms"
    is InputTextStep -> "输入「${text.take(16)}${if (text.length > 16) "…" else ""}」"
    is GlobalActionStep -> if (action.equals("back", ignoreCase = true)) "返回键" else "回到主屏"
    is SaveScreenshotStep -> "保存截图「$tag」"
    is AiTextStep -> buildString {
        append(
            if (sections.isEmpty()) "仅补充要求"
            else sections.joinToString("、") { IdentitySections.label(it) },
        )
        if (inputResult) append(" · 写入输入框")
        if (optional) append(" · 可选")
    }
    is PhotoMatchStep -> buildString {
        append(template.ifBlank { "未选择模板" })
        if (tap) append(" · 命中点击")
        append(" · 超时 ${timeoutMs}ms")
        if (optional) append(" · 可选")
    }
}

/** @param matchTemplate 新建 MatchStep 时使用服务端已加载的第一个模板（没有则为空，稍后手动选择） */
internal fun StepType.defaultStep(matchTemplate: String = ""): Step = when (this) {
    StepType.LAUNCH -> LaunchStep()
    StepType.WAIT -> WaitStep(ms = 1500)
    StepType.MATCH -> MatchStep(template = matchTemplate)
    StepType.TAP -> TapStep(x = 0, y = 0)
    StepType.SWIPE -> SwipeStep(x1 = 0, y1 = 0, x2 = 0, y2 = 0)
    StepType.INPUT -> InputTextStep(text = "")
    StepType.GLOBAL -> GlobalActionStep(action = "back")
    StepType.SCREENSHOT -> SaveScreenshotStep(tag = "凭证")
    StepType.AI_TEXT -> AiTextStep(sections = IdentitySections.all.map { it.key })
    StepType.PHOTO_MATCH -> PhotoMatchStep(template = "")
}

/**
 * 工作流的运行参数。
 *
 * 目标应用与运行参数都下放到功能库的「启动应用」功能里：工作流不再提供统一设置，
 * 编辑启动应用时一并编辑这两项，保存步骤后写回工作流草稿。
 */
internal data class RunParameters(
    val intervalMs: Long,
    val maxRetries: Int,
)

/**
 * Parameter sheet. Type is chosen by the function library; this surface only shapes the step.
 *
 * @param runInterval 当前工作流轮询间隔（仅「启动应用」展示，随步骤一起保存）
 * @param runRetries 当前工作流动作重试次数（仅「启动应用」展示，随步骤一起保存）
 * @param onDelete 传入时显示删除入口（流程页/功能库页的步骤都通过这里移除）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StepEditorSheet(
    base: Step,
    runInterval: String,
    runRetries: String,
    onDismiss: () -> Unit,
    onConfirm: (Step, RunParameters?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val type = base.toStepType()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localTemplates = remember(context) { LocalTemplateStore.list(context).map { it.name } }
    val identity by AppRepository.identityInfo.collectAsStateWithLifecycle()
    val templateNames by AppRepository.templateNames.collectAsStateWithLifecycle()
    val templateNamesLoading by AppRepository.templateNamesLoading.collectAsStateWithLifecycle()
    val templateNamesError by AppRepository.templateNamesError.collectAsStateWithLifecycle()

    var pkg by remember(base) { mutableStateOf((base as? LaunchStep)?.packageName ?: "") }
    var ms by remember(base) { mutableStateOf((base as? WaitStep)?.ms?.toString() ?: "1500") }
    var template by remember(base) { mutableStateOf((base as? MatchStep)?.template ?: "") }
    var showLaunchPicker by remember(base) { mutableStateOf(false) }
    var showTemplatePicker by remember(base) { mutableStateOf(false) }
    var showLocalTemplatePicker by remember(base) { mutableStateOf(false) }
    var doTap by remember(base) { mutableStateOf((base as? MatchStep)?.tap ?: true) }
    var timeoutMs by remember(base) { mutableStateOf((base as? MatchStep)?.timeoutMs?.toString() ?: "10000") }
    var offsetX by remember(base) { mutableStateOf((base as? MatchStep)?.offsetX?.toString() ?: "0") }
    var offsetY by remember(base) { mutableStateOf((base as? MatchStep)?.offsetY?.toString() ?: "0") }
    var x by remember(base) { mutableStateOf((base as? TapStep)?.x?.toString() ?: "0") }
    var y by remember(base) { mutableStateOf((base as? TapStep)?.y?.toString() ?: "0") }
    var x1 by remember(base) { mutableStateOf((base as? SwipeStep)?.x1?.toString() ?: "0") }
    var y1 by remember(base) { mutableStateOf((base as? SwipeStep)?.y1?.toString() ?: "0") }
    var x2 by remember(base) { mutableStateOf((base as? SwipeStep)?.x2?.toString() ?: "0") }
    var y2 by remember(base) { mutableStateOf((base as? SwipeStep)?.y2?.toString() ?: "0") }
    var swipeMs by remember(base) { mutableStateOf((base as? SwipeStep)?.durationMs?.toString() ?: "300") }
    var text by remember(base) { mutableStateOf((base as? InputTextStep)?.text ?: "") }
    var action by remember(base) { mutableStateOf((base as? GlobalActionStep)?.action ?: "back") }
    var tag by remember(base) { mutableStateOf((base as? SaveScreenshotStep)?.tag ?: "凭证") }
    var aiSections by remember(base) {
        mutableStateOf(
            (base as? AiTextStep)?.sections ?: IdentitySections.all.map { it.key },
        )
    }
    var aiInstruction by remember(base) { mutableStateOf((base as? AiTextStep)?.instruction ?: "") }
    var aiInputResult by remember(base) { mutableStateOf((base as? AiTextStep)?.inputResult ?: true) }
    var photoTemplate by remember(base) { mutableStateOf((base as? PhotoMatchStep)?.template ?: "") }
    var photoTap by remember(base) { mutableStateOf((base as? PhotoMatchStep)?.tap ?: true) }
    var photoTimeout by remember(base) {
        mutableStateOf((base as? PhotoMatchStep)?.timeoutMs?.toString() ?: "10000")
    }
    var optional by remember(base) { mutableStateOf(base.optional) }
    // 运行参数只在「启动应用」功能里设置（工作流不再统一设置）
    var intervalDraft by remember(base) { mutableStateOf(runInterval) }
    var retriesDraft by remember(base) { mutableStateOf(runRetries) }
    // 底部弹窗是独立窗口，顶层提示会被盖住；表单错误同时在这里就地显示
    var validationError by remember(base) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember(base) { mutableStateOf(false) }

    // 识别模板来自服务端：打开「模板匹配」表单时按需拉取一次
    LaunchedEffect(type) {
        if (type == StepType.MATCH) AppRepository.ensureTemplateNamesLoaded()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconPlate(type.icon, size = 42)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("编辑 · ${type.label}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        type.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除步骤",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            when (type) {
                StepType.LAUNCH -> {
                    AppSelectorField(
                        label = "目标应用",
                        packageName = pkg,
                        emptyLabel = "未选择",
                        hint = "必须选择要启动的应用",
                        onClick = { showLaunchPicker = true },
                    )
                    if (pkg.isBlank()) {
                        Text(
                            "请选择要启动的目标应用（工作流不再提供默认目标）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    NumField(
                        "匹配轮询间隔（毫秒）",
                        intervalDraft,
                        { intervalDraft = it },
                        Modifier.fillMaxWidth(),
                    )
                    NumField(
                        "动作重试次数",
                        retriesDraft,
                        { retriesDraft = it },
                        Modifier.fillMaxWidth(),
                    )
                    Text(
                        "运行参数作用于整条工作流：间隔用于匹配轮询与步骤间停顿，重试用于点击、滑动等动作失败后的重放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StepType.WAIT -> NumField("等待时长（毫秒）", ms, { ms = it }, Modifier.fillMaxWidth())

                StepType.MATCH -> {
                    TemplateSelectorField(
                        label = "识别模板",
                        value = template,
                        hint = when {
                            templateNamesLoading -> "正在从服务端获取模板…"
                            templateNamesError.isNotBlank() -> "获取失败：$templateNamesError"
                            templateNames.isEmpty() -> "尚未获取到模板，打开后点右上角刷新"
                            else -> "服务端模板库 · 共 ${templateNames.size} 个"
                        },
                        onClick = {
                            showTemplatePicker = true
                            AppRepository.ensureTemplateNamesLoaded()
                        },
                    )
                    if (
                        template.isNotBlank() &&
                        templateNames.isNotEmpty() &&
                        template !in templateNames
                    ) {
                        Text(
                            "「$template」已不在服务端模板列表中，请重新选择。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("命中后自动点击", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "识别到模板后直接点击命中区域",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = doTap, onCheckedChange = { doTap = it })
                    }
                    NumField("匹配超时（毫秒）", timeoutMs, { timeoutMs = it }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SignedNumField(
                            "点击偏移 X（±$CLICK_OFFSET_LIMIT）",
                            offsetX,
                            { offsetX = it },
                            Modifier.weight(1f),
                        )
                        SignedNumField(
                            "点击偏移 Y（±$CLICK_OFFSET_LIMIT）",
                            offsetY,
                            { offsetY = it },
                            Modifier.weight(1f),
                        )
                    }
                    Text(
                        "在模板命中点上叠加的像素偏移（可为负），用来微调实际点击位置；仅开启「命中后自动点击」时生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StepType.TAP -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("X", x, { x = it }, Modifier.weight(1f))
                    NumField("Y", y, { y = it }, Modifier.weight(1f))
                }

                StepType.SWIPE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumField("起点 X", x1, { x1 = it }, Modifier.weight(1f))
                        NumField("起点 Y", y1, { y1 = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumField("终点 X", x2, { x2 = it }, Modifier.weight(1f))
                        NumField("终点 Y", y2, { y2 = it }, Modifier.weight(1f))
                    }
                    NumField("滑动时长（毫秒）", swipeMs, { swipeMs = it }, Modifier.fillMaxWidth())
                }

                StepType.INPUT -> OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("要输入的文本") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                StepType.GLOBAL -> {
                    Text("动作", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = action == "back",
                            onClick = { action = "back" },
                            label = { Text("返回") },
                        )
                        FilterChip(
                            selected = action == "home",
                            onClick = { action = "home" },
                            label = { Text("回到主屏") },
                        )
                    }
                }

                StepType.SCREENSHOT -> OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("截图标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                StepType.AI_TEXT -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "提示词内容",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            aiSections = IdentitySections.all.map { it.key }
                        }) { Text("全选") }
                        TextButton(onClick = { aiSections = emptyList() }) { Text("清空") }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IdentitySections.all.forEach { section ->
                            val selected = section.key in aiSections
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    aiSections = if (selected) {
                                        aiSections - section.key
                                    } else {
                                        aiSections + section.key
                                    }
                                },
                                label = { Text(section.label) },
                            )
                        }
                    }
                    Text(
                        "选中的段按固定顺序拼接；设置里为空的段会自动跳过。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (
                        aiSections.isNotEmpty() &&
                        aiInstruction.isBlank() &&
                        aiSections.none { identity.valueOf(it).isNotBlank() }
                    ) {
                        Text(
                            "选中的段当前都为空：请先到 设置 → 身份信息 填写，否则执行时会提示提示词为空。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = aiInstruction,
                        onValueChange = { aiInstruction = it },
                        label = { Text("补充要求（可选）") },
                        placeholder = { Text("例如：写一段 150 字左右的日报，语气正式") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("生成后写入当前输入框", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "执行前请先用模板匹配点击目标输入框",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = aiInputResult, onCheckedChange = { aiInputResult = it })
                    }
                }

                StepType.PHOTO_MATCH -> {
                    TemplateSelectorField(
                        label = "图片模板（本地）",
                        value = photoTemplate,
                        hint = if (localTemplates.isEmpty()) {
                            "尚未导入本地模板"
                        } else {
                            "本机模板 · 共 ${localTemplates.size} 个"
                        },
                        enabled = localTemplates.isNotEmpty(),
                        onClick = { showLocalTemplatePicker = true },
                    )
                    if (localTemplates.isEmpty()) {
                        Text(
                            "还没有本地模板：请到 设置 → 本地模板 导入图片后回来选择。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("命中后自动点击", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "识别到本地模板后直接点击命中区域",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = photoTap, onCheckedChange = { photoTap = it })
                    }
                    NumField(
                        "匹配超时（毫秒）",
                        photoTimeout,
                        { photoTimeout = it },
                        Modifier.fillMaxWidth(),
                    )
                    Text(
                        "模板图片与当前截图一起提交给 /See_Submit_Photo，服务端不查 Photo/ 目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("可选步骤", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "失败时不中止整条工作流",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = optional, onCheckedChange = { optional = it })
                }
            }

            validationError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val error = when (type) {
                            StepType.LAUNCH -> {
                                val intervalValue = intervalDraft.toLongOrNull()
                                val retriesValue = retriesDraft.toIntOrNull()
                                when {
                                    pkg.isBlank() -> "请选择要启动的目标应用"
                                    intervalValue == null || intervalValue <= 0 ->
                                        "轮询间隔需为大于 0 的整数"
                                    retriesValue == null || retriesValue < 0 ->
                                        "重试次数需为不小于 0 的整数"
                                    else -> null
                                }
                            }
                            StepType.WAIT -> if (ms.toLongOrNull() == null) "等待时长需为数字" else null
                            StepType.MATCH -> when {
                                template.isBlank() -> "请先选择识别模板"
                                timeoutMs.toLongOrNull() == null -> "匹配超时需为数字"
                                !isValidOffset(offsetX) || !isValidOffset(offsetY) ->
                                    "点击偏移需为 -$CLICK_OFFSET_LIMIT ~ $CLICK_OFFSET_LIMIT 的整数"
                                else -> null
                            }
                            StepType.TAP -> if (x.toIntOrNull() == null || y.toIntOrNull() == null) "点击坐标需为整数" else null
                            StepType.SWIPE ->
                                if (listOf(x1, y1, x2, y2).any { it.toIntOrNull() == null }) "滑动坐标需为整数"
                                else if (swipeMs.toLongOrNull() == null) "滑动时长需为数字"
                                else null
                            StepType.AI_TEXT ->
                                if (aiSections.isEmpty() && aiInstruction.isBlank()) {
                                    "AI 文案至少选择一段身份信息或填写补充要求"
                                } else null
                            StepType.PHOTO_MATCH ->
                                if (photoTemplate.isBlank()) "请先选择本地图片模板"
                                else if (photoTimeout.toLongOrNull() == null) "匹配超时需为数字"
                                else null
                            else -> null
                        }
                        if (error != null) {
                            validationError = error
                            AppRepository.showHint(error, HintLevel.WARNING)
                        } else {
                            validationError = null
                            onConfirm(
                                when (type) {
                                    StepType.LAUNCH -> LaunchStep(packageName = pkg.trim(), optional = optional)
                                    StepType.WAIT -> WaitStep(ms = ms.toLong(), optional = optional)
                                    StepType.MATCH -> MatchStep(
                                        template = template,
                                        tap = doTap,
                                        timeoutMs = timeoutMs.toLong(),
                                        offsetX = offsetValue(offsetX),
                                        offsetY = offsetValue(offsetY),
                                        optional = optional,
                                    )
                                    StepType.TAP -> TapStep(x = x.toInt(), y = y.toInt(), optional = optional)
                                    StepType.SWIPE -> SwipeStep(
                                        x1 = x1.toInt(),
                                        y1 = y1.toInt(),
                                        x2 = x2.toInt(),
                                        y2 = y2.toInt(),
                                        durationMs = swipeMs.toLong(),
                                        optional = optional,
                                    )
                                    StepType.INPUT -> InputTextStep(text = text, optional = optional)
                                    StepType.GLOBAL -> GlobalActionStep(action = action, optional = optional)
                                    StepType.SCREENSHOT -> SaveScreenshotStep(
                                        tag = tag.ifBlank { "凭证" },
                                        optional = optional,
                                    )
                                    StepType.AI_TEXT -> AiTextStep(
                                        sections = aiSections,
                                        instruction = aiInstruction.trim(),
                                        inputResult = aiInputResult,
                                        optional = optional,
                                    )
                                    StepType.PHOTO_MATCH -> PhotoMatchStep(
                                        template = photoTemplate,
                                        tap = photoTap,
                                        timeoutMs = photoTimeout.toLong(),
                                        optional = optional,
                                    )
                                },
                                if (type == StepType.LAUNCH) {
                                    RunParameters(
                                        intervalMs = intervalDraft.toLong(),
                                        maxRetries = retriesDraft.toInt(),
                                    )
                                } else {
                                    null
                                },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存步骤") }
                TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("取消") }
            }
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这个步骤？") },
            text = { Text("「${type.label}」将从流程中移除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showLaunchPicker) {
        AppPickerDialog(
            currentPackage = pkg,
            onDismiss = { showLaunchPicker = false },
            onPick = { packageName ->
                pkg = packageName
                showLaunchPicker = false
            },
        )
    }

    if (showTemplatePicker) {
        TemplatePickerDialog(
            title = "识别模板",
            subtitle = "来自服务端 Photo 模板库",
            current = template,
            options = templateNames,
            loading = templateNamesLoading,
            errorText = templateNamesError,
            onRefresh = {
                scope.launch { AppRepository.refreshTemplateNames() }
            },
            onDismiss = { showTemplatePicker = false },
            onSelect = {
                template = it
                showTemplatePicker = false
            },
        )
    }

    if (showLocalTemplatePicker) {
        TemplatePickerDialog(
            title = "图片模板",
            subtitle = "来自本机导入的模板图片",
            current = photoTemplate,
            options = localTemplates,
            loading = false,
            errorText = if (localTemplates.isEmpty()) "还没有本地模板，请到 设置 → 本地模板 导入图片。" else "",
            onRefresh = null,
            onDismiss = { showLocalTemplatePicker = false },
            onSelect = {
                photoTemplate = it
                showLocalTemplatePicker = false
            },
        )
    }
}

/** 偏移输入合法性：空值 / 仅负号为 0；其余必须是 ±[CLICK_OFFSET_LIMIT] 内的整数 */
private fun isValidOffset(text: String): Boolean {
    if (text.isBlank() || text == "-") return true
    val value = text.toIntOrNull() ?: return false
    return value in -CLICK_OFFSET_LIMIT..CLICK_OFFSET_LIMIT
}

private fun offsetValue(text: String): Int = text.toIntOrNull() ?: 0

/**
 * 整块可点击的模板选择入口（不再只有右侧小箭头可点）。
 * 点开后在独立对话框里搜索 / 刷新 / 单选，比原来的下拉菜单更适合长列表。
 */
@Composable
private fun TemplateSelectorField(
    label: String,
    value: String,
    hint: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    value.ifBlank { "选择模板" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (value.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 现代版模板选择器：标题栏右上角可直接刷新（服务端 /UpdataPhotoName），
 * 支持搜索过滤与当前项高亮。本地模板复用同一界面，只是没有刷新入口。
 */
@Composable
private fun TemplatePickerDialog(
    title: String,
    subtitle: String,
    current: String,
    options: List<String>,
    loading: Boolean,
    errorText: String,
    onRefresh: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            options
        } else {
            options.filter { it.contains(keyword, ignoreCase = true) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 580.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh, enabled = !loading) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "刷新模板列表",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索模板") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorText.isNotBlank()) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                when {
                    options.isEmpty() && loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    options.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (errorText.isBlank()) {
                                if (onRefresh != null) {
                                    "服务端还没有可用模板，上传后点右上角刷新"
                                } else {
                                    "还没有可用模板"
                                }
                            } else {
                                "加载失败，点右上角重试"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    filtered.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "没有匹配「$query」的模板",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered) { name ->
                            TemplateOptionRow(
                                name = name,
                                selected = name == current,
                                onClick = { onSelect(name) },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 ${options.size} 个模板",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun TemplateOptionRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (selected) scheme.primary else Color.Transparent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Text(
                    "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
    }
}
