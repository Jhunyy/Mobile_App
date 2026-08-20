package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.SafetyTipRepository
import com.apcida.smishingdetector.databinding.FragmentSafetyTipsBinding
import com.apcida.smishingdetector.view.adapter.SafetyTipAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SafetyTipsFragment : Fragment() {

    private var _binding: FragmentSafetyTipsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SafetyTipAdapter
    private lateinit var safetyTipRepository: SafetyTipRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSafetyTipsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRepository()
        setupRecyclerView()
        observeTips()
    }

    private fun setupRepository() {
        val database = AppDatabase.getInstance(requireContext())
        safetyTipRepository = SafetyTipRepository(database.safetyTipDao())
    }

    private fun setupRecyclerView() {
        adapter = SafetyTipAdapter()
        binding.recyclerTips.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SafetyTipsFragment.adapter
        }
    }

    private fun observeTips() {
        viewLifecycleOwner.lifecycleScope.launch {
            safetyTipRepository.getAllActiveTips().collectLatest { tips ->
                adapter.submitList(tips)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}