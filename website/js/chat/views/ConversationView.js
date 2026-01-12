/**
 * MumbleChat Conversation View
 * Individual chat conversation
 */

import { state, saveMessages, saveContacts } from '../state.js';
import { getMessages, sendMessage, deleteMessage, copyMessage, handleTypingInput, markMessagesAsRead, escapeHtml } from '../messages.js';
import { getContact, clearChatHistory, exportChatHistory, blockContactByAddress, removeContact } from '../contacts.js';
import { shortenAddress, getInitials, getAvatarColor, showModal, showConfirm, showToast, copyToClipboard } from '../ui.js';

/**
 * Render conversation view
 */
export function renderConversationView(container, address) {
    const contact = getContact(address);
    if (!contact) {
        container.innerHTML = '<p>Contact not found</p>';
        return;
    }
    
    state.activeChat = address;
    markMessagesAsRead(address);
    
    container.innerHTML = `
        <div class="conversation-container">
            <div class="conversation-header">
                <button class="back-btn" id="backBtn">←</button>
                <div class="chat-header-info" id="chatHeaderInfo">
                    <div class="chat-avatar" style="background: ${getAvatarColor(address)}">
                        ${getInitials(contact.name)}
                    </div>
                    <div class="chat-info">
                        <h2 id="chatName">${escapeHtml(contact.name)}</h2>
                        <p id="chatStatus">${contact.online ? 'Online' : 'Offline'}</p>
                    </div>
                </div>
                <button class="header-btn" id="chatMenuBtn">⋮</button>
            </div>
            
            <div class="messages-container" id="messagesContainer"></div>
            
            <div id="typingIndicator" class="typing-indicator" style="display: none;">
                <span></span><span></span><span></span>
            </div>
            
            <div class="input-area">
                <div class="input-actions">
                    <button class="action-btn" id="attachBtn">📎</button>
                </div>
                <textarea class="message-input" id="messageInput" placeholder="Type a message..." rows="1"></textarea>
                <button class="send-btn" id="sendBtn" disabled>➤</button>
            </div>
        </div>
    `;
    
    renderMessages(address);
    setupConversationListeners(address);
    
    // Listen for contact status changes to update header
    const statusHandler = (e) => {
        if (e.detail.address?.toLowerCase() === address.toLowerCase()) {
            const chatStatus = document.getElementById('chatStatus');
            if (chatStatus) {
                chatStatus.textContent = e.detail.online ? 'Online' : 'Offline';
                chatStatus.style.color = e.detail.online ? '#22c55e' : '#9ca3af';
            }
        }
    };
    window.addEventListener('contactStatusChanged', statusHandler);
}

/**
 * Render messages list
 */
export function renderMessages(address) {
    const container = document.getElementById('messagesContainer');
    if (!container) return;
    
    const messages = getMessages(address);
    
    if (messages.length === 0) {
        container.innerHTML = `
            <div class="empty-messages">
                <p>No messages yet</p>
                <p class="hint">Send a message to start the conversation</p>
            </div>
        `;
        return;
    }
    
    container.innerHTML = messages.map(msg => `
        <div class="message ${msg.sent ? 'sent' : 'received'}" data-id="${msg.id}">
            <div class="message-bubble">
                ${escapeHtml(msg.text)}
                <span class="message-status">
                    ${msg.sent ? getStatusIcon(msg.status) : ''}
                </span>
            </div>
            <div class="message-time">${msg.time}</div>
        </div>
    `).join('');
    
    // Scroll to bottom
    container.scrollTop = container.scrollHeight;
}

/**
 * Get status icon for message (WhatsApp-style)
 * sending: clock ⏳
 * sent: single gray tick ✓
 * pending: clock (recipient offline) 🕐
 * delivered: double gray ticks ✓✓
 * read: double blue ticks ✓✓ (blue)
 * failed: red X ❌
 */
