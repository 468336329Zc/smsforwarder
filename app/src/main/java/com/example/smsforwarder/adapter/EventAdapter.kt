package com.example.smsforwarder.adapter

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.smsforwarder.R
import com.example.smsforwarder.databinding.ItemEventBinding
import com.example.smsforwarder.util.EventLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_EVENT = 0
        private const val TYPE_FOOTER = 1
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }

    private var events: List<EventLogStore.Event> = emptyList()
    private var systemLogs: String = ""

    fun submit(items: List<EventLogStore.Event>, logs: String) {
        events = items
        systemLogs = logs
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = events.size + if (systemLogs.isBlank()) 0 else 1

    override fun getItemViewType(position: Int): Int =
        if (position < events.size) TYPE_EVENT else TYPE_FOOTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_EVENT) {
            EventVH(ItemEventBinding.inflate(inflater, parent, false))
        } else {
            FooterVH(TextView(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is EventVH && position < events.size) {
            holder.bind(events[position])
        } else if (holder is FooterVH) {
            holder.bind(systemLogs)
        }
    }

    inner class EventVH(private val binding: ItemEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(event: EventLogStore.Event) {
            val context = binding.root.context
            val color = ContextCompat.getColor(
                context,
                if (event.sent) R.color.green_ok else R.color.amber_text
            )
            binding.colorBar.setBackgroundColor(color)
            binding.tvBadge.text = if (event.sent) "已转发" else "未转发"
            binding.tvBadge.setTextColor(color)
            binding.tvBadge.setBackgroundResource(
                if (event.sent) R.drawable.bg_badge_sent else R.drawable.bg_badge_skip
            )
            binding.tvEventTime.text = timeFormat.format(Date(event.at))
            binding.tvMeta.text = "${event.channel}通道 · 来自 ${event.sender} · ${event.body}"

            val builder = SpannableStringBuilder()
            event.steps.forEachIndexed { index, step ->
                if (index > 0) builder.append("\n")
                val start = builder.length
                builder.append(step.text)
                val stepColor = when (step.level) {
                    "pass" -> Color.parseColor("#2E7D32")
                    "fail" -> Color.parseColor("#B26A00")
                    else -> ContextCompat.getColor(context, R.color.text_secondary)
                }
                builder.setSpan(
                    ForegroundColorSpan(stepColor),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            binding.tvSteps.text = builder
        }
    }

    inner class FooterVH(textView: TextView) : RecyclerView.ViewHolder(textView) {
        private val view: TextView = textView

        fun bind(logs: String) {
            view.setTextColor(ContextCompat.getColor(view.context, R.color.text_secondary))
            view.textSize = 11f
            view.setPadding(4, 24, 4, 24)
            view.text = "系统日志\n$logs"
        }
    }
}
