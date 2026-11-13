package com.example.smsforwarder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smsforwarder.adapter.BotAdapter
import com.example.smsforwarder.databinding.ActivityBotListBinding
import com.example.smsforwarder.util.BotManager
import com.example.smsforwarder.util.ConfigBackup
import com.example.smsforwarder.util.StatsStore

class BotListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBotListBinding
    private lateinit var botManager: BotManager
    private lateinit var adapter: BotAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBotListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        botManager = BotManager(this)
        adapter = BotAdapter(
            onEdit = { bot ->
                startActivity(Intent(this, BotEditActivity::class.java).putExtra("bot_id", bot.id))
            },
            onTest = { bot ->
                SmsForwardService.startForward(
                    this,
                    "10690000",
                    "【测试系统】这是发送给「${bot.name}」的测试消息，验证码：123456，5分钟内有效。",
                    System.currentTimeMillis(),
                    isTest = true,
                    botId = bot.id
                )
                Toast.makeText(this, "已向「${bot.name}」发送测试消息", Toast.LENGTH_SHORT).show()
            },
            onDelete = { bot ->
                AlertDialog.Builder(this)
                    .setTitle("删除机器人")
                    .setMessage("确定删除「${bot.name}」？删除后该机器人的配置与关键词将一并移除。")
                    .setPositiveButton("删除") { _, _ ->
                        botManager.deleteBot(bot.id)
                        refreshList()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onToggle = { bot, checked ->
                botManager.toggleBot(bot.id, checked)
            }
        )

        binding.recyclerBots.layoutManager = LinearLayoutManager(this)
        binding.recyclerBots.adapter = adapter

        binding.fabAddBot.setOnClickListener {
            startActivity(Intent(this, BotEditActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bot_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_config -> {
                exportConfig()
                true
            }
            R.id.action_import_config -> {
                confirmImport()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportConfig() {
        val json = ConfigBackup.export(botManager.getAllBots(), StatsStore.all(this))
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SMSForwarder 配置备份", json))
        Toast.makeText(
            this,
            "已复制到剪贴板（${botManager.getAllBots().size} 个机器人），建议粘贴到笔记或对话框保存",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun confirmImport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val payload = ConfigBackup.parse(text)
        if (payload == null || payload.bots.isEmpty()) {
            Toast.makeText(this, "剪贴板里没有可识别的配置备份", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setMessage("将导入 ${payload.bots.size} 个机器人配置，并覆盖当前本机配置，确定继续？")
            .setPositiveButton("导入") { _, _ ->
                botManager.replaceAll(payload.bots)
                if (payload.stats.isNotEmpty()) {
                    StatsStore.replaceAll(this, payload.stats)
                }
                refreshList()
                Toast.makeText(this, "导入完成", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val bots = botManager.getAllBots()
        adapter.submitList(bots)
        binding.tvEmptyBots.visibility = if (bots.isEmpty()) View.VISIBLE else View.GONE
    }
}
