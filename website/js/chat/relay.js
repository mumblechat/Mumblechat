/**
 * MumbleChat Relay Connection
 * Handles WebSocket connection to relay nodes
 */

import { state, updateState } from './state.js';
import { receiveMessage } from './messages.js';
import { updateContactStatus } from './contacts.js';

let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_DELAY = 5000;

/**
 * Connect to relay node
 */
export function connectToRelay() {
    if (!state.address) {
        console.warn('Cannot connect to relay: No wallet address');
        return;
    }

    const relayUrl = state.settings.relayUrl || state.relayUrl;
    console.log('Connecting to relay:', relayUrl);

    try {
        state.relaySocket = new WebSocket(relayUrl);

        state.relaySocket.onopen = handleRelayOpen;
        state.relaySocket.onmessage = handleRelayMessage;
        state.relaySocket.onclose = handleRelayClose;
        state.relaySocket.onerror = handleRelayError;
    } catch (error) {
        console.error('Relay connection failed:', error);
        updateRelayStatus(false);
        scheduleReconnect();
    }
}

/**
 * Handle relay connection opened
 */
function handleRelayOpen() {
    console.log('✅ Connected to relay node');
    state.relayConnected = true;
    reconnectAttempts = 0;
    updateRelayStatus(true);

    // Authenticate with relay
    sendToRelay({
        type: 'authenticate',
        walletAddress: state.address,
        displayName: state.displayName || state.username,
        timestamp: Date.now()
    });

    // Request pending messages after authentication
    setTimeout(() => {
        sendToRelay({
            type: 'sync',
            walletAddress: state.address,
            lastSyncTime: 0
        });
    }, 1000);
}

/**
 * Handle incoming relay messages
 */
function handleRelayMessage(event) {
    try {
        const data = JSON.parse(event.data);
        
        switch (data.type) {
            case 'message':
            case 'relay':
                receiveMessage(data);
                break;
                
            case 'authenticated':
                console.log('✅ Authenticated with relay node');
                updateRelayStatus(true);
                break;
                
            case 'sync_response':
                if (data.messages && data.messages.length > 0) {
                    console.log(`📥 Received ${data.messages.length} pending messages`);
                    data.messages.forEach(msg => receiveMessage(msg));
                }
                break;
                
            case 'delivery_receipt':
                console.log('✅ Message delivered:', data.messageId);
                markMessageDelivered(data.messageId);
                break;
                
            case 'read_receipt':
                console.log('👁️ Message read:', data.messageId);
                markMessageRead(data.messageId);
                break;
                
            case 'typing':
                handleTypingIndicator(data);
                break;
                
            case 'user_online':
                updateContactStatus(data.address, true);
                break;
                
            case 'user_offline':
                updateContactStatus(data.address, false);
                break;
                
            case 'group_message':
                receiveGroupMessage(data);
                break;
                
            case 'error':
                console.error('❌ Relay error:', data.message);
                break;
                
            default:
                console.log('Unknown message type:', data.type);
        }
    } catch (error) {
        console.error('Error parsing relay message:', error);
    }
}

/**
 * Handle relay connection closed
 */
function handleRelayClose() {
    console.log('Relay connection closed');
    state.relayConnected = false;
    updateRelayStatus(false);
    scheduleReconnect();
}

/**
 * Handle relay connection error
 */
function handleRelayError(error) {
    console.error('Relay error:', error);
    updateRelayStatus(false);
}

/**
 * Schedule reconnection attempt
 */
function scheduleReconnect() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        console.error('Max reconnection attempts reached');
        return;
    }

    reconnectAttempts++;
    const delay = RECONNECT_DELAY * Math.min(reconnectAttempts, 5);
    
    console.log(`Reconnecting in ${delay / 1000}s (attempt ${reconnectAttempts})`);
    setTimeout(connectToRelay, delay);
}

/**
 * Send message to relay
 */
export function sendToRelay(message) {
    if (!state.relaySocket || state.relaySocket.readyState !== WebSocket.OPEN) {
        console.warn('Relay not connected');
        return false;
    }

    try {
        state.relaySocket.send(JSON.stringify(message));
        return true;
    } catch (error) {
        console.error('Error sending to relay:', error);
        return false;
    }
}

/**
 * Send a direct message via relay
 */
