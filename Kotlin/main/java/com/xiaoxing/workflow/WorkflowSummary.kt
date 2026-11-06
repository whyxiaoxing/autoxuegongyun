package com.xiaoxing.workflow

import androidx.compose.runtime.Immutable

/** Lightweight card/list data — reading every JSON for the UI should not require opening an editor. */
@Immutable
data class WorkflowSummary(
    val name: String,
    val targetPackage: String = "",
    val stepCount: Int = 0,
    val parseFailed: Boolean = false,
)
