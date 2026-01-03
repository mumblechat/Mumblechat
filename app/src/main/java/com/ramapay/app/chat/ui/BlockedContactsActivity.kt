package com.ramapay.app.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ramapay.app.R
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.entity.ContactEntity
import com.ramapay.app.databinding.ActivityBlockedContactsBinding
import com.ramapay.app.databinding.ItemBlockedContactBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity to display and manage blocked contacts.
 */
@AndroidEntryPoint
class BlockedContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedContactsBinding
    private lateinit var adapter: BlockedContactsAdapter
    
    @Inject
    lateinit var contactDao: ContactDao
    
    @Inject
    lateinit var walletBridge: WalletBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadBlockedContacts()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.blocked_users)
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = BlockedContactsAdapter(
            onUnblockClick = { contact ->
                showUnblockConfirmation(contact)
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadBlockedContacts() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            showEmptyState()
            return
        }

        lifecycleScope.launch {
            contactDao.getBlockedContacts(walletAddress).collectLatest { contacts ->
                if (contacts.isEmpty()) {
                    showEmptyState()
                } else {
                    binding.recyclerView.isVisible = true
                    binding.emptyState.isVisible = false
                    adapter.submitList(contacts)
                }
            }
        }
    }

    private fun showEmptyState() {
        binding.recyclerView.isVisible = false
        binding.emptyState.isVisible = true
    }

    private fun showUnblockConfirmation(contact: ContactEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unblock_user)
            .setMessage(R.string.unblock_confirm_message)
            .setPositiveButton(R.string.unblock_user) { _, _ ->
                unblockContact(contact)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unblockContact(contact: ContactEntity) {
        lifecycleScope.launch {
            contactDao.setBlocked(contact.id, false)
            Toast.makeText(
                this@BlockedContactsActivity,
                R.string.user_unblocked,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * Adapter for blocked contacts list.
 */
class BlockedContactsAdapter(
    private val onUnblockClick: (ContactEntity) -> Unit
) : ListAdapter<ContactEntity, BlockedContactsAdapter.ViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockedContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemBlockedContactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: ContactEntity) {
            // Display name or address
            binding.textName.text = contact.nickname ?: formatAddress(contact.address)
            binding.textAddress.text = contact.address
            
            // Avatar
            val avatarText = contact.address.removePrefix("0x").take(2).uppercase()
            binding.textAvatar.text = avatarText
            
            // Unblock button
            binding.buttonUnblock.setOnClickListener {
                onUnblockClick(contact)
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

    class ContactDiffCallback : DiffUtil.ItemCallback<ContactEntity>() {
        override fun areItemsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
            return oldItem == newItem
        }
    }
}
