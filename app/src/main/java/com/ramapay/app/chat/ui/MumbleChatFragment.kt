package com.ramapay.app.chat.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.ramapay.app.R
import com.ramapay.app.chat.MumbleChatContracts
import com.ramapay.app.chat.network.ConnectionState
import com.ramapay.app.chat.ui.adapter.ConversationListAdapter
import com.ramapay.app.chat.ui.conversation.ConversationActivity
import com.ramapay.app.chat.ui.newchat.NewChatActivity
import com.ramapay.app.chat.viewmodel.ChatViewModel
import com.ramapay.app.chat.viewmodel.RegistrationState
import com.ramapay.app.databinding.FragmentMumblechatBinding
import com.ramapay.app.entity.SignAuthenticationCallback
import com.ramapay.app.entity.WalletType
import com.ramapay.app.service.GasService
import com.ramapay.app.ui.BaseFragment
import com.ramapay.app.ui.widget.entity.ActionSheetCallback
import com.ramapay.app.web3.entity.Address
import com.ramapay.app.web3.entity.Web3Transaction
import com.ramapay.app.widget.ActionSheet
import com.ramapay.app.widget.ActionSheetDialog
import com.ramapay.app.widget.AWalletAlertDialog
import com.ramapay.token.entity.Signable
import com.ramapay.hardware.SignatureFromKey
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigInteger
import javax.inject.Inject

/**
 * Native MumbleChat Fragment with proper blockchain registration.
 * 
 * Features:
 * - Conversation list with unread badges
 * - Group chat list
 * - Registration flow with real smart contract transaction
 * - Connection status indicator
 * - FAB for new chat/group
 */
