package com.ramapay.app.chat.ui.group

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ramapay.app.R
import com.ramapay.app.chat.data.entity.GroupMemberEntity
import com.ramapay.app.chat.data.entity.GroupRole
import com.ramapay.app.chat.viewmodel.GroupInfoViewModel
import com.ramapay.app.databinding.ActivityGroupInfoBinding
import com.ramapay.app.databinding.ItemGroupMemberBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Activity for viewing and editing group information.
 */
@AndroidEntryPoint
class GroupInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        
        fun createIntent(context: Context, groupId: String): Intent {
            return Intent(context, GroupInfoActivity::class.java).apply {
                putExtra(EXTRA_GROUP_ID, groupId)
            }
        }
    }

    private lateinit var binding: ActivityGroupInfoBinding
    private val viewModel: GroupInfoViewModel by viewModels()
    private lateinit var memberAdapter: GroupMemberAdapter
    private var groupId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: run {
            finish()
            return
        }

        setupToolbar()
        setupMemberList()
        setupButtons()
        
        viewModel.loadGroup(groupId)
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.group_info)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (viewModel.isAdmin.value) {
            menuInflater.inflate(R.menu.menu_group_info_admin, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit_group -> {
                showEditGroupDialog()
                true
            }
            R.id.action_delete_group -> {
                showDeleteGroupConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupMemberList() {
        memberAdapter = GroupMemberAdapter(
            currentWallet = viewModel.currentWalletAddress,
            isAdmin = { viewModel.isAdmin.value },
            onMemberClick = { member ->
                showMemberOptions(member)
            }
        )

        binding.recyclerMembers.apply {
            layoutManager = LinearLayoutManager(this@GroupInfoActivity)
            adapter = memberAdapter
        }
    }

    private fun setupButtons() {
        binding.buttonAddMember.setOnClickListener {
            // TODO: Open add member dialog
            Toast.makeText(this, "Add member coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.buttonLeaveGroup.setOnClickListener {
            showLeaveConfirmation()
        }

        binding.buttonMuteGroup.setOnClickListener {
            viewModel.toggleMute()
        }
    }

    private fun showMemberOptions(member: GroupMemberEntity) {
        if (!viewModel.isAdmin.value || member.memberAddress == viewModel.currentWalletAddress) {
            return
        }

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (member.role == GroupRole.MEMBER) {
            options.add(getString(R.string.make_admin))
            actions.add { viewModel.makeMemberAdmin(member.memberAddress) }
        } else if (member.role == GroupRole.ADMIN) {
            options.add(getString(R.string.remove_admin))
            actions.add { viewModel.removeMemberAdmin(member.memberAddress) }
        }

        options.add(getString(R.string.remove_from_group))
        actions.add { showRemoveMemberConfirmation(member) }

        MaterialAlertDialogBuilder(this)
            .setTitle(member.displayName ?: formatAddress(member.memberAddress))
            .setItems(options.toTypedArray()) { _, which ->
                actions[which]()
            }
            .show()
    }

    private fun showRemoveMemberConfirmation(member: GroupMemberEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_member)
            .setMessage(getString(R.string.remove_member_confirm, 
                member.displayName ?: formatAddress(member.memberAddress)))
            .setPositiveButton(R.string.remove) { _, _ ->
                viewModel.removeMember(member.memberAddress)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun showEditGroupDialog() {
        val group = viewModel.group.value ?: return
        
        val editText = android.widget.EditText(this).apply {
            setText(group.name)
            hint = getString(R.string.group_name)
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_group_name)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    viewModel.updateGroupName(newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteGroupConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_group)
            .setMessage(R.string.delete_group_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteGroup()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatAddress(address: String): String {
        return if (address.length > 10) {
            "${address.take(6)}...${address.takeLast(4)}"
        } else {
            address
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.group.collect { group ->
                group?.let {
                    binding.textGroupName.text = it.name
                    binding.textDescription.text = it.description ?: getString(R.string.no_description)
                    binding.textCreatedBy.text = getString(R.string.created_by, 
                        formatAddress(it.createdBy))
                    
                    binding.buttonMuteGroup.text = if (it.isMuted) {
                        getString(R.string.unmute)
                    } else {
                        getString(R.string.mute)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.members.collect { members ->
                memberAdapter.submitList(members)
                binding.textMembersCount.text = getString(R.string.members_count, members.size)
            }
        }

        lifecycleScope.launch {
            viewModel.isAdmin.collect { isAdmin ->
                binding.buttonAddMember.isVisible = isAdmin
                invalidateOptionsMenu()
            }
        }

        lifecycleScope.launch {
            viewModel.leftGroup.collect { left ->
                if (left) {
                    Toast.makeText(this@GroupInfoActivity, R.string.left_group, Toast.LENGTH_SHORT).show()
                    // Finish both this and the chat activity
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.groupDeleted.collect { deleted ->
                if (deleted) {
                    Toast.makeText(this@GroupInfoActivity, R.string.group_deleted, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }
}

/**
 * Adapter for group members.
 */
class GroupMemberAdapter(
    private val currentWallet: String,
    private val isAdmin: () -> Boolean,
    private val onMemberClick: (GroupMemberEntity) -> Unit
) : ListAdapter<GroupMemberEntity, GroupMemberAdapter.ViewHolder>(MemberDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupMemberBinding.inflate(
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
        private val binding: ItemGroupMemberBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(member: GroupMemberEntity) {
            binding.textName.text = member.displayName ?: formatAddress(member.memberAddress)
            binding.textAddress.text = formatAddress(member.memberAddress)
            
            binding.textRole.text = when (member.role) {
                GroupRole.OWNER -> binding.root.context.getString(R.string.role_owner)
                GroupRole.ADMIN -> binding.root.context.getString(R.string.role_admin)
                GroupRole.MEMBER -> binding.root.context.getString(R.string.role_member)
            }
            
            binding.textRole.isVisible = member.role != GroupRole.MEMBER
            
            // Show "You" indicator
            if (member.memberAddress.equals(currentWallet, ignoreCase = true)) {
                binding.textYou.isVisible = true
            } else {
                binding.textYou.isVisible = false
            }

            // Only allow clicking if current user is admin
            if (isAdmin() && member.memberAddress != currentWallet) {
                binding.root.setOnClickListener { onMemberClick(member) }
            } else {
                binding.root.setOnClickListener(null)
                binding.root.isClickable = false
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

    class MemberDiffCallback : DiffUtil.ItemCallback<GroupMemberEntity>() {
        override fun areItemsTheSame(oldItem: GroupMemberEntity, newItem: GroupMemberEntity): Boolean {
            return oldItem.memberAddress == newItem.memberAddress && oldItem.groupId == newItem.groupId
        }

        override fun areContentsTheSame(oldItem: GroupMemberEntity, newItem: GroupMemberEntity): Boolean {
            return oldItem == newItem
        }
    }
}
