package com.example.smsforwarder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.smsforwarder.databinding.ActivityMainBinding
import com.example.smsforwarder.util.BotManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var botManager: BotManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        botManager = BotManager(this)
        requestRequiredPermissions()
        SmsForwardService.startKeepAlive(this)

        binding.cardTestForward.setOnClickListener { showTestDialog() }
        binding.cardManageBots.setOnClickListener {
            startActivity(Intent(this, BotListActivity::class.java))
        }
        binding.cardPermissions.setOnClickListener { showPermissionsDialog() }
        binding.cardLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val smsOk = isSmsPermGranted()
        val notifOk = isNotificationListenerEnabled()
        val allBots = botManager.getAllBots()
        val enabledBots = allBots.filter { it.isEnabled }

        binding.tvServiceSubtitle.text = "运行正常 · 已启用 ${enabledBots.size} 个机器人"
        binding.tvPermDetails.text = buildString {
            append("短信权限: ").append(if (smsOk) "已授权 ✓" else "未授权 ✗").append("\n")
            append("通知监听: ").append(if (notifOk) "已开启 ✓" else "未开启 ✗").append("\n")
            append("机器人总数: ").append(allBots.size).append("\n")
            append("已启用: ").append(enabledBots.size)
        }
        binding.tvEnabledBots.text = if (enabledBots.isEmpty()) {
            "• 暂无已启用的机器人"
        } else {
            enabledBots.joinToString("\n") { "• ${it.name}" }
        }
    }

    private fun showTestDialog() {
        val enabledCount = botManager.getEnabledBots().size
        AlertDialog.Builder(this)
            .setTitle("选择测试方式")
            .setMessage("您可以：\n\n1. 在「机器人管理」中点击每个机器人的发送按钮单独测试\n\n2. 测试所有启用的机器人（共 $enabledCount 个）")
            .setPositiveButton("测试所有") { _, _ ->
                if (enabledCount == 0) {
                    Toast.makeText(this, "请先启用至少一个机器人", Toast.LENGTH_SHORT).show()
                } else {
                    SmsForwardService.startForward(
                        this,
                        "10690000",
                        "【测试平台】您的验证码是 372814，3分钟内有效。",
                        System.currentTimeMillis()
                    )
                    Toast.makeText(this, "已向匹配的启用机器人发送测试消息", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("去单独测试") { _, _ ->
                startActivity(Intent(this, BotListActivity::class.java))
            }
            .show()
    }

    private fun showPermissionsDialog() {
        val notifStatus = if (isNotificationListenerEnabled()) "✓" else "(未开启)"
        val batteryStatus = if (isIgnoringBatteryOptimizations()) "✓" else "(建议开启)"
        val items = arrayOf(
            "检查短信权限",
            "开启通知监听权限 $notifStatus",
            "重启通知监听服务",
            "电池优化设置 $batteryStatus",
            "应用权限详情（小米请开「通知类短信」与「省电无限制」）",
            "跳转手机自启动管理（防后台被杀）",
            "诊断日志"
        )
        AlertDialog.Builder(this)
            .setTitle("权限和设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> requestRequiredPermissions()
                    1 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    2 -> {
                        NotificationListenerService.requestRebind(
                            ComponentName(this, SmsNotificationService::class.java)
                        )
                        Toast.makeText(this, "通知监听服务重启请求已发送", Toast.LENGTH_SHORT).show()
                    }
                    3 -> requestIgnoreBatteryOptimizations()
                    4 -> openAppDetailsSettings()
                    5 -> openOemAutoStartSettings()
                    6 -> startActivity(Intent(this, LogActivity::class.java))
                }
            }
            .show()
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun openOemAutoStartSettings() {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        )
        for (it in candidates) {
            try {
                startActivity(it)
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings()
    }

    private fun isSmsPermGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            Toast.makeText(this, "短信权限已全部授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, SmsNotificationService::class.java).flattenToString()
        return flat.contains(cn)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (!isIgnoringBatteryOptimizations()) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } else {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
        }
    }
}
