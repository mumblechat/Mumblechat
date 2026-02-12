package com.ramapay.app.chat.ui.newchat

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ramapay.app.C
import com.ramapay.app.R
import com.ramapay.app.chat.ui.conversation.ConversationActivity
import com.ramapay.app.chat.ui.dialog.QRCodeDialog
import com.ramapay.app.databinding.ActivityNewChatBinding
import com.ramapay.app.widget.AWalletAlertDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity to start a new chat with a wallet address.
 */
@AndroidEntryPoint
class NewChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewChatBinding
    private val viewModel: NewChatViewModel by viewModels()
    private var verificationJob: Job? = null
    private var lastVerifiedAddress: String? = null  // Track to prevent re-verification

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupInput()
        setupButtons()
        observeViewModel()
        
        // Handle incoming address from intent (e.g., from QR scan)
        intent.getStringExtra("address")?.let { address ->
            binding.editAddress.setText(address)
            verifyAddress(address)
        }
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
        
        // Add text watcher for real-time verification
        binding.editAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val address = s?.toString()?.trim() ?: ""
                if (isValidAddress(address)) {
                    // Only verify if address changed from last verified
                    if (address.lowercase() != lastVerifiedAddress?.lowercase()) {
                        // Debounce verification
                        verificationJob?.cancel()
                        verificationJob = lifecycleScope.launch {
                            delay(500) // Wait 500ms before verifying
                            verifyAddress(address)
                        }
                    }
                } else {
                    lastVerifiedAddress = null
                    hideVerificationStatus()
                }
            }
        })
    }

    private fun setupButtons() {
        // Start Chat button
        binding.buttonStartChat.setOnClickListener {
            startChat()
        }

        // Paste button
        binding.buttonPaste.setOnClickListener {
            pasteFromClipboard()
        }

        // Scan QR button
        binding.buttonScanQr.setOnClickListener {
            // Launch QR scanner activity
            val intent = Intent(this, com.ramapay.app.ui.QRScanning.QRScannerActivity::class.java)
            startActivityForResult(intent, REQUEST_CODE_QR_SCAN)
        }

        // Show My QR button
        binding.buttonShowMyQr.setOnClickListener {
            showMyQRCode()
        }
    }
    
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        
        if (clipData != null && clipData.itemCount > 0) {
            val pastedText = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
            
            if (pastedText.isNotEmpty()) {
                // Pre-set lastVerifiedAddress to prevent TextWatcher from triggering verification
                if (isValidAddress(pastedText)) {
                    lastVerifiedAddress = pastedText.lowercase()
                }
                
                binding.editAddress.setText(pastedText)
                binding.editAddress.setSelection(pastedText.length)
                
                // Auto-verify if valid address
                if (isValidAddress(pastedText)) {
                    verifyAddress(pastedText)
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showMyQRCode() {
        val myAddress = viewModel.getMyAddress()
        if (myAddress.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.no_wallet_connected), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show QR code dialog
        QRCodeDialog.newInstance(
            address = myAddress,
            title = getString(R.string.your_mumblechat_qr),
            subtitle = getString(R.string.scan_to_chat)
        ).show(supportFragmentManager, "qr_dialog")
    }
    
    private fun verifyAddress(address: String) {
        if (!isValidAddress(address)) {
            hideVerificationStatus()
            return
        }
        
        // Show loading state
        binding.registrationStatusCard.isVisible = true
        binding.statusIcon.isVisible = false
        binding.statusProgress.isVisible = true
        binding.statusText.text = getString(R.string.verifying_address)
        binding.registrationStatusCard.strokeColor = getColor(R.color.text_secondary)
        
        viewModel.checkIfRegistered(address)
    }
    
    private fun showRegisteredStatus(onChainDisplayName: String? = null) {
        binding.registrationStatusCard.isVisible = true
        binding.statusIcon.isVisible = true
        binding.statusProgress.isVisible = false
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(getColor(R.color.success))
        
        // Show on-chain display name if available
        if (!onChainDisplayName.isNullOrBlank()) {
            binding.statusText.text = getString(R.string.user_registered_with_name, onChainDisplayName)
            // Auto-fill display name if user hasn't entered one
            if (binding.editDisplayName.text.isNullOrEmpty()) {
                binding.editDisplayName.setText(onChainDisplayName)
            }
        } else {
            binding.statusText.text = getString(R.string.user_registered)
        }
        
        binding.statusText.setTextColor(getColor(R.color.success))
        binding.registrationStatusCard.strokeColor = getColor(R.color.success)
        binding.buttonStartChat.isEnabled = true
        binding.buttonInvite.isVisible = false
    }
    
    private fun showNotRegisteredStatus() {
        binding.registrationStatusCard.isVisible = true
        binding.statusIcon.isVisible = true
        binding.statusProgress.isVisible = false
        binding.statusIcon.setImageResource(R.drawable.ic_warning)
        binding.statusIcon.setColorFilter(getColor(R.color.error))
        binding.statusText.text = getString(R.string.user_not_registered)
        binding.statusText.setTextColor(getColor(R.color.error))
        binding.registrationStatusCard.strokeColor = getColor(R.color.error)
        binding.buttonStartChat.isEnabled = false
        
        // Show invite button
        binding.buttonInvite.isVisible = true
        binding.buttonInvite.setOnClickListener {
            showInviteOptions(binding.editAddress.text.toString().trim())
        }
    }
    
    private fun showInviteDialog() {
        val address = binding.editAddress.text.toString().trim()
        
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.user_not_registered)
            .setMessage(R.string.invite_user_message)
            .setPositiveButton(R.string.send_invite) { _, _ ->
                showInviteOptions(address)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }
    
    private fun showInviteOptions(address: String) {
        val deepLink = "https://mumblechat.io/chat?address=${viewModel.getMyAddress()}"
        val inviteMessage = getString(R.string.invite_message_template, deepLink)
        
        val options = arrayOf(
            getString(R.string.invite_via_whatsapp),
            getString(R.string.invite_via_sms),
            getString(R.string.invite_via_email),
            getString(R.string.invite_via_share)
        )
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_invite_method)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendWhatsAppInvite(inviteMessage)
                    1 -> sendSmsInvite(inviteMessage)
                    2 -> sendEmailInvite(inviteMessage)
                    3 -> sendGenericShare(inviteMessage)
                }
            }
            .show()
    }
    
    private fun sendWhatsAppInvite(message: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, message)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // WhatsApp not installed, use generic share
            sendGenericShare(message)
        }
    }
    
    private fun sendSmsInvite(message: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("sms:")
            putExtra("sms_body", message)
        }
        startActivity(intent)
    }
    
    private fun sendEmailInvite(message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.invite_email_subject))
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.send_invite)))
    }
    
    private fun sendGenericShare(message: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.send_invite)))
    }
    
    private fun hideVerificationStatus() {
        binding.registrationStatusCard.isVisible = false
        binding.buttonStartChat.isEnabled = true
        binding.buttonInvite.isVisible = false
    }

    private fun startChat() {
        val address = binding.editAddress.text.toString().trim()
        val displayName = binding.editDisplayName.text.toString().trim().takeIf { it.isNotEmpty() }
        
        if (address.isEmpty()) {
            binding.inputLayout.error = getString(R.string.enter_wallet_address)
            return
        }

        if (!isValidAddress(address)) {
            binding.inputLayout.error = getString(R.string.invalid_wallet_address)
            return
        }

        binding.inputLayout.error = null
        viewModel.startConversation(address, displayName)
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
                    is NewChatState.AddressVerified -> {
                        // Store verified address to prevent re-verification
                        lastVerifiedAddress = state.address
                        if (state.isRegistered) {
                            showRegisteredStatus(state.onChainDisplayName)
                        } else {
                            showNotRegisteredStatus()
                        }
                    }
                }
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_QR_SCAN && resultCode == RESULT_OK) {
            // QRScannerActivity uses C.EXTRA_QR_CODE key
            val scannedData = data?.getStringExtra(C.EXTRA_QR_CODE) 
                ?: data?.getStringExtra("address") 
                ?: data?.getStringExtra("result")
            
            scannedData?.let { rawData ->
                // Clean the address (remove any prefixes like "ethereum:" or EIP-681 format)
                val cleanAddress = when {
                    rawData.startsWith("ethereum:") -> {
                        // EIP-681 format: ethereum:0x1234...@chainId/...
                        rawData.removePrefix("ethereum:").split("@", "?", "/").first()
                    }
                    rawData.startsWith("0x") && rawData.length >= 42 -> {
                        // Already a plain address, extract just the address part
                        rawData.substring(0, 42)
                    }
                    else -> rawData
                }
                
                if (isValidAddress(cleanAddress)) {
                    // Set as last verified to prevent TextWatcher from re-verifying
                    lastVerifiedAddress = cleanAddress.lowercase()
                    binding.editAddress.setText(cleanAddress)
                    binding.editAddress.setSelection(cleanAddress.length)
                    verifyAddress(cleanAddress)
                } else {
                    Toast.makeText(this, getString(R.string.invalid_wallet_address), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    companion object {
        private const val REQUEST_CODE_QR_SCAN = 1001
    }
}
