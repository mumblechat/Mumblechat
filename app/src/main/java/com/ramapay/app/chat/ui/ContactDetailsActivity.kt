package com.ramapay.app.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ramapay.app.R
import com.ramapay.app.chat.blockchain.MumbleChatBlockchainService
import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.entity.ContactEntity
import com.ramapay.app.databinding.ActivityContactDetailsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Activity to display and edit contact details.
 */
@AndroidEntryPoint
class ContactDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactDetailsBinding
    
    @Inject lateinit var contactDao: ContactDao
    @Inject lateinit var blockchainService: MumbleChatBlockchainService
    
    private var contactAddress: String = ""
    private var ownerWallet: String = ""
    private var currentContact: ContactEntity? = null
    
    companion object {
        const val EXTRA_CONTACT_ADDRESS = "contact_address"
        const val EXTRA_OWNER_WALLET = "owner_wallet"
        
        fun start(context: Context, contactAddress: String, ownerWallet: String) {
            val intent = Intent(context, ContactDetailsActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_ADDRESS, contactAddress)
                putExtra(EXTRA_OWNER_WALLET, ownerWallet)
            }
            context.startActivity(intent)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        contactAddress = intent.getStringExtra(EXTRA_CONTACT_ADDRESS) ?: ""
        ownerWallet = intent.getStringExtra(EXTRA_OWNER_WALLET) ?: ""
        
        if (contactAddress.isEmpty()) {
            Toast.makeText(this, "Invalid contact", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupToolbar()
        setupClickListeners()
        loadContactDetails()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun setupClickListeners() {
        // Copy address
        binding.cardAddress.setOnClickListener {
            copyToClipboard(contactAddress)
            Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show()
        }
        
        // Edit nickname
        binding.buttonEditNickname.setOnClickListener {
            showEditNicknameDialog()
        }
        
        // Block/Unblock
        binding.buttonBlockUnblock.setOnClickListener {
            if (currentContact?.isBlocked == true) {
                unblockContact()
            } else {
                blockContact()
            }
        }
        
        // Favorite toggle
        binding.buttonFavorite.setOnClickListener {
            toggleFavorite()
        }
    }
    
    private fun loadContactDetails() {
        lifecycleScope.launch {
            // Load local contact data
            val contact = withContext(Dispatchers.IO) {
                contactDao.getByAddress(ownerWallet, contactAddress)
            }
            currentContact = contact
            
            // Display address
            binding.textAddress.text = contactAddress
            binding.textAddressShort.text = formatAddress(contactAddress)
            
            // Display nickname
            val nickname = contact?.nickname
            binding.textNickname.text = nickname ?: getString(R.string.no_nickname_set)
            
            // Update block button
            updateBlockButton(contact?.isBlocked == true)
            
            // Update favorite button
            updateFavoriteButton(contact?.isFavorite == true)
            
            // Load on-chain display name
            loadOnChainDisplayName()
        }
    }
    
    private fun loadOnChainDisplayName() {
        lifecycleScope.launch {
            try {
                val onChainName = blockchainService.getOnChainDisplayName(contactAddress)
                binding.textOnChainName.text = onChainName ?: getString(R.string.not_set)
                
                // Show "Use this name" button if on-chain name exists and different from nickname
                if (!onChainName.isNullOrBlank() && onChainName != currentContact?.nickname) {
                    binding.buttonUseOnChainName.visibility = android.view.View.VISIBLE
                    binding.buttonUseOnChainName.setOnClickListener {
                        useOnChainNameAsNickname(onChainName)
                    }
                } else {
                    binding.buttonUseOnChainName.visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load on-chain display name")
                binding.textOnChainName.text = getString(R.string.error_loading)
            }
        }
    }
    
    private fun showEditNicknameDialog() {
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(currentContact?.nickname ?: "")
            hint = getString(R.string.enter_nickname)
            setPadding(48, 32, 48, 32)
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_nickname)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val newNickname = editText.text?.toString()?.trim()
                saveNickname(newNickname)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun saveNickname(nickname: String?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val contact = currentContact
                if (contact != null) {
                    contactDao.update(contact.copy(nickname = nickname))
                } else {
                    // Create new contact entry
                    contactDao.insert(ContactEntity(
                        id = contactAddress,
                        ownerWallet = ownerWallet,
                        address = contactAddress,
                        nickname = nickname,
                        sessionPublicKey = null,
                        addedAt = System.currentTimeMillis()
                    ))
                }
            }
            loadContactDetails()
            Toast.makeText(this@ContactDetailsActivity, R.string.nickname_saved, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun useOnChainNameAsNickname(name: String) {
        saveNickname(name)
    }
    
    private fun blockContact() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.block_user)
            .setMessage(R.string.block_confirm_message)
            .setPositiveButton(R.string.block) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        currentContact?.let {
                            contactDao.update(it.copy(isBlocked = true))
                        } ?: run {
                            contactDao.insert(ContactEntity(
                                id = contactAddress,
                                ownerWallet = ownerWallet,
                                address = contactAddress,
                                sessionPublicKey = null,
                                isBlocked = true,
                                addedAt = System.currentTimeMillis()
                            ))
                        }
                    }
                    loadContactDetails()
                    Toast.makeText(this@ContactDetailsActivity, R.string.user_blocked, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun unblockContact() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                currentContact?.let {
                    contactDao.update(it.copy(isBlocked = false))
                }
            }
            loadContactDetails()
            Toast.makeText(this@ContactDetailsActivity, R.string.user_unblocked, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun toggleFavorite() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val isFavorite = currentContact?.isFavorite != true
                currentContact?.let {
                    contactDao.update(it.copy(isFavorite = isFavorite))
                } ?: run {
                    contactDao.insert(ContactEntity(
                        id = contactAddress,
                        ownerWallet = ownerWallet,
                        address = contactAddress,
                        sessionPublicKey = null,
                        isFavorite = isFavorite,
                        addedAt = System.currentTimeMillis()
                    ))
                }
            }
            loadContactDetails()
            val message = if (currentContact?.isFavorite != true) R.string.added_to_favorites else R.string.removed_from_favorites
            Toast.makeText(this@ContactDetailsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateBlockButton(isBlocked: Boolean) {
        if (isBlocked) {
            binding.buttonBlockUnblock.text = getString(R.string.unblock_user)
            binding.buttonBlockUnblock.setIconResource(R.drawable.ic_block)
        } else {
            binding.buttonBlockUnblock.text = getString(R.string.block_user)
            binding.buttonBlockUnblock.setIconResource(R.drawable.ic_block)
        }
    }
    
    private fun updateFavoriteButton(isFavorite: Boolean) {
        if (isFavorite) {
            binding.buttonFavorite.text = getString(R.string.remove_favorite)
        } else {
            binding.buttonFavorite.text = getString(R.string.add_favorite)
        }
    }
    
    private fun formatAddress(address: String): String {
        return if (address.length > 12) {
            "${address.take(6)}...${address.takeLast(4)}"
        } else {
            address
        }
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Address", text)
        clipboard.setPrimaryClip(clip)
    }
}