function getStatusIcon(status) {
    switch (status) {
        case 'sending': 
            return '<span class="status-icon sending" title="Sending...">🕐</span>';
        case 'sent': 
            return '<span class="status-icon sent" title="Sent">✓</span>';
        case 'pending': 
            return '<span class="status-icon pending" title="Recipient offline - queued for delivery">⏳</span>';
        case 'delivered': 
            return '<span class="status-icon delivered" title="Delivered">✓✓</span>';
        case 'read': 
            return '<span class="status-icon read" title="Read" style="color: #1b8cff;">✓✓</span>';
        case 'failed': 
            return '<span class="status-icon failed" title="Failed to send - tap to retry">❌</span>';
        default: 
            return '';
    }
}

/**
 * Setup conversation event listeners
 */
function setupConversationListeners(address) {
    const backBtn = document.getElementById('backBtn');
    const chatMenuBtn = document.getElementById('chatMenuBtn');
    const chatHeaderInfo = document.getElementById('chatHeaderInfo');
    const messageInput = document.getElementById('messageInput');
    const sendBtn = document.getElementById('sendBtn');
    const attachBtn = document.getElementById('attachBtn');
    const messagesContainer = document.getElementById('messagesContainer');
    
    // Back button
    backBtn?.addEventListener('click', () => {
        state.activeChat = null;
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'chats' } }));
    });
    
    // Chat header - show contact info
    chatHeaderInfo?.addEventListener('click', () => {
        showContactInfo(address);
    });
    
    // Menu button
    chatMenuBtn?.addEventListener('click', (e) => {
        showChatMenu(address, e);
    });
    
    // Message input
    messageInput?.addEventListener('input', () => {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 100) + 'px';
        sendBtn.disabled = !messageInput.value.trim() || !state.relayConnected;
        
        // Send typing indicator
        handleTypingInput(address, messageInput.value.length > 0);
    });
    
    messageInput?.addEventListener('keypress', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSendMessage(address);
        }
    });
    
    // Send button
    sendBtn?.addEventListener('click', () => {
        handleSendMessage(address);
    });
    
    // Attach button
    attachBtn?.addEventListener('click', () => {
        showToast('File attachments coming soon', 'info');
    });
    
    // Message context menu
    messagesContainer?.addEventListener('contextmenu', (e) => {
        const messageEl = e.target.closest('.message');
        if (messageEl) {
            e.preventDefault();
            showMessageContextMenu(messageEl.dataset.id, address, e);
        }
    });
    
    // Long press for mobile
    let longPressTimer;
    messagesContainer?.addEventListener('touchstart', (e) => {
        const messageEl = e.target.closest('.message');
        if (messageEl) {
            longPressTimer = setTimeout(() => {
                showMessageContextMenu(messageEl.dataset.id, address, e);
            }, 500);
        }
    });
    
    messagesContainer?.addEventListener('touchend', () => {
        clearTimeout(longPressTimer);
    });
    
    // Listen for new messages
    window.addEventListener('messageReceived', (e) => {
        if (e.detail.from === address) {
            renderMessages(address);
        }
    });
    
    // Listen for message status updates (sent -> delivered -> read)
    window.addEventListener('messageStatusUpdated', (e) => {
        if (e.detail.recipientAddress === address) {
            // Update just the status icon instead of re-rendering all messages
            const messageEl = document.querySelector(`.message[data-id="${e.detail.messageId}"]`);
            if (messageEl) {
                const statusSpan = messageEl.querySelector('.message-status');
                if (statusSpan) {
                    statusSpan.innerHTML = getStatusIcon(e.detail.status);
                }
            }
        }
    });
    
    // Listen for message_queued events (recipient offline)
    window.addEventListener('messageStatusChanged', (e) => {
        if (e.detail.to?.toLowerCase() === address.toLowerCase()) {
            const messageEl = document.querySelector(`.message[data-id="${e.detail.messageId}"]`);
            if (messageEl) {
                const statusSpan = messageEl.querySelector('.message-status');
                if (statusSpan) {
                    statusSpan.innerHTML = getStatusIcon(e.detail.status);
                }
            }
        }
    });
}

/**
 * Handle sending message (async for E2EE)
 */
