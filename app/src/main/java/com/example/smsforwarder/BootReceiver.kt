package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smsforwarder.util.AppLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AppLogger.init(context)
            AppLogger.d("BootReceiver", "收到系统事件($action)，拉起前台服务")
            SmsForwardService.startKeepAlive(context)
        }
    }
}
