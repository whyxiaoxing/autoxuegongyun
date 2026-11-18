package com.xiaoxing.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.HintLevel
import com.xiaoxing.ui.components.IconPlate
import com.xiaoxing.ui.components.NumField
import com.xiaoxing.ui.components.SectionEyebrow
import com.xiaoxing.workflow.LaunchStep
import com.xiaoxing.workflow.MAX_FAILURE_RETRIES
import com.xiaoxing.workflow.Step
import com.xiaoxing.workflow.Workflow
import com.xiaoxing.workflow.WorkflowCodec
import com.xiaoxing.workflow.flattenSteps
import kotlinx.coroutines.launch

private const val PAGE_FLOW = 0
private const val PAGE_LIBRARY = 1

/** 功能库默认露出的排数（每排 2 个），其余功能在库区域内上下滑动查看 */
private const val LIBRARY_VISIBLE_ROWS = 3

/** 与 [FunctionTile] 的 52dp 最小高度、排间距 8dp 保持一致，用于锁定 3 排的可见高度 */
private val LIBRARY_TILE_HEIGHT = 52.dp
private val LIBRARY_ROW_GAP = 8.dp

/** 功能库两列排布在进程内固定，提到顶层避免每次重组都重新 chunked。 */
private val STEP_TYPE_ROWS = StepType.entries.chunked(2)

