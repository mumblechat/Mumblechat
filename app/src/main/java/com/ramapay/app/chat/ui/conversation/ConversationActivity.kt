package com.ramapay.app.chat.ui.conversation

import android.os.Bundle
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
import com.ramapay.app.chat.ui.adapter.MessageListAdapter
import com.ramapay.app.chat.viewmodel.ConversationViewModel
import com.ramapay.app.chat.viewmodel.SendingState
import com.ramapay.app.databinding.ActivityConversationBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Activity for 1:1 conversation.
 */
@AndroidEntryPoint
class ConversationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_PEER_ADDRESS = "peer_address"
    }

    private lateinit var binding: ActivityConversationBinding
    private val viewModel: ConversationViewModel by viewModels()
    private lateinit var messageAdapter: MessageListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: run {
            finish()
            return
        }
        val peerAddress = intent.getStringExtra(EXTRA_PEER_ADDRESS) ?: run {
            finish()
            return
        }

        setupToolbar(peerAddress)
        setupRecyclerView()
        setupMessageInput()

        viewModel.loadConversation(conversationId, peerAddress)
        observeViewModel()
    }

    private fun setupToolbar(peerAddress: String) {
        binding.toolbar.title = formatAddress(peerAddress)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageListAdapter(
            currentWalletAddress = viewModel.currentWalletAddress,
            onRetryClick = { message ->
                viewModel.retryMessage(message)
            },
            onMessageLongClick = { message ->
                // TODO: Show message options (copy, delete, etc.)
                true
            }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply {
                stackFromEnd = true
                reverseLayout = false
            }
            adapter = messageAdapter
        }
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

        // Typing indicator
        binding.editMessage.addTextChangedListener {
            viewModel.onTyping(it?.isNotEmpty() == true)
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
                    viewModel.conversation.collect { conversation ->
                        conversation?.let {
                            // Update title with custom name if available
                            binding.toolbar.title = it.customName ?: formatAddress(it.peerAddress)
                        }
                    }
                }
                
                launch {
                    viewModel.messages.collect { messages ->
                        messageAdapter.submitList(messages)
                        // Scroll to bottom on new message
                        if (messages.isNotEmpty()) {
                            binding.recyclerMessages.post {
                                binding.recyclerMessages.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }

                launch {
                    viewModel.peerTyping.collect { isTyping ->
                        binding.typingIndicator.isVisible = isTyping
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
                                // Show error to user
                                Toast.makeText(
                                    this@ConversationActivity,
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
            }
        }
    }

    private fun formatAddress(address: String): String {
        return if (address.length > 10) {
            "${address.take(6)}...${address.takeLast(4)}"
        } else {
            address
        }
    }
}
