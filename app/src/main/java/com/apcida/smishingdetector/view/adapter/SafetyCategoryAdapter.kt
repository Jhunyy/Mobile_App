package com.apcida.smishingdetector.view.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.apcida.smishingdetector.databinding.ItemSafetyCategoryBinding

data class SafetyCategory(
    val name: String,
    val count: Int
)

class SafetyCategoryAdapter(
    private var categories: List<SafetyCategory> = emptyList(),
    private val onCategoryClick: (SafetyCategory) -> Unit
) : RecyclerView.Adapter<SafetyCategoryAdapter.CategoryViewHolder>() {

    inner class CategoryViewHolder(
        private val binding: ItemSafetyCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: SafetyCategory) {

            binding.textCategory.text =
                formatCategoryName(category.name)

            binding.textCount.text =
                if (category.count == 1) {
                    "1 tip"
                } else {
                    "${category.count} tips"
                }

            binding.root.setOnClickListener {
                onCategoryClick(category)
            }
        }

        private fun formatCategoryName(category: String): String {
            return category
                .lowercase()
                .split("_")
                .joinToString(" ") { word ->
                    if (word == "url") {
                        "URL"
                    } else {
                        word.replaceFirstChar {
                            it.uppercase()
                        }
                    }
                }
        }
    }

    fun submitList(newCategories: List<SafetyCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): CategoryViewHolder {

        val binding = ItemSafetyCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: CategoryViewHolder,
        position: Int
    ) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int {
        return categories.size
    }
}
