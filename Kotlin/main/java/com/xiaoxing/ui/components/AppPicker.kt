package com.xiaoxing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaoxing.apps.AppListHelper

/**
 * 整块可点击的目标应用选择入口（下拉选择，不允许手动输入包名）。
 * 包名为空时显示 [emptyLabel]。
 */
@Composable
internal fun AppSelectorField(
    label: String,
    packageName: String,
    emptyLabel: String,
    hint: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
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
                    packageName.ifBlank { emptyLabel },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (packageName.isBlank()) {
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
 * 已安装应用下拉选择器（可搜索）。
 *
 * @param allowInherit 为 true 时列表首项提供「留空 = 继承上层目标」选项，回传空包名
 * @param inheritLabel 继承项的标题
 * @param inheritHint 继承项的说明
 */
@Composable
internal fun AppPickerDialog(
    currentPackage: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    allowInherit: Boolean = false,
    inheritLabel: String = "",
    inheritHint: String = "",
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppListHelper.AppEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        apps = AppListHelper.launchableApps(context)
        loading = false
    }

    val filtered = remember(apps, query) {
        val keyword = query.trim()
        val list = apps.orEmpty()
        if (keyword.isBlank()) {
            list
        } else {
            list.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
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
                        Text("选择目标应用", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "来自已安装应用列表",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { reloadKey += 1 },
                        enabled = !loading,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重新加载应用列表",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    apps == null && loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    apps == null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "未能读取应用列表",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    filtered.isEmpty() && query.isNotBlank() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "没有匹配「$query」的应用",
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
                        if (allowInherit && query.isBlank()) {
                            item {
                                AppOptionRow(
                                    title = inheritLabel,
                                    subtitle = inheritHint,
                                    selected = currentPackage.isBlank(),
                                    onClick = { onPick("") },
                                )
                            }
                        }
                        items(filtered, key = { it.packageName }) { app ->
                            AppOptionRow(
                                title = app.label,
                                subtitle = app.packageName,
                                selected = app.packageName == currentPackage,
                                onClick = { onPick(app.packageName) },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 ${apps?.size ?: 0} 个应用",
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
private fun AppOptionRow(
    title: String,
    subtitle: String,
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (selected) scheme.primary else Color.Transparent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
