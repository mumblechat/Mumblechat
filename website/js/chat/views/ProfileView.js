/**
 * MumbleChat Profile View
 */

import { state } from '../state.js';
import { updateDisplayName } from '../wallet.js';
import { showToast, shortenAddress } from '../ui.js';

/**
 * Render profile view
 */
export function renderProfileView(container) {
    container.innerHTML = `
        <div class="profile-view">
            <div class="profile-header">
                <button class="back-btn" id="backBtn">←</button>
                <h2>My Profile</h2>
            </div>
            
            <div class="profile-content">
                <div class="profile-avatar-section">
                    <div class="profile-avatar">${state.displayName?.charAt(0)?.toUpperCase() || '?'}</div>
                    <h3>${state.displayName || 'Not set'}</h3>
                    <p class="profile-address">${shortenAddress(state.address)}</p>
                </div>
                
                <div class="profile-info">
                    <div class="info-card">
                        <div class="info-label">Display Name</div>
                        <div class="info-value-editable">
                            <input type="text" id="displayNameInput" class="profile-input" 
                                value="${state.displayName || ''}" placeholder="Enter display name">
                            <button class="btn-small" id="updateNameBtn">Update</button>
                        </div>
                    </div>
                    
                    <div class="info-card">
                        <div class="info-label">Wallet Address</div>
                        <div class="info-value">${state.address}</div>
                    </div>
                    
                    <div class="info-card">
                        <div class="info-label">Public Key</div>
                        <div class="info-value">${state.publicKey || 'Not available'}</div>
                    </div>
                    
                    <div class="info-card">
                        <div class="info-label">Key Version</div>
                        <div class="info-value">${state.keyVersion || 1}</div>
                    </div>
                    
                    <div class="info-card">
                        <div class="info-label">Registration Status</div>
                        <div class="info-value">
                            ${state.isOnChainRegistered ? '✅ Registered on-chain' : '❌ Not registered'}
                        </div>
                    </div>
                    
                    ${state.registeredAt ? `
                        <div class="info-card">
                            <div class="info-label">Registered At</div>
                            <div class="info-value">${new Date(state.registeredAt).toLocaleString()}</div>
                        </div>
                    ` : ''}
                </div>
            </div>
        </div>
    `;
    
    setupProfileListeners();
}

/**
 * Setup event listeners
 */
function setupProfileListeners() {
    document.getElementById('backBtn')?.addEventListener('click', () => {
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'settings' } }));
    });
    
    document.getElementById('updateNameBtn')?.addEventListener('click', async () => {
        const input = document.getElementById('displayNameInput');
        const newName = input.value.trim();
        
        if (!newName) {
            showToast('Please enter a display name', 'error');
            return;
        }
        
        if (newName === state.displayName) {
            showToast('Name unchanged', 'info');
            return;
        }
        
        try {
            await updateDisplayName(newName);
            showToast('Display name updated', 'success');
            renderProfileView(document.getElementById('mainView'));
        } catch (error) {
            showToast('Failed to update name: ' + error.message, 'error');
        }
    });
}

/**
 * Get styles for profile view
 */
export function getProfileStyles() {
    return `
        .profile-view {
            height: 100%;
            display: flex;
            flex-direction: column;
            background: linear-gradient(180deg, #0f1f34 0%, #0c1729 100%);
        }
        
        .profile-header {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 16px;
            border-bottom: 1px solid var(--border);
        }
        
        .profile-header h2 {
            margin: 0;
            font-size: 20px;
            font-weight: 700;
        }
        
        .profile-content {
            flex: 1;
            overflow-y: auto;
            padding: 24px;
        }
        
        .profile-avatar-section {
            text-align: center;
            padding: 24px 0;
            border-bottom: 1px solid var(--border);
            margin-bottom: 24px;
        }
        
        .profile-avatar {
            width: 100px;
            height: 100px;
            border-radius: 50%;
            background: linear-gradient(135deg, #1b8cff, #4bc0c8);
            color: white;
            font-size: 48px;
            font-weight: 700;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            margin-bottom: 16px;
            box-shadow: 0 10px 30px rgba(27, 140, 255, 0.35);
        }
        
        .profile-avatar-section h3 {
            margin: 0 0 8px;
            font-size: 24px;
            color: var(--text);
        }
        
        .profile-address {
            color: var(--text-secondary);
            font-size: 14px;
            margin: 0;
        }
        
        .profile-info {
            max-width: 600px;
            margin: 0 auto;
        }
        
        .info-card {
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 16px;
            margin-bottom: 12px;
        }
        
        .info-label {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-secondary);
            margin-bottom: 8px;
        }
        
        .info-value {
            color: var(--text);
            word-break: break-all;
        }
        
        .info-value-editable {
            display: flex;
            gap: 12px;
            align-items: center;
        }
        
        .profile-input {
            flex: 1;
            padding: 10px 12px;
            background: var(--bg-soft);
            border: 1px solid var(--border);
            border-radius: 8px;
            color: var(--text);
            font-size: 14px;
        }
        
        .profile-input:focus {
            outline: none;
            border-color: var(--primary);
        }
        
        .btn-small {
            padding: 10px 16px;
            background: var(--primary);
            color: white;
            border: none;
            border-radius: 8px;
            font-weight: 600;
            cursor: pointer;
            font-size: 13px;
        }
        
        .btn-small:hover {
            opacity: 0.9;
        }
    `;
}
