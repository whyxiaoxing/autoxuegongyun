package com.xiaoxing.config

import androidx.compose.runtime.Immutable

/** 身份信息的一段：key 用于工作流 JSON 持久化，label/placeholder 面向设置页与编辑器 */
@Immutable
data class IdentitySection(
    val key: String,
    val label: String,
    val placeholder: String,
)

object IdentitySections {
    const val UNIT = "unit"
    const val ROLE = "role"
    const val TODAY_EVENTS = "todayEvents"
    const val DAILY_REPORT = "dailyReport"
    const val WEEKLY_REPORT = "weeklyReport"
    const val MONTHLY_REPORT = "monthlyReport"

    val all: List<IdentitySection> = listOf(
        IdentitySection(UNIT, "实习单位", "例如：XX 科技有限公司 · 后端开发实习生"),
        IdentitySection(ROLE, "身份描述", "例如：计算机专业大三学生，负责订单接口与数据整理"),
        IdentitySection(TODAY_EVENTS, "今日事件", "今天完成的事情、遇到的问题、明天的计划……"),
        IdentitySection(DAILY_REPORT, "日报", "已有的日报内容或日报写作偏好"),
        IdentitySection(WEEKLY_REPORT, "周报", "已有的周报内容或周报写作偏好"),
        IdentitySection(MONTHLY_REPORT, "月报", "已有的月报内容或月报写作偏好"),
    )

    private val byKey: Map<String, IdentitySection> = all.associateBy { it.key }

    fun find(key: String): IdentitySection? = byKey[key]

    fun label(key: String): String = byKey[key]?.label ?: key
}

/**
 * 身份信息：作为 `/AiText` 路由的提示词素材。
 *
 * 工作流的 AI 文案步骤只保存「选择了哪些段」，具体内容始终读取设置页的最新值，
 * 因此修改身份信息后不需要重新编辑工作流。
 */
@Immutable
data class IdentityInfo(
    val unit: String = "",
    val role: String = "",
    val todayEvents: String = "",
    val dailyReport: String = "",
    val weeklyReport: String = "",
    val monthlyReport: String = "",
) {
    fun valueOf(key: String): String = when (key) {
        IdentitySections.UNIT -> unit
        IdentitySections.ROLE -> role
        IdentitySections.TODAY_EVENTS -> todayEvents
        IdentitySections.DAILY_REPORT -> dailyReport
        IdentitySections.WEEKLY_REPORT -> weeklyReport
        IdentitySections.MONTHLY_REPORT -> monthlyReport
        else -> ""
    }

    /** 已填写的段数（设置页摘要与编辑器提示用） */
    val filledCount: Int get() = IdentitySections.all.count { valueOf(it.key).isNotBlank() }

    /**
     * 按选择顺序拼接提示词。空白段自动跳过；补充要求放在最后。
     * 返回空串表示没有任何可用内容 —— 工作流会把它当作失败并提示用户。
     */
    fun buildPrompt(selectedKeys: List<String>, instruction: String = ""): String {
        val selected = selectedKeys.toSet()
        val body = IdentitySections.all
            .map { it.key }
            .filter { it in selected }
            .mapNotNull { key ->
                val value = valueOf(key).trim()
                if (value.isBlank()) null else "【${IdentitySections.label(key)}】\n$value"
            }
            .joinToString("\n\n")
        val extra = instruction.trim()
        return listOfNotNull(
            body.takeIf { it.isNotBlank() },
            extra.takeIf { it.isNotBlank() }?.let { "【补充要求】\n$it" },
        ).joinToString("\n\n")
    }
}
