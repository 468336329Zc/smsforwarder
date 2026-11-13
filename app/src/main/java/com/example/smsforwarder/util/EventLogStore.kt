package com.example.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 事件级转发日志：一条短信从捕获到推送的完整判定过程。
 * 步骤 level：info（说明）、pass（通过）、fail（未通过 / 跳过）。
 */
object EventLogStore {
    private const val PREFS_NAME = "forward_events"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 100

    data class Step(
        val level: String = "info",
        val text: String = ""
    )

    data class Event(
        val id: String = "",
        val at: Long = System.currentTimeMillis(),
        val sender: String = "",
        val body: String = "",
        val channel: String = "",
        val sent: Boolean = false,
        val steps: List<Step> = emptyList(),
        val bots: List<String> = emptyList()
    )

    private val gson = Gson()
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(context: Context): List<Event> = synchronized(lock) {
        val json = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        try {
            val type = object : TypeToken<List<Event>>() {}.type
            gson.fromJson<List<Event>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, event: Event) {
        synchronized(lock) {
            val list = all(context).toMutableList()
            val index = list.indexOfFirst { it.id == event.id }
            if (index >= 0) list[index] = event else list.add(0, event)
            val kept = list.sortedByDescending { it.at }.take(MAX_EVENTS)
            prefs(context).edit().putString(KEY_EVENTS, gson.toJson(kept)).apply()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_EVENTS).apply()
    }

    fun newId(): String = "ev" + System.currentTimeMillis() + (0..999).random()
}