export function sendMessageViaRelay(recipientAddress, text, messageId) {
    return sendToRelay({
        type: 'relay',
        messageId: messageId,
        senderAddress: state.address,
        recipientAddress: recipientAddress,
        encryptedBlob: btoa(text), // TODO: Implement proper encryption
        timestamp: Date.now(),
        ttlDays: 7
    });
}

/**
 * Send a group message via relay
 */
export function sendGroupMessageViaRelay(groupId, text, messageId) {
    return sendToRelay({
        type: 'group_message',
        messageId: messageId,
        senderAddress: state.address,
        groupId: groupId,
        encryptedBlob: btoa(text),
        timestamp: Date.now()
    });
}

/**
 * Send typing indicator
 */
export function sendTypingIndicator(recipientAddress, isTyping) {
    if (!state.settings.typingIndicators) return;
    
    return sendToRelay({
        type: 'typing',
        senderAddress: state.address,
        recipientAddress: recipientAddress,
        isTyping: isTyping
    });
}

/**
 * Send read receipt
 */
export function sendReadReceipt(messageId, senderAddress) {
    if (!state.settings.readReceipts) return;
    
    return sendToRelay({
        type: 'read_receipt',
        messageId: messageId,
        senderAddress: senderAddress,
        readerAddress: state.address,
        timestamp: Date.now()
    });
}

/**
 * Update relay status in UI
 */
export function updateRelayStatus(connected) {
    const relayDot = document.getElementById('relayDot');
    const relayStatus = document.getElementById('relayStatus');
    
    if (relayDot && relayStatus) {
        if (connected) {
            relayDot.classList.add('connected');
            relayStatus.textContent = 'Relay: Connected';
        } else {
            relayDot.classList.remove('connected');
            relayStatus.textContent = 'Relay: Reconnecting...';
        }
    }
    
    // Update send button state
    const sendBtn = document.getElementById('sendBtn');
    if (sendBtn) {
        const messageInput = document.getElementById('messageInput');
        sendBtn.disabled = !connected || !messageInput?.value.trim();
    }
}

/**
 * Disconnect from relay
 */
export function disconnectRelay() {
    if (state.relaySocket) {
        state.relaySocket.close();
        state.relaySocket = null;
    }
    state.relayConnected = false;
    updateRelayStatus(false);
}

/**
 * Handle typing indicator from other users
 */
function handleTypingIndicator(data) {
    const typingIndicator = document.getElementById('typingIndicator');
    if (typingIndicator && state.activeChat === data.senderAddress) {
        if (data.isTyping) {
            typingIndicator.style.display = 'block';
            typingIndicator.textContent = 'typing...';
        } else {
            typingIndicator.style.display = 'none';
        }
    }
}

/**
 * Mark message as delivered
 */
function markMessageDelivered(messageId) {
    // Find and update message status
    for (const address in state.messages) {
        const messages = state.messages[address];
        const msg = messages.find(m => m.id === messageId);
        if (msg) {
            msg.status = 'delivered';
            break;
        }
    }
}

/**
 * Mark message as read
 */
function markMessageRead(messageId) {
    for (const address in state.messages) {
        const messages = state.messages[address];
        const msg = messages.find(m => m.id === messageId);
        if (msg) {
            msg.status = 'read';
            break;
        }
    }
}

/**
 * Receive group message
 */
function receiveGroupMessage(data) {
    const groupId = data.groupId;
    const group = state.groups.find(g => g.id === groupId);
    
    if (!group) {
        console.warn('Received message for unknown group:', groupId);
        return;
    }
    
    // Decode message
    let text = data.text;
    if (data.encryptedBlob && !text) {
        try {
            text = atob(data.encryptedBlob);
        } catch (e) {
            text = data.encryptedBlob;
        }
    }
    
    if (!state.messages[`group_${groupId}`]) {
        state.messages[`group_${groupId}`] = [];
    }
    
    state.messages[`group_${groupId}`].push({
        id: data.messageId || Date.now(),
        text,
        sent: data.senderAddress === state.address,
        senderAddress: data.senderAddress,
        senderName: data.senderName || data.senderAddress.slice(0, 8),
        time: new Date(data.timestamp || Date.now()).toLocaleTimeString([], { 
            hour: '2-digit', 
            minute: '2-digit' 
        })
    });
    
    // Trigger UI update
    if (state.activeGroup === groupId) {
        window.dispatchEvent(new CustomEvent('messagesUpdated', { detail: { groupId } }));
    }
}
