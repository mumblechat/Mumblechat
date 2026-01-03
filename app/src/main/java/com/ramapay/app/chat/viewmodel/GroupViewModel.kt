package com.ramapay.app.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.entity.ContactEntity
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State for group creation.
 */
sealed class GroupCreationState {
    object Idle : GroupCreationState()
    object Creating : GroupCreationState()
    data class Success(val groupId: String) : GroupCreationState()
    data class Error(val message: String) : GroupCreationState()
}

/**
 * ViewModel for group operations (create, list).
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val walletBridge: WalletBridge,
    private val groupRepository: GroupRepository,
    private val contactDao: ContactDao
) : ViewModel() {

    val currentWalletAddress: String
        get() = walletBridge.getCurrentWalletAddress() ?: ""

    private val _groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    val groups: StateFlow<List<GroupEntity>> = _groups

    private val _contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val contacts: StateFlow<List<ContactEntity>> = _contacts

    private val _selectedMembers = MutableStateFlow<Set<String>>(emptySet())
    val selectedMembers: StateFlow<Set<String>> = _selectedMembers

    private val _creationState = MutableStateFlow<GroupCreationState>(GroupCreationState.Idle)
    val creationState: StateFlow<GroupCreationState> = _creationState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * Load groups for current wallet.
     */
    fun loadGroups() {
        val wallet = walletBridge.getCurrentWalletAddress() ?: return
        
        viewModelScope.launch {
            groupRepository.getGroups(wallet).collect { groupList ->
                _groups.value = groupList
            }
        }
    }

    /**
     * Load contacts for member selection.
     */
    fun loadContacts() {
        val wallet = walletBridge.getCurrentWalletAddress() ?: return
        
        viewModelScope.launch {
            contactDao.getAllForWallet(wallet).collect { contactList ->
                // Exclude blocked contacts
                _contacts.value = contactList.filter { !it.isBlocked }
            }
        }
    }

    /**
     * Toggle member selection.
     */
    fun toggleMemberSelection(address: String, isSelected: Boolean) {
        val current = _selectedMembers.value.toMutableSet()
        if (isSelected) {
            current.add(address)
        } else {
            current.remove(address)
        }
        _selectedMembers.value = current
    }

    /**
     * Create a new group.
     */
    fun createGroup(name: String, description: String?) {
        val wallet = walletBridge.getCurrentWalletAddress()
        if (wallet == null) {
            _creationState.value = GroupCreationState.Error("No wallet connected")
            return
        }

        val members = _selectedMembers.value.toMutableList()
        // Always include self
        if (!members.contains(wallet)) {
            members.add(0, wallet)
        }

        if (members.size < 2) {
            _creationState.value = GroupCreationState.Error("Select at least one member")
            return
        }

        viewModelScope.launch {
            _creationState.value = GroupCreationState.Creating
            
            try {
                val group = groupRepository.createGroup(
                    walletAddress = wallet,
                    name = name,
                    description = description,
                    memberAddresses = members
                )
                
                _creationState.value = GroupCreationState.Success(group.id)
                
                // Reset selection
                _selectedMembers.value = emptySet()
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to create group")
                _creationState.value = GroupCreationState.Error(e.message ?: "Failed to create group")
            }
        }
    }

    /**
     * Delete a group (owner only).
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            try {
                groupRepository.deleteGroup(groupId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete group")
            }
        }
    }

    /**
     * Reset creation state.
     */
    fun resetCreationState() {
        _creationState.value = GroupCreationState.Idle
    }
}
