package com.xiaoxing.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoxing.AppRepository
import com.xiaoxing.ui.components.AppPanel
import com.xiaoxing.ui.components.EmptyState
import com.xiaoxing.ui.components.MetaChip
import com.xiaoxing.workflow.WorkflowSummary

/** Workflow library: every workflow is one long, tappable banner; add lives in the top bar. */
@Composable
internal fun WorkflowsPage(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val summaries by AppRepository.workflowSummaries.collectAsStateWithLifecycle()

    if (summaries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            AppPanel(Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "还没有工作流",
                    message = "添加一条工作流，开始编排可复用的自动化步骤。",
                    action = {
                        Button(onClick = onAdd) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加工作流")
                        }
                    },
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${summaries.size} 条流程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "点击卡片编辑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        itemsIndexed(summaries, key = { _, summary -> summary.name }) { index, summary ->
            WorkflowBanner(
                index = index,
                summary = summary,
                onClick = { onEdit(summary.name) },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun WorkflowBanner(
    index: Int,
    summary: WorkflowSummary,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = scheme.surfaceContainerLow.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primary.copy(alpha = 0.22f),
                                scheme.tertiary.copy(alpha = 0.14f),
                            ),
                        ),
                        RoundedCornerShape(17.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    color = scheme.primary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (summary.parseFailed) {
                        MetaChip("文件异常")
                    } else {
                        MetaChip("${summary.stepCount} 个步骤")
                        MetaChip(
                            summary.targetPackage
                                .takeIf { it.isNotBlank() }
                                ?.substringAfterLast('.')
                                ?: "未指定目标应用",
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "编辑 ${summary.name}",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
