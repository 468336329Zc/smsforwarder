package com.example.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smsforwarder.adapter.BotAdapter
import com.example.smsforwarder.databinding.ActivityBotListBinding
import com.example.smsforwarder.util.BotManager

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
                botManager.deleteBot(bot.id)
                refreshList()
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

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(botManager.getAllBots())
    }
}
