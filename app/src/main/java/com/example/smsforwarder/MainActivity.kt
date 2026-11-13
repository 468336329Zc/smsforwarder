package com.example.smsforwarder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.smsforwarder.databinding.ActivityMainBinding
import com.example.smsforwarder.databinding.ItemKeywordRankBinding
import com.example.smsforwarder.util.BotManager
import com.example.smsforwarder.util.NotificationAccess
import com.example.smsforwarder.util.StatsStore

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var botManager: BotManager
    private var statMode = StatsStore.Mode.DAY

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

        binding.statModeGroup.check(binding.btnModeDay.id)
        binding.statModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            statMode = when (checkedId) {
                binding.btnModeWeek.id -> StatsStore.Mode.WEEK
                binding.btnModeMonth.id -> StatsStore.Mode.MONTH
                else -> StatsStore.Mode.DAY
            }
            refreshStats()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshStats()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manage_bots -> {
                startActivity(Intent(this, BotListActivity::class.java))
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(
                buildString {
                    append("版本：").append(BuildConfig.VERSION_NAME)
                    append("（versionCode ").append(BuildConfig.VERSION_CODE).append("）\n\n")
                    append("本机短信监听 → 量子密信群机器人转发。三通道冗余监听（系统广播 / 通知栏 / 短信库），")
                    append("关键词支持条件组（组内且、组间或）与排除词，首页提供转发趋势与关键词 Top5 统计。")
                    append("全部配置、转发记录与日志只保存在本机，无远程服务器。\n\n")
                    append("包名：").append(packageName)
                }
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun refreshStatus() {
        val smsOk = isSmsPermGranted()
        val notifOk = isNotificationListenerEnabled()
        val batteryOk = isIgnoringBatteryOptimizations()
        val enabledBots = botManager.getEnabledBots()
        val today = StatsStore.todayCount(this)

        binding.tvServiceSubtitle.text =
            "已启用 ${enabledBots.size} 个机器人 · 今日已转发 $today 条"
        binding.tvPermChips.text = buildChips(smsOk, notifOk, batteryOk)
    }

    private fun buildChips(smsOk: Boolean, notifOk: Boolean, batteryOk: Boolean): Spanned {
        val chips = listOf(
            "短信权限" to smsOk,
            "通知监听" to notifOk,
            "电池白名单" to batteryOk
        )
        val builder = SpannableString(
            chips.joinToString("   ") { (name, ok) -> if (ok) "$name ✓" else "$name ✗" }
        )
        var cursor = 0
        chips.forEach { (name, ok) ->
            val text = if (ok) "$name ✓" else "$name ✗"
            val color = if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#B26A00")
            builder.setSpan(
                ForegroundColorSpan(color),
                cursor,
                cursor + text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            cursor += text.length + 3
        }
        return builder
    }

    private fun refreshStats() {
        val summary = StatsStore.summary(this, statMode)
        binding.tvStatTotal.text = summary.total.toString()

        if (summary.previousTotal > 0) {
            val delta = ((summary.total - summary.previousTotal) * 100f / summary.previousTotal).toInt()
            binding.tvStatDelta.text = if (delta >= 0) "较上期 +$delta%" else "较上期 $delta%"
            binding.tvStatDelta.setTextColor(
                ContextCompat.getColor(this, if (delta >= 0) R.color.green_ok else R.color.red_error)
            )
        } else {
            binding.tvStatDelta.text = "无上期数据"
            binding.tvStatDelta.setTextColor(ContextCompat.getColor(this, R.color.text_helper))
        }

        binding.chartTrend.setData(summary.labels, summary.values)
        renderTop5(summary.top)

        binding.tvStatNote.text = when (statMode) {
            StatsStore.Mode.DAY -> "近 14 天 · 数据来自本机转发记录"
            StatsStore.Mode.WEEK -> "近 8 周（按周一开始） · 数据来自本机转发记录"
            StatsStore.Mode.MONTH -> "近 6 个月 · 数据来自本机转发记录"
        }
    }

    private fun renderTop5(top: List<Pair<String, Int>>) {
        binding.llTop5.removeAllViews()
        if (top.isEmpty()) {
            val empty = android.widget.TextView(this).apply {
                text = "本周期还没有转发记录"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_helper))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }
            binding.llTop5.addView(empty)
            return
        }
        val max = top.first().second.coerceAtLeast(1)
        val inflater = LayoutInflater.from(this)
        top.forEach { (keyword, count) ->
            val row = ItemKeywordRankBinding.inflate(inflater, binding.llTop5, false)
            row.tvKeywordName.text = keyword
            row.pbKeyword.progress = ((count * 100) / max).coerceAtLeast(4)
            row.tvKeywordCount.text = count.toString()
            binding.llTop5.addView(row.root)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                        System.currentTimeMillis(),
                        channel = "测试"
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

    private fun isNotificationListenerEnabled(): Boolean = NotificationAccess.isEnabled(this)

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
