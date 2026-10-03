package com.apcida.smishingdetector.view.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.databinding.FragmentMessagesBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.view.adapter.MessageListAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MessagesFragment : Fragment() {

    private enum class InboxFilter { ALL, SCAM, SAFE, UNREAD }

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MessageListAdapter
    private lateinit var messageRepository: MessageRepository
    private var allMessages: List<Message> = emptyList()
    private var selectedFilter = InboxFilter.ALL
    private var newestFirst = true
    private val readMessageIds = mutableSetOf<String>()

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
        loadReadState()
        setupRecyclerView()
        setupInboxControls()
        observeMessages()
    }

    private fun setupRepository() {
        val database = AppDatabase.getInstance(requireContext())
        messageRepository = MessageRepository(database.messageDao())
    }

    private fun setupRecyclerView() {
        adapter = MessageListAdapter(
            onMessageClick = { message ->
                markRead(message.messageId)
                findNavController().navigate(
                    R.id.action_messages_to_detail,
                    bundleOf("messageId" to message.messageId)
                )
            },
            isUnread = { message -> message.messageId.toString() !in readMessageIds }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MessagesFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun loadReadState() {
        val saved = requireContext().getSharedPreferences(Constants.PREFS_NAME, 0)
            .getStringSet("read_message_ids", emptySet()).orEmpty()
        readMessageIds.clear()
        readMessageIds.addAll(saved)
    }

    private fun saveReadState() {
        requireContext().getSharedPreferences(Constants.PREFS_NAME, 0)
            .edit().putStringSet("read_message_ids", readMessageIds.toSet()).apply()
    }

    private fun markRead(messageId: Long) {
        if (readMessageIds.add(messageId.toString())) {
            saveReadState()
            renderMessages()
            adapter.notifyDataSetChanged()
        }
    }

    private fun setupInboxControls() {
        binding.inputSearch.doAfterTextChanged { renderMessages() }

        val chips = mapOf(
            InboxFilter.ALL to binding.chipAll,
            InboxFilter.SCAM to binding.chipScam,
            InboxFilter.SAFE to binding.chipSafe,
            InboxFilter.UNREAD to binding.chipUnread
        )
        chips.forEach { (filter, chip) ->
            chip.setOnClickListener {
                selectedFilter = filter
                chips.forEach { (option, view) ->
                    val selected = option == selectedFilter
                    view.setBackgroundResource(
                        if (selected) R.drawable.bg_inbox_chip_selected else R.drawable.bg_inbox_chip
                    )
                    view.setTextColor(ContextCompat.getColor(
                        requireContext(), if (selected) R.color.inbox_green else R.color.inbox_muted
                    ))
                }
                renderMessages()
            }
        }

        binding.btnSort.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add("Newest first").setOnMenuItemClickListener {
                    newestFirst = true
                    renderMessages()
                    true
                }
                menu.add("Oldest first").setOnMenuItemClickListener {
                    newestFirst = false
                    renderMessages()
                    true
                }
                show()
            }
        }

        binding.btnMore.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add("Mark all as read").setOnMenuItemClickListener {
                    readMessageIds.addAll(allMessages.map { it.messageId.toString() })
                    saveReadState()
                    renderMessages()
                    adapter.notifyDataSetChanged()
                    true
                }
                menu.add("Mark all as unread").setOnMenuItemClickListener {
                    readMessageIds.clear()
                    saveReadState()
                    renderMessages()
                    adapter.notifyDataSetChanged()
                    true
                }
                show()
            }
        }
    }

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            messageRepository.getAllMessages().collectLatest { messages ->
                allMessages = messages
                renderMessages()
            }
        }
    }

    private fun renderMessages() {
        val query = binding.inputSearch.text?.toString().orEmpty().trim()
        val visibleMessages = allMessages.asSequence()
            .filter { message ->
                when (selectedFilter) {
                    InboxFilter.ALL -> true
                    InboxFilter.SCAM -> message.riskLevel == Constants.RISK_SCAM
                    InboxFilter.SAFE -> message.riskLevel == Constants.RISK_SAFE
                    InboxFilter.UNREAD -> message.messageId.toString() !in readMessageIds
                }
            }
            .filter { query.isEmpty() || it.content.contains(query, ignoreCase = true) }
            .sortedWith(if (newestFirst) compareByDescending { it.receivedAt }
                        else compareBy { it.receivedAt })
            .toList()

        adapter.submitList(visibleMessages)
        val isEmpty = visibleMessages.isEmpty()
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerMessages.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.textEmptyTitle.text = if (allMessages.isEmpty()) "No messages yet" else "No matching messages"
        binding.textEmptyDescription.text = if (allMessages.isEmpty()) {
            "Your messages will appear here once they are detected."
        } else {
            "Try another search or filter."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
