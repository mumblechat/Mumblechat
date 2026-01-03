package com.ramapay.app.chat.ui.group

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ramapay.app.R
import com.ramapay.app.chat.data.entity.ContactEntity
import com.ramapay.app.chat.viewmodel.GroupViewModel
import com.ramapay.app.chat.viewmodel.GroupCreationState
import com.ramapay.app.databinding.ActivityNewGroupBinding
import com.ramapay.app.databinding.ItemContactSelectBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Activity for creating a new group chat.
 */
@AndroidEntryPoint
class NewGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewGroupBinding
    private val viewModel: GroupViewModel by viewModels()
    private lateinit var contactAdapter: SelectableContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupContactList()
        setupButtons()
        observeViewModel()
        
        // Load contacts
        viewModel.loadContacts()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.create_group)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupContactList() {
        contactAdapter = SelectableContactAdapter { contact, isSelected ->
            viewModel.toggleMemberSelection(contact.address, isSelected)
            updateCreateButton()
        }

        binding.recyclerContacts.apply {
            layoutManager = LinearLayoutManager(this@NewGroupActivity)
            adapter = contactAdapter
        }
    }

    private fun setupButtons() {
        binding.buttonCreate.setOnClickListener {
            createGroup()
        }
        
        binding.buttonAddMember.setOnClickListener {
            // TODO: Add manual address input
            Toast.makeText(this, getString(R.string.add_member_manually), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCreateButton() {
        val hasName = binding.editGroupName.text?.isNotBlank() == true
        val hasMembers = viewModel.selectedMembers.value.isNotEmpty()
        binding.buttonCreate.isEnabled = hasName && hasMembers
    }

    private fun createGroup() {
        val name = binding.editGroupName.text?.toString()?.trim() ?: ""
        val description = binding.editDescription.text?.toString()?.trim()

        if (name.isBlank()) {
            binding.inputLayoutName.error = getString(R.string.enter_group_name)
            return
        }

        viewModel.createGroup(name, description)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.contacts.collect { contacts ->
                contactAdapter.submitList(contacts)
                binding.textNoContacts.isVisible = contacts.isEmpty()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedMembers.collect { selected ->
                binding.textSelectedCount.text = getString(R.string.members_selected, selected.size)
                binding.textSelectedCount.isVisible = selected.isNotEmpty()
            }
        }

        lifecycleScope.launch {
            viewModel.creationState.collect { state ->
                when (state) {
                    is GroupCreationState.Idle -> {
                        binding.progressBar.isVisible = false
                        binding.buttonCreate.isEnabled = true
                    }
                    is GroupCreationState.Creating -> {
                        binding.progressBar.isVisible = true
                        binding.buttonCreate.isEnabled = false
                    }
                    is GroupCreationState.Success -> {
                        binding.progressBar.isVisible = false
                        Toast.makeText(this@NewGroupActivity, R.string.group_created, Toast.LENGTH_SHORT).show()
                        
                        // Navigate to group chat
                        val intent = Intent(this@NewGroupActivity, GroupChatActivity::class.java).apply {
                            putExtra(GroupChatActivity.EXTRA_GROUP_ID, state.groupId)
                        }
                        startActivity(intent)
                        finish()
                    }
                    is GroupCreationState.Error -> {
                        binding.progressBar.isVisible = false
                        binding.buttonCreate.isEnabled = true
                        Toast.makeText(this@NewGroupActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

/**
 * Adapter for selectable contacts.
 */
class SelectableContactAdapter(
    private val onSelectionChanged: (ContactEntity, Boolean) -> Unit
) : ListAdapter<ContactEntity, SelectableContactAdapter.ViewHolder>(ContactDiffCallback()) {

    private val selectedAddresses = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactSelectBinding.inflate(
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
        private val binding: ItemContactSelectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: ContactEntity) {
            val isSelected = selectedAddresses.contains(contact.address)
            
            binding.textName.text = contact.nickname ?: formatAddress(contact.address)
            binding.textAddress.text = formatAddress(contact.address)
            binding.checkbox.isChecked = isSelected

            binding.root.setOnClickListener {
                val newSelection = !binding.checkbox.isChecked
                binding.checkbox.isChecked = newSelection
                
                if (newSelection) {
                    selectedAddresses.add(contact.address)
                } else {
                    selectedAddresses.remove(contact.address)
                }
                
                onSelectionChanged(contact, newSelection)
            }

            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedAddresses.add(contact.address)
                } else {
                    selectedAddresses.remove(contact.address)
                }
                onSelectionChanged(contact, isChecked)
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
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: ContactEntity, newItem: ContactEntity): Boolean {
            return oldItem == newItem
        }
    }
}