/**
 * 工作流编辑分两页（底部切换）：
 *
 *  - 流程：只做编排。步骤直接长按拖动排序，不再有左侧手柄。
 *  - 功能库：添加功能、点按步骤编辑参数；目标应用与运行参数下放到「启动应用」步骤里。
 *
 * 工作流本身不再有默认目标应用和统一运行参数，流程页保持「一眼看清执行顺序」的干净画布。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkflowEditorPage(
    initialName: String?,
    onBack: () -> Unit,
) {
    val repo = AppRepository
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val templateLoading by repo.templateNamesLoading.collectAsStateWithLifecycle()

    var loadedOnce by rememberSaveable { mutableStateOf(initialName == null) }
    var loading by rememberSaveable { mutableStateOf(initialName != null) }
    var loadFailed by rememberSaveable { mutableStateOf(false) }
    var originalName by rememberSaveable { mutableStateOf(initialName.orEmpty()) }
    var wfName by rememberSaveable { mutableStateOf("") }
    // 运行参数不再有统一设置：只在「启动应用」步骤里编辑，这里保存草稿值
    var wfInterval by rememberSaveable { mutableStateOf("1000") }
    // 流程控制：每个功能失败后的重试次数（0 = 不重试）
    var wfFailureRetries by rememberSaveable { mutableStateOf("0") }
    var stepsJson by rememberSaveable { mutableStateOf("") }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableIntStateOf(-1) }
    var showDiscard by remember { mutableStateOf(false) }
    var workflowMenuOpen by remember { mutableStateOf(false) }
    var showDeleteWorkflow by remember { mutableStateOf(false) }

    // 新建时直接落在功能库页（先命名、先加功能）；编辑已有流程时先看执行顺序。
    var page by rememberSaveable {
        mutableIntStateOf(if (initialName == null) PAGE_LIBRARY else PAGE_FLOW)
    }

    // 拖拽排序：拖动期间只做视觉位移，松手时才真正交换步骤，避免列表在手指下反复重组
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val steps = remember(stepsJson) {
        if (stepsJson.isBlank()) {
            emptyList()
        } else {
            runCatching { WorkflowCodec.decode(stepsJson) }.getOrNull()?.steps.orEmpty()
        }
    }

    // 进入编辑页就按需预取模板名，新增「模板匹配」步骤时无需等待
    LaunchedEffect(Unit) {
        AppRepository.ensureTemplateNamesLoaded()
    }

    LaunchedEffect(initialName) {
        if (loadedOnce) return@LaunchedEffect
        val raw = initialName?.let { repo.loadWorkflowJson(it) }
        val workflow = raw?.let { runCatching { WorkflowCodec.decode(it) }.getOrNull() }
        if (workflow == null) {
            loadFailed = true
            repo.showHint("读取失败: ${initialName.orEmpty()}", HintLevel.ERROR)
        } else {
            originalName = initialName.orEmpty()
            wfName = workflow.name
            wfInterval = workflow.intervalMs.toString()
            wfFailureRetries = workflow.failureRetries.toString()
            stepsJson = WorkflowCodec.encodeCompact(workflow)
            dirty = false
        }
        loading = false
        loadedOnce = true
    }

    /**
     * 内存草稿默认用紧凑 JSON；只有真正落盘时才用 pretty，保持磁盘格式不变。
     */
    fun encodedWorkflow(newSteps: List<Step>, pretty: Boolean = false): String {
        val workflow = Workflow(
            name = wfName.ifBlank { "未命名工作流" },
            // 工作流不再保存默认目标应用：目标随「启动应用」步骤一起保存
            targetPackage = "",
            intervalMs = wfInterval.toLongOrNull() ?: 1000L,
            failureRetries = (wfFailureRetries.toIntOrNull() ?: 0)
                .coerceIn(0, MAX_FAILURE_RETRIES),
            steps = newSteps,
        )
        return if (pretty) WorkflowCodec.encode(workflow) else WorkflowCodec.encodeCompact(workflow)
    }

    /**
     * Read the authoritative list from [stepsJson] at event time.
     * A fast double-tap can deliver two callbacks before recomposition; using the
     * composition-captured `steps` would make the second write discard the first step.
     */
    fun currentSteps(): List<Step> {
        if (stepsJson.isBlank()) return emptyList()
        return runCatching { WorkflowCodec.decode(stepsJson) }.getOrNull()?.steps.orEmpty()
    }

    fun setSteps(newSteps: List<Step>) {
        stepsJson = encodedWorkflow(newSteps)
        dirty = true
    }

    /** 功能库点按即加入流程末尾，并直接打开该步骤的参数面板（添加 + 编辑一步到位） */
    fun addStep(type: StepType) {
        val updated = currentSteps() + type.defaultStep(
            matchTemplate = repo.templateNames.value.firstOrNull().orEmpty(),
        )
        setSteps(updated)
        editingIndex = updated.lastIndex
        repo.showHint("已添加「${type.label}」，设置参数后点「保存步骤」", HintLevel.INFO)
    }

    /** 拖拽松手时把步骤从 [from] 移到 [to]（保持其余步骤相对顺序） */
    fun moveStep(from: Int, to: Int) {
        val current = currentSteps()
        if (from == to || from !in current.indices || to !in current.indices) return
        setSteps(current.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** 根据拖动位移估算手指下的目标槽位（用条目中点作为切换阈值） */
    fun dropTargetFor(index: Int, translationY: Float): Int {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return index
        val dragged = visible.firstOrNull { it.index == index } ?: return index
        val center = dragged.offset + dragged.size / 2f + translationY
        return visible.lastOrNull { center >= it.offset + it.size / 2f }?.index
            ?: visible.first().index
    }

    fun requestBack() {
        if (dirty && !saving) showDiscard = true else onBack()
    }

    fun save() {
        val name = wfName.trim()
        val latestSteps = currentSteps()
        val nameTaken = repo.workflows.value.any { it == name && it != originalName }
        when {
            name.isBlank() -> {
                // 名称在功能库页，跳过去让用户直接填写
                page = PAGE_LIBRARY
                repo.showHint("请先填写工作流名称", HintLevel.WARNING)
            }

            nameTaken -> repo.showHint("已有同名工作流，请换一个名称", HintLevel.WARNING)
            latestSteps.isEmpty() -> repo.showHint("至少添加一个步骤", HintLevel.WARNING)
            latestSteps.flattenSteps().filterIsInstance<LaunchStep>().any { it.packageName.isBlank() } -> {
                page = PAGE_LIBRARY
                repo.showHint("「启动应用」步骤还没选择目标应用", HintLevel.WARNING)
            }

            else -> scope.launch {
                saving = true
                val previousName = originalName
                repo.saveWorkflow(name, encodedWorkflow(latestSteps, pretty = true))
                    .onSuccess {
                        if (previousName.isNotBlank() && previousName != name) {
                            repo.deleteWorkflow(previousName)
                        }
                        originalName = name
                        dirty = false
                        repo.showHint("「$name」已保存（${latestSteps.size} 个步骤）", HintLevel.SUCCESS)
                        onBack()
                    }
                    .onFailure { repo.showHint("保存失败: ${it.message}", HintLevel.ERROR) }
                saving = false
            }
        }
    }

    // 在功能库页按返回先回流程页，再按一次才确认离开
    BackHandler {
        if (page != PAGE_FLOW) page = PAGE_FLOW else requestBack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            wfName.ifBlank {
                                if (initialName == null) "新建工作流" else "编辑工作流"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                saving -> "正在保存…"
                                dirty -> "有未保存的更改"
                                else -> "步骤按顺序执行"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dirty) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 右上角刷新：让服务端重新扫描 Photo/ 目录（/UpdataPhotoName）
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (!repo.serverConfig.value.isComplete) {
                                    repo.showHint("请先在设置中配置服务器后再刷新模板", HintLevel.WARNING)
                                } else {
                                    repo.refreshTemplateNames()
                                        .onSuccess {
                                            repo.showHint(
                                                "模板列表已刷新（${repo.templateNames.value.size} 个）",
                                                HintLevel.SUCCESS,
                                            )
                                        }
                                        .onFailure {
                                            repo.showHint("刷新模板失败: ${it.message}", HintLevel.ERROR)
                                        }
                                }
                            }
                        },
                        enabled = !templateLoading,
                    ) {
                        if (templateLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新识别模板",
                            )
                        }
                    }
                    if (initialName != null) {
                        Box {
                            IconButton(onClick = { workflowMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "更多工作流操作",
                                )
                            }
                            DropdownMenu(
                                expanded = workflowMenuOpen,
                                onDismissRequest = { workflowMenuOpen = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "删除工作流",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        workflowMenuOpen = false
                                        showDeleteWorkflow = true
                                    },
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = ::save,
                        enabled = !saving && !loading && !loadFailed,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("保存", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            if (!loading && !loadFailed) {
                EditorBottomBar(
                    page = page,
                    onSelect = { target ->
                        if (page != target) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            page = target
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            loadFailed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("无法读取这条工作流", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "文件可能已损坏或被删除，请返回列表重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = onBack) { Text("返回列表") }
            }

            page == PAGE_LIBRARY -> LibraryPage(
                wfName = wfName,
                onNameChange = {
                    wfName = it
                    dirty = true
                },
                failureRetries = wfFailureRetries,
                onFailureRetriesChange = {
                    wfFailureRetries = it
                    dirty = true
                },
                steps = steps,
                onAdd = ::addStep,
                onEditStep = { editingIndex = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
            )

            else -> FlowCanvasPage(
                steps = steps,
                listState = listState,
                draggingIndex = draggingIndex,
                dragOffsetY = dragOffsetY,
                onDragStart = { index ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    draggingIndex = index
                    dragOffsetY = 0f
                },
                onDragDelta = { index, delta ->
                    if (draggingIndex == index) dragOffsetY += delta
                },
                onDragEnd = { index ->
                    if (draggingIndex == index) {
                        val target = dropTargetFor(index, dragOffsetY)
                        draggingIndex = null
                        dragOffsetY = 0f
                        moveStep(index, target)
                    }
                },
                onOpenLibrary = { page = PAGE_LIBRARY },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            )
        }
    }

    if (editingIndex >= 0) {
        val step = steps.getOrNull(editingIndex)
        if (step == null) {
            editingIndex = -1
        } else {
            StepEditorSheet(
                base = step,
                runInterval = wfInterval,
                onDismiss = { editingIndex = -1 },
                onConfirm = { updated, runParams ->
                    // 「启动应用」保存时会带回运行参数（工作流级字段，但没有其他入口编辑）
                    if (runParams != null) {
                        wfInterval = runParams.intervalMs.toString()
                    }
                    val current = currentSteps()
                    if (editingIndex in current.indices) {
                        setSteps(current.toMutableList().apply { this[editingIndex] = updated })
                    }
                    editingIndex = -1
                },
                onDelete = {
                    val current = currentSteps()
                    if (editingIndex in current.indices) {
                        setSteps(current.toMutableList().apply { removeAt(editingIndex) })
                        repo.showHint("已删除「${step.toStepType().label}」步骤", HintLevel.INFO)
                    }
                    editingIndex = -1
                },
            )
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("放弃未保存的更改？") },
            text = { Text("当前流程的修改还没有保存。离开后这些修改将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    onBack()
                }) { Text("放弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    if (showDeleteWorkflow) {
        AlertDialog(
            onDismissRequest = { showDeleteWorkflow = false },
            title = { Text("删除这条工作流？") },
            text = {
                Text("「${originalName.ifBlank { wfName }}」及其全部步骤将被永久删除，此操作无法撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteWorkflow = false
                    scope.launch {
                        val name = originalName.ifBlank { wfName }
                        repo.deleteWorkflow(name)
                        repo.showHint("已删除「$name」", HintLevel.SUCCESS)
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWorkflow = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 流程画布：只负责「看顺序 + 拖动排序」。
 * 卡片本身就是拖拽手柄（长按后直接拖），没有左侧按钮。
 */
@Composable
private fun FlowCanvasPage(
    steps: List<Step>,
    listState: LazyListState,
    draggingIndex: Int?,
    dragOffsetY: Float,
    onDragStart: (Int) -> Unit,
    onDragDelta: (Int, Float) -> Unit,
    onDragEnd: (Int) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("流程编排", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                "${steps.size} 个步骤",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "长按步骤直接拖动排序",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (steps.isEmpty()) {
                item { EmptyFlowState(onOpenLibrary) }
            } else {
                items(steps.size) { index ->
                    val isDragging = draggingIndex == index
                    FlowStepRow(
                        index = index,
                        step = steps[index],
                        isDragging = isDragging,
                        dragTranslationY = if (isDragging) dragOffsetY else 0f,
                        onDragStart = { onDragStart(index) },
                        onDragDelta = { delta -> onDragDelta(index, delta) },
                        onDragEnd = { onDragEnd(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowStepRow(
    index: Int,
    step: Step,
    isDragging: Boolean,
    dragTranslationY: Float,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val type = step.toStepType()
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragTranslationY
                scaleX = if (isDragging) 1.015f else 1f
                scaleY = if (isDragging) 1.015f else 1f
            }
            .pointerInput(index) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.y)
                }
            },
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        shadowElevation = if (isDragging) 8.dp else 0.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.62f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepIndexBadge(index)
            Spacer(Modifier.width(10.dp))
            Icon(
                type.icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, style = MaterialTheme.typography.labelLarge)
                    if (step.optional) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "可选",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                Text(
                    step.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 功能库页：添加/编辑功能 + 「当前流程」只读预览。
 * 目标应用与运行参数不再单独设置，统一放在「启动应用」功能的参数面板里。
 */
@Composable
private fun LibraryPage(
    wfName: String,
    onNameChange: (String) -> Unit,
    failureRetries: String,
    onFailureRetriesChange: (String) -> Unit,
    steps: List<Step>,
    onAdd: (StepType) -> Unit,
    onEditStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        SectionEyebrow("基础信息")
        OutlinedTextField(
            value = wfName,
            onValueChange = onNameChange,
            label = { Text("工作流名称") },
            placeholder = { Text("例如：每日签到") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        SectionEyebrow("流程控制")
        NumField(
            label = "失败重复次数",
            value = failureRetries,
            onValueChange = onFailureRetriesChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "每个功能执行失败后就地重试该步骤的次数（首次执行不算，最多 $MAX_FAILURE_RETRIES 次），" +
                "0 表示失败即停止。模板匹配超时、动作失败等都按这里的次数重试；" +
                "重试耗尽仍失败时中止流程并震动响铃提醒。" +
                "IF 分支内的每个步骤同样各自重试（IF 设为可选则跳过剩余分支）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(4.dp))
        SectionEyebrow("功能库")
        Text(
            "点按功能加入流程末尾；目标应用与运行参数在「启动应用」里设置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        // 只露出 3 排：高度锁定为 3 个格子 + 2 个排间距，多出来的功能在库区域内滑动查看
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    LIBRARY_TILE_HEIGHT * LIBRARY_VISIBLE_ROWS +
                        LIBRARY_ROW_GAP * (LIBRARY_VISIBLE_ROWS - 1),
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LIBRARY_ROW_GAP),
        ) {
            STEP_TYPE_ROWS.forEach { rowTypes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowTypes.forEach { type ->
                        FunctionTile(
                            type = type,
                            onClick = { onAdd(type) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (STEP_TYPE_ROWS.size > LIBRARY_VISIBLE_ROWS) {
            Text(
                "上下滑动查看全部 ${StepType.entries.size} 个功能",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("当前流程", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                if (steps.isEmpty()) "还没有步骤" else "${steps.size} 个步骤 · 点按编辑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (steps.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    "从上方功能库添加步骤后，这里会按执行顺序列出；排序请到「流程」页拖动。",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            steps.forEachIndexed { index, step ->
                PreviewStepRow(
                    index = index,
                    step = step,
                    onClick = { onEditStep(index) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PreviewStepRow(
    index: Int,
    step: Step,
    onClick: () -> Unit,
) {
    val type = step.toStepType()
    val scheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.62f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepIndexBadge(index)
            Spacer(Modifier.width(10.dp))
            Icon(
                type.icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, style = MaterialTheme.typography.labelLarge)
                    if (step.optional) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "可选",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                Text(
                    step.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.Edit,
                contentDescription = "编辑",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StepIndexBadge(index: Int) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyFlowState(onOpenLibrary: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("还没有任何步骤", style = MaterialTheme.typography.bodyMedium)
            Text(
                "到功能库添加第一个功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onOpenLibrary) { Text("打开功能库") }
        }
    }
}

@Composable
private fun FunctionTile(
    type: StepType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconPlate(icon = type.icon, size = 32)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    type.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    type.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EditorBottomBar(
    page: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = page == PAGE_FLOW,
                onClick = { onSelect(PAGE_FLOW) },
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text("流程") },
                colors = editorNavColors(),
            )
            NavigationBarItem(
                selected = page == PAGE_LIBRARY,
                onClick = { onSelect(PAGE_LIBRARY) },
                icon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text("功能库") },
                colors = editorNavColors(),
            )
        }
    }
}

@Composable
private fun editorNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

