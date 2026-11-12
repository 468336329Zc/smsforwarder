package com.example.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import com.example.smsforwarder.model.QuantumBot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BotManager(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "bot_configs"
        private const val KEY_BOTS = "quantum_bots"
        @Volatile
        private var cachedBots: List<QuantumBot>? = null
    }

    @Synchronized
    fun getAllBots(): List<QuantumBot> {
        cachedBots?.let { return it }
        return loadBotsFromPrefs().also { cachedBots = it }
    }

    private fun loadBotsFromPrefs(): List<QuantumBot> {
        val json = prefs.getString(KEY_BOTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<QuantumBot>>() {}.type
            gson.fromJson<List<QuantumBot>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.e("BotManager", "解析机器人配置失败: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    private fun saveBots(bots: List<QuantumBot>) {
        prefs.edit().putString(KEY_BOTS, gson.toJson(bots)).apply()
        cachedBots = bots.toList()
    }

    fun addBot(bot: QuantumBot) {
        val list = getAllBots().toMutableList()
        list.add(bot)
        saveBots(list)
    }

    fun updateBot(updatedBot: QuantumBot) {
        val list = getAllBots().toMutableList()
        val idx = list.indexOfFirst { it.id == updatedBot.id }
        if (idx >= 0) {
            list[idx] = updatedBot
            saveBots(list)
        }
    }

    fun deleteBot(botId: String) {
        val list = getAllBots().toMutableList()
        list.removeAll { it.id == botId }
        saveBots(list)
    }

    fun toggleBot(botId: String, enabled: Boolean) {
        val bot = getBotById(botId) ?: return
        updateBot(bot.copy(isEnabled = enabled))
    }

    fun getBotById(id: String): QuantumBot? = getAllBots().firstOrNull { it.id == id }

    fun getEnabledBots(): List<QuantumBot> = getAllBots().filter { it.isEnabled }

    fun getMatchingBots(
        sender: String,
        content: String,
        isFromNotificationTitle: Boolean = false
    ): List<QuantumBot> {
        return getEnabledBots().filter { bot ->
            bot.matchesSender(sender, isFromNotificationTitle) && bot.matchesContent(content)
        }
    }
}
