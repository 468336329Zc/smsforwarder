package com.example.smsforwarder.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.smsforwarder.SmsNotificationService

/** 通知使用权（NotificationListener）检测：解析系统里已授权的 listener 组件。 */
object NotificationAccess {

    fun isEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val targetClass = SmsNotificationService::class.java.name
        return flat.split(":").any { entry ->
            val component = ComponentName.unflattenFromString(entry) ?: return@any false
            component.packageName == context.packageName && component.className == targetClass
        }
    }
}
