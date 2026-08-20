package com.apcida.smishingdetector.view.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.apcida.smishingdetector.databinding.ItemSafetyTipBinding
import com.apcida.smishingdetector.model.entity.SafetyTip

class SafetyTipAdapter : ListAdapter<SafetyTip, SafetyTipAdapter.TipViewHolder>(
    TipDiffCallback()
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TipViewHolder {
        val binding = ItemSafetyTipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TipViewHolder(
        private val binding: ItemSafetyTipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tip: SafetyTip) {
            binding.apply {
                textTipTitle.text = tip.title
                textTipContent.text = tip.content
                textTipCategory.text = tip.category ?: "GENERAL"
            }
        }
    }

    class TipDiffCallback : DiffUtil.ItemCallback<SafetyTip>() {
        override fun areItemsTheSame(oldItem: SafetyTip, newItem: SafetyTip): Boolean {
            return oldItem.tipId == newItem.tipId
        }

        override fun areContentsTheSame(oldItem: SafetyTip, newItem: SafetyTip): Boolean {
            return oldItem == newItem
        }
    }
}