@AndroidEntryPoint
class MumbleChatFragment : BaseFragment(), 
    MumbleChatRegisterDialog.RegistrationCallback,
    ActionSheetCallback {

    companion object {
        const val REQUEST_CAMERA_ACCESS = 126
        private const val TAG = "MumbleChatFragment"
    }

    private var _binding: FragmentMumblechatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var conversationAdapter: ConversationListAdapter
    
    // Registration dialog
    private var registerDialog: MumbleChatRegisterDialog? = null
    private var confirmationDialog: ActionSheet? = null
    private var resultDialog: AWalletAlertDialog? = null
    
    // For gas settings activity result
    private lateinit var gasSettingsLauncher: ActivityResultLauncher<Intent>
    
    // Track pending transaction
    private var pendingTxData: String? = null
    
    @Inject
    lateinit var injectedGasService: GasService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMumblechatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupSwipeRefresh()
        setupResultLaunchers()
        observeViewModel()

        // Initialize chat
        viewModel.initialize()
    }
    
    private fun setupResultLaunchers() {
        gasSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Gas settings updated, confirmation dialog will handle it
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.chat_label)
        binding.toolbar.inflateMenu(R.menu.menu_chat)
        
        // Set subtitle with wallet address
        viewModel.currentWalletAddress?.let { address ->
            binding.toolbar.subtitle = "Connected • ${formatAddress(address)}"
        }
        
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    openChatSettings()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun formatAddress(address: String): String {
        return if (address.length > 10) {
            "${address.take(4)}...${address.takeLast(4)}"
        } else {
            address
        }
    }
    
    private fun openChatSettings() {
        // Navigate to chat settings activity
        val intent = Intent(requireContext(), ChatSettingsActivity::class.java)
        startActivity(intent)
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationListAdapter(
            onItemClick = { conversation ->
                val intent = Intent(requireContext(), ConversationActivity::class.java).apply {
                    putExtra(ConversationActivity.EXTRA_CONVERSATION_ID, conversation.id)
                    putExtra(ConversationActivity.EXTRA_PEER_ADDRESS, conversation.peerAddress)
                }
                startActivity(intent)
            },
            onItemLongClick = { conversation ->
                showConversationOptions(conversation.id, conversation.isPinned, conversation.isMuted)
                true
            }
        )

        binding.recyclerConversations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = conversationAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupFab() {
        binding.fabNewChat.setOnClickListener {
            // Check if registered first
            if (viewModel.registrationState.value == RegistrationState.Registered) {
                showNewChatOptions()
            } else {
                showRegistrationDialog()
            }
        }
    }
    
    private fun showNewChatOptions() {
        val options = arrayOf(
            getString(R.string.new_chat),
            getString(R.string.create_group)
        )
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.start_conversation)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), NewChatActivity::class.java))
                    1 -> startActivity(Intent(requireContext(), com.ramapay.app.chat.ui.group.NewGroupActivity::class.java))
                }
            }
            .show()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.initialize()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe loading state
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressLoading.isVisible = isLoading && conversationAdapter.itemCount == 0
                        binding.swipeRefresh.isRefreshing = isLoading && conversationAdapter.itemCount > 0
                    }
                }

                // Observe conversations
                launch {
                    viewModel.conversations.collect { conversations ->
                        conversationAdapter.submitList(conversations)
                        binding.emptyState.isVisible = conversations.isEmpty() && !viewModel.isLoading.value
                    }
                }

                // Observe connection state
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionStatus(state)
                    }
                }

                // Observe registration state
                launch {
                    viewModel.registrationState.collect { state ->
                        handleRegistrationState(state)
                    }
                }
                
                // Observe transaction results
                launch {
                    viewModel.transactionResult.collect { result ->
                        if (result != null) {
                            handleTransactionSuccess(result)
                        }
                    }
                }
                
                // Observe transaction errors
                launch {
                    viewModel.transactionError.collect { error ->
                        if (error != null) {
                            handleTransactionError(error)
                        }
                    }
                }
            }
        }
    }
    
    private fun handleTransactionSuccess(result: com.ramapay.app.entity.TransactionReturn) {
        Timber.d("$TAG: Transaction successful: ${result.hash}")
        resultDialog?.dismiss()
        confirmationDialog?.dismiss()
        registerDialog?.dismiss()
        
        // Show success with full transaction hash
        val txHash = result.hash ?: "Unknown"
        val shortHash = if (txHash.length > 20) "${txHash.take(10)}...${txHash.takeLast(8)}" else txHash
        
        // Complete registration
        viewModel.completeRegistration()
        
        showSuccessWithTxHash(
            "Registration Successful!",
            "Your MumbleChat identity is now active on the Ramestta blockchain.",
            txHash
        )
        viewModel.clearTransactionResult()
    }
    
    private fun showSuccessWithTxHash(title: String, message: String, txHash: String) {
        val dialog = AWalletAlertDialog(requireActivity())
        dialog.setTitle(title)
        dialog.setMessage("$message\n\nTransaction Hash:\n$txHash")
        dialog.setIcon(AWalletAlertDialog.SUCCESS)
        dialog.setButtonText(R.string.ok)
        dialog.setButtonListener { dialog.dismiss() }
        dialog.setSecondaryButtonText(R.string.copy)
        dialog.setSecondaryButtonListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Transaction Hash", txHash)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Transaction hash copied", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }
    
    private fun handleTransactionError(error: com.ramapay.app.entity.TransactionReturn) {
        Timber.e("$TAG: Transaction failed: ${error.throwable?.message}")
        resultDialog?.dismiss()
        
        val errorMessage = error.throwable?.message ?: "Unknown error occurred"
        
        // Check for nonce-related errors
        when {
            errorMessage.contains("nonce too low", ignoreCase = true) -> {
                // Nonce issue - might already be registered or have pending tx
                handleNonceTooLowError()
            }
            errorMessage.contains("already known", ignoreCase = true) || 
            errorMessage.contains("pending", ignoreCase = true) -> {
                // Transaction already in mempool - show with clear option
                showNonceErrorWithClearOption(
                    "Transaction Pending",
                    "There may be a pending transaction for this wallet.\n\nPlease wait 1-2 minutes and try again. If the problem persists, tap 'Clear Pending' to reset."
                )
            }
            errorMessage.contains("insufficient funds", ignoreCase = true) -> {
                showError("Insufficient RAMA", 
                    "You need RAMA tokens to pay for gas. Please add RAMA to your wallet and try again.")
            }
            else -> {
                showError("Transaction Failed", errorMessage)
            }
        }
        
        viewModel.clearTransactionResult()
    }
    
    private fun handleNonceTooLowError() {
        val walletAddress = viewModel.currentWalletAddress ?: return
        
        // Dismiss all dialogs
        confirmationDialog?.dismiss()
        registerDialog?.dismiss()
        
        viewLifecycleOwner.lifecycleScope.launch {
            // Force check from blockchain (bypass cache)
            val isRegistered = viewModel.forceCheckRegistration(walletAddress)
            
            if (isRegistered) {
                // User is already registered!
                val dialog = AWalletAlertDialog(requireActivity())
                dialog.setTitle("Already Registered!")
                dialog.setMessage("Your wallet is already registered for MumbleChat. You can start chatting now!")
                dialog.setIcon(AWalletAlertDialog.SUCCESS)
                dialog.setButtonText(R.string.ok)
                dialog.setButtonListener { 
                    dialog.dismiss()
                    viewModel.completeRegistration()
                }
                dialog.show()
            } else {
                // Not registered but nonce error - show option to clear pending transactions
                showNonceErrorWithClearOption()
            }
        }
    }
    
    private fun showNonceErrorWithClearOption(
        title: String = "Transaction Nonce Error",
        message: String = "There may be pending transactions blocking new ones.\n\nTap 'Clear Pending' to reset and try again."
    ) {
        val dialog = AWalletAlertDialog(requireActivity())
        dialog.setTitle(title)
        dialog.setMessage(message)
        dialog.setIcon(AWalletAlertDialog.WARNING)
        dialog.setButtonText(R.string.ok)
        dialog.setButtonListener { dialog.dismiss() }
        dialog.setSecondaryButtonText(R.string.action_clear)
        dialog.setSecondaryButtonListener {
            dialog.dismiss()
            clearPendingTransactionsAndRetry()
        }
        dialog.show()
    }
    
    private fun clearPendingTransactionsAndRetry() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show progress
                val progressDialog = AWalletAlertDialog(requireActivity())
                progressDialog.setTitle("Clearing Pending Transactions")
                progressDialog.setMessage("Please wait...")
                progressDialog.setProgressMode()
                progressDialog.setCancelable(false)
                progressDialog.show()
                
                // Clear nonce cache and pending transactions
                viewModel.clearPendingTransactions()
                
                // Wait a moment for network to sync
                delay(2000)
                
                progressDialog.dismiss()
                
                // Show success and offer to retry
                val successDialog = AWalletAlertDialog(requireActivity())
                successDialog.setTitle("Cleared!")
                successDialog.setMessage("Pending transactions cleared. You can now try registering again.")
                successDialog.setIcon(AWalletAlertDialog.SUCCESS)
                successDialog.setButtonText("Register Now")
                successDialog.setButtonListener {
                    successDialog.dismiss()
                    // Retry registration
                    showRegistrationDialog()
                }
                successDialog.setSecondaryButtonText(R.string.cancel)
                successDialog.setSecondaryButtonListener { successDialog.dismiss() }
                successDialog.show()
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear pending transactions")
                Toast.makeText(requireContext(), "Failed to clear: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        // Update connection dot color in menu
        val menu = binding.toolbar.menu
        val connectionItem = menu.findItem(R.id.action_connection_status)
        
        when (state) {
            ConnectionState.CONNECTED -> {
                connectionItem?.setIcon(R.drawable.ic_connection_dot)
                connectionItem?.icon?.setTint(resources.getColor(R.color.success, null))
                viewModel.currentWalletAddress?.let { address ->
                    binding.toolbar.subtitle = "Connected • ${formatAddress(address)}"
                }
            }
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                connectionItem?.setIcon(R.drawable.ic_connection_dot)
                connectionItem?.icon?.setTint(resources.getColor(R.color.warning, null))
                binding.toolbar.subtitle = "Connecting..."
            }
            ConnectionState.DISCONNECTED -> {
                connectionItem?.setIcon(R.drawable.ic_connection_dot)
                connectionItem?.icon?.setTint(resources.getColor(R.color.error, null))
                binding.toolbar.subtitle = "Disconnected"
            }
            ConnectionState.ERROR -> {
                connectionItem?.setIcon(R.drawable.ic_connection_dot)
                connectionItem?.icon?.setTint(resources.getColor(R.color.error, null))
                binding.toolbar.subtitle = "Connection Error"
            }
        }
    }

    private fun handleRegistrationState(state: RegistrationState) {
        when (state) {
            is RegistrationState.Unknown -> {
                // Still checking
            }
            is RegistrationState.NotRegistered -> {
                // Show registration dialog after a short delay
                binding.root.postDelayed({
                    if (isAdded && registerDialog == null) {
                        showRegistrationDialog()
                    }
                }, 500)
            }
            is RegistrationState.Registering -> {
                // Show loading in dialog
                registerDialog?.showLoading(getString(R.string.awaiting_confirmation))
            }
            is RegistrationState.Registered -> {
                registerDialog?.dismiss()
                registerDialog = null
                binding.registrationOverlay.isVisible = false
            }
            is RegistrationState.Error -> {
                registerDialog?.hideLoading()
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRegistrationDialog() {
        if (!isAdded || registerDialog?.isVisible == true) return
        
        // Check if already registered
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val address = viewModel.currentWalletAddress
                if (address.isNullOrEmpty()) {
                    showError(getString(R.string.title_dialog_error), "No wallet address found")
                    return@launch
                }
                
                // Force check blockchain registration status (bypass cache)
                // This prevents "nonce too low" errors when switching wallets
                val isRegistered = viewModel.forceCheckRegistration(address)
                if (isRegistered) {
                    // Already registered, just complete initialization
                    Timber.d("$TAG: User already registered on blockchain")
                    Toast.makeText(requireContext(), "You are already registered for MumbleChat", Toast.LENGTH_LONG).show()
                    viewModel.completeRegistration()
                    return@launch
                }
                
                registerDialog = MumbleChatRegisterDialog.newInstance()
                registerDialog?.show(childFragmentManager, MumbleChatRegisterDialog.TAG)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error checking registration")
                showError(getString(R.string.title_dialog_error), e.message ?: "Unknown error")
            }
        }
    }

    // ============ Registration Dialog Callbacks ============
    
    override fun onRegisterClicked() {
        Timber.d("$TAG: Register clicked, preparing transaction")
        registerDialog?.showLoading(getString(R.string.sign_registration_tx))
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get registration transaction data from ChatService
                val result = viewModel.prepareRegistrationTransaction()
                
                result.fold(
                    onSuccess = { txData ->
                        pendingTxData = txData
                        showTransactionConfirmation(txData)
                    },
                    onFailure = { error ->
                        Timber.e(error, "$TAG: Failed to prepare registration")
                        registerDialog?.hideLoading()
                        showError(getString(R.string.registration_failed), error.message ?: "Unknown error")
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Exception during registration")
                registerDialog?.hideLoading()
                showError(getString(R.string.registration_failed), e.message ?: "Unknown error")
            }
        }
    }
    
    override fun onLaterClicked() {
        registerDialog?.dismiss()
        registerDialog = null
    }
    
    private fun showTransactionConfirmation(txData: String) {
        val activity = activity ?: return
        
        // Get the native token for gas payment
        val chainId = MumbleChatContracts.CHAIN_ID
        val token = viewModel.getNativeToken(chainId)
        
        if (token == null) {
            registerDialog?.hideLoading()
            showError(getString(R.string.registration_failed), "Cannot find RAMA token for gas payment")
            return
        }
        
        // Create Web3Transaction for the registration call
        val w3tx = Web3Transaction(
            Address(MumbleChatContracts.REGISTRY_PROXY),  // recipient (contract)
            null,                                           // contract (null for direct call)
            Address(viewModel.currentWalletAddress ?: ""), // sender
            BigInteger.ZERO,                                // value (no RAMA needed)
            BigInteger.ZERO,                                // gasPrice (will be estimated)
            BigInteger.valueOf(250000),                     // gasLimit estimate (registration needs ~213k)
            -1,                                              // nonce
            txData,                                          // payload (encoded function call)
            -1                                               // leaf position
        )
        
        // Hide registration dialog before showing confirmation
        registerDialog?.dismiss()
        
        // Show the standard transaction confirmation dialog
        confirmationDialog = ActionSheetDialog(
            activity,
            w3tx,
            token,
            "MumbleChat Registry",
            MumbleChatContracts.REGISTRY_PROXY,
            viewModel.getTokenService(),
            this
        )
        confirmationDialog?.setCanceledOnTouchOutside(false)
        confirmationDialog?.show()
    }
    
    private fun showError(title: String, message: String) {
        if (!isAdded) return
        
        resultDialog?.dismiss()
        resultDialog = AWalletAlertDialog(requireContext())
        resultDialog?.apply {
            setTitle(title)
            setMessage(message)
            setIcon(AWalletAlertDialog.ERROR)
            setButtonText(R.string.ok)
            setButtonListener { dismiss() }
            show()
        }
    }
    
    private fun showSuccess(message: String) {
        if (!isAdded) return
        
        resultDialog?.dismiss()
        resultDialog = AWalletAlertDialog(requireContext())
        resultDialog?.apply {
            setTitle(getString(R.string.registration_success))
            setMessage(message)
            setIcon(AWalletAlertDialog.SUCCESS)
            setButtonText(R.string.ok)
            setButtonListener { 
                dismiss()
                // Complete registration after tx confirmed
                viewModel.completeRegistration()
            }
            show()
        }
    }

    private fun showTransactionPending() {
        if (!isAdded) return
        
        resultDialog?.dismiss()
        resultDialog = AWalletAlertDialog(requireContext())
        resultDialog?.apply {
            setTitle("Transaction Pending")
            setMessage("Waiting for blockchain confirmation...")
            setIcon(AWalletAlertDialog.NONE)
            // No button - will dismiss when confirmed or timeout
            show()
        }
    }

    private fun showConversationOptions(conversationId: String, isPinned: Boolean, isMuted: Boolean) {
        val options = arrayOf(
            if (isPinned) getString(R.string.unpin) else getString(R.string.pin),
            if (isMuted) getString(R.string.unmute) else getString(R.string.mute),
            getString(R.string.delete)
        )

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.togglePin(conversationId, isPinned)
                    1 -> viewModel.toggleMute(conversationId, isMuted)
                    2 -> confirmDeleteConversation(conversationId)
                }
            }
            .show()
    }

    private fun confirmDeleteConversation(conversationId: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_conversation)
            .setMessage(R.string.delete_conversation_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteConversation(conversationId)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    // ============ ActionSheetCallback Implementation ============
    
    override fun getAuthorisation(callback: SignAuthenticationCallback) {
        viewModel.getAuthorisation(activity, callback)
    }
    
    override fun sendTransaction(finalTx: Web3Transaction) {
        // Actually send the transaction to the blockchain via ViewModel
        Timber.d("$TAG: Sending registration transaction to blockchain...")
        
        // Show loading state
        showTransactionPending()
        
        // Clear any previous results
        viewModel.clearTransactionResult()
        
        // Request signature and send transaction
        viewModel.requestSignature(finalTx)
    }
    
    override fun completeSendTransaction(tx: Web3Transaction, signature: SignatureFromKey) {
        // For hardware wallet - send transaction with the signature
        viewModel.sendTransaction(tx, signature)
    }
    
    override fun dismissed(txHash: String?, callbackId: Long, actionCompleted: Boolean) {
        confirmationDialog = null
        if (!actionCompleted) {
            // User cancelled, show registration dialog again
            showRegistrationDialog()
        }
    }
    
    override fun notifyConfirm(mode: String?) {
        // Confirmation started
    }
    
    override fun signTransaction(tx: Web3Transaction) {
        // For hardware wallets
    }
    
    override fun gasSelectLauncher(): ActivityResultLauncher<Intent> = gasSettingsLauncher
    
    override fun getWalletType(): WalletType = WalletType.KEYSTORE
    
    override fun getGasService(): GasService = injectedGasService
    
    override fun signingComplete(signature: SignatureFromKey, message: Signable) {
        // Handle signing completion
    }
    
    override fun signingFailed(error: Throwable, message: Signable) {
        showError("Signing Failed", error.message ?: "Unknown error")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        confirmationDialog?.dismiss()
        resultDialog?.dismiss()
        _binding = null
    }
}
