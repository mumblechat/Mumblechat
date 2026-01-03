package com.ramapay.app.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.entity.GroupMemberEntity
import com.ramapay.app.chat.data.entity.GroupRole
import com.ramapay.app.chat.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for group info screen.
 */
@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    private val walletBridge: WalletBridge,
    private val groupRepository: GroupRepository
) : ViewModel() {

    private var groupId: String? = null

    val currentWalletAddress: String
        get() = walletBridge.getCurrentWalletAddress() ?: ""

    private val _group = MutableStateFlow<GroupEntity?>(null)
    val group: StateFlow<GroupEntity?> = _group

    private val _members = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    val members: StateFlow<List<GroupMemberEntity>> = _members

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin

    private val _leftGroup = MutableStateFlow(false)
    val leftGroup: StateFlow<Boolean> = _leftGroup

    private val _groupDeleted = MutableStateFlow(false)
    val groupDeleted: StateFlow<Boolean> = _groupDeleted

    /**
     * Load group information.
     */
    fun loadGroup(id: String) {
        groupId = id
        val wallet = walletBridge.getCurrentWalletAddress() ?: return

        viewModelScope.launch {
            // Load group
            groupRepository.getById(id)?.let { g ->
                _group.value = g
                _isAdmin.value = g.myRole == GroupRole.OWNER || g.myRole == GroupRole.ADMIN
            }

            // Load members
            groupRepository.getMembersFlow(id).collect { memberList ->
                // Sort: Owner first, then admins, then members
                _members.value = memberList.sortedWith(
                    compareBy<GroupMemberEntity> { 
                        when (it.role) {
                            GroupRole.OWNER -> 0
                            GroupRole.ADMIN -> 1
                            GroupRole.MEMBER -> 2
                        }
                    }.thenBy { it.joinedAt }
                )
            }
        }
    }

    /**
     * Update group name.
     */
    fun updateGroupName(name: String) {
        val gId = groupId ?: return

        viewModelScope.launch {
            try {
                // Would need to add this method to GroupRepository/Dao
                // groupRepository.updateName(gId, name)
                _group.value = _group.value?.copy(name = name)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update group name")
            }
        }
    }

    /**
     * Toggle group mute status.
     */
    fun toggleMute() {
        val current = _group.value ?: return
        _group.value = current.copy(isMuted = !current.isMuted)
        // TODO: Persist to database
    }

    /**
     * Make a member an admin.
     */
    fun makeMemberAdmin(memberAddress: String) {
        val gId = groupId ?: return

        viewModelScope.launch {
            try {
                groupRepository.updateMemberRole(gId, memberAddress, GroupRole.ADMIN)
            } catch (e: Exception) {
                Timber.e(e, "Failed to make member admin")
            }
        }
    }

    /**
     * Remove admin role from a member.
     */
    fun removeMemberAdmin(memberAddress: String) {
        val gId = groupId ?: return

        viewModelScope.launch {
            try {
                groupRepository.updateMemberRole(gId, memberAddress, GroupRole.MEMBER)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove admin role")
            }
        }
    }

    /**
     * Remove a member from the group.
     */
    fun removeMember(memberAddress: String) {
        val gId = groupId ?: return

        viewModelScope.launch {
            try {
                groupRepository.removeMember(gId, memberAddress)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove member")
            }
        }
    }

    /**
     * Leave the group.
     */
    fun leaveGroup() {
        val gId = groupId ?: return
        val wallet = walletBridge.getCurrentWalletAddress() ?: return

        viewModelScope.launch {
            try {
                groupRepository.leaveGroup(gId, wallet)
                _leftGroup.value = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to leave group")
            }
        }
    }

    /**
     * Delete the group (owner only).
     */
    fun deleteGroup() {
        val gId = groupId ?: return

        viewModelScope.launch {
            try {
                groupRepository.deleteGroup(gId)
                _groupDeleted.value = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete group")
            }
        }
    }
}
