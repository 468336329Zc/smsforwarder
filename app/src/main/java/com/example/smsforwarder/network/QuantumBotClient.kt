package com.example.smsforwarder.network

import com.example.smsforwarder.model.QuantumBot
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.SecurityUtils
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object QuantumBotClient {
    private const val TAG = "QuantumBotClient"
    private const val MAX_MESSAGES_PER_MINUTE = 20
    private const val RATE_WINDOW_MS = 60_000L
    private const val MAX_UPLOAD_BYTES = 30L * 1024L * 1024L

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val sendTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private object SafeDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.any { isPrivateAddress(it) }) {
                throw IOException("已拦截内网解析地址: $hostname")
            }
            return addresses
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .dns(SafeDns)
        .build()

    private fun isPrivateAddress(addr: InetAddress): Boolean =
        addr.isAnyLocalAddress || addr.isLoopbackAddress ||
            addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress

    fun isValidWebhookUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        return try {
            val host = URI(url).host?.lowercase() ?: return false
            if (host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal")
            ) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendText(bot: QuantumBot, content: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        val textMsg = mutableMapOf<String, Any>("content" to content)
        if (bot.isMentioned) {
            textMsg["isMentioned"] = true
            textMsg["mentionType"] = bot.mentionType
            if (bot.mentionType == 2) {
                textMsg["mentionedMobileList"] = bot.getMentionedMobileList()
            }
        }
        val payload = mapOf("type" to "text", "textMsg" to textMsg)
        enqueueWithRateLimit(bot, gson.toJson(payload), onComplete)
    }

    fun sendImage(bot: QuantumBot, fileId: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        val payload = mapOf("type" to "image", "imageMsg" to mapOf("fileId" to fileId))
        enqueueWithRateLimit(bot, gson.toJson(payload), onComplete)
    }

    fun sendFile(bot: QuantumBot, fileId: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        val payload = mapOf("type" to "file", "fileMsg" to mapOf("fileId" to fileId))
        enqueueWithRateLimit(bot, gson.toJson(payload), onComplete)
    }

    fun sendNews(
        bot: QuantumBot,
        title: String,
        url: String,
        description: String? = null,
        picUrl: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val info = mutableMapOf<String, Any>("title" to title.take(128), "url" to url)
        if (!description.isNullOrBlank()) info["description"] = description.take(512)
        if (!picUrl.isNullOrBlank()) info["picUrl"] = picUrl
        val payload = mapOf("type" to "news", "news" to mapOf("info" to info))
        enqueueWithRateLimit(bot, gson.toJson(payload), onComplete)
    }

    fun uploadAttachment(webhookUrl: String, file: File, type: Int, callback: (Result<String>) -> Unit) {
        if (!file.exists() || file.length() > MAX_UPLOAD_BYTES) {
            callback(Result.failure(IllegalArgumentException("文件不存在或超过 30MB 限制")))
            return
        }
        val key = extractKeyFromWebhook(webhookUrl)
        if (key.isNullOrBlank()) {
            callback(Result.failure(IllegalArgumentException("无法从 Webhook URL 中提取 key")))
            return
        }
        val uploadUrl = "https://imtwo.zdxlz.com/im-external/v1/webhook/upload-attachment?key=$key&type=$type"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("*/*".toMediaType()))
            .build()
        val request = Request.Builder().url(uploadUrl).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val respStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        callback(Result.failure(IOException("上传失败 HTTP ${resp.code}: $respStr")))
                        return
                    }
                    try {
                        val map = gson.fromJson(respStr, Map::class.java)
                        val data = map["data"] as? Map<*, *>
                        val fileId = data?.get("id")?.toString()
                        if (fileId.isNullOrBlank()) {
                            callback(Result.failure(IOException("响应缺少 fileId: $respStr")))
                        } else {
                            callback(Result.success(fileId))
                        }
                    } catch (e: Exception) {
                        callback(Result.failure(e))
                    }
                }
            }
        })
    }

    private fun extractKeyFromWebhook(webhookUrl: String): String? {
        return try {
            val query = URI(webhookUrl).query ?: return null
            query.split("&").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "key") parts[1] else null
            }.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun enqueueWithRateLimit(bot: QuantumBot, jsonBody: String, onComplete: ((Boolean, String) -> Unit)?) {
        if (bot.webhookUrl.isBlank() || !isValidWebhookUrl(bot.webhookUrl)) {
            val msg = "[${bot.name}] Webhook 地址为空或不合法"
            AppLogger.e(TAG, msg)
            onComplete?.invoke(false, msg)
            return
        }
        val delayMs = computeRateLimitDelay(bot.id)
        if (delayMs <= 0L) {
            executePost(bot, jsonBody, onComplete)
        } else {
            AppLogger.d(TAG, "[${bot.name}] 触发 20条/分钟 限流，延迟 ${delayMs}ms 发送")
            scheduler.schedule({ executePost(bot, jsonBody, onComplete) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun computeRateLimitDelay(botId: String): Long {
        val queue = sendTimestamps.computeIfAbsent(botId) { ArrayDeque() }
        synchronized(queue) {
            val now = System.currentTimeMillis()
            while (queue.isNotEmpty() && now - queue.peekFirst() >= RATE_WINDOW_MS) {
                queue.removeFirst()
            }
            if (queue.size < MAX_MESSAGES_PER_MINUTE) {
                queue.addLast(now)
                return 0L
            }
            val oldest = queue.peekFirst() ?: now
            val nextSlot = oldest + RATE_WINDOW_MS + 100L
            queue.addLast(nextSlot)
            return (nextSlot - now).coerceAtLeast(100L)
        }
    }

    private fun executePost(bot: QuantumBot, jsonBody: String, onComplete: ((Boolean, String) -> Unit)?) {
        AppLogger.d(TAG, "[${bot.name}] 推送到: ${SecurityUtils.maskWebhookUrl(bot.webhookUrl)}")
        val request = Request.Builder()
            .url(bot.webhookUrl)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val msg = "[${bot.name}] 发送失败: ${e.message}"
                AppLogger.e(TAG, msg)
                onComplete?.invoke(false, msg)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        AppLogger.d(TAG, "[${bot.name}] 发送成功, HTTP ${resp.code}")
                        onComplete?.invoke(true, bodyStr)
                    } else {
                        val msg = "[${bot.name}] 发送异常, HTTP ${resp.code}: $bodyStr"
                        AppLogger.e(TAG, msg)
                        onComplete?.invoke(false, msg)
                    }
                }
            }
        })
    }
}
