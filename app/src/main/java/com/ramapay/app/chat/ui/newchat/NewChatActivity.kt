package com.ramapay.app.chat.ui.newchat

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ramapay.app.R
import com.ramapay.app.chat.ui.conversation.ConversationActivity
import com.ramapay.app.databinding.ActivityNewChatBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import android.content.Intent

/**
 * Activity to start a new chat with a wallet address.
 */
@AndroidEntryPoint
class NewChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewChatBinding
    private val viewModel: NewChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupInput()
        setupButtons()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.new_chat)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupInput() {
        binding.editAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startChat()
                true
            } else {
                false
            }
        }
    }

    private fun setupButtons() {
        binding.buttonStartChat.setOnClickListener {
            startChat()
        }

        binding.buttonScanQr.setOnClickListener {
            // TODO: Launch QR scanner
            Toast.makeText(this, "QR Scanner coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startChat() {
        val address = binding.editAddress.text.toString().trim()
        
        if (address.isEmpty()) {
            binding.inputLayout.error = getString(R.string.enter_wallet_address)
            return
        }

        if (!isValidAddress(address)) {
            binding.inputLayout.error = getString(R.string.invalid_wallet_address)
            return
        }

        binding.inputLayout.error = null
        viewModel.startConversation(address)
    }

    private fun isValidAddress(address: String): Boolean {
        return address.matches(Regex("^0x[a-fA-F0-9]{40}$"))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is NewChatState.Idle -> {
                        binding.progressBar.isVisible = false
                        binding.buttonStartChat.isEnabled = true
                    }
                    is NewChatState.Loading -> {
                        binding.progressBar.isVisible = true
                        binding.buttonStartChat.isEnabled = false
                    }
                    is NewChatState.Success -> {
                        binding.progressBar.isVisible = false
                        // Navigate to conversation
                        val intent = Intent(this@NewChatActivity, ConversationActivity::class.java).apply {
                            putExtra(ConversationActivity.EXTRA_CONVERSATION_ID, state.conversationId)
                            putExtra(ConversationActivity.EXTRA_PEER_ADDRESS, state.peerAddress)
                        }
                        startActivity(intent)
                        finish()
                    }
                    is NewChatState.Error -> {
                        binding.progressBar.isVisible = false
                        binding.buttonStartChat.isEnabled = true
                        Toast.makeText(this@NewChatActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
