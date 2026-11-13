package com.example.smsforwarder.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 转发成功记录，用于首页统计。只存「时间 + 命中关键词」，不含短信正文。 */
object StatsStore {
    private const val PREFS_NAME = "send_stats"
    private const val KEY_RECORDS = "records"
    private const val KEEP_DAYS = 200
    private const val MAX_RECORDS = 20_000

    enum class Mode { DAY, WEEK, MONTH }

    data class Record(
        val at: Long = System.currentTimeMillis(),
        val keyword: String = ""
    )

    data class Summary(
        val labels: List<String>,
        val values: List<Int>,
        val total: Int,
        val previousTotal: Int,
        val top: List<Pair<String, Int>>
    )

    private val gson = Gson()
    private val lock = Any()
    private val labelFormat = ThreadLocal.withInitial { SimpleDateFormat("M/d", Locale.getDefault()) }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<Record> = synchronized(lock) {
        val json = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        try {
            val type = object : TypeToken<List<Record>>() {}.type
            gson.fromJson<List<Record>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(context: Context, keyword: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val list = load(context).toMutableList()
            list.add(Record(at = now, keyword = keyword))
            val cutoff = now - KEEP_DAYS.toLong() * 24 * 60 * 60 * 1000
            val kept = list.filter { it.at >= cutoff }.takeLast(MAX_RECORDS)
            prefs(context).edit().putString(KEY_RECORDS, gson.toJson(kept)).apply()
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECORDS).apply()
    }

    fun todayCount(context: Context): Int {
        val start = dayStart(System.currentTimeMillis())
        return load(context).count { it.at >= start }
    }

    fun summary(context: Context, mode: Mode): Summary {
        val records = load(context)
        val count = when (mode) {
            Mode.DAY -> 14
            Mode.WEEK -> 8
            Mode.MONTH -> 6
        }
        val starts = ArrayList<Long>(count)
        for (i in count - 1 downTo 0) {
            starts.add(bucketStart(System.currentTimeMillis(), mode, i))
        }
        val ranges = ArrayList<Pair<Long, Long>>(count)
        for (i in 0 until count) {
            val start = starts[i]
            val end = if (i < count - 1) starts[i + 1] else dayStart(System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
            ranges.add(start to end)
        }
        val values = ranges.map { (s, e) -> records.count { it.at in s until e } }
        val labels = starts.map { formatLabel(it, mode) }

        val windowStart = starts.firstOrNull() ?: System.currentTimeMillis()
        val windowEnd = ranges.last().second
        val span = (windowEnd - windowStart).coerceAtLeast(1L)
        val previousTotal = records.count { it.at in (windowStart - span) until windowStart }

        val top = records.filter { it.at in windowStart until windowEnd }
            .groupingBy { it.keyword.ifBlank { "全部短信" } }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        return Summary(
            labels = labels,
            values = values,
            total = values.sum(),
            previousTotal = previousTotal,
            top = top
        )
    }

    fun all(context: Context): List<Record> = load(context)

    private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun bucketStart(now: Long, mode: Mode, offset: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart(now) }
        when (mode) {
            Mode.DAY -> cal.add(Calendar.DAY_OF_YEAR, -offset)
            Mode.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -offset * 7)
                val weekOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一为一周起点
                cal.add(Calendar.DAY_OF_YEAR, -weekOffset)
            }
            Mode.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, -offset)
            }
        }
        return cal.timeInMillis
    }

    private fun formatLabel(ts: Long, mode: Mode): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return if (mode == Mode.MONTH) {
            "${cal.get(Calendar.MONTH) + 1}月"
        } else {
            labelFormat.get()?.format(cal.time) ?: ""
        }
    }
}
