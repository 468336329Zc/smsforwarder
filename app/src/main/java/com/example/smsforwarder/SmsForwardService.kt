package com.example.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.example.smsforwarder.model.MatchResult
import com.example.smsforwarder.model.QuantumBot
import com.example.smsforwarder.network.QuantumBotClient
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.BotManager
import com.example.smsforwarder.util.EventLogStore
import com.example.smsforwarder.util.MessageDeduplicator
import com.example.smsforwarder.util.SecurityUtils
import com.example.smsforwarder.util.StatsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsForwardService : Service() {
    companion object {
        private const val TAG = "SmsForwardService"
        private const val CHANNEL_ID = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 4_000L

        const val EXTRA_SENDER = "sender"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_IS_TEST = "is_test"
        const val EXTRA_BOT_ID = "bot_id"
        const val EXTRA_KEEP_ALIVE = "keep_alive"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
        const val EXTRA_CHANNEL = "capture_channel"

        fun startKeepAlive(context: Context) {
            val intent = Intent(context, SmsForwardService::class.java).apply {
                putExtra(EXTRA_KEEP_ALIVE, true)
            }
            startServiceCompat(context, intent)
        }

        fun startForward(
            context: Context,
            sender: String,
            content: String,
            timestamp: Long,
            isTest: Boolean = false,
            botId: String? = null,
            fromNotificationTitle: Boolean = false,
            channel: String = "系统广播"
        ) {
            val intent = Intent(context, SmsForwardService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_TIMESTAMP, timestamp)
                putExtra(EXTRA_IS_TEST, isTest)
                if (!botId.isNullOrBlank()) putExtra(EXTRA_BOT_ID, botId)
                putExtra(EXTRA_FROM_NOTIFICATION, fromNotificationTitle)
                putExtra(EXTRA_CHANNEL, channel)
            }
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var botManager: BotManager
    private var smsObserver: SmsContentObserver? = null
    private var dynamicSmsReceiver: SmsReceiver? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            smsObserver?.scanNewInboxMessages()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        botManager = BotManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        registerDynamicSmsReceiver()
        registerSmsDatabaseObserver()
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        AppLogger.d(TAG, "前台服务已启动（广播接收器 + 通知监听 + 短信库游标 + 4秒兜底轮询）")
    }

    private fun registerDynamicSmsReceiver() {
        if (dynamicSmsReceiver != null) return
        val receiver = SmsReceiver()
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            addAction(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        dynamicSmsReceiver = receiver
    }

    private fun registerSmsDatabaseObserver() {
        if (smsObserver != null) return
        val observer = SmsContentObserver(this) { sender, body, timestamp ->
            forwardSms(sender, body, timestamp, fromNotificationTitle = false, channel = "短信库")
        }
        observer.initBaselineId()
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            smsObserver = observer
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册本机短信库监听失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.getBooleanExtra(EXTRA_KEEP_ALIVE, false)) {
            return START_STICKY
        }

        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        val isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false)
        val botId = intent.getStringExtra(EXTRA_BOT_ID)
        val fromNotif = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        val channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty().ifBlank { "系统广播" }

        if (content.isBlank()) return START_STICKY

        if (isTest) {
            val bot = botId?.let { botManager.getBotById(it) }
            if (bot != null && bot.isEnabled) {
                sendTestMessage(bot, sender, content, timestamp)
            } else {
                AppLogger.e(TAG, "测试模式：指定的机器人不存在或未启用")
            }
        } else {
            forwardSms(sender, content, timestamp, fromNotif, channel)
        }

        return START_STICKY
    }

    /**
     * 事件级转发：一条短信 → 一个事件，完整记录 捕获 / 号码过滤 / 规则判定 / 去重 / 推送。
     */
    private fun forwardSms(
        sender: String,
        content: String,
        timestamp: Long,
        fromNotificationTitle: Boolean,
        channel: String
    ) {
        val eventId = EventLogStore.newId()
        val steps = mutableListOf<EventLogStore.Step>()
        val matchedBots = mutableListOf<Pair<QuantumBot, MatchResult>>()
        val maskedSender = SecurityUtils.maskSensitiveContent(sender)
        val maskedBody = SecurityUtils.maskSensitiveContent(content)

        fun step(level: String, text: String) {
            steps.add(EventLogStore.Step(level, text))
        }

        step("info", "[$channel] 捕获短信：${maskedBody.take(56)}")

        // 同一条短信会被三通道重复捕获：其它通道已成功转发过，直接跳过（不写事件，避免日志里出现"未转发"假象）
        if (MessageDeduplicator.isAlreadyForwarded(content)) {
            AppLogger.d(TAG, "[$channel] 同一短信已由其它通道转发，本次仅去重")
            return
        }
        if (MessageDeduplicator.isDuplicateCapture(content)) {
            AppLogger.d(TAG, "[$channel] 10 秒内重复捕获同一文本，本次仅去重")
            return
        }

        val enabledBots = botManager.getEnabledBots()
        if (enabledBots.isEmpty()) {
            step("fail", "没有已启用的机器人 → 短信丢弃")
            persistEvent(eventId, maskedSender, maskedBody, channel, false, steps, emptyList())
            AppLogger.d(TAG, "无已启用机器人，短信丢弃")
            return
        }

        for (bot in enabledBots) {
            if (!bot.matchesSender(sender, fromNotificationTitle)) {
                step("fail", "「${bot.name}」号码过滤未命中（监听 ${bot.getSenderFilterList().joinToString(" / ")}）")
                continue
            }
            val rule = bot.getRule()
            val result = rule.matches(content)
            step("info", "「${bot.name}」规则：${rule.summary()}")
            if (!result.matched) {
                step("fail", "「${bot.name}」${result.reason} → 跳过")
                continue
            }
            step("pass", "「${bot.name}」${result.reason}")
            matchedBots.add(bot to result)
        }

        if (matchedBots.isEmpty()) {
            step("fail", "本次事件结束：未转发")
            persistEvent(eventId, maskedSender, maskedBody, channel, false, steps, emptyList())
            AppLogger.d(TAG, "未匹配任何机器人，跳过转发")
            return
        }

        persistEvent(eventId, maskedSender, maskedBody, channel, true, steps, matchedBots.map { it.first.name })

        for ((bot, result) in matchedBots) {
            val keyword = result.keywords.joinToString(" 且 ")
            enqueueSend(bot, sender, content, timestamp, eventId, steps, maskedSender, maskedBody, channel, keyword)
        }
    }

    private fun enqueueSend(
        bot: QuantumBot,
        sender: String,
        content: String,
        timestamp: Long,
        eventId: String,
        steps: MutableList<EventLogStore.Step>,
        maskedSender: String,
        maskedBody: String,
        channel: String,
        keyword: String
    ) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForwarder::SendLock")
        wakeLock.acquire(15_000L)

        val timeStr = dateFormat.get()?.format(Date(timestamp)) ?: ""
        val formattedText = buildString {
            append("📩 收到短信通知\n")
            append("━━━━━━━━━━━━━━\n")
            append("📱 发送方: ").append(sender).append("\n")
            append("⏰ 时间: ").append(timeStr).append("\n")
            append("💬 内容:\n").append(content)
        }

        AppLogger.d(TAG, "「${bot.name}」推送中（关键词：${keyword.ifBlank { "全部短信" }}）")
        QuantumBotClient.sendText(bot, formattedText) { success, message ->
            if (wakeLock.isHeld) wakeLock.release()
            val step = if (success) {
                StatsStore.record(this, keyword.ifBlank { "全部短信" })
                MessageDeduplicator.markForwarded(content)
                AppLogger.d(TAG, "「${bot.name}」推送成功")
                EventLogStore.Step(
                    "pass",
                    "「${bot.name}」推送成功 · HTTP 200 · 关键词「${keyword.ifBlank { "全部短信" }}」"
                )
            } else {
                AppLogger.e(TAG, "「${bot.name}」推送失败")
                EventLogStore.Step(
                    "fail",
                    "「${bot.name}」推送失败：${SecurityUtils.maskSensitiveContent(message).take(60)}"
                )
            }
            synchronized(steps) { steps.add(step) }
            updateStoredEvent(eventId, maskedSender, maskedBody, channel, bot, steps)
        }
    }

    private fun sendTestMessage(bot: QuantumBot, sender: String, content: String, timestamp: Long) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForwarder::TestLock")
        wakeLock.acquire(15_000L)
        val timeStr = dateFormat.get()?.format(Date(timestamp)) ?: ""
        val formattedText = "📩 收到短信通知\n━━━━━━━━━━━━━━\n📱 发送方: $sender\n⏰ 时间: $timeStr\n💬 内容:\n$content"
        QuantumBotClient.sendText(bot, formattedText) { success, _ ->
            if (wakeLock.isHeld) wakeLock.release()
            if (success) {
                AppLogger.d(TAG, "测试消息发送成功：${bot.name}")
            } else {
                AppLogger.e(TAG, "测试消息发送失败：${bot.name}")
            }
        }
    }

    private fun persistEvent(
        eventId: String,
        sender: String,
        body: String,
        channel: String,
        sent: Boolean,
        steps: List<EventLogStore.Step>,
        bots: List<String>
    ) {
        EventLogStore.save(
            this,
            EventLogStore.Event(
                id = eventId,
                at = System.currentTimeMillis(),
                sender = sender,
                body = body.take(80),
                channel = channel,
                sent = sent,
                steps = steps.toList(),
                bots = bots
            )
        )
    }

    private fun updateStoredEvent(
        eventId: String,
        sender: String,
        body: String,
        channel: String,
        bot: QuantumBot,
        steps: List<EventLogStore.Step>
    ) {
        val current = EventLogStore.all(this).firstOrNull { it.id == eventId } ?: return
        val botNames = (current.bots + bot.name).distinct()
        EventLogStore.save(
            this,
            current.copy(
                sender = sender,
                body = body.take(80),
                channel = channel,
                bots = botNames,
                steps = steps.toList()
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "短信转发服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持短信监听与转发服务后台运行"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("短信转发服务运行中")
            .setContentText("广播 + 通知 + 本机短信库三通道监听中")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        dynamicSmsReceiver?.let {
            unregisterReceiver(it)
            dynamicSmsReceiver = null
        }
        smsObserver?.let {
            contentResolver.unregisterContentObserver(it)
            smsObserver = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
