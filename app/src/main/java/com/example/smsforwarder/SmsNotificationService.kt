package com.example.smsforwarder

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.smsforwarder.util.AppLogger
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
        if (pkg == packageName) return
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

        val body = when {
            bigText.isNotEmpty() -> bigText
            textLines.isNotEmpty() -> textLines
            normalText.isNotEmpty() -> normalText
            else -> subText
        }
        if (body.isBlank()) return
        if (isSystemTip(body)) return

        val sender = title.ifBlank { pkg }
        // 短信通知的发送方通常在标题里，正文只有内容；把标题并入正文，关键词规则才能匹配到发送方
        val content = if (title.isNotBlank() && !title.equals(pkg, true) && !body.contains(title)) {
            "$title $body"
        } else {
            body
        }
        AppLogger.d(
            TAG,
            "通知捕获($pkg) - 标题: ${SecurityUtils.maskSensitiveContent(sender)}, 内容: ${SecurityUtils.maskSensitiveContent(content)}"
        )

        SmsForwardService.startForward(
            this,
            sender,
            content,
            sbn.postTime,
            fromNotificationTitle = true,
            channel = "通知监听"
        )
    }

    /** 过滤系统/应用自身提示类通知（"短信正在运行""服务运行中"等），避免污染事件日志 */
    private fun isSystemTip(body: String): Boolean {
        val markers = listOf("正在运行", "三通道监听中", "了解详情或停止应用", "点按即可")
        return markers.any { body.contains(it) }
    }

    private fun isSmsPackage(pkg: String): Boolean {
        if (SMS_PACKAGES.contains(pkg)) return true
        val lower = pkg.lowercase()
        return listOf("sms", "mms", "messaging", "短信", "信息").any { lower.contains(it) }
    }
}
