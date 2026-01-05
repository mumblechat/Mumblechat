/**
 * MumbleChat Groups Management
 * Handles group chat operations
 */

import { state, saveGroups, saveMessages } from './state.js';
import { sendGroupMessageViaRelay, sendToRelay } from './relay.js';

/**
 * Create a new group
 */
export function createGroup(name, members = []) {
    const groupId = 'group_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    
    const group = {
        id: groupId,
        name: name,
        description: '',
        avatar: null,
        createdBy: state.address,
        createdAt: Date.now(),
        members: [
            {
                address: state.address,
                name: state.displayName || state.username,
                role: 'admin',
                joinedAt: Date.now()
            },
            ...members.map(m => ({
                address: m.address.toLowerCase(),
                name: m.name,
                role: 'member',
                joinedAt: Date.now()
            }))
        ],
        lastMessage: '',
        lastMessageTime: null,
        unread: 0,
        isMuted: false
    };
    
    state.groups.push(group);
    state.messages[`group_${groupId}`] = [];
    
    saveGroups();
    
    // Notify relay about new group
    sendToRelay({
        type: 'create_group',
        groupId: groupId,
        name: name,
        creatorAddress: state.address,
        members: group.members.map(m => m.address)
    });
    
    return group;
}

/**
 * Get group by ID
 */
export function getGroup(groupId) {
    return state.groups.find(g => g.id === groupId);
}

/**
 * Update group info
 */
export function updateGroup(groupId, updates) {
    const group = getGroup(groupId);
    
    if (group) {
        Object.assign(group, updates);
        saveGroups();
        
        // Notify relay about group update
        sendToRelay({
            type: 'update_group',
            groupId: groupId,
            updates: updates
        });
    }
    
    return group;
}

/**
 * Add member to group
 */
export function addGroupMember(groupId, memberAddress, memberName) {
    const group = getGroup(groupId);
    
    if (!group) return false;
    
    // Check if already a member
    if (group.members.some(m => m.address.toLowerCase() === memberAddress.toLowerCase())) {
        return false;
    }
    
    const member = {
        address: memberAddress.toLowerCase(),
        name: memberName,
        role: 'member',
        joinedAt: Date.now()
    };
    
    group.members.push(member);
    saveGroups();
    
    // Notify relay
    sendToRelay({
        type: 'add_group_member',
        groupId: groupId,
        member: member
    });
    
    return true;
}

/**
 * Remove member from group
 */
export function removeGroupMember(groupId, memberAddress) {
    const group = getGroup(groupId);
    
    if (!group) return false;
    
    // Check if user is admin
    const currentUser = group.members.find(m => m.address.toLowerCase() === state.address.toLowerCase());
    if (!currentUser || currentUser.role !== 'admin') {
        throw new Error('Only admins can remove members');
    }
    
    // Cannot remove last admin
    const admins = group.members.filter(m => m.role === 'admin');
    if (admins.length === 1 && admins[0].address.toLowerCase() === memberAddress.toLowerCase()) {
        throw new Error('Cannot remove the last admin');
    }
    
    group.members = group.members.filter(m => m.address.toLowerCase() !== memberAddress.toLowerCase());
    saveGroups();
    
    // Notify relay
    sendToRelay({
        type: 'remove_group_member',
        groupId: groupId,
        memberAddress: memberAddress
    });
    
    return true;
}

/**
 * Make member an admin
 */
export function makeGroupAdmin(groupId, memberAddress) {
    const group = getGroup(groupId);
    
    if (!group) return false;
    
    const member = group.members.find(m => m.address.toLowerCase() === memberAddress.toLowerCase());
    
    if (member) {
        member.role = 'admin';
        saveGroups();
        
        sendToRelay({
            type: 'update_member_role',
            groupId: groupId,
            memberAddress: memberAddress,
            role: 'admin'
        });
    }
    
    return true;
}

/**
 * Leave a group
 */
export function leaveGroup(groupId) {
    const group = getGroup(groupId);
    
    if (!group) return false;
    
    // Remove self from members
    group.members = group.members.filter(m => m.address.toLowerCase() !== state.address.toLowerCase());
    
    // If last member, delete group
    if (group.members.length === 0) {
        deleteGroup(groupId);
    } else {
        saveGroups();
        
        // Notify relay
        sendToRelay({
            type: 'leave_group',
            groupId: groupId,
            memberAddress: state.address
        });
    }
    
    // Clear active group if leaving current
    if (state.activeGroup === groupId) {
        state.activeGroup = null;
    }
    
    return true;
}

/**
 * Delete a group (admin only)
 */
export function deleteGroup(groupId) {
    state.groups = state.groups.filter(g => g.id !== groupId);
    delete state.messages[`group_${groupId}`];
    
    saveGroups();
    import('./state.js').then(({ saveMessages }) => saveMessages());
    
    sendToRelay({
        type: 'delete_group',
        groupId: groupId
    });
    
    return true;
}

/**
 * Send message to group
 */
export function sendGroupMessage(groupId, text) {
    if (!text.trim()) return null;
    if (!state.relayConnected) {
        throw new Error('Not connected to relay');
    }
    
    const group = getGroup(groupId);
    if (!group) {
        throw new Error('Group not found');
    }
    
    const messageKey = `group_${groupId}`;
    if (!state.messages[messageKey]) {
        state.messages[messageKey] = [];
    }
    
    const messageId = 'gmsg_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    
    const message = {
        id: messageId,
        text: text.trim(),
        sent: true,
        senderAddress: state.address,
        senderName: state.displayName || state.username,
        status: 'sending',
        time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        timestamp: Date.now()
    };
    
    state.messages[messageKey].push(message);
    saveMessages();
    
    // Send via relay
    const sent = sendGroupMessageViaRelay(groupId, text.trim(), messageId);
    
    if (sent) {
        message.status = 'sent';
    } else {
        message.status = 'failed';
    }
    
    // Update group last message
    group.lastMessage = text.trim();
    group.lastMessageTime = 'now';
    saveGroups();
    
    return message;
}

/**
 * Get group messages
 */
export function getGroupMessages(groupId) {
    return state.messages[`group_${groupId}`] || [];
}

/**
 * Get sorted groups list
 */
export function getSortedGroups() {
    return [...state.groups].sort((a, b) => {
        const timeA = a.lastMessageTime ? new Date(a.lastMessageTime).getTime() : 0;
        const timeB = b.lastMessageTime ? new Date(b.lastMessageTime).getTime() : 0;
        return timeB - timeA;
    });
}

/**
 * Toggle mute for group
 */
export function toggleMuteGroup(groupId) {
    const group = getGroup(groupId);
    
    if (group) {
        group.isMuted = !group.isMuted;
        saveGroups();
    }
    
    return group?.isMuted;
}

/**
 * Clear group unread count
 */
export function clearGroupUnread(groupId) {
    const group = getGroup(groupId);
    
    if (group) {
        group.unread = 0;
        saveGroups();
    }
}

/**
 * Search groups
 */
export function searchGroups(query) {
    const lowerQuery = query.toLowerCase();
    
    return state.groups.filter(group => {
        return group.name.toLowerCase().includes(lowerQuery);
    });
}
