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
import com.example.smsforwarder.network.QuantumBotClient
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.BotManager
import com.example.smsforwarder.util.SecurityUtils
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

        fun startKeepAlive(context: Context) {
            val intent = Intent(context, SmsForwardService::class.java).apply {
                putExtra(EXTRA_KEEP_ALIVE, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startForward(
            context: Context,
            sender: String,
            content: String,
            timestamp: Long,
            isTest: Boolean = false,
            botId: String? = null,
            fromNotificationTitle: Boolean = false
        ) {
            val intent = Intent(context, SmsForwardService::class.java).apply {
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_TIMESTAMP, timestamp)
                putExtra(EXTRA_IS_TEST, isTest)
                if (!botId.isNullOrBlank()) putExtra(EXTRA_BOT_ID, botId)
                putExtra(EXTRA_FROM_NOTIFICATION, fromNotificationTitle)
            }
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
        AppLogger.d(TAG, "前台服务已启动（动态广播 + 短信库监听 + 4秒本地兜底轮询）")
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
            forwardSms(sender, body, timestamp, fromNotificationTitle = false)
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

        if (content.isBlank()) return START_STICKY

        AppLogger.d(
            TAG,
            "收到转发请求（${if (isTest) "测试" else "真实短信"}）- 发送方: ${SecurityUtils.maskSensitiveContent(sender)}"
        )

        if (isTest && !botId.isNullOrBlank()) {
            val bot = botManager.getBotById(botId)
            if (bot != null && bot.isEnabled) {
                sendMessage(bot, sender, content, timestamp)
            } else {
                AppLogger.e(TAG, "测试模式：指定的机器人不存在或未启用")
            }
        } else {
            forwardSms(sender, content, timestamp, fromNotif)
        }

        return START_STICKY
    }

    private fun forwardSms(sender: String, content: String, timestamp: Long, fromNotificationTitle: Boolean) {
        val matchedBots = botManager.getMatchingBots(sender, content, fromNotificationTitle)
        if (matchedBots.isEmpty()) {
            AppLogger.d(TAG, "未匹配任何启用机器人，跳过转发")
            return
        }
        AppLogger.d(TAG, "匹配 ${matchedBots.size} 个机器人，开始转发")
        for (bot in matchedBots) {
            sendMessage(bot, sender, content, timestamp)
        }
    }

    private fun sendMessage(bot: com.example.smsforwarder.model.QuantumBot, sender: String, content: String, timestamp: Long) {
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

        QuantumBotClient.sendText(bot, formattedText) { _, _ ->
            if (wakeLock.isHeld) wakeLock.release()
        }
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
