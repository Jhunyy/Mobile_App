package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.databinding.FragmentMessagesBinding
import com.apcida.smishingdetector.view.adapter.MessageListAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MessageListAdapter
    private lateinit var messageRepository: MessageRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRepository()
        setupRecyclerView()
        observeMessages()
    }

    private fun setupRepository() {
        val database = AppDatabase.getInstance(requireContext())
        messageRepository = MessageRepository(database.messageDao())
    }

    private fun setupRecyclerView() {
        adapter = MessageListAdapter { message ->
            // Navigate to message detail when clicked
            findNavController().navigate(
                R.id.action_messages_to_detail,
                bundleOf("messageId" to message.messageId)
            )
        }

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MessagesFragment.adapter
        }
    }

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            messageRepository.getAllMessages().collectLatest { messages ->
                adapter.submitList(messages)

                // Show empty state if no messages
                if (messages.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerMessages.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerMessages.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}