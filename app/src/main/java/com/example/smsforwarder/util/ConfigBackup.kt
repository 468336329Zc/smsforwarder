package com.example.smsforwarder.util

import com.example.smsforwarder.model.QuantumBot
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * 配置备份：把机器人配置 / 统计记录序列化为 JSON 文本，便于换机或重装后恢复。
 * 统计只含「时间 + 命中关键词」，不含短信正文。
 */
object ConfigBackup {

    data class Payload(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val bots: List<QuantumBot> = emptyList(),
        val stats: List<StatsStore.Record> = emptyList()
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun export(bots: List<QuantumBot>, stats: List<StatsStore.Record>): String =
        gson.toJson(Payload(bots = bots, stats = stats))

    fun parse(json: String): Payload? {
        if (json.isBlank()) return null
        return try {
            val type = object : TypeToken<Payload>() {}.type
            gson.fromJson<Payload>(json, type)
        } catch (_: Exception) {
            null
        }
    }
}
