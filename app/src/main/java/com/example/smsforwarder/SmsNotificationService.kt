package com.example.smsforwarder

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.MessageDeduplicator
import com.example.smsforwarder.util.SecurityUtils

class SmsNotificationService : NotificationListenerService() {
    companion object {
        private const val TAG = "SmsNotificationService"
        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.android.messaging",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.miui.sms",
            "com.miui.message",
            "com.xiaomi.sms",
            "com.miui.securitycenter",
            "com.huawei.message",
            "com.hihonor.mms",
            "com.oppo.message",
            "com.coloros.mms",
            "com.oneplus.mms",
            "com.vivo.message",
            "com.meizu.flyme.mms"
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "通知监听服务已创建")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.d(TAG, "通知监听器已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLogger.d(TAG, "通知监听器已断开，请求重连")
        requestRebind(ComponentName(this, SmsNotificationService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!isSmsPackage(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")
            .orEmpty()

        val content = when {
            bigText.isNotEmpty() -> bigText
            textLines.isNotEmpty() -> textLines
            normalText.isNotEmpty() -> normalText
            else -> subText
        }
        if (content.isBlank()) return

        val sender = title.ifBlank { pkg }
        AppLogger.d(
            TAG,
            "通知捕获($pkg) - 标题: ${SecurityUtils.maskSensitiveContent(sender)}, 内容: ${SecurityUtils.maskSensitiveContent(content)}"
        )

        if (!MessageDeduplicator.isNewMessage(sender, content)) return

        SmsForwardService.startForward(
            this,
            sender,
            content,
            sbn.postTime,
            fromNotificationTitle = true
        )
    }

    private fun isSmsPackage(pkg: String): Boolean {
        if (SMS_PACKAGES.contains(pkg)) return true
        val lower = pkg.lowercase()
        return listOf("sms", "mms", "messaging", "短信", "信息").any { lower.contains(it) }
    }
}
