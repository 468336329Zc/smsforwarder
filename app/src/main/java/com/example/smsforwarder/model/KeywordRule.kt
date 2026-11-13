package com.example.smsforwarder.model

import com.google.gson.Gson

enum class RuleMode { ANY, GROUPS }

data class MatchResult(
    val matched: Boolean,
    val reason: String,
    val keywords: List<String> = emptyList()
)

/**
 * 关键词规则：组内 AND、组间 OR，另有全局排除词。
 * - mode = ANY：terms 中任一命中即转发（旧版行为）
 * - mode = GROUPS：groups 中任意一组全部命中才转发
 */
data class KeywordRule(
    val mode: RuleMode = RuleMode.GROUPS,
    val terms: List<String> = emptyList(),
    val groups: List<List<String>> = emptyList(),
    val excludes: List<String> = emptyList()
) {

    fun summary(): String {
        if (mode == RuleMode.ANY) {
            val list = terms.map { it.trim() }.filter { it.isNotEmpty() }
            return if (list.isEmpty()) "全部短信" else list.joinToString(" / ")
        }
        val cleanGroups = groups.map { g -> g.map { it.trim() }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        if (cleanGroups.isEmpty()) return "全部短信"
        return cleanGroups.joinToString(" 或 ") { g -> g.joinToString(" 且 ") }
    }

    fun matches(content: String): MatchResult {
        val normalized = normalize(content)

        val hitExclude = excludes.map { it.trim() }.firstOrNull {
            it.isNotEmpty() && normalized.contains(normalize(it))
        }
        if (hitExclude != null) {
            return MatchResult(false, "命中排除词「$hitExclude」")
        }

        if (mode == RuleMode.ANY) {
            val list = terms.map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) return MatchResult(true, "未设置关键词，全部转发")
            val hit = list.firstOrNull { normalized.contains(normalize(it)) }
            return if (hit != null) {
                MatchResult(true, "命中关键词「$hit」", listOf(hit))
            } else {
                MatchResult(false, "未命中任何关键词")
            }
        }

        val cleanGroups = groups.map { g -> g.map { it.trim() }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        if (cleanGroups.isEmpty()) return MatchResult(true, "未配置条件组，全部转发")

        for (group in cleanGroups) {
            if (group.all { normalized.contains(normalize(it)) }) {
                return MatchResult(true, "条件组命中：${group.joinToString(" 且 ")}", group)
            }
        }
        val partial = cleanGroups.map { g -> g.filter { normalized.contains(normalize(it)) } }
            .filter { it.isNotEmpty() }
        return if (partial.isNotEmpty()) {
            MatchResult(false, "各组未同时满足（部分命中：${partial.joinToString(" / ") { it.joinToString("+") }}）")
        } else {
            MatchResult(false, "未命中任何条件组")
        }
    }

    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        fun fromJson(json: String?): KeywordRule? {
            if (json.isNullOrBlank()) return null
            return try {
                gson.fromJson(json, KeywordRule::class.java)
            } catch (_: Exception) {
                null
            }
        }

        /** 把旧版「逗号分隔关键词」迁移成 ANY 规则 */
        fun legacy(keywords: String): KeywordRule {
            val list = keywords.split(",", "，", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return KeywordRule(mode = RuleMode.ANY, terms = list)
        }
    }
}

private val WHITESPACE_REGEX = Regex("\\s+")

internal fun normalize(text: String): String =
    text.replace(WHITESPACE_REGEX, " ").trim().lowercase()
