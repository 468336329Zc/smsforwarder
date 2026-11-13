package com.example.smsforwarder.util

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object MessageDeduplicator {
    private const val TAG = "MessageDeduplicator"
    private const val EXPIRE_TIME_MS = 10_000L
    private const val CLEAN_INTERVAL_MS = 30_000L
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val processedMessages = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastCleanTime = 0L

    fun isNewMessage(sender: String, content: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCleanTime > CLEAN_INTERVAL_MS) {
            cleanExpiredRecords(now)
            lastCleanTime = now
        }

        val fingerprint = generateFingerprint(content)
        val prevTime = processedMessages[fingerprint]
        if (prevTime != null && (now - prevTime) < EXPIRE_TIME_MS) {
            AppLogger.d(TAG, "10秒内多通道重复捕获同一短信，跳过: $fingerprint")
            return false
        }
        processedMessages[fingerprint] = now
        return true
    }

    private fun generateFingerprint(content: String): String {
        val normalizedContent = content.trim().replace(WHITESPACE_REGEX, " ")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizedContent.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun cleanExpiredRecords(now: Long) {
        val it = processedMessages.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value >= EXPIRE_TIME_MS) {
                it.remove()
            }
        }
    }
}
