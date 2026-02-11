/**
 * MumbleChat Messages Management
 * Handles message sending, receiving, and storage
 * WITH END-TO-END ENCRYPTION
 */

import { state, saveMessages, saveContacts, isBlocked } from './state.js';
import { sendMessageViaRelay, sendTypingIndicator, sendReadReceipt } from './relay.js';
import { getContact, clearUnread, getContactPublicKey, storeContactPublicKey } from './contacts.js';
import { 
    encryptMessage, 
    decryptMessage, 
    signMessage, 
    verifySignature,
    generateSecureMessageId,
    exportPublicKey 
} from './crypto.js';

// Message character limit to prevent spam
const MAX_MESSAGE_LENGTH = 1024;

/**
 * Update message status (sending -> sent -> delivered -> read)
 * Status flow: sending -> sent -> pending (if offline) -> delivered -> read
 */
export function updateMessageStatus(messageId, recipientAddress, newStatus) {
    if (!messageId || !recipientAddress) return false;
    
    recipientAddress = recipientAddress.toLowerCase();
    const messages = state.messages[recipientAddress];
    
    if (!messages) return false;
    
    const message = messages.find(m => m.id === messageId);
    if (!message) return false;
    
    // Only update if it's a forward progression (don't go backwards)
    const statusOrder = ['sending', 'failed', 'pending', 'sent', 'delivered', 'read'];
    const currentIndex = statusOrder.indexOf(message.status);
    const newIndex = statusOrder.indexOf(newStatus);
    
    // Allow update if new status is higher priority or it's 'failed' or 'pending'
    if (newStatus === 'failed' || newStatus === 'pending' || newIndex > currentIndex) {
        message.status = newStatus;
        message.statusUpdatedAt = Date.now();
        saveMessages();
        
        // Trigger UI update
        window.dispatchEvent(new CustomEvent('messageStatusUpdated', {
            detail: { messageId, recipientAddress, status: newStatus }
        }));
        
        return true;
    }
    
    return false;
}

/**
 * Send a message to a contact (with E2EE)
 */
export async function sendMessage(recipientAddress, text) {
    if (!text.trim()) return null;
    if (!state.relayConnected) {
        throw new Error('Not connected to relay');
    }
    
    // Enforce 1024 character limit
    if (text.length > MAX_MESSAGE_LENGTH) {
        throw new Error(`Message too long. Maximum ${MAX_MESSAGE_LENGTH} characters allowed.`);
    }
    
    recipientAddress = recipientAddress.toLowerCase();
    
    // Initialize messages array if needed
    if (!state.messages[recipientAddress]) {
        state.messages[recipientAddress] = [];
    }
    
    // Generate secure message ID
    const messageId = generateSecureMessageId();
    
    // Get contact's public key for encryption
    const contactPublicKey = getContactPublicKey(recipientAddress);
    
    // Encrypt the message
    let encryptedPayload;
    let isEncrypted = false;
    
    if (contactPublicKey) {
        encryptedPayload = await encryptMessage(recipientAddress, contactPublicKey, text.trim());
        isEncrypted = encryptedPayload.encrypted;
    } else {
        encryptedPayload = { encrypted: false, data: text.trim() };
    }
    
    // Sign the message for authenticity
    const signature = await signMessage(text.trim() + messageId);
    
    // Create message object for local storage
    const message = {
        id: messageId,
        text: text.trim(),  // Store plaintext locally
        sent: true,
        status: 'sending',
        encrypted: isEncrypted,
        time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        timestamp: Date.now()
    };
    
    // Add to local state
    state.messages[recipientAddress].push(message);
    saveMessages();
    
    // Send encrypted message via relay
    const sent = sendMessageViaRelay(recipientAddress, {
        encryptedData: encryptedPayload.data,
        encrypted: isEncrypted,
        algorithm: encryptedPayload.algorithm || 'none',
        signature: signature,
        senderPublicKey: await exportPublicKey()
    }, messageId);
    
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
        contact.lastMessageTimestamp = Date.now(); // For sorting
        saveContacts();
    }
    
    return message;
}

