package com.example.smsforwarder

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smsforwarder.databinding.ActivityBotEditBinding
import com.example.smsforwarder.model.QuantumBot
import com.example.smsforwarder.network.QuantumBotClient
import com.example.smsforwarder.util.BotManager

class BotEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBotEditBinding
    private lateinit var botManager: BotManager
    private var editingBot: QuantumBot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBotEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        botManager = BotManager(this)
        val botId = intent.getStringExtra("bot_id")
        if (!botId.isNullOrBlank()) {
            title = "编辑机器人"
            editingBot = botManager.getBotById(botId)
            editingBot?.let { bot ->
                binding.etBotName.setText(bot.name)
                binding.etWebhookUrl.setText(bot.webhookUrl)
                binding.etKeywords.setText(bot.keywords)
                binding.etSenderFilters.setText(bot.senderFilters)
                binding.switchBotEnabled.isChecked = bot.isEnabled
                binding.cbMentioned.isChecked = bot.isMentioned
                binding.etMentionMobiles.setText(bot.mentionedMobiles)
            }
        }

        binding.btnSaveBot.setOnClickListener {
            val name = binding.etBotName.text.toString().trim()
            val url = binding.etWebhookUrl.text.toString().trim()
            val keywords = binding.etKeywords.text.toString().trim()
            val senders = binding.etSenderFilters.text.toString().trim()
            val enabled = binding.switchBotEnabled.isChecked
            val isMentioned = binding.cbMentioned.isChecked
            val mobiles = binding.etMentionMobiles.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(this, "请输入机器人名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!QuantumBotClient.isValidWebhookUrl(url)) {
                Toast.makeText(this, "Webhook 地址必须以 https:// 开头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val current = editingBot
            if (current == null) {
                botManager.addBot(
                    QuantumBot(
                        name = name,
                        webhookUrl = url,
                        keywords = keywords,
                        senderFilters = senders,
                        isEnabled = enabled,
                        isMentioned = isMentioned,
                        mentionType = if (mobiles.isBlank()) 1 else 2,
                        mentionedMobiles = mobiles
                    )
                )
            } else {
                botManager.updateBot(
                    current.copy(
                        name = name,
                        webhookUrl = url,
                        keywords = keywords,
                        senderFilters = senders,
                        isEnabled = enabled,
                        isMentioned = isMentioned,
                        mentionType = if (mobiles.isBlank()) 1 else 2,
                        mentionedMobiles = mobiles
                    )
                )
            }
            finish()
        }
    }
}
