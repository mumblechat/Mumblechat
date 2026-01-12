/**
 * MumbleChat Relay Connection
 * Handles WebSocket connection to relay nodes
 */

import { state, updateState } from './state.js';
import { receiveMessage, updateMessageStatus } from './messages.js';
import { updateContactStatus, storeContactPublicKey } from './contacts.js';
import { getBestRelayEndpoint, RELAY_DEFAULTS } from './config.js';

let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;
const RECONNECT_DELAY = 5000;

/**
 * Connect to relay node - ALWAYS fetches fresh endpoint
 */
export async function connectToRelay() {
    if (!state.address) {
        console.warn('Cannot connect to relay: No wallet address');
        return;
    }

    try {
        // ALWAYS fetch fresh endpoint (tunnel IDs change on restart)
        const relayUrl = await getBestRelayEndpoint();
        console.log('Connecting to relay:', relayUrl);
        
        // Update state with fresh URL
        state.relayUrl = relayUrl;
        state.settings.relayUrl = relayUrl;

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
async function handleRelayOpen() {
    console.log('✅ Connected to relay node');
    state.relayConnected = true;
    reconnectAttempts = 0;
    updateRelayStatus(true);

    // Get our public key for E2EE key exchange
    let publicKey = null;
    try {
        const { exportPublicKey } = await import('./crypto.js');
        publicKey = await exportPublicKey();
    } catch (e) {
        console.warn('Could not get public key for auth:', e);
    }

    // Authenticate with relay (include public key for key exchange)
    sendToRelay({
        type: 'authenticate',
        walletAddress: state.address,
        address: state.address,
        displayName: state.displayName || state.username,
        publicKey: publicKey,  // E2EE public key for key exchange
        timestamp: Date.now()
    });

    // Request pending messages after authentication
    setTimeout(() => {
        sendToRelay({
            type: 'sync',
            address: state.address
        });
    }, 500);
}

/**
 * Handle incoming relay message
 */
function handleRelayMessage(event) {
    try {
        const data = JSON.parse(event.data);
        
        switch (data.type) {
            case 'authenticated':
            case 'auth_success':
            case 'CONNECTED':
                console.log('✅ Relay authenticated');
                updateRelayStatus(true);
                break;

            case 'message':
                // Handle both encrypted and plain messages
                receiveMessage({
                    from: data.from || data.senderAddress,
                    to: data.to || state.address,
                    // E2EE fields
                    encrypted: data.encrypted || false,
                    encryptedData: data.encryptedData || data.payload || data.encryptedBlob,
                    signature: data.signature,
                    senderPublicKey: data.senderPublicKey,
                    // Legacy fields
                    text: data.text,
                    content: data.content,
                    payload: data.payload || data.encryptedBlob,
                    timestamp: data.timestamp || Date.now(),
                    messageId: data.messageId
                });
                break;

            case 'presence':
            case 'status':
                if (data.address) {
                    updateContactStatus(data.address, data.status || data.online);
                }
                break;

            case 'pong':
                break;

            case 'relay_ack':
            case 'delivery_receipt':
                // Message was delivered to recipient
                console.log('✅ Message delivered:', data.messageId);
                if (data.messageId && data.to) {
                    updateMessageStatus(data.messageId, data.to, 'delivered');
                }
                // Dispatch event for UI update
                window.dispatchEvent(new CustomEvent('messageStatusChanged', {
                    detail: { messageId: data.messageId, status: 'delivered', to: data.to }
                }));
                break;
            
            case 'message_queued':
                // Message queued for offline user - will be delivered when they come online
                console.log('📬 Message queued for offline delivery:', data.messageId);
                if (data.messageId && data.recipient) {
                    updateMessageStatus(data.messageId, data.recipient, 'pending');
                }
                // Dispatch event for UI update
                window.dispatchEvent(new CustomEvent('messageStatusChanged', {
                    detail: { messageId: data.messageId, status: 'pending', to: data.recipient, reason: 'recipient_offline' }
                }));
                break;
            
            case 'read_receipt':
                // Message was read by recipient
                console.log('👁 Message read:', data.messageId);
                if (data.messageId && data.from) {
                    updateMessageStatus(data.messageId, data.from, 'read');
                }
                window.dispatchEvent(new CustomEvent('messageStatusChanged', {
                    detail: { messageId: data.messageId, status: 'read', to: data.from }
                }));
                break;
            
            case 'key_exchange':
            case 'public_key':
                // Received a contact's public key for E2EE
                if (data.address && data.publicKey) {
                    console.log('🔑 Received public key from:', data.address);
                    storeContactPublicKey(data.address, data.publicKey);
                }
                break;
            
            case 'key_request':
                // Someone requested our public key - send it
                if (data.from) {
                    sendPublicKeyToContact(data.from);
                }
                break;
            
            case 'stored_messages':
            case 'offline_messages':
                // Received offline messages after coming online
                if (data.messages && Array.isArray(data.messages)) {
                    console.log(`📬 Received ${data.messages.length} offline messages`);
                    data.messages.forEach(msg => {
                        receiveMessage(msg);
                    });
                    // Dispatch event to update UI
                    window.dispatchEvent(new CustomEvent('offlineMessagesDelivered', {
                        detail: { count: data.messages.length }
                    }));
                }
                break;

            case 'sync_response':
                if (data.messages && Array.isArray(data.messages)) {
                    data.messages.forEach(msg => {
                        receiveMessage(msg);
                    });
                }
                break;

            case 'error':
                console.error('Relay error:', data.message || data.error);
                break;

            default:
                console.log('Unknown message type:', data.type);
        }
    } catch (error) {
        console.error('Error handling relay message:', error);
    }
}

/**
 * Handle relay connection closed
 */
function handleRelayClose(event) {
    console.log('Relay connection closed');
    state.relayConnected = false;
    updateRelayStatus(false);
    scheduleReconnect();
}

/**
 * Handle relay error
 */
function handleRelayError(error) {
    console.error('Relay error:', error);
    state.relayConnected = false;
    updateRelayStatus(false);
}

/**
 * Schedule reconnection
 */
function scheduleReconnect() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        console.error('Max reconnection attempts reached');
        updateRelayStatus(false, 'Failed');
        return;
    }

    reconnectAttempts++;
    const delay = RECONNECT_DELAY * Math.min(reconnectAttempts, 3);
    console.log(`Reconnecting in ${delay/1000}s (attempt ${reconnectAttempts})`);
    
    updateRelayStatus(false, 'Reconnecting...');
    setTimeout(connectToRelay, delay);
}

