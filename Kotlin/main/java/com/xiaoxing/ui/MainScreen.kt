package com.xiaoxing.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.ui.components.AppBackground
import com.xiaoxing.ui.components.HintOverlay
import com.xiaoxing.ui.components.ProjectionConfirmDialog
import com.xiaoxing.ui.components.StatusPill
import com.xiaoxing.ui.pages.HomePage
import com.xiaoxing.ui.pages.SettingsDestination
import com.xiaoxing.ui.pages.SettingsDetailPage
import com.xiaoxing.ui.pages.SettingsPage
import com.xiaoxing.ui.pages.WorkflowEditorPage
import com.xiaoxing.ui.pages.WorkflowsPage
import com.xiaoxing.ui.theme.AppTheme

private const val TAB_HOME = 0
private const val TAB_WORKFLOWS = 1
private const val TAB_SETTINGS = 2

/** 底部导航项只创建一次，避免 MainTabs 每次重组都分配新的 Pair 列表。 */
private val NAV_ITEMS = listOf(
    "首页" to Icons.Filled.Home,
    "流程" to Icons.AutoMirrored.Filled.List,
    "设置" to Icons.Filled.Settings,
)

/**
 * App shell.
 *
 * Primary navigation is a three-item bottom bar (首页 / 流程 / 设置). Secondary screens
 * (workflow editor, one settings detail) replace the shell entirely so their own top bar
 * is the single, predictable back affordance.
 */
@Composable
fun MainScreen(
    onRequestStart: (workflowNames: List<String>) -> Unit,
    onStop: () -> Unit,
    onConfirmProjection: () -> Unit,
    onCancelProjection: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorName by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsKey by rememberSaveable { mutableStateOf("") }

    val settingsDestination = SettingsDestination.entries.firstOrNull { it.key == settingsKey }

    // 放在详情页/编辑器之外：进入二级页时 MainTabs 离开组合，但它的 rememberSaveable
    // 状态（选中流程、列表滚动位置）由这个 holder 保存，返回后恢复。
    val stateHolder = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize()) {
        AppBackground {
            when {
                editorOpen -> WorkflowEditorPage(
                    initialName = editorName,
                    onBack = {
                        editorOpen = false
                        editorName = null
                    },
                )

                settingsDestination != null -> SettingsDetailPage(
                    destination = settingsDestination,
                    onBack = { settingsKey = "" },
                )

                else -> stateHolder.SaveableStateProvider("main-tabs") {
                    MainTabs(
                        tab = tab,
                        onTabSelected = { tab = it },
                        stateHolder = stateHolder,
                        onRequestStart = onRequestStart,
                        onStop = onStop,
                        onAddWorkflow = {
                            editorName = null
                            editorOpen = true
                        },
                        onEditWorkflow = { name ->
                            editorName = name
                            editorOpen = true
                        },
                        onOpenSettings = { destination ->
                            settingsKey = destination.key
                        },
                    )
                }
            }
        }

        // 顶层提示永远盖在页面与底部导航之上；错误提示常驻，其余自动收起。
        HintOverlay(Modifier.align(Alignment.TopCenter))
    }

    ProjectionConfirmDialog(
        onConfirm = onConfirmProjection,
        onCancel = onCancelProjection,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(
    tab: Int,
    onTabSelected: (Int) -> Unit,
    stateHolder: SaveableStateHolder,
    onRequestStart: (List<String>) -> Unit,
    onStop: () -> Unit,
    onAddWorkflow: () -> Unit,
    onEditWorkflow: (String) -> Unit,
    onOpenSettings: (SettingsDestination) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isRunning by AppRepository.isRunning.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            when (tab) {
                TAB_HOME -> TopAppBar(
                    title = {
                        Column {
                            Text("工学云", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "自动化控制台",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (isRunning) {
                            StatusPill(
                                text = "运行中",
                                color = AppTheme.extended.success,
                                modifier = Modifier.padding(end = 14.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )

                TAB_WORKFLOWS -> TopAppBar(
                    title = { Text("工作流", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        FilledTonalButton(
                            onClick = onAddWorkflow,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .height(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "添加",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )

                else -> TopAppBar(
                    title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 0.dp,
                ) {
                    NAV_ITEMS.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = {
                                if (tab != index) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onTabSelected(index)
                                }
                            },
                            icon = {
                                Icon(
                                    icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                TAB_HOME -> stateHolder.SaveableStateProvider("tab-home") {
                    HomePage(
                        onRequestStart = onRequestStart,
                        onStop = onStop,
                        onOpenServerSettings = { onOpenSettings(SettingsDestination.SERVER) },
                        onOpenPermissions = { onOpenSettings(SettingsDestination.PERMISSIONS) },
                    )
                }

                TAB_WORKFLOWS -> stateHolder.SaveableStateProvider("tab-workflows") {
                    WorkflowsPage(
                        onAdd = onAddWorkflow,
                        onEdit = onEditWorkflow,
                    )
                }

                else -> stateHolder.SaveableStateProvider("tab-settings") {
                    SettingsPage(onOpen = onOpenSettings)
                }
            }
        }
    }
}
