package com.ramapay.app.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ramapay.app.R
import com.ramapay.app.chat.viewmodel.ProfileViewModel
import com.ramapay.app.databinding.ActivityProfileBinding
import com.ramapay.app.entity.SignAuthenticationCallback
import com.ramapay.app.entity.WalletType
import com.ramapay.app.service.GasService
import com.ramapay.app.ui.widget.entity.ActionSheetCallback
import com.ramapay.app.web3.entity.Web3Transaction
import com.ramapay.app.widget.AWalletAlertDialog
import com.ramapay.hardware.SignatureFromKey
import com.ramapay.token.entity.Signable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Profile activity showing user's MumbleChat registration info.
 */
@AndroidEntryPoint
class ProfileActivity : AppCompatActivity(), ActionSheetCallback {

    private lateinit var binding: ActivityProfileBinding
    private val viewModel: ProfileViewModel by viewModels()
    
    @Inject
    lateinit var _gasService: GasService
    
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViews()
        observeViewModel()
        
        viewModel.loadProfile()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.my_profile)
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupViews() {
        // Edit display name
        binding.btnEditName.setOnClickListener {
            showEditNameDialog()
        }
        
        // Copy wallet address
        binding.textWalletAddress.setOnClickListener {
            copyToClipboard(binding.textWalletAddress.text.toString(), "Wallet address")
        }
        
        // Copy connected wallet (full address)
        binding.iconCopyWallet.setOnClickListener {
            viewModel.profileData.value?.walletAddress?.let {
                copyToClipboard(it, "Wallet address")
            }
        }
        
        binding.textConnectedWallet.setOnClickListener {
            viewModel.profileData.value?.walletAddress?.let {
                copyToClipboard(it, "Wallet address")
            }
        }
        
        // Copy public key
        binding.textPublicKey.setOnClickListener {
            val fullKey = viewModel.profileData.value?.publicKey
            if (fullKey != null) {
                copyToClipboard(fullKey, "Public key")
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.profileData.collect { data ->
                if (data != null) {
                    updateUI(data)
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        
        lifecycleScope.launch {
            viewModel.updateSuccess.collect { success ->
                if (success) {
                    Toast.makeText(this@ProfileActivity, "Display name updated successfully!", Toast.LENGTH_SHORT).show()
                    viewModel.loadProfile() // Refresh
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.error.collect { error ->
                if (error != null) {
                    Toast.makeText(this@ProfileActivity, "Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUI(data: ProfileViewModel.ProfileData) {
        // Connected wallet (prominent display)
        binding.textConnectedWallet.text = shortenAddress(data.walletAddress)
        
        // Display name
        binding.textDisplayName.text = data.displayName.ifEmpty { "Not set" }
        
        // Wallet address (shortened)
        binding.textWalletAddress.text = shortenAddress(data.walletAddress)
        
        // Public key (shortened)
        binding.textPublicKey.text = data.publicKey?.let { shortenAddress(it) } ?: "Loading..."
        
        // Registration date
        val date = Date(data.registeredAt * 1000)
        binding.textRegistrationDate.text = dateFormat.format(date)
        
        // Last updated
        val lastUpdated = Date(data.lastUpdated * 1000)
        binding.textLastUpdated.text = dateFormat.format(lastUpdated)
        
        // Registration status
        binding.textRegistrationStatus.text = if (data.isActive) "✓ Active" else "Inactive"
        binding.textRegistrationStatus.setTextColor(
            if (data.isActive) getColor(R.color.green) else getColor(R.color.error)
        )
    }

    private fun showEditNameDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter display name"
        input.setText(viewModel.profileData.value?.displayName ?: "")
        input.setPadding(50, 20, 50, 20)
        
        val dialog = AWalletAlertDialog(this)
        dialog.setTitle("Update Display Name")
        dialog.setMessage("This will be stored on the blockchain and visible to other users.")
        dialog.setView(input)
        dialog.setButtonText(R.string.update)
        dialog.setSecondaryButtonText(R.string.cancel)
        
        dialog.setButtonListener {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            } else if (newName.length > 50) {
                Toast.makeText(this, "Name too long (max 50 characters)", Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                viewModel.updateDisplayName(newName)
            }
        }
        
        dialog.setSecondaryButtonListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shortenAddress(address: String): String {
        return if (address.length > 16) {
            "${address.substring(0, 8)}...${address.substring(address.length - 6)}"
        } else {
            address
        }
    }
    
    // ============ ActionSheetCallback Implementation ============

    override fun getAuthorisation(callback: SignAuthenticationCallback) {
        viewModel.getAuthorisation(this, callback)
    }

    override fun sendTransaction(finalTx: Web3Transaction) {
        viewModel.sendTransaction(finalTx)
    }

    override fun completeSendTransaction(tx: Web3Transaction, signature: SignatureFromKey) {
        // For hardware wallet
    }

    override fun dismissed(txHash: String?, callbackId: Long, actionCompleted: Boolean) {
        // Actionsheet dismissed
    }

    override fun notifyConfirm(mode: String) {
        // Confirm notification
    }

    override fun gasSelectLauncher(): ActivityResultLauncher<Intent>? = null

    override fun getWalletType(): WalletType = WalletType.KEYSTORE

    override fun getGasService(): GasService = _gasService

    override fun signingComplete(signature: SignatureFromKey, message: Signable) {
        // Signing complete
    }

    override fun signingFailed(error: Throwable, message: Signable) {
        Toast.makeText(this, "Signing failed: ${error.message}", Toast.LENGTH_LONG).show()
    }
}
