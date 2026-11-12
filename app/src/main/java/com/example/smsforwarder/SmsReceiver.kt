package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.MessageDeduplicator
import com.example.smsforwarder.util.SecurityUtils

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
        ) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val grouped = LinkedHashMap<String, Pair<StringBuilder, Long>>()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: "未知号码"
            val body = sms.displayMessageBody ?: sms.messageBody ?: ""
            val ts = sms.timestampMillis
            val entry = grouped[sender]
            if (entry == null) {
                grouped[sender] = StringBuilder(body) to ts
            } else {
                entry.first.append(body)
            }
        }

        for ((sender, pair) in grouped) {
            val fullContent = pair.first.toString()
            val timestamp = pair.second
            if (fullContent.isBlank()) continue
            AppLogger.d(
                TAG,
                "广播捕获短信 - 发送者: ${SecurityUtils.maskSensitiveContent(sender)}, 内容: ${SecurityUtils.maskSensitiveContent(fullContent)}"
            )
            if (!MessageDeduplicator.isNewMessage(sender, fullContent)) continue
            SmsForwardService.startForward(context, sender, fullContent, timestamp, fromNotificationTitle = false)
        }
    }
}
