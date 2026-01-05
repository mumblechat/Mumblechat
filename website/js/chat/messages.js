/**
 * MumbleChat Messages Management
 * Handles message sending, receiving, and storage
 */

import { state, saveMessages, saveContacts, isBlocked } from './state.js';
import { sendMessageViaRelay, sendTypingIndicator, sendReadReceipt } from './relay.js';
import { getContact, clearUnread } from './contacts.js';

/**
 * Send a message to a contact
 */
export function sendMessage(recipientAddress, text) {
    if (!text.trim()) return null;
    if (!state.relayConnected) {
        throw new Error('Not connected to relay');
    }
    
    recipientAddress = recipientAddress.toLowerCase();
    
    // Initialize messages array if needed
    if (!state.messages[recipientAddress]) {
        state.messages[recipientAddress] = [];
    }
    
    // Generate message ID
    const messageId = 'msg_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    
    // Create message object
    const message = {
        id: messageId,
        text: text.trim(),
        sent: true,
        status: 'sending',
        time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        timestamp: Date.now()
    };
    
    // Add to local state
    state.messages[recipientAddress].push(message);
    saveMessages();
    
    // Send via relay
    const sent = sendMessageViaRelay(recipientAddress, text.trim(), messageId);
    
    if (sent) {
        message.status = 'sent';
    } else {
        message.status = 'failed';
    }
    
    // Update contact last message
    const contact = getContact(recipientAddress);
    if (contact) {
        contact.lastMessage = text.trim();
        contact.lastMessageTime = 'now';
        saveContacts();
    }
    
    return message;
}

/**
 * Receive a message
 */
export function receiveMessage(data) {
    let from = (data.senderAddress || data.from || '').toLowerCase();
    let text = data.text;
    
    // Check if blocked
    if (isBlocked(from)) {
        console.log('Message from blocked contact ignored:', from);
        return;
    }
    
    // Decode if base64 encoded
    if (data.encryptedBlob && !text) {
        try {
            text = atob(data.encryptedBlob);
        } catch (e) {
            text = data.encryptedBlob;
        }
    }
    
    const timestamp = data.timestamp || Date.now();
    
    // Initialize messages array if needed
    if (!state.messages[from]) {
        state.messages[from] = [];
    }
    
    // Check for duplicate message
    const existingMsg = state.messages[from].find(m => m.id === data.messageId);
    if (existingMsg) {
        console.log('Duplicate message ignored:', data.messageId);
        return;
    }
    
    // Create message object
    const message = {
        id: data.messageId || Date.now(),
        text,
        sent: false,
        status: 'received',
        time: new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        timestamp
    };
    
    state.messages[from].push(message);
    saveMessages();
    
    // Update or create contact
    let contact = getContact(from);
    if (!contact) {
        // Auto-add new contact
        contact = {
            id: Date.now(),
            name: data.senderName || from.slice(0, 8),
            address: from,
            displayName: data.senderDisplayName || null,
            isRegistered: true,
            lastMessage: text,
            unread: 1,
            online: true,
            lastMessageTime: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
            isPinned: false,
            isMuted: false,
            isArchived: false,
            addedAt: Date.now()
        };
        state.contacts.push(contact);
    } else {
        contact.lastMessage = text;
        contact.lastMessageTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        
        // Increment unread only if not active chat
        if (state.activeChat !== from) {
            contact.unread = (contact.unread || 0) + 1;
        }
    }
    
    saveContacts();
    
    // Send read receipt if this is the active chat
    if (state.activeChat === from) {
        sendReadReceipt(data.messageId, from);
    }
    
    // Trigger UI update
    window.dispatchEvent(new CustomEvent('messageReceived', { 
        detail: { from, message } 
    }));
    
    // Show notification if not active chat
    if (state.activeChat !== from && state.settings.notifications) {
        showMessageNotification(contact.name, text);
    }
    
    return message;
}

/**
 * Retry failed message
 */
export function retryMessage(messageId, recipientAddress) {
    recipientAddress = recipientAddress.toLowerCase();
    const messages = state.messages[recipientAddress];
    
    if (!messages) return false;
    
    const message = messages.find(m => m.id === messageId);
    if (!message || message.status !== 'failed') return false;
    
    message.status = 'sending';
    
    const sent = sendMessageViaRelay(recipientAddress, message.text, messageId);
    
    if (sent) {
        message.status = 'sent';
    } else {
        message.status = 'failed';
    }
    
    saveMessages();
    return sent;
}

/**
 * Delete a message
 */
export function deleteMessage(messageId, address) {
    address = address.toLowerCase();
    
    if (state.messages[address]) {
        state.messages[address] = state.messages[address].filter(m => m.id !== messageId);
        saveMessages();
        return true;
    }
    
    return false;
}

/**
 * Get messages for a contact
 */
export function getMessages(address) {
    return state.messages[address.toLowerCase()] || [];
}

/**
 * Handle typing status
 */
let typingTimeout = null;

export function handleTypingInput(recipientAddress, isTyping) {
    if (!state.settings.typingIndicators) return;
    
    // Clear existing timeout
    if (typingTimeout) {
        clearTimeout(typingTimeout);
    }
    
    // Send typing indicator
    sendTypingIndicator(recipientAddress, isTyping);
    
    // Auto-stop typing after 3 seconds
    if (isTyping) {
        typingTimeout = setTimeout(() => {
            sendTypingIndicator(recipientAddress, false);
        }, 3000);
    }
}

/**
 * Mark messages as read
 */
export function markMessagesAsRead(address) {
    address = address.toLowerCase();
    clearUnread(address);
    
    // Send read receipts for unread messages
    const messages = state.messages[address] || [];
    const unreadMessages = messages.filter(m => !m.sent && m.status === 'received');
    
    unreadMessages.forEach(msg => {
        sendReadReceipt(msg.id, address);
        msg.status = 'read';
    });
    
    if (unreadMessages.length > 0) {
        saveMessages();
    }
}

/**
 * Show message notification
 */
function showMessageNotification(senderName, text) {
    if (!('Notification' in window)) return;
    
    if (Notification.permission === 'granted') {
        new Notification(senderName, {
            body: text.length > 50 ? text.substring(0, 50) + '...' : text,
            icon: '/icons/icon-192x192.png',
            tag: 'mumblechat-message'
        });
    } else if (Notification.permission !== 'denied') {
        Notification.requestPermission();
    }
}

/**
 * Copy message text to clipboard
 */
export function copyMessage(messageId, address) {
    address = address.toLowerCase();
    const messages = state.messages[address] || [];
    const message = messages.find(m => m.id === messageId);
    
    if (message) {
        navigator.clipboard.writeText(message.text);
        return true;
    }
    
    return false;
}

/**
 * Escape HTML for safe display
 */
export function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