/**
 * Receive a message (with E2EE decryption)
 */
export async function receiveMessage(data) {
    console.log('📥 receiveMessage called with:', JSON.stringify(data).slice(0, 500));
    
    let from = (data.senderAddress || data.from || '').toLowerCase();
    let text = null;
    let isEncrypted = data.encrypted || false;
    let signatureValid = false;
    
    // Check if blocked
    if (isBlocked(from)) {
        console.log('Message from blocked contact ignored:', from);
        return;
    }
    
    // Handle encrypted messages
    if (data.encrypted && data.encryptedData) {
        console.log('🔐 Decrypting E2E encrypted message...');
        
        // Get sender's public key (from message or contact)
        const senderPublicKey = data.senderPublicKey || getContactPublicKey(from);
        
        if (senderPublicKey) {
            // Store sender's public key if new
            if (data.senderPublicKey) {
                storeContactPublicKey(from, data.senderPublicKey);
            }
            
            // Decrypt the message
            text = await decryptMessage(from, senderPublicKey, data.encryptedData);
            console.log('🔓 Message decrypted successfully');
            
            // Verify signature if present
            if (data.signature && text) {
                signatureValid = await verifySignature(text + data.messageId, data.signature, from);
                console.log('✍️ Signature verification:', signatureValid ? 'VALID' : 'INVALID');
            }
        } else {
            console.warn('⚠️ No public key for decryption, message may be unreadable');
            text = data.encryptedData; // Store encrypted as-is
        }
    } else {
        // Handle unencrypted messages (legacy or no key exchange)
        text = data.text || data.payload || data.content || data.encryptedBlob;
        
        // Handle case where payload is an object
        if (text && typeof text === 'object') {
            text = text.text || text.payload || text.content || text.encryptedBlob || JSON.stringify(text);
        }
    }
    
    console.log('📥 Extracted - from:', from, 'text:', text?.slice?.(0, 50) || text);
    
    // Decode if base64 encoded
    if (!text && data.encryptedBlob) {
        try {
            text = atob(data.encryptedBlob);
        } catch (e) {
            text = data.encryptedBlob;
        }
    }
    
    // Final check - if still an object, try to extract string
    if (text && typeof text === 'object') {
        text = text.toString() !== '[object Object]' ? text.toString() : null;
    }
    
    // Skip if no text content
    if (!text || text === '[object Object]') {
        console.log('❌ Message has no valid text content:', data);
        return;
    }
    
    console.log('📥 Final text:', text);
    
    const timestamp = data.timestamp || Date.now();
    
    // Initialize messages array if needed
    if (!state.messages[from]) {
        state.messages[from] = [];
    }
    
    // Check for duplicate message
    const existingMsg = state.messages[from].find(m => m.id === data.messageId);
    if (existingMsg) {
        // If this is an offline delivery confirmation, update status to delivered
        if (data.isOfflineMessage && data.status === 'delivered') {
            existingMsg.status = 'delivered';
            saveMessages();
            console.log('✅ Updated offline message to DELIVERED:', data.messageId);
        } else {
            console.log('Duplicate message ignored:', data.messageId);
        }
        return;
    }
    
    // Create message object
    const message = {
        id: data.messageId || Date.now(),
        text,
        sent: false,
        status: data.isOfflineMessage ? 'delivered' : 'received',  // Mark offline messages as delivered
        encrypted: isEncrypted,
        signatureValid: signatureValid,
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
            lastMessageTimestamp: Date.now(), // For sorting
            isPinned: false,
            isMuted: false,
            isArchived: false,
            addedAt: Date.now()
        };
        state.contacts.push(contact);
    } else {
        contact.lastMessage = text;
        contact.lastMessageTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        contact.lastMessageTimestamp = Date.now(); // For sorting
        
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
    if (!text) return; // Guard against undefined text
    
    if (Notification.permission === 'granted') {
        new Notification(senderName || 'New Message', {
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
