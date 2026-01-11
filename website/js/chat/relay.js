/**
 * MumbleChat Relay Connection
 * Handles WebSocket connection to relay nodes
 */

import { state, updateState } from './state.js';
import { receiveMessage } from './messages.js';
import { updateContactStatus } from './contacts.js';
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
function handleRelayOpen() {
    console.log('✅ Connected to relay node');
    state.relayConnected = true;
    reconnectAttempts = 0;
    updateRelayStatus(true);

    // Authenticate with relay
    sendToRelay({
        type: 'authenticate',
        walletAddress: state.address,
        address: state.address,
        displayName: state.displayName || state.username,
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
                const messageContent = data.payload || data.encryptedBlob || data.content;
                receiveMessage({
                    from: data.from || data.senderAddress,
                    to: data.to || state.address,
                    content: messageContent,
                    payload: messageContent,
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
                console.log('Message delivered:', data.messageId);
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
 * Send chat message via relay
 */
export function sendMessageViaRelay(to, content) {
    const message = {
        type: 'relay',
        to: to.toLowerCase(),
        from: state.address.toLowerCase(),
        payload: content,
        messageId: `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        timestamp: Date.now()
    };

    return sendToRelay(message);
}

/**
 * Update relay connection status in UI - FIXED to use correct IDs
 */
export function updateRelayStatus(connected, statusText = null) {
    state.relayConnected = connected;
    
    // Update by ID (matches app.js status bar)
    const relayDot = document.getElementById('relayDot');
    const relayStatusText = document.getElementById('relayStatus');
    
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
