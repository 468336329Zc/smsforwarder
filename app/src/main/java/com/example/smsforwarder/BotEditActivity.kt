package com.example.smsforwarder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smsforwarder.databinding.ActivityBotEditBinding
import com.example.smsforwarder.databinding.ItemRuleGroupBinding
import com.example.smsforwarder.model.KeywordRule
import com.example.smsforwarder.model.QuantumBot
import com.example.smsforwarder.model.RuleMode
import com.example.smsforwarder.network.QuantumBotClient
import com.example.smsforwarder.util.BotManager
import com.google.android.material.chip.Chip

class BotEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBotEditBinding
    private lateinit var botManager: BotManager
    private var editingBot: QuantumBot? = null

    private var mode = RuleMode.GROUPS
    private val anyTerms = mutableListOf<String>()
    private val groups = mutableListOf<MutableList<String>>()

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
                binding.etSenderFilters.setText(bot.senderFilters)
                binding.switchBotEnabled.isChecked = bot.isEnabled
                binding.cbMentioned.isChecked = bot.isMentioned
                binding.etMentionMobiles.setText(bot.mentionedMobiles)
                loadRule(bot.getRule())
            }
        }
        if (editingBot == null) {
            groups.add(mutableListOf())
            loadRule(KeywordRule(mode = RuleMode.GROUPS))
        }

        binding.ruleModeGroup.check(
            if (mode == RuleMode.GROUPS) binding.btnModeGroups.id else binding.btnModeAny.id
        )
        binding.ruleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == binding.btnModeAny.id) RuleMode.ANY else RuleMode.GROUPS
            if (mode == RuleMode.GROUPS && groups.isEmpty()) {
                val seeded = mutableListOf<String>()
                seeded.addAll(anyTerms)
                groups.add(seeded)
            }
            if (mode == RuleMode.ANY && anyTerms.isEmpty()) {
                groups.flatten().forEach { term ->
                    if (!anyTerms.contains(term)) anyTerms.add(term)
                }
            }
            renderAll()
        }

        binding.btnAddGroup.setOnClickListener {
            groups.add(mutableListOf())
            renderAll()
        }

        binding.etExcludes.setOnEditorActionListener { _, _, _ -> false }
        binding.etExcludes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSummary()
        }

        binding.btnSaveBot.setOnClickListener { save() }
        renderAll()
    }

    private fun loadRule(rule: KeywordRule) {
        mode = rule.mode
        anyTerms.clear()
        groups.clear()
        if (rule.mode == RuleMode.ANY) {
            anyTerms.addAll(rule.terms)
        } else {
            rule.groups.forEach { groups.add(it.toMutableList()) }
            if (groups.isEmpty()) groups.add(mutableListOf())
        }
        binding.etExcludes.setText(rule.excludes.joinToString(", "))
    }

    private fun renderAll() {
        val groupMode = mode == RuleMode.GROUPS
        binding.llGroups.visibility = if (groupMode) View.VISIBLE else View.GONE
        binding.llAnyContainer.visibility = if (groupMode) View.GONE else View.VISIBLE
        binding.btnAddGroup.visibility = if (groupMode) View.VISIBLE else View.GONE
        binding.tvRuleHint.text = if (groupMode) {
            "组内所有词必须同时出现；多个条件组之间任一满足即转发。例如：\n[条件组1] 竹云科技 + 验证码   [条件组2] 4A"
        } else {
            "任一关键词出现即转发（适合宽松场景，容易误触发）"
        }

        if (groupMode) {
            renderGroups()
        } else {
            renderAny()
        }
        updateSummary()
    }

    private fun renderAny() {
        binding.llAnyContainer.removeAllViews()
        val groupView = inflateGroupBlock()
        groupView.tvGroupTitle.text = "任一关键词命中即转发"
        groupView.tvRemoveGroup.visibility = View.GONE
        bindTerms(groupView, anyTerms)
        binding.llAnyContainer.addView(groupView.root)
    }

    private fun renderGroups() {
        binding.llGroups.removeAllViews()
        groups.forEachIndexed { index, terms ->
            val groupView = inflateGroupBlock()
            groupView.tvGroupTitle.text = "条件组 ${index + 1}"
            groupView.tvRemoveGroup.visibility = if (groups.size > 1) View.VISIBLE else View.GONE
            groupView.tvRemoveGroup.setOnClickListener {
                groups.removeAt(index)
                renderAll()
            }
            bindTerms(groupView, terms)
            binding.llGroups.addView(groupView.root)
        }
    }

    private fun inflateGroupBlock(): ItemRuleGroupBinding =
        ItemRuleGroupBinding.inflate(LayoutInflater.from(this), null, false)

    private fun bindTerms(groupView: ItemRuleGroupBinding, terms: MutableList<String>) {
        groupView.chipGroupTerms.removeAllViews()
        terms.forEach { term ->
            val chip = Chip(this).apply {
                text = term
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener {
                    terms.remove(term)
                    renderAll()
                }
            }
            groupView.chipGroupTerms.addView(chip)
        }
        val addTerm = {
            val input = groupView.etNewTerm.text.toString().trim()
            if (input.isNotEmpty()) {
                if (!terms.contains(input)) terms.add(input)
                groupView.etNewTerm.setText("")
                renderAll()
            }
        }
        groupView.btnAddTerm.setOnClickListener { addTerm() }
        groupView.etNewTerm.setOnEditorActionListener { _, _, _ ->
            addTerm()
            true
        }
        groupView.etNewTerm.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSummary()
        }
    }

    private fun buildRule(): KeywordRule {
        val excludes = binding.etExcludes.text.toString()
            .split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (mode == RuleMode.ANY) {
            KeywordRule(mode = RuleMode.ANY, terms = anyTerms.toList(), excludes = excludes)
        } else {
            val cleanGroups = groups
                .map { group -> group.map { it.trim() }.filter { it.isNotEmpty() } }
                .filter { it.isNotEmpty() }
            KeywordRule(mode = RuleMode.GROUPS, groups = cleanGroups, excludes = excludes)
        }
    }

    private fun updateSummary() {
        val rule = buildRule()
        binding.tvRuleSummary.text = "当前规则：${rule.summary()}"
    }

    private fun save() {
        val name = binding.etBotName.text.toString().trim()
        val url = binding.etWebhookUrl.text.toString().trim()
        val senders = binding.etSenderFilters.text.toString().trim()
        val enabled = binding.switchBotEnabled.isChecked
        val isMentioned = binding.cbMentioned.isChecked
        val mobiles = binding.etMentionMobiles.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "请输入机器人名称", Toast.LENGTH_SHORT).show()
            return
        }
        if (!QuantumBotClient.isValidWebhookUrl(url)) {
            Toast.makeText(this, "Webhook 地址必须以 https:// 开头", Toast.LENGTH_SHORT).show()
            return
        }

        val rule = buildRule()
        if (rule.summary() != "全部短信" && mode == RuleMode.GROUPS && rule.groups.isEmpty()) {
            Toast.makeText(this, "请至少给一个条件组添加关键词", Toast.LENGTH_SHORT).show()
            return
        }

        val legacyKeywords = if (rule.mode == RuleMode.ANY) {
            rule.terms.joinToString(", ")
        } else {
            rule.groups.flatten().distinct().joinToString(", ")
        }
        val ruleJson = rule.toJson()

        val current = editingBot
        if (current == null) {
            botManager.addBot(
                QuantumBot(
                    name = name,
                    webhookUrl = url,
                    keywords = legacyKeywords,
                    senderFilters = senders,
                    isEnabled = enabled,
                    isMentioned = isMentioned,
                    mentionType = if (mobiles.isBlank()) 1 else 2,
                    mentionedMobiles = mobiles,
                    ruleJson = ruleJson
                )
            )
        } else {
            botManager.updateBot(
                current.copy(
                    name = name,
                    webhookUrl = url,
                    keywords = legacyKeywords,
                    senderFilters = senders,
                    isEnabled = enabled,
                    isMentioned = isMentioned,
                    mentionType = if (mobiles.isBlank()) 1 else 2,
                    mentionedMobiles = mobiles,
                    ruleJson = ruleJson
                )
            )
        }
        finish()
    }
}