/**
 * Send message to relay
 */
export function sendToRelay(message) {
    if (!state.relaySocket || state.relaySocket.readyState !== WebSocket.OPEN) {
        console.warn('⚠ Relay not connected');
        return false;
    }

    try {
        state.relaySocket.send(JSON.stringify(message));
        return true;
    } catch (error) {
        console.error('Failed to send to relay:', error);
        return false;
    }
}

/**
 * Send chat message via relay (supports E2EE)
 */
export function sendMessageViaRelay(to, content, messageId) {
    // Handle encrypted message payload
    let payload, encrypted = false, encryptedData = null, signature = null, senderPublicKey = null;
    
    if (typeof content === 'object' && content.encryptedData !== undefined) {
        // E2EE encrypted message
        encrypted = content.encrypted;
        encryptedData = content.encryptedData;
        signature = content.signature;
        senderPublicKey = content.senderPublicKey;
        payload = encryptedData; // Send encrypted data
    } else {
        // Plain text (legacy/no key exchange)
        payload = content;
    }
    
    const message = {
        type: 'relay',
        to: to.toLowerCase(),
        from: state.address.toLowerCase(),
        payload: payload,
        encrypted: encrypted,
        encryptedData: encryptedData,
        signature: signature,
        senderPublicKey: senderPublicKey,
        messageId: messageId || `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        timestamp: Date.now()
    };

    return sendToRelay(message);
}

/**
 * Update relay connection status in UI - Updates sidebar status bar
 */
export function updateRelayStatus(connected, statusText = null) {
    state.relayConnected = connected;
    
    // Update sidebar status bar (new location)
    const sidebarRelayDot = document.getElementById('sidebarRelayDot');
    const sidebarRelayStatus = document.getElementById('sidebarRelayStatus');
    
    // Also check for old IDs for backwards compatibility
    const relayDot = sidebarRelayDot || document.getElementById('relayDot');
    const relayStatusText = sidebarRelayStatus || document.getElementById('relayStatus');
    
    if (relayDot) {
        relayDot.style.background = connected ? '#22c55e' : '#ef4444';
        relayDot.classList.toggle('connected', connected);
    }
    
    if (relayStatusText) {
        if (statusText) {
            relayStatusText.textContent = `Relay: ${statusText}`;
        } else {
            relayStatusText.textContent = connected ? 'Relay: Connected' : 'Relay: Disconnected';
        }
    }

    // Dispatch event for UI updates
    window.dispatchEvent(new CustomEvent('relayStatusChanged', { 
        detail: { connected, status: statusText } 
    }));
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
 * Send typing indicator
 */
export function sendTypingIndicator(to) {
    sendToRelay({
        type: 'typing',
        to: to.toLowerCase(),
        from: state.address.toLowerCase()
    });
}

/**
 * Send read receipt
 */
export function sendReadReceipt(to, messageId) {
    sendToRelay({
        type: 'read',
        to: to.toLowerCase(),
        from: state.address.toLowerCase(),
        messageId
    });
}

/**
 * Send our public key to a contact (for key exchange)
 */
export async function sendPublicKeyToContact(contactAddress) {
    try {
        const { exportPublicKey } = await import('./crypto.js');
        const publicKey = await exportPublicKey();
        
        if (publicKey) {
            sendToRelay({
                type: 'public_key',
                to: contactAddress.toLowerCase(),
                from: state.address.toLowerCase(),
                publicKey: publicKey,
                timestamp: Date.now()
            });
            console.log('🔑 Sent public key to:', contactAddress);
        }
    } catch (error) {
        console.error('Failed to send public key:', error);
    }
}

/**
 * Request a contact's public key
 */
export function requestContactPublicKey(contactAddress) {
    sendToRelay({
        type: 'key_request',
        to: contactAddress.toLowerCase(),
        from: state.address.toLowerCase(),
        timestamp: Date.now()
    });
    console.log('🔑 Requested public key from:', contactAddress);
}
