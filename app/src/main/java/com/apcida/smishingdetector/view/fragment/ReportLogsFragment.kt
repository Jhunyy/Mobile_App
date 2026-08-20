package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.databinding.FragmentReportLogsBinding
import com.apcida.smishingdetector.view.adapter.ReportLogAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReportLogsFragment : Fragment() {

    private var _binding: FragmentReportLogsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ReportLogAdapter
    private lateinit var reportRepository: ReportRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRepository()
        setupRecyclerView()
        observeReports()
    }

    private fun setupRepository() {
        val database = AppDatabase.getInstance(requireContext())
        reportRepository = ReportRepository(database.reportDao())
    }

    private fun setupRecyclerView() {
        adapter = ReportLogAdapter()
        binding.recyclerReports.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ReportLogsFragment.adapter
        }
    }

    private fun observeReports() {
        viewLifecycleOwner.lifecycleScope.launch {
            reportRepository.getAllReports().collectLatest { reports ->
                adapter.submitList(reports)

                if (reports.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerReports.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerReports.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}