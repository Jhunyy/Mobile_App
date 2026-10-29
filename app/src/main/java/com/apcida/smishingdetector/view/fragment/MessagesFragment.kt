package com.apcida.smishingdetector.view.fragment

import android.Manifest
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.apcida.smishingdetector.R
import com.apcida.smishingdetector.backend.database.AppDatabase
import com.apcida.smishingdetector.backend.repository.MessageRepository
import com.apcida.smishingdetector.backend.worker.SmsInboxSyncWorker
import com.apcida.smishingdetector.databinding.FragmentMessagesBinding
import com.apcida.smishingdetector.model.entity.Message
import com.apcida.smishingdetector.util.ContactNameResolver
import com.apcida.smishingdetector.util.Constants
import com.apcida.smishingdetector.view.adapter.MessageListAdapter
import com.apcida.smishingdetector.view.presentation.DetectionPresentation
import com.apcida.smishingdetector.view.widget.PullToRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MessagesFragment : Fragment() {

    private enum class InboxFilter { ALL, SCAM, SAFE, UNREAD }

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MessageListAdapter
    private lateinit var messageRepository: MessageRepository
    private lateinit var pullToRefresh: PullToRefreshLayout
    private var allMessages: List<Message> = emptyList()
    private var syncInProgress = false
    private var selectedFilter = InboxFilter.ALL
    private var newestFirst = true
    private val readMessageIds = mutableSetOf<String>()
    private var contactNames: Map<String, String> = emptyMap()
    private var contactLookupJob: Job? = null
    private var observingContacts = false
    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshContactNames()
        }
    }
    private val requestContactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requireContext().getSharedPreferences(Constants.PREFS_NAME, 0).edit()
            .putBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, granted).apply()
        updateContactsObserver()
        refreshContactNames()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        pullToRefresh = PullToRefreshLayout(requireContext()).apply { setContent(binding.root) }
        return pullToRefresh
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRepository()
        loadReadState()
        setupRecyclerView()
        setupInboxControls()
        observeMessages()
        observeInboxSync()
        setupPullToRefresh()
        offerContactNamesOnce()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            if (com.apcida.smishingdetector.util.PermissionHelper.hasReadSmsPermission(requireContext())) {
                SmsInboxSyncWorker.enqueue(requireContext().applicationContext)
            }
            updateContactsObserver()
            refreshContactNames()
        }
    }

    override fun onPause() {
        stopContactsObserver()
        super.onPause()
    }

    override fun onDestroyView() {
        contactLookupJob?.cancel()
        super.onDestroyView()
        _binding = null
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
                menu.add(if (ContactNameResolver.isEnabled(requireContext()))
                    getString(R.string.hide_contact_names)
                else getString(R.string.show_contact_names)).setOnMenuItemClickListener {
                    toggleContactNames()
                    true
                }
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
                refreshContactNames()
            }
        }
    }

    private fun observeInboxSync() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(SmsInboxSyncWorker.WORK_NAME)
            .observe(viewLifecycleOwner) { work ->
                syncInProgress = work?.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                } == true
                renderMessages()
            }
    }

    private fun setupPullToRefresh() {
        pullToRefresh.canChildScrollUp = { binding.recyclerMessages.canScrollVertically(-1) }
        pullToRefresh.setOnRefreshListener {
            val layout = pullToRefresh
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    SmsInboxSyncWorker.enqueue(requireContext().applicationContext)
                    allMessages = messageRepository.getAllMessages().first()
                    loadReadState()
                    renderMessages()
                    refreshContactNames()
                    Toast.makeText(requireContext(), "Checking stored messages.", Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(requireContext(), "Could not refresh messages.", Toast.LENGTH_SHORT).show()
                } finally {
                    layout.isRefreshing = false
                }
            }
        }
    }

    private fun renderMessages() {
        // A Room emission can arrive while Navigation is tearing down this view.
        if (_binding == null) return
        val query = binding.inputSearch.text?.toString().orEmpty().trim()
        val visibleMessages = allMessages.asSequence()
            .filter { message ->
                when (selectedFilter) {
                    InboxFilter.ALL -> true
                    InboxFilter.SCAM -> DetectionPresentation.forMessage(message).verdict == Constants.RISK_SCAM
                    InboxFilter.SAFE -> DetectionPresentation.forMessage(message).isSafeVerified
                    InboxFilter.UNREAD -> message.messageId.toString() !in readMessageIds
                }
            }
            .filter { message -> query.isEmpty() ||
                message.content.contains(query, ignoreCase = true) ||
                message.senderAddress?.contains(query, ignoreCase = true) == true ||
                message.senderAddress?.let(contactNames::get)
                    ?.contains(query, ignoreCase = true) == true
            }
            .sortedWith(if (newestFirst) compareByDescending { it.receivedAt }
                        else compareBy { it.receivedAt })
            .toList()

        adapter.submitList(visibleMessages)
        val isEmpty = visibleMessages.isEmpty()
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerMessages.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.textEmptyTitle.text = when {
            allMessages.isEmpty() && syncInProgress -> "Scanning stored messages…"
            allMessages.isEmpty() -> "No messages yet"
            else -> "No matching messages"
        }
        binding.textEmptyDescription.text = if (allMessages.isEmpty()) {
            if (syncInProgress) "Messages will appear here as they are detected."
            else "Your messages will appear here once they are detected."
        } else {
            "Try another search or filter."
        }
    }

    private fun toggleContactNames() {
        if (ContactNameResolver.isEnabled(requireContext())) {
            requireContext().getSharedPreferences(Constants.PREFS_NAME, 0).edit()
                .putBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, false).apply()
            updateContactsObserver()
            refreshContactNames()
        } else if (ContactNameResolver.hasPermission(requireContext())) {
            requireContext().getSharedPreferences(Constants.PREFS_NAME, 0).edit()
                .putBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, true).apply()
            updateContactsObserver()
            refreshContactNames()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.contacts_permission_title)
                .setMessage(R.string.contacts_permission_explanation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
                }
                .show()
        }
    }

    private fun offerContactNamesOnce() {
        val preferences = requireContext().getSharedPreferences(Constants.PREFS_NAME, 0)
        if (preferences.getBoolean(Constants.PREF_CONTACT_PROMPT_SHOWN, false) ||
            ContactNameResolver.isEnabled(requireContext())) return

        preferences.edit().putBoolean(Constants.PREF_CONTACT_PROMPT_SHOWN, true).apply()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.contacts_permission_title)
            .setMessage(R.string.contacts_permission_explanation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                if (ContactNameResolver.hasPermission(requireContext())) {
                    preferences.edit().putBoolean(Constants.PREF_CONTACT_NAMES_ENABLED, true).apply()
                    updateContactsObserver()
                    refreshContactNames()
                } else {
                    requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            .show()
    }

    private fun updateContactsObserver() {
        val shouldObserve = ContactNameResolver.isEnabled(requireContext())
        if (shouldObserve && !observingContacts) {
            requireContext().contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, contactsObserver
            )
            observingContacts = true
        } else if (!shouldObserve) {
            stopContactsObserver()
        }
    }

    private fun stopContactsObserver() {
        if (observingContacts) {
            requireContext().contentResolver.unregisterContentObserver(contactsObserver)
            observingContacts = false
        }
    }

    private fun refreshContactNames() {
        if (!::adapter.isInitialized || _binding == null) return
        contactLookupJob?.cancel()
        if (!ContactNameResolver.isEnabled(requireContext())) {
            contactNames = emptyMap()
            adapter.setContactNames(contactNames)
            renderMessages()
            return
        }

        val context = requireContext().applicationContext
        val addresses = allMessages.mapNotNull { it.senderAddress }.distinct()
        contactLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val names = buildMap {
                for (address in addresses) {
                    ContactNameResolver.findName(context, address)?.let { put(address, it) }
                }
            }
            contactNames = names
            adapter.setContactNames(names)
            renderMessages()
        }
    }

}
