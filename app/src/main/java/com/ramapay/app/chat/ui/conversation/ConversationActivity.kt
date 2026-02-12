package com.ramapay.app.chat.ui.conversation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.ramapay.app.chat.notification.ChatNotificationHelper
import com.ramapay.app.chat.ui.ContactDetailsActivity
import com.ramapay.app.chat.ui.adapter.MessageListAdapter
import com.ramapay.app.chat.viewmodel.ConversationViewModel
import com.ramapay.app.chat.viewmodel.SendingState
import com.ramapay.app.databinding.ActivityConversationBinding
import com.ramapay.app.service.AppSecurityManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
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
    private var peerAddress: String = ""
    private var isUserBlocked: Boolean = false
    
    @Inject
    lateinit var chatService: ChatService
    
    @Inject
    lateinit var chatNotificationHelper: ChatNotificationHelper
    
    @Inject
    lateinit var appSecurityManager: AppSecurityManager
    
    // Handler for session refresh
    private val sessionRefreshHandler = Handler(Looper.getMainLooper())
    private val sessionRefreshRunnable = object : Runnable {
        override fun run() {
            if (appSecurityManager.isBypassLockInChatEnabled()) {
                appSecurityManager.refreshSession()
            }
            // Refresh every 30 seconds while in chat
            sessionRefreshHandler.postDelayed(this, 30_000L)
        }
    }
    
    // File picker for attachments
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            // Handle selected file
            Toast.makeText(this, "File attachments coming soon", Toast.LENGTH_SHORT).show()
            // TODO: Implement file transfer
            // viewModel.sendFile(selectedUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: run {
            finish()
            return
        }
        peerAddress = intent.getStringExtra(EXTRA_PEER_ADDRESS) ?: run {
            finish()
            return
        }

        setupToolbar(peerAddress)
        setupRecyclerView()
        setupMessageInput()
        setupAttachButton()

        viewModel.loadConversation(conversationId, peerAddress)
        observeViewModel()
        
        // Cancel any existing notifications for this conversation
        chatNotificationHelper.cancelNotification(conversationId)
        
        // Initialize ChatService if needed
        initializeChatService()
        
        // Check if user is blocked
        checkBlockStatus()
    }
    
    private fun initializeChatService() {
        lifecycleScope.launch {
            if (!chatService.isInitialized.value) {
                val result = chatService.initialize()
                result.onFailure { error ->
                    Toast.makeText(
                        this@ConversationActivity,
                        "Chat initialization failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun setupAttachButton() {
        binding.buttonAttach.setOnClickListener {
            showAttachmentOptions()
        }
    }
    
    private fun showAttachmentOptions() {
        val options = arrayOf(
            getString(R.string.attach_image),
            getString(R.string.attach_document),
            getString(R.string.attach_file)
        )
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.attach_file)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> filePickerLauncher.launch("image/*")
                    1 -> filePickerLauncher.launch("application/pdf")
                    2 -> filePickerLauncher.launch("*/*")
                }
            }
            .show()
    }
    
    private fun checkBlockStatus() {
        lifecycleScope.launch {
            isUserBlocked = viewModel.isUserBlocked(peerAddress)
            invalidateOptionsMenu()
        }
    }

    private fun setupToolbar(peerAddress: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = formatAddress(peerAddress)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Load and display contact's nickname if available
        lifecycleScope.launch {
            val displayName = viewModel.getContactDisplayName(peerAddress)
            if (!displayName.isNullOrBlank()) {
                supportActionBar?.title = displayName
                supportActionBar?.subtitle = formatAddress(peerAddress)
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_conversation, menu)
        
        // Show/hide block/unblock based on current status
        menu.findItem(R.id.action_block_user)?.isVisible = !isUserBlocked
        menu.findItem(R.id.action_unblock_user)?.isVisible = isUserBlocked
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_contact -> {
                ContactDetailsActivity.start(
                    this,
                    contactAddress = peerAddress,
                    ownerWallet = viewModel.currentWalletAddress
                )
                true
            }
            R.id.action_block_user -> {
                showBlockConfirmation()
                true
            }
            R.id.action_unblock_user -> {
                showUnblockConfirmation()
                true
            }
            R.id.action_archive_chat -> {
                archiveChat()
                true
            }
            R.id.action_clear_history -> {
                showClearHistoryConfirmation()
                true
            }
            R.id.action_delete_contact -> {
                showDeleteContactConfirmation()
                true
            }
            R.id.action_export_chat -> {
                exportChat()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showBlockConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.block_user)
            .setMessage(R.string.block_confirm_message)
            .setPositiveButton(R.string.block_user) { _, _ ->
                blockUser()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showUnblockConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unblock_user)
            .setMessage(R.string.unblock_confirm_message)
            .setPositiveButton(R.string.unblock_user) { _, _ ->
                unblockUser()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun blockUser() {
        lifecycleScope.launch {
            val success = viewModel.blockUser(peerAddress)
            if (success) {
                isUserBlocked = true
                invalidateOptionsMenu()
                Toast.makeText(this@ConversationActivity, R.string.user_blocked, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ConversationActivity, "Failed to block user", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun unblockUser() {
        lifecycleScope.launch {
            val success = viewModel.unblockUser(peerAddress)
            if (success) {
                isUserBlocked = false
                invalidateOptionsMenu()
                Toast.makeText(this@ConversationActivity, R.string.user_unblocked, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ConversationActivity, "Failed to unblock user", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun archiveChat() {
        lifecycleScope.launch {
            viewModel.archiveConversation()
            Toast.makeText(this@ConversationActivity, R.string.chat_archived, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun showClearHistoryConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_chat_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.clear_chat_history) { _, _ ->
                clearChatHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun clearChatHistory() {
        lifecycleScope.launch {
            viewModel.clearChatHistory()
            Toast.makeText(this@ConversationActivity, R.string.chat_cleared, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showDeleteContactConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_contact)
            .setMessage(R.string.delete_contact_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteContact()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun deleteContact() {
        lifecycleScope.launch {
            val success = viewModel.deleteContact(peerAddress)
            if (success) {
                Toast.makeText(this@ConversationActivity, R.string.contact_deleted, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@ConversationActivity, "Failed to delete contact", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun exportChat() {
        lifecycleScope.launch {
            val messages = viewModel.messages.value
            if (messages.isEmpty()) {
                Toast.makeText(this@ConversationActivity, "No messages to export", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val chatExport = StringBuilder()
            chatExport.appendLine("MumbleChat Export")
            chatExport.appendLine("================")
            chatExport.appendLine("Chat with: $peerAddress")
            chatExport.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            chatExport.appendLine()
            chatExport.appendLine("Messages:")
            chatExport.appendLine("---------")
            
            messages.forEach { msg ->
                val sender = if (msg.senderAddress == viewModel.currentWalletAddress) "You" else formatAddress(msg.senderAddress)
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))
                chatExport.appendLine("[$time] $sender: ${msg.content}")
            }
            
            // Share via intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MumbleChat Export - ${formatAddress(peerAddress)}")
                putExtra(Intent.EXTRA_TEXT, chatExport.toString())
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_chat)))
        }
    }
    
    private fun formatAddress(address: String): String {
        return if (address.length > 12) "${address.take(6)}...${address.takeLast(4)}" else address
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageListAdapter(
            currentWalletAddress = viewModel.currentWalletAddress,
            onRetryClick = { message ->
                viewModel.retryMessage(message)
            },
            onMessageLongClick = { message ->
                showMessageOptionsDialog(message)
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
    
    private fun showMessageOptionsDialog(message: com.ramapay.app.chat.data.entity.MessageEntity) {
        val options = arrayOf(
            getString(R.string.copy_message),
            getString(R.string.delete_message)
        )
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.message_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyMessage(message)
                    1 -> showDeleteMessageConfirmation(message)
                }
            }
            .show()
    }
    
    private fun copyMessage(message: com.ramapay.app.chat.data.entity.MessageEntity) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", message.content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show()
    }
    
    private fun showDeleteMessageConfirmation(message: com.ramapay.app.chat.data.entity.MessageEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_message)
            .setMessage(R.string.delete_message_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteMessage(message)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun deleteMessage(message: com.ramapay.app.chat.data.entity.MessageEntity) {
        lifecycleScope.launch {
            viewModel.deleteMessage(message)
            Toast.makeText(this@ConversationActivity, R.string.message_deleted, Toast.LENGTH_SHORT).show()
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
    
    override fun onResume() {
        super.onResume()
        // Start refreshing session to bypass auto-lock while in chat
        if (appSecurityManager.isBypassLockInChatEnabled()) {
            appSecurityManager.refreshSession()
            sessionRefreshHandler.postDelayed(sessionRefreshRunnable, 30_000L)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop refreshing session when leaving chat
        sessionRefreshHandler.removeCallbacks(sessionRefreshRunnable)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sessionRefreshHandler.removeCallbacks(sessionRefreshRunnable)
    }
}
