package com.apcida.smishingdetector.view.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apcida.smishingdetector.databinding.ItemMessageBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.model.data.ProcessingState
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.util.DateUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageListAdapter(
    private val onMessageClick: (Message) -> Unit
) : ListAdapter<Message, MessageListAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            binding.apply {
                val isAnalyzing = message.processingState in setOf(
                    ProcessingState.PENDING.name,
                    ProcessingState.RULE_ANALYZED.name,
                    ProcessingState.AI_QUEUED.name,
                    ProcessingState.AI_ANALYZING.name
                )

                // Sender — show hashed sender as placeholder
                // since raw sender is not stored
                textSender.text = "Unknown Sender"

                // Message preview — first 100 characters
                textPreview.text = message.content.take(100)

                // Time
                textTime.text = formatTime(message.receivedAt)

                // Risk indicator dot color
                val indicatorColor = when {
                    isAnalyzing -> Color.parseColor("#1565C0")
                    message.finalClassification == Constants.RISK_SCAM -> Color.parseColor("#B71C1C")
                    message.finalClassification == Constants.RISK_SUSPICIOUS -> Color.parseColor("#E65100")
                    else -> Color.parseColor("#2E7D32")
                }
                riskIndicator.setBackgroundColor(indicatorColor)

                // Risk badge
                if (isAnalyzing || message.isFlagged) {
                    textRiskBadge.visibility = View.VISIBLE
                    textRiskBadge.text = if (isAnalyzing) "ANALYZING" else message.finalClassification

                    val badgeColor = when {
                        isAnalyzing -> Color.parseColor("#1565C0")
                        message.finalClassification == Constants.RISK_SCAM -> Color.parseColor("#B71C1C")
                        message.finalClassification == Constants.RISK_SUSPICIOUS -> Color.parseColor("#E65100")
                        else -> Color.parseColor("#2E7D32")
                    }
                    textRiskBadge.setBackgroundColor(badgeColor)
                } else {
                    textRiskBadge.visibility = View.GONE
                }

                // Click listener
                root.setOnClickListener {
                    onMessageClick(message)
                }
            }
        }

        private fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.messageId == newItem.messageId
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
}