async function handleSendMessage(address) {
    const input = document.getElementById('messageInput');
    const text = input.value.trim();
    
    if (!text) return;
    
    try {
        // Disable input while sending
        input.disabled = true;
        document.getElementById('sendBtn').disabled = true;
        
        await sendMessage(address, text);
        
        input.value = '';
        input.style.height = 'auto';
        input.disabled = false;
        renderMessages(address);
        
        // Stop typing indicator
        handleTypingInput(address, false);
    } catch (error) {
        input.disabled = false;
        showToast(error.message, 'error');
    }
}

/**
 * Show chat menu
 */
function showChatMenu(address, event) {
    const contact = getContact(address);
    
    const menu = document.createElement('div');
    menu.className = 'context-menu';
    menu.innerHTML = `
        <div class="context-menu-item" data-action="viewContact">👤 View contact</div>
        <div class="context-menu-item" data-action="search">🔍 Search</div>
        <div class="context-menu-item" data-action="mute">
            ${contact?.isMuted ? '🔔 Unmute' : '🔕 Mute notifications'}
        </div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item" data-action="export">📤 Export chat</div>
        <div class="context-menu-item" data-action="clear">🧹 Clear history</div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item danger" data-action="block">🚫 Block</div>
        <div class="context-menu-item danger" data-action="delete">🗑️ Delete chat</div>
    `;
    
    menu.style.position = 'fixed';
    menu.style.right = '16px';
    menu.style.top = '60px';
    
    document.body.appendChild(menu);
    
    menu.addEventListener('click', (e) => {
        const action = e.target.dataset.action;
        if (action) {
            handleChatMenuAction(action, address);
            menu.remove();
        }
    });
    
    setTimeout(() => {
        document.addEventListener('click', function closeMenu() {
            menu.remove();
            document.removeEventListener('click', closeMenu);
        });
    }, 100);
}

/**
 * Handle chat menu action
 */
function handleChatMenuAction(action, address) {
    const contact = getContact(address);
    
    switch (action) {
        case 'viewContact':
            showContactInfo(address);
            break;
            
        case 'search':
            showToast('Search coming soon', 'info');
            break;
            
        case 'mute':
            import('../contacts.js').then(({ toggleMuteContact }) => {
                toggleMuteContact(address);
                showToast(contact?.isMuted ? 'Notifications unmuted' : 'Notifications muted', 'success');
            });
            break;
            
        case 'export':
            const exportData = exportChatHistory(address);
            const blob = new Blob([exportData], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `chat_${shortenAddress(address)}_${Date.now()}.json`;
            a.click();
            URL.revokeObjectURL(url);
            showToast('Chat exported', 'success');
            break;
            
        case 'clear':
            showConfirm('Clear History', 'Delete all messages in this chat?', () => {
                clearChatHistory(address);
                renderMessages(address);
                showToast('Chat history cleared', 'success');
            });
            break;
            
        case 'block':
            showConfirm('Block Contact', 'You will no longer receive messages from this contact.', () => {
                blockContactByAddress(address);
                state.activeChat = null;
                window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'chats' } }));
                showToast('Contact blocked', 'success');
            });
            break;
            
        case 'delete':
            showConfirm('Delete Chat', 'This will delete the chat and remove the contact.', () => {
                removeContact(address);
                state.activeChat = null;
                window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'chats' } }));
                showToast('Chat deleted', 'success');
            });
            break;
    }
}

/**
 * Show contact info modal
 */
function showContactInfo(address) {
    const contact = getContact(address);
    if (!contact) return;
    
    showModal({
        title: 'Contact Info',
        content: `
            <div class="contact-info-modal">
                <div class="contact-avatar-large" style="background: ${getAvatarColor(address)}">
                    ${getInitials(contact.name)}
                </div>
                <h2>${escapeHtml(contact.name)}</h2>
                ${contact.displayName ? `<p class="display-name">${escapeHtml(contact.displayName)}</p>` : ''}
                
                <div class="info-row">
                    <span class="label">Address</span>
                    <span class="value" id="contactAddress">${shortenAddress(address)}</span>
                    <button class="copy-btn" onclick="navigator.clipboard.writeText('${address}')">📋</button>
                </div>
                
                <div class="info-row">
                    <span class="label">Status</span>
                    <span class="value">${contact.online ? '🟢 Online' : '⚪ Offline'}</span>
                </div>
                
                <div class="info-row">
                    <span class="label">Registered</span>
                    <span class="value">${contact.isRegistered ? '✅ Yes' : '❌ No'}</span>
                </div>
            </div>
        `,
        buttons: [
            { text: 'Close', action: 'close' }
        ]
    });
}

