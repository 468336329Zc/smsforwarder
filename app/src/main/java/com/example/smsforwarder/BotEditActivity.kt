package com.example.smsforwarder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        commitVisibleInputs()
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
        groupView.etNewTerm.tag = terms
        val addTerm = {
            addTermsFromText(groupView, terms)
            renderAll()
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

    /** 把输入框里还没点「添加」的词提交进对应条件组，避免切换模式 / 新增分组 / 保存时丢失 */
    private fun commitVisibleInputs() {
        listOf(binding.llGroups, binding.llAnyContainer).forEach { container ->
            for (index in 0 until container.childCount) {
                val root = container.getChildAt(index)
                val editText = root.findViewById<EditText>(R.id.etNewTerm) ?: continue
                val terms = editText.tag as? MutableList<String> ?: continue
                addTermsFromText(editText, terms)
            }
        }
    }

    /** 支持逗号 / 顿号 / 换行一次录入多个词 */
    private fun addTermsFromText(editText: EditText, terms: MutableList<String>) {
        val inputs = editText.text.toString()
            .split(",", "，", "、", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        inputs.forEach { term ->
            if (!terms.contains(term)) terms.add(term)
        }
        editText.setText("")
    }

    private fun addTermsFromText(groupView: ItemRuleGroupBinding, terms: MutableList<String>) {
        addTermsFromText(groupView.etNewTerm, terms)
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

        commitVisibleInputs()
        val rule = buildRule()
        if (mode == RuleMode.GROUPS && rule.groups.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没有关键词")
                .setMessage("当前条件组里没有任何关键词，保存后该机器人会转发全部短信。确定保存？")
                .setPositiveButton("仍然保存") { _, _ -> persist(rule, name, url, senders, enabled, isMentioned, mobiles) }
                .setNegativeButton("返回编辑", null)
                .show()
            return
        }
        persist(rule, name, url, senders, enabled, isMentioned, mobiles)
    }

    private fun persist(
        rule: KeywordRule,
        name: String,
        url: String,
        senders: String,
        enabled: Boolean,
        isMentioned: Boolean,
        mobiles: String
    ) {

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
