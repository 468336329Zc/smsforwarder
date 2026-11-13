package com.example.smsforwarder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smsforwarder.adapter.EventAdapter
import com.example.smsforwarder.databinding.ActivityLogBinding
import com.example.smsforwarder.util.AppLogger
import com.example.smsforwarder.util.BotManager
import com.example.smsforwarder.util.EventLogStore
import com.example.smsforwarder.util.NotificationAccess

class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: EventAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = EventAdapter()
        binding.recyclerEvents.layoutManager = LinearLayoutManager(this)
        binding.recyclerEvents.adapter = adapter

        binding.btnClearLogs.setOnClickListener {
            EventLogStore.clear(this)
            AppLogger.clear()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        refreshSelfCheck()
        val events = EventLogStore.all(this)
        val sysLogs = AppLogger.readAll().takeLast(12).joinToString("\n")
        adapter.submit(events, sysLogs)
        binding.tvEmptyEvents.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshSelfCheck() {
        val smsOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val notifOk = NotificationAccess.isEnabled(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val enabledBots = BotManager(this).getEnabledBots().size

        binding.tvSelfCheck.text = buildString {
            append("短信权限 ").append(if (smsOk) "✓" else "✗")
            append("   通知监听 ").append(if (notifOk) "✓" else "✗")
            append("   电池白名单 ").append(if (batteryOk) "✓" else "✗")
            append("\n已启用 ").append(enabledBots).append(" 个机器人 · 事件 ")
            append(EventLogStore.all(this@LogActivity).size).append(" 条")
        }
    }
}
