package com.ramapay.app.chat.ui.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ramapay.app.R
import com.ramapay.app.chat.core.ChatService
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.ui.adapter.MessageListAdapter
import com.ramapay.app.chat.viewmodel.GroupChatViewModel
import com.ramapay.app.chat.viewmodel.SendingState
import com.ramapay.app.databinding.ActivityGroupChatBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity for group chat conversation.
 */
@AndroidEntryPoint
class GroupChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        
        fun createIntent(context: Context, groupId: String): Intent {
            return Intent(context, GroupChatActivity::class.java).apply {
                putExtra(EXTRA_GROUP_ID, groupId)
            }
        }
    }

    private lateinit var binding: ActivityGroupChatBinding
    private val viewModel: GroupChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageListAdapter
    private var groupId: String = ""

    @Inject
    lateinit var chatService: ChatService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: run {
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        setupMessageInput()
        setupAttachButton()

        viewModel.loadGroup(groupId)
        observeViewModel()
        
        // Initialize ChatService
        initializeChatService()
    }

    private fun initializeChatService() {
        lifecycleScope.launch {
            if (!chatService.isInitialized.value) {
                chatService.initialize()
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.group)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Click on toolbar to see group info
        binding.toolbar.setOnClickListener {
            openGroupInfo()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_group_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_group_info -> {
                openGroupInfo()
                true
            }
            R.id.action_mute -> {
                viewModel.toggleMute()
                true
            }
            R.id.action_leave_group -> {
                showLeaveConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openGroupInfo() {
        startActivity(GroupInfoActivity.createIntent(this, groupId))
    }

    private fun showLeaveConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leave_group)
            .setMessage(R.string.leave_group_confirm)
            .setPositiveButton(R.string.leave_group) { _, _ ->
                viewModel.leaveGroup()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageListAdapter(
            currentWalletAddress = viewModel.currentWalletAddress,
            onRetryClick = { message ->
                viewModel.retryMessage(message)
            },
            onMessageLongClick = { message ->
                showMessageOptions(message)
                true
            },
            showSenderInfo = true  // Show sender names in group chat
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(this@GroupChatActivity).apply {
                stackFromEnd = true
                reverseLayout = false
            }
            adapter = messageAdapter
        }
    }

    private fun showMessageOptions(message: MessageEntity) {
        val options = arrayOf(
            getString(R.string.copy_message),
            getString(R.string.delete_message)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.message_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyMessage(message)
                    1 -> viewModel.deleteMessage(message)
                }
            }
            .show()
    }

    private fun copyMessage(message: MessageEntity) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("message", message.content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show()
    }

    private fun setupMessageInput() {
        binding.buttonSend.setOnClickListener {
            sendMessage()
        }

        binding.editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        binding.editMessage.addTextChangedListener {
            viewModel.onTyping(it?.isNotEmpty() == true)
        }
    }

    private fun setupAttachButton() {
        binding.buttonAttach.setOnClickListener {
            Toast.makeText(this, "File attachments coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage() {
        val text = binding.editMessage.text.toString().trim()
        if (text.isNotEmpty()) {
            viewModel.sendMessage(text)
            binding.editMessage.text?.clear()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.group.collect { group ->
                        group?.let {
                            binding.toolbar.title = it.name
                            binding.toolbar.subtitle = getString(R.string.members_count, 
                                viewModel.memberCount.value)
                        }
                    }
                }

                launch {
                    viewModel.memberCount.collect { count ->
                        binding.toolbar.subtitle = getString(R.string.members_count, count)
                    }
                }

                launch {
                    viewModel.messages.collect { messages ->
                        messageAdapter.submitList(messages)
                        if (messages.isNotEmpty()) {
                            binding.recyclerMessages.post {
                                binding.recyclerMessages.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }

                launch {
                    viewModel.typingMembers.collect { typing ->
                        if (typing.isNotEmpty()) {
                            binding.typingIndicator.isVisible = true
                            binding.typingText.text = if (typing.size == 1) {
                                getString(R.string.member_typing, typing.first())
                            } else {
                                getString(R.string.members_typing, typing.size)
                            }
                        } else {
                            binding.typingIndicator.isVisible = false
                        }
                    }
                }

                launch {
                    viewModel.sendingState.collect { state ->
                        when (state) {
                            is SendingState.Sending -> {
                                binding.buttonSend.isEnabled = false
                                binding.progressSending.isVisible = true
                            }
                            is SendingState.Error -> {
                                binding.buttonSend.isEnabled = true
                                binding.progressSending.isVisible = false
                                Toast.makeText(
                                    this@GroupChatActivity,
                                    "Failed to send: ${state.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            else -> {
                                binding.buttonSend.isEnabled = true
                                binding.progressSending.isVisible = false
                            }
                        }
                    }
                }

                launch {
                    viewModel.leftGroup.collect { left ->
                        if (left) {
                            Toast.makeText(this@GroupChatActivity, R.string.left_group, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
        }
    }
}
