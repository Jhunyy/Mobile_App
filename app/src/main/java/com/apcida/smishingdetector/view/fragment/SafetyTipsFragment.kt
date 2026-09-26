package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.SafetyTipRepository
import com.apcida.smishingdetector.databinding.FragmentSafetyTipsBinding
import com.apcida.smishingdetector.model.entity.SafetyTip
import com.apcida.smishingdetector.view.adapter.SafetyCategory
import com.apcida.smishingdetector.view.adapter.SafetyCategoryAdapter
import com.apcida.smishingdetector.view.adapter.SafetyTipAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SafetyTipsFragment : Fragment() {

    private var _binding: FragmentSafetyTipsBinding? = null
    private val binding get() = _binding!!

    private lateinit var safetyTipAdapter: SafetyTipAdapter
    private lateinit var categoryAdapter: SafetyCategoryAdapter
    private lateinit var safetyTipRepository: SafetyTipRepository

    // Keep the complete list so we can filter it locally.
    private var allTips: List<SafetyTip> = emptyList()

    // Null means we are currently viewing categories.
    private var selectedCategory: String? = null

    private lateinit var backPressedCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentSafetyTipsBinding.inflate(
            inflater,
            container,
            false
        )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupRepository()
        setupAdapters()
        setupBackButton()
        setupSystemBackButton()
        observeTips()
    }

    private fun setupRepository() {
        val database =
            AppDatabase.getInstance(requireContext())

        safetyTipRepository =
            SafetyTipRepository(
                database.safetyTipDao()
            )
    }

    private fun setupAdapters() {

        safetyTipAdapter = SafetyTipAdapter()

        categoryAdapter =
            SafetyCategoryAdapter { category ->
                showCategory(category.name)
            }

        // Start on category screen.
        showCategories()
    }

    private fun observeTips() {

        viewLifecycleOwner.lifecycleScope.launch {

            safetyTipRepository
                .getAllActiveTips()
                .collectLatest { tips ->

                    allTips = tips

                    if (selectedCategory == null) {
                        showCategories()
                    } else {
                        showCategory(selectedCategory!!)
                    }
                }
        }
    }

    /**
     * Main Safety Tips screen.
     *
     * Shows category cards instead of individual tips.
     */
    private fun showCategories() {

        selectedCategory = null

        binding.textSafetyTitle.text = "Safety Tips"

        binding.imgSafety.visibility = View.VISIBLE
        binding.btnBack.visibility = View.GONE

        val categories = allTips
            .groupBy { tip ->
                normalizeCategory(tip.category)
            }
            .map { entry ->
                SafetyCategory(
                    name = entry.key,
                    count = entry.value.size
                )
            }
            .sortedBy { category ->
                category.name
            }

        categoryAdapter.submitList(categories)

        binding.recyclerTips.apply {

            layoutManager =
                GridLayoutManager(
                    requireContext(),
                    2
                )

            adapter = categoryAdapter
        }

        updateBackButtonState()
    }

    /**
     * Opens one category and displays only
     * the tips belonging to it.
     */
    private fun showCategory(category: String) {

        val normalizedCategory =
            normalizeCategory(category)

        selectedCategory = normalizedCategory

        binding.textSafetyTitle.text =
            formatCategoryName(normalizedCategory)

        binding.imgSafety.visibility = View.GONE
        binding.btnBack.visibility = View.VISIBLE

        val filteredTips = allTips.filter { tip ->
            normalizeCategory(tip.category)
                .equals(
                    normalizedCategory,
                    ignoreCase = true
                )
        }

        safetyTipAdapter.submitList(filteredTips)

        binding.recyclerTips.apply {

            layoutManager =
                LinearLayoutManager(
                    requireContext()
                )

            adapter = safetyTipAdapter
        }

        // Return to top whenever a category opens.
        binding.recyclerTips.scrollToPosition(0)

        updateBackButtonState()
    }

    private fun setupBackButton() {

        binding.btnBack.setOnClickListener {

            if (selectedCategory != null) {
                showCategories()
            }
        }
    }

    /**
     * Makes Android's physical/system back button
     * return to categories instead of leaving the tab.
     */
    private fun setupSystemBackButton() {

        backPressedCallback =
            object : OnBackPressedCallback(false) {

                override fun handleOnBackPressed() {
                    showCategories()
                }
            }

        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                backPressedCallback
            )
    }

    private fun updateBackButtonState() {

        if (::backPressedCallback.isInitialized) {
            backPressedCallback.isEnabled =
                selectedCategory != null
        }
    }

    /**
     * Converts null/blank categories to GENERAL
     * and keeps database categories consistent.
     */
    private fun normalizeCategory(
        category: String?
    ): String {

        return if (category.isNullOrBlank()) {
            "GENERAL"
        } else {
            category.trim().uppercase()
        }
    }

    /**
     * Example:
     *
     * GENERAL       -> General
     * URL_SAFETY    -> URL Safety
     * BANKING_SCAM  -> Banking Scam
     */
    private fun formatCategoryName(
        category: String
    ): String {

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
