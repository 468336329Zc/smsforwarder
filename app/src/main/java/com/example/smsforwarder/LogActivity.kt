package com.example.smsforwarder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smsforwarder.databinding.ActivityLogBinding
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.BotManager

class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        refreshSelfCheck()
        refreshLogs()
        binding.btnClearLogs.setOnClickListener {
            AppLogger.clear()
            refreshLogs()
        }
    }

    private fun refreshSelfCheck() {
        val smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val notifOk = flat.contains(ComponentName(this, SmsNotificationService::class.java).flattenToString())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val enabledBots = BotManager(this).getEnabledBots().size

        binding.tvSelfCheck.text = buildString {
            append("== 自检信息 ==\n\n")
            append("短信权限: ").append(if (smsOk) "已授权 ✓" else "未授权 ✗").append("\n")
            append("通知监听: ").append(if (notifOk) "已开启 ✓" else "未开启 ✗").append("\n")
            append("电池优化白名单: ").append(if (batteryOk) "已加入 ✓" else "未加入 ✗").append("\n")
            append("启用机器人数: ").append(enabledBots)
        }
    }

    private fun refreshLogs() {
        val lines = AppLogger.readAll()
        binding.tvLogs.text = if (lines.isEmpty()) "暂无运行日志" else lines.reversed().joinToString("\n\n")
    }
}
