/**
 * MumbleChat New Chat View
 * Add new contact / start new conversation
 */

import { state } from '../state.js';
import { addContact, getContact } from '../contacts.js';
import { showToast } from '../ui.js';

/**
 * Render new chat view
 */
export function renderNewChatView(container) {
    container.innerHTML = `
        <div class="new-chat-view">
            <div class="new-chat-header">
                <button class="back-btn" id="backBtn">←</button>
                <h2>New Chat</h2>
            </div>
            
            <div class="new-chat-content">
                <div class="form-group">
                    <label>Enter wallet address</label>
                    <input type="text" id="addressInput" class="form-input" 
                        placeholder="0x..." autocomplete="off">
                </div>
                
                <div class="form-group">
                    <label>Display name (optional)</label>
                    <input type="text" id="nameInput" class="form-input" 
                        placeholder="Contact name">
                </div>
                
                <button class="btn-primary" id="addContactBtn">Add Contact</button>
                
                <div class="quick-actions">
                    <button class="btn-secondary" id="pasteBtn">📋 Paste from clipboard</button>
                    <button class="btn-secondary" id="qrBtn">📷 Scan QR code</button>
                </div>
                
                <div class="address-info" id="addressInfo" style="display: none;">
                    <div class="info-item">
                        <span class="label">Status:</span>
                        <span class="value" id="regStatus">Checking...</span>
                    </div>
                </div>
            </div>
        </div>
    `;
    
    setupNewChatListeners();
}

/**
 * Setup event listeners
 */
function setupNewChatListeners() {
    const backBtn = document.getElementById('backBtn');
    const addContactBtn = document.getElementById('addContactBtn');
    const addressInput = document.getElementById('addressInput');
    const nameInput = document.getElementById('nameInput');
    const pasteBtn = document.getElementById('pasteBtn');
    const qrBtn = document.getElementById('qrBtn');
    
    backBtn?.addEventListener('click', () => {
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'chats' } }));
    });
    
    addContactBtn?.addEventListener('click', handleAddContact);
    
    addressInput?.addEventListener('input', (e) => {
        const address = e.target.value.trim();
        if (address.match(/^0x[a-fA-F0-9]{40}$/)) {
            checkAddressRegistration(address);
        } else {
            document.getElementById('addressInfo').style.display = 'none';
        }
    });
    
    pasteBtn?.addEventListener('click', async () => {
        try {
            const text = await navigator.clipboard.readText();
            if (text.match(/^0x[a-fA-F0-9]{40}$/)) {
                addressInput.value = text;
                checkAddressRegistration(text);
            } else {
                showToast('Invalid address in clipboard', 'error');
            }
        } catch (err) {
            showToast('Could not read clipboard', 'error');
        }
    });
    
    qrBtn?.addEventListener('click', () => {
        showToast('QR scanning not yet implemented', 'info');
    });
}

/**
 * Handle add contact
 */
async function handleAddContact() {
    const addressInput = document.getElementById('addressInput');
    const nameInput = document.getElementById('nameInput');
    
    const address = addressInput.value.trim();
    const name = nameInput.value.trim();
    
    if (!address) {
        showToast('Please enter an address', 'error');
        return;
    }
    
    if (!address.match(/^0x[a-fA-F0-9]{40}$/)) {
        showToast('Invalid Ethereum address', 'error');
        return;
    }
    
    if (address.toLowerCase() === state.address?.toLowerCase()) {
        showToast('Cannot add yourself as a contact', 'error');
        return;
    }
    
    const existing = getContact(address);
    if (existing) {
        showToast('Contact already exists', 'error');
        return;
    }
    
    try {
        await addContact(address, name || address.slice(0, 8));
        showToast('Contact added successfully', 'success');
        
        // Navigate to chat with new contact
        setTimeout(() => {
            window.dispatchEvent(new CustomEvent('openConversation', { detail: { address } }));
        }, 500);
    } catch (error) {
        showToast('Failed to add contact: ' + error.message, 'error');
    }
}

/**
 * Check if address is registered on contract
 */
async function checkAddressRegistration(address) {
    const addressInfo = document.getElementById('addressInfo');
    const regStatus = document.getElementById('regStatus');
    
    addressInfo.style.display = 'block';
    regStatus.textContent = 'Checking...';
    
    try {
        // TODO: Implement actual contract check
        // For now, just show as valid
        setTimeout(() => {
            regStatus.textContent = '✅ Valid address';
            regStatus.style.color = 'var(--success)';
        }, 500);
    } catch (error) {
        regStatus.textContent = '❌ Not registered';
        regStatus.style.color = 'var(--error)';
    }
}

/**
 * Get styles for new chat view
 */
export function getNewChatStyles() {
    return `
        .new-chat-view {
            height: 100%;
            display: flex;
            flex-direction: column;
            background: linear-gradient(180deg, #0f1f34 0%, #0c1729 100%);
        }
        
        .new-chat-header {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 16px;
            border-bottom: 1px solid var(--border);
        }
        
        .new-chat-header h2 {
            margin: 0;
            font-size: 20px;
            font-weight: 700;
        }
        
        .back-btn {
            width: 40px;
            height: 40px;
            border-radius: 12px;
            background: var(--bg-soft);
            border: 1px solid var(--border);
            color: var(--text);
            font-size: 20px;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        
        .back-btn:hover {
            background: #17345a;
        }
        
        .new-chat-content {
            padding: 24px;
            max-width: 500px;
            margin: 0 auto;
            width: 100%;
        }
        
        .quick-actions {
            display: flex;
            gap: 12px;
            margin-top: 16px;
        }
        
        .quick-actions .btn-secondary {
            flex: 1;
        }
        
        .address-info {
            margin-top: 20px;
            padding: 16px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 12px;
        }
        
        .info-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .info-item .label {
            font-weight: 600;
            color: var(--text-secondary);
        }
        
        .info-item .value {
            color: var(--text);
        }
    `;
}
