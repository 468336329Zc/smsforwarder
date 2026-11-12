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
    val mentionedMobiles: String = ""
) {
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

    fun matchesContent(content: String): Boolean {
        val kwList = getKeywordList()
        if (kwList.isEmpty()) return true
        val normalizedContent = content.replace("\s+".toRegex(), " ").lowercase()
        return kwList.any { kw ->
            val normalizedKw = kw.replace("\s+".toRegex(), " ").lowercase()
            normalizedContent.contains(normalizedKw)
        }
    }
}
