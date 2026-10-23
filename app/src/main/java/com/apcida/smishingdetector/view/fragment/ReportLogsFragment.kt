package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.ReportRepository
import com.apcida.smishingdetector.backend.worker.ReportUploadWorker
import com.apcida.smishingdetector.databinding.FragmentReportLogsBinding
import com.apcida.smishingdetector.view.adapter.ReportLogAdapter
import com.apcida.smishingdetector.view.widget.PullToRefreshLayout
import com.apcida.smishingdetector.model.entity.Report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReportLogsFragment : Fragment() {

    private var _binding: FragmentReportLogsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ReportLogAdapter
    private lateinit var reportRepository: ReportRepository
    private lateinit var pullToRefresh: PullToRefreshLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportLogsBinding.inflate(inflater, container, false)
        pullToRefresh = PullToRefreshLayout(requireContext()).apply { setContent(binding.root) }
        return pullToRefresh
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRepository()
        setupRecyclerView()
        observeReports()
        setupPullToRefresh()
    }

    override fun onResume() {
        super.onResume()
        ReportUploadWorker.enqueue(requireContext().applicationContext)
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
                renderReports(reports)
            }
        }
    }

    private fun setupPullToRefresh() {
        pullToRefresh.canChildScrollUp = { binding.recyclerReports.canScrollVertically(-1) }
        pullToRefresh.setOnRefreshListener {
            val layout = pullToRefresh
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    ReportUploadWorker.enqueue(requireContext().applicationContext)
                    renderReports(reportRepository.getAllReports().first())
                    Toast.makeText(requireContext(), "Reports refreshed.", Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(requireContext(), "Could not refresh reports.", Toast.LENGTH_SHORT).show()
                } finally {
                    layout.isRefreshing = false
                }
            }
        }
    }

    private fun renderReports(reports: List<Report>) {
        adapter.submitList(reports)
        binding.emptyState.visibility = if (reports.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerReports.visibility = if (reports.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
