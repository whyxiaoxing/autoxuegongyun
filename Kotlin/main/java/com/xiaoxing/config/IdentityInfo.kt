package com.xiaoxing.config

import androidx.compose.runtime.Immutable

/** 身份信息的一段：key 用于工作流 JSON 持久化，label/placeholder 面向设置页与编辑器 */
@Immutable
data class IdentitySection(
    val key: String,
    val label: String,
    val placeholder: String,
)

/**
 * 报告类型：决定 assistant 内容结尾的「这是一份 …」。
 *
 * @property sectionKey 对应的身份信息正文段（日报 / 周报 / 月报），用于提示词
 */
@Immutable
data class ReportType(
    val key: String,
    val label: String,
    val sectionKey: String,
)

object IdentitySections {
    const val UNIT = "unit"
    const val ROLE = "role"
    const val TODAY_EVENTS = "todayEvents"
    const val DAILY_REPORT = "dailyReport"
    const val WEEKLY_REPORT = "weeklyReport"
    const val MONTHLY_REPORT = "monthlyReport"

    /** 发送到 assistant 的身份段：实习单位、身份描述 */
    val assistant: List<IdentitySection> = listOf(
        IdentitySection(UNIT, "实习单位", "例如：XX 科技有限公司 · 后端开发实习生"),
        IdentitySection(ROLE, "身份描述", "例如：计算机专业大三学生，负责订单接口与数据整理"),
    )

    /** 放进提示词的正文段：今日事件与日报/周报/月报内容 */
    val prompt: List<IdentitySection> = listOf(
        IdentitySection(TODAY_EVENTS, "今日事件", "今天完成的事情、遇到的问题、明天的计划……"),
        IdentitySection(DAILY_REPORT, "日报", "已有的日报内容或日报写作偏好"),
        IdentitySection(WEEKLY_REPORT, "周报", "已有的周报内容或周报写作偏好"),
        IdentitySection(MONTHLY_REPORT, "月报", "已有的月报内容或月报写作偏好"),
    )

    /** 固定顺序：身份段在前、正文段在后（设置页与提示词都按这个顺序展示） */
    val all: List<IdentitySection> = assistant + prompt

    private val byKey: Map<String, IdentitySection> = all.associateBy { it.key }

    fun find(key: String): IdentitySection? = byKey[key]

    fun label(key: String): String = byKey[key]?.label ?: key
}

object ReportTypes {
    /** 不指定报告类型：assistant 里不加「这是一份 …」 */
    const val NONE = ""
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"

    val all: List<ReportType> = listOf(
        ReportType(DAILY, "日报", IdentitySections.DAILY_REPORT),
        ReportType(WEEKLY, "周报", IdentitySections.WEEKLY_REPORT),
        ReportType(MONTHLY, "月报", IdentitySections.MONTHLY_REPORT),
    )

    private val byKey: Map<String, ReportType> = all.associateBy { it.key }

    fun find(key: String): ReportType? = byKey[key]

    /** 未知 / 空 key 返回空串 */
    fun label(key: String): String = byKey[key]?.label.orEmpty()
}

/**
 * 身份信息：`/AiText` 的 assistant 内容与 user 提示词素材。
 *
 * 分工（与 2026-11 服务端 `/AiText` 契约一致）：
 *  - 实习单位、身份描述 → `assistant提示词`（模型回复的前缀预填），默认发送；
 *  - 今日事件、日报/周报/月报 → `提示词`；
 *  - 工作流选择了报告类型时，assistant 会以「这是一份周报」这类句子收尾，让模型接着写正文。
 *
 * 工作流的 AI 文案步骤只保存「选择了哪些正文段」与报告类型，具体内容始终读取设置页的最新值，
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
    /**
     * 实习单位 / 身份描述是否默认发送到 assistant。
     * 在 设置 → 身份信息 里勾选；关闭后新工作流不再发送身份段（旧流程里显式勾选的段仍会发送）。
     */
    val assistantIdentity: Boolean = true,
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

    /** 本次要发送到 assistant 的身份段：默认两段都发；关闭开关后只发旧流程显式勾选的段 */
    private fun assistantKeys(selectedKeys: List<String>): List<String> {
        val keys = IdentitySections.assistant.map { it.key }
        return if (assistantIdentity) keys else keys.filter { it in selectedKeys }
    }

    /**
     * assistant 内容：身份段 + 「这是一份周报」（报告类型）。
     *
     * 报告类型放在最后，是因为 assistant 是模型回复的前缀预填 —— 模型会从这句话后面继续写正文。
     * 空白段自动跳过；没有任何内容时返回空串。
     */
    fun buildAssistant(selectedKeys: List<String>, reportType: String): String {
        val body = assistantKeys(selectedKeys)
            .mapNotNull { key ->
                val value = valueOf(key).trim()
                if (value.isBlank()) null else "【${IdentitySections.label(key)}】\n$value"
            }
            .joinToString("\n\n")
        val report = ReportTypes.label(reportType)
            .takeIf { it.isNotBlank() }
            ?.let { "这是一份$it" }
            .orEmpty()
        return listOfNotNull(
            body.takeIf { it.isNotBlank() },
            report.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")
    }

    /**
     * 按选择顺序拼接 user 提示词正文段。空白段自动跳过；补充要求放在最后。
     * 实习单位/身份描述不进入提示词 —— 它们走 [buildAssistant]。
     * 返回空串表示没有任何可用内容 —— 工作流会把它当作失败并提示用户。
     */
    fun buildPrompt(selectedKeys: List<String>, instruction: String = ""): String {
        val selected = selectedKeys.toSet()
        val body = IdentitySections.prompt
            .filter { it.key in selected }
            .mapNotNull { section ->
                val value = valueOf(section.key).trim()
                if (value.isBlank()) null else "【${section.label}】\n$value"
            }
            .joinToString("\n\n")
        val extra = instruction.trim()
        return listOfNotNull(
            body.takeIf { it.isNotBlank() },
            extra.takeIf { it.isNotBlank() }?.let { "【补充要求】\n$it" },
        ).joinToString("\n\n")
    }
}
