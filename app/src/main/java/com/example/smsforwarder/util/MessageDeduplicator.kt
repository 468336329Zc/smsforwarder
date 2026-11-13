package com.example.smsforwarder.util

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 三通道（系统广播 / 通知监听 / 短信库）会重复捕获同一条短信。
 * 各通道拿到的文本并不完全一致（通知通道通常只有正文，短信库带【发送方】前缀），
 * 因此除"完全相同文本"外，还需要按"验证码/长数字指纹"判断是否为同一条短信；
 * 并且只在真正转发成功后登记，避免先捕获的残缺文本把后面更完整的文本挡掉。
 */
object MessageDeduplicator {
    private const val TAG = "MessageDeduplicator"
    private const val EXPIRE_TIME_MS = 10_000L
    private const val CLEAN_INTERVAL_MS = 30_000L
    private const val MIN_TOKEN_LENGTH = 4
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val DIGIT_RUN_REGEX = Regex("\\d{4,}")
    private val NON_WORD_REGEX = Regex("[^0-9a-zA-Z\\u4e00-\\u9fa5]")

    /** 已处理过的完整文本指纹（防止同一通道短时间重复投递） */
    private val seenCaptures = ConcurrentHashMap<String, Long>()

    /** 已成功转发的短信指纹（跨通道去重依据） */
    private val forwardedSignatures = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastCleanTime = 0L

    /** 完全相同文本在 10 秒内重复捕获 */
    fun isDuplicateCapture(content: String): Boolean {
        cleanIfNeeded()
        val fingerprint = hash(content.trim().replace(WHITESPACE_REGEX, " "))
        val prev = seenCaptures[fingerprint]
        seenCaptures[fingerprint] = System.currentTimeMillis()
        return prev != null && System.currentTimeMillis() - prev < EXPIRE_TIME_MS
    }

    /** 这条短信是否已由其它通道成功转发过 */
    fun isAlreadyForwarded(content: String): Boolean {
        cleanIfNeeded()
        val signature = signatureOf(content)
        val prev = forwardedSignatures[signature]
        return prev != null && System.currentTimeMillis() - prev < EXPIRE_TIME_MS
    }

    /** 推送成功后才登记，避免未转发的残缺文本误挡后续完整文本 */
    fun markForwarded(content: String) {
        cleanIfNeeded()
        forwardedSignatures[signatureOf(content)] = System.currentTimeMillis()
        AppLogger.d(TAG, "登记已转发短信：${signatureOf(content).take(16)}")
    }

    /** 同一条短信的跨通道指纹：优先取长数字（验证码 / 单号），否则退化为正文摘要 */
    private fun signatureOf(content: String): String {
        val tokens = DIGIT_RUN_REGEX.findAll(content)
            .map { it.value }
            .distinct()
            .sorted()
            .joinToString("|")
        if (tokens.isNotEmpty()) return "d:$tokens"
        return "t:" + hash(content.replace(NON_WORD_REGEX, "").lowercase())
    }

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun cleanIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanTime <= CLEAN_INTERVAL_MS) return
        lastCleanTime = now
        seenCaptures.entries.removeAll { now - it.value >= EXPIRE_TIME_MS }
        forwardedSignatures.entries.removeAll { now - it.value >= EXPIRE_TIME_MS }
    }
}
