package com.example.smsforwarder.model

import java.util.UUID

data class QuantumBot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val webhookUrl: String,
    val keywords: String = "",
    val senderFilters: String = "",
    val isEnabled: Boolean = true,
    val isMentioned: Boolean = false,
    val mentionType: Int = 2,
    val mentionedMobiles: String = "",
    /** 新版关键词规则（JSON），为空时回退到旧版 keywords */
    val ruleJson: String = ""
) {
    /** 当前生效的规则：优先解析 ruleJson，旧配置自动迁移成「任一关键词」 */
    fun getRule(): KeywordRule =
        KeywordRule.fromJson(ruleJson) ?: KeywordRule.legacy(keywords)

    fun getKeywordList(): List<String> =
        keywords.split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun getSenderFilterList(): List<String> =
        senderFilters.split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun getMentionedMobileList(): List<String> =
        mentionedMobiles.split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun matchesSender(sender: String, isFromNotificationTitle: Boolean = false): Boolean {
        val filters = getSenderFilterList()
        if (filters.isEmpty()) return true
        val normalizedSender = sender.trim().removePrefix("+86").replace(" ", "")
        if (isFromNotificationTitle && !normalizedSender.any { it.isDigit() }) {
            return true
        }
        return filters.any { filter ->
            val f = filter.removePrefix("+86").replace(" ", "")
            normalizedSender.startsWith(f, ignoreCase = true) ||
                normalizedSender.contains(f, ignoreCase = true)
        }
    }

    /** 兼容旧调用点：只判断是否命中 */
    fun matchesContent(content: String): Boolean = getRule().matches(content).matched

    fun matches(sender: String, content: String, isFromNotificationTitle: Boolean = false): MatchResult {
        if (!matchesSender(sender, isFromNotificationTitle)) {
            return MatchResult(false, "号码过滤未命中")
        }
        return getRule().matches(content)
    }
}
