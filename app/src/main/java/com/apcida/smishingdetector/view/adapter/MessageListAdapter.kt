package com.apcida.smishingdetector.view.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.databinding.ItemMessageBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageListAdapter(
    private val onMessageClick: (Message) -> Unit,
    private val isUnread: (Message) -> Boolean
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
                val context = root.context
                val unread = isUnread(message)
                val pending = message.gemmaInvoked && message.gemmaClassification == null

                // Sender — show hashed sender as placeholder
                // since raw sender is not stored
                textSender.text = "Unknown Sender"

                // Message preview — first 100 characters
                textPreview.text = message.content.take(100)

                // Time
                textTime.text = formatTime(message.receivedAt)

                val indicatorColor = when {
                    pending -> ContextCompat.getColor(context, R.color.inbox_pending)
                    message.riskLevel == Constants.RISK_SCAM ->
                        ContextCompat.getColor(context, R.color.inbox_scam)
                    message.riskLevel == Constants.RISK_SUSPICIOUS ->
                        ContextCompat.getColor(context, R.color.inbox_suspicious)
                    else -> ContextCompat.getColor(context, R.color.inbox_safe)
                }
                avatarCircle.setCardBackgroundColor(indicatorColor)
                unreadDot.visibility = if (unread) View.VISIBLE else View.GONE
                cardMessage.setCardBackgroundColor(ContextCompat.getColor(
                    context, if (unread) R.color.inbox_unread_bg else R.color.white
                ))
                cardMessage.strokeColor = ContextCompat.getColor(
                    context, if (unread) R.color.inbox_unread_border else R.color.inbox_card_border
                )

                textRiskBadge.visibility = View.VISIBLE
                textRiskBadge.text = if (pending) "ANALYZING" else message.riskLevel
                val badgeBackground = when {
                    pending -> R.drawable.bg_inbox_badge_pending
                    message.riskLevel == Constants.RISK_SCAM -> R.drawable.bg_inbox_badge_scam
                    message.riskLevel == Constants.RISK_SUSPICIOUS -> R.drawable.bg_inbox_badge_suspicious
                    else -> R.drawable.bg_inbox_badge_safe
                }
                textRiskBadge.setBackgroundResource(badgeBackground)
                textRiskBadge.setTextColor(indicatorColor)

                // Click listener
                cardMessage.setOnClickListener {
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
