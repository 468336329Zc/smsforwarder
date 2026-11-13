package com.example.smsforwarder.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.smsforwarder.databinding.ItemBotBinding
import com.example.smsforwarder.model.QuantumBot

class BotAdapter(
    private val onEdit: (QuantumBot) -> Unit,
    private val onTest: (QuantumBot) -> Unit,
    private val onDelete: (QuantumBot) -> Unit,
    private val onToggle: (QuantumBot, Boolean) -> Unit
) : ListAdapter<QuantumBot, BotAdapter.BotViewHolder>(DiffCallback) {

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_TEST = 2
        private const val MENU_DELETE = 3
    }

    object DiffCallback : DiffUtil.ItemCallback<QuantumBot>() {
        override fun areItemsTheSame(oldItem: QuantumBot, newItem: QuantumBot) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: QuantumBot, newItem: QuantumBot) = oldItem == newItem
    }

    inner class BotViewHolder(private val binding: ItemBotBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(bot: QuantumBot) {
            binding.tvBotName.text = bot.name
            binding.tvBotKeywords.text = "关键词: ${bot.getRule().summary()}"
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = bot.isEnabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(bot, checked) }
            binding.root.setOnClickListener { onEdit(bot) }
            binding.btnTest.setOnClickListener { onTest(bot) }
            binding.btnMore.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menu.add(0, MENU_EDIT, 0, "编辑")
                    menu.add(0, MENU_TEST, 1, "测试发送")
                    menu.add(0, MENU_DELETE, 2, "删除")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            MENU_EDIT -> { onEdit(bot); true }
                            MENU_TEST -> { onTest(bot); true }
                            MENU_DELETE -> { onDelete(bot); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BotViewHolder {
        val binding = ItemBotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BotViewHolder, position: Int) = holder.bind(getItem(position))
}