/**
 * Show message context menu
 */
function showMessageContextMenu(messageId, address, event) {
    const menu = document.createElement('div');
    menu.className = 'context-menu';
    menu.innerHTML = `
        <div class="context-menu-item" data-action="copy">📋 Copy</div>
        <div class="context-menu-item danger" data-action="delete">🗑️ Delete</div>
    `;
    
    menu.style.position = 'fixed';
    menu.style.left = (event.pageX || event.touches?.[0]?.pageX) + 'px';
    menu.style.top = (event.pageY || event.touches?.[0]?.pageY) + 'px';
    
    document.body.appendChild(menu);
    
    menu.addEventListener('click', (e) => {
        const action = e.target.dataset.action;
        if (action === 'copy') {
            copyMessage(messageId, address);
            showToast('Message copied', 'success');
        } else if (action === 'delete') {
            deleteMessage(messageId, address);
            renderMessages(address);
            showToast('Message deleted', 'success');
        }
        menu.remove();
    });
    
    setTimeout(() => {
        document.addEventListener('click', function closeMenu() {
            menu.remove();
            document.removeEventListener('click', closeMenu);
        });
    }, 100);
}

/**
 * Get conversation view styles
 */
export function getConversationStyles() {
    return `
        .conversation-container {
            display: flex;
            flex-direction: column;
            height: 100%;
            background: linear-gradient(180deg, #0f1f34 0%, #0b1425 100%);
        }
        
        .conversation-header {
            display: flex;
            align-items: center;
            padding: 12px 16px;
            border-bottom: 1px solid var(--border);
            background: rgba(15, 31, 52, 0.9);
        }
        
        .back-btn {
            width: 40px;
            height: 40px;
            border-radius: 12px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            color: var(--text);
            cursor: pointer;
            font-size: 20px;
            margin-right: 12px;
        }
        
        .chat-header-info {
            display: flex;
            align-items: center;
            gap: 12px;
            flex: 1;
            cursor: pointer;
        }
        
        .chat-avatar {
            width: 40px;
            height: 40px;
            border-radius: 14px;
            color: white;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
        }
        
        .chat-info h2 {
            font-size: 15px;
            font-weight: 600;
            margin: 0 0 2px 0;
            color: var(--text);
        }
        
        .chat-info p {
            font-size: 12px;
            color: var(--text-secondary);
            margin: 0;
        }
        
        .messages-container {
            flex: 1;
            overflow-y: auto;
            padding: 18px 20px 12px;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        
        .message {
            display: flex;
            flex-direction: column;
            max-width: 75%;
        }
        
        .message.sent {
            align-self: flex-end;
        }
        
        .message.received {
            align-self: flex-start;
        }
        
        .message-bubble {
            padding: 12px 14px;
            border-radius: 16px;
            font-size: 15px;
            line-height: 1.5;
            position: relative;
            box-shadow: 0 8px 22px rgba(0,0,0,0.25);
        }
        
        .message.sent .message-bubble {
            background: linear-gradient(135deg, #1b8cff 0%, #4bc0c8 100%);
            color: white;
            border-bottom-right-radius: 6px;
        }
        
        .message.received .message-bubble {
            background: #152a46;
            color: var(--text);
            border-bottom-left-radius: 6px;
            border: 1px solid var(--border);
        }
        
        .message-status {
            font-size: 12px;
            margin-left: 8px;
            opacity: 0.9;
            display: inline-flex;
            align-items: center;
            vertical-align: middle;
        }
        
        /* Status icon styles */
        .status-icon {
            cursor: help;
        }
        
        .status-icon.sending {
            color: rgba(255, 255, 255, 0.6);
        }
        
        .status-icon.sent {
            color: rgba(255, 255, 255, 0.7);
        }
        
        .status-icon.pending {
            color: #f59e0b;
            animation: pulse 2s infinite;
        }
        
        .status-icon.delivered {
            color: rgba(255, 255, 255, 0.85);
        }
        
        .status-icon.read {
            color: #1b8cff !important;
            font-weight: bold;
        }
        
        .status-icon.failed {
            color: #f43f5e;
            cursor: pointer;
        }
        
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.5; }
        }
        
        .message-time {
            font-size: 11px;
            color: var(--text-secondary);
            margin-top: 4px;
            padding: 0 4px;
        }
        
        .message.sent .message-time {
            text-align: right;
        }
        
        .typing-indicator {
            padding: 8px 20px;
            font-size: 13px;
            color: var(--text-secondary);
        }
        
        .typing-indicator span {
            display: inline-block;
            width: 8px;
            height: 8px;
            background: var(--text-secondary);
            border-radius: 50%;
            margin-right: 4px;
            animation: typing 1.4s infinite ease-in-out;
        }
        
        .typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
        .typing-indicator span:nth-child(3) { animation-delay: 0.4s; }
        
        @keyframes typing {
            0%, 60%, 100% { transform: translateY(0); }
            30% { transform: translateY(-8px); }
        }
        
        .input-area {
            padding: 14px 16px;
            display: flex;
            gap: 10px;
            align-items: flex-end;
            background: rgba(12, 23, 41, 0.9);
            border-top: 1px solid var(--border);
        }
        
        .input-actions {
            display: flex;
            gap: 8px;
        }
        
        .action-btn {
            width: 38px;
            height: 38px;
            border-radius: 12px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            cursor: pointer;
            font-size: 18px;
            display: flex;
            align-items: center;
            justify-content: center;
            color: var(--text);
        }
        
        .message-input {
            flex: 1;
            padding: 12px 16px;
            border: 1px solid var(--border);
            border-radius: 14px;
            font-size: 15px;
            font-family: inherit;
            resize: none;
            max-height: 100px;
            background: var(--bg-card);
            color: var(--text);
        }
        
        .message-input:focus {
            outline: none;
            border-color: rgba(27, 140, 255, 0.6);
        }
        
        .send-btn {
            width: 42px;
            height: 42px;
            border-radius: 14px;
            background: linear-gradient(135deg, #1b8cff, #4bc0c8);
            border: none;
            color: white;
            cursor: pointer;
            font-size: 18px;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 12px 30px rgba(27, 140, 255, 0.35);
        }
        
        .send-btn:disabled {
            opacity: 0.4;
            cursor: not-allowed;
            box-shadow: none;
        }
        
        .empty-messages {
            flex: 1;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            color: var(--text-secondary);
        }
        
        .empty-messages .hint {
            font-size: 12px;
            margin-top: 8px;
            opacity: 0.7;
        }
        
        .contact-info-modal {
            text-align: center;
            padding: 20px 0;
        }
        
        .contact-avatar-large {
            width: 80px;
            height: 80px;
            border-radius: 20px;
            margin: 0 auto 16px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 32px;
            font-weight: 700;
            color: white;
        }
        
        .contact-info-modal h2 {
            margin: 0 0 4px 0;
            font-size: 20px;
        }
        
        .display-name {
            color: var(--text-secondary);
            font-size: 14px;
            margin-bottom: 20px;
        }
        
        .info-row {
            display: flex;
            align-items: center;
            padding: 12px 0;
            border-bottom: 1px solid var(--border);
        }
        
        .info-row .label {
            width: 100px;
            color: var(--text-secondary);
            font-size: 13px;
        }
        
        .info-row .value {
            flex: 1;
            font-size: 14px;
        }
        
        .copy-btn {
            background: none;
            border: none;
            cursor: pointer;
            font-size: 14px;
            padding: 4px 8px;
        }
    `;
}
