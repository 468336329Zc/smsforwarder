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

    /** 导入备份时整体恢复历史记录（保留原始时间戳） */
    fun replaceAll(context: Context, records: List<Record>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cutoff = now - KEEP_DAYS.toLong() * 24 * 60 * 60 * 1000
            val kept = records.filter { it.at >= cutoff }.sortedBy { it.at }.takeLast(MAX_RECORDS)
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
        val now = System.currentTimeMillis()

        val periodStart = periodStart(now, mode)
        val total = records.count { it.at in periodStart..now }
        val previousTotal = previousPeriodTotal(records, now, mode)

        val ranges = chartRanges(now, mode)
        val values = ranges.map { (start, end) -> records.count { it.at in start until end } }
        val labels = ranges.map { (start, _) -> formatLabel(start, mode) }

        // 关键词排名与总数同口径：今日 / 本周 / 本月当前自然周期，不按折线图窗口放宽。
        val top = records.filter { it.at in periodStart..now }
            .groupingBy { it.keyword.ifBlank { "全部短信" } }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        return Summary(
            labels = labels,
            values = values,
            total = total,
            previousTotal = previousTotal,
            top = top
        )
    }

    /** 当前自然周期起点：日=今天 0 点；周=本周一 0 点；月=本月 1 号 0 点 */
    private fun periodStart(now: Long, mode: Mode): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart(now) }
        when (mode) {
            Mode.DAY -> return cal.timeInMillis
            Mode.WEEK -> {
                val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.add(Calendar.DAY_OF_YEAR, -offset)
                return cal.timeInMillis
            }
            Mode.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                return cal.timeInMillis
            }
        }
    }

    /** 折线图窗口：日=近 7 天；周=本月按周一切分的各周；月=近 6 个月 */
    private fun chartRanges(now: Long, mode: Mode): List<Pair<Long, Long>> {
        val dayMs = 24 * 60 * 60 * 1000L
        return when (mode) {
            Mode.DAY -> (6 downTo 0).map { i ->
                val start = dayStart(now - i * dayMs)
                start to start + dayMs
            }
            Mode.MONTH -> (5 downTo 0).map { i ->
                val start = monthStart(now, i)
                val end = monthStart(now, i - 1)
                start to end
            }
            Mode.WEEK -> monthWeekRanges(now)
        }
    }

    /** 本月按周一为界切成若干周（月初到第一个周一为第一周，不足 7 天） */
    private fun monthWeekRanges(now: Long): List<Pair<Long, Long>> {
        val dayMs = 24 * 60 * 60 * 1000L
        val monthStartCal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStart = monthStartCal.timeInMillis
        val monthEnd = (monthStartCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        val firstMonday = monthStart + ((monthStartCal.get(Calendar.DAY_OF_WEEK) + 5) % 7) * dayMs

        val starts = ArrayList<Long>()
        var cursor = monthStart
        var firstWeek = true
        while (cursor < monthEnd) {
            starts.add(cursor)
            cursor += if (firstWeek && firstMonday > monthStart) firstMonday - monthStart else dayMs * 7
            firstWeek = false
        }
        val ends = starts.drop(1) + monthEnd
        return starts.zip(ends)
    }

    private fun previousPeriodTotal(records: List<Record>, now: Long, mode: Mode): Int {
        val period = periodStart(now, mode)
        val prevStart = when (mode) {
            Mode.DAY -> period - 24 * 60 * 60 * 1000L
            Mode.WEEK -> period - 7 * 24 * 60 * 60 * 1000L
            Mode.MONTH -> {
                val cal = Calendar.getInstance().apply { timeInMillis = period; add(Calendar.MONTH, -1) }
                cal.timeInMillis
            }
        }
        return records.count { it.at in prevStart until period }
    }

    private fun monthStart(now: Long, offsetMonths: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -offsetMonths)
        }
        return cal.timeInMillis
    }

    fun all(context: Context): List<Record> = load(context)

    private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun formatLabel(ts: Long, mode: Mode): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return if (mode == Mode.MONTH) {
            "${cal.get(Calendar.MONTH) + 1}月"
        } else {
            labelFormat.get()?.format(cal.time) ?: ""
        }
    }
}
