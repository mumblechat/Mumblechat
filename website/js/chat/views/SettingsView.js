/**
 * MumbleChat Settings View
 */

import { state, saveSettings } from '../state.js';
import { disconnectWallet, rotateKeys } from '../wallet.js';
import { showToast, showConfirm } from '../ui.js';

/**
 * Render settings view
 */
export function renderSettingsView(container) {
    container.innerHTML = `
        <div class="settings-view">
            <div class="settings-header">
                <button class="back-btn" id="backBtn">←</button>
                <h2>Settings</h2>
            </div>
            
            <div class="settings-content">
                <!-- Profile Section -->
                <div class="settings-section">
                    <h3>Profile</h3>
                    <div class="settings-item" id="profileBtn">
                        <div class="item-icon">👤</div>
                        <div class="item-info">
                            <div class="item-title">My Profile</div>
                            <div class="item-subtitle">${state.displayName || 'Not set'}</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                </div>
                
                <!-- Privacy Section -->
                <div class="settings-section">
                    <h3>Privacy & Security</h3>
                    <div class="settings-item" id="rotateKeysBtn">
                        <div class="item-icon">🔑</div>
                        <div class="item-info">
                            <div class="item-title">Rotate Encryption Keys</div>
                            <div class="item-subtitle">Current version: ${state.keyVersion || 1}</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                    <div class="settings-item" id="blockedBtn">
                        <div class="item-icon">🚫</div>
                        <div class="item-info">
                            <div class="item-title">Blocked Contacts</div>
                            <div class="item-subtitle">${state.blockedContacts?.length || 0} blocked</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                </div>
                
                <!-- Relay Section -->
                <div class="settings-section">
                    <h3>Network</h3>
                    <div class="settings-item" id="relayBtn">
                        <div class="item-icon">📡</div>
                        <div class="item-info">
                            <div class="item-title">Relay Node</div>
                            <div class="item-subtitle">${state.relayConnected ? 'Connected' : 'Disconnected'}</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                </div>
                
                <!-- Data Section -->
                <div class="settings-section">
                    <h3>Data & Storage</h3>
                    <div class="settings-item" id="backupBtn">
                        <div class="item-icon">💾</div>
                        <div class="item-info">
                            <div class="item-title">Backup Data</div>
                            <div class="item-subtitle">Export contacts & messages</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                    <div class="settings-item danger" id="clearDataBtn">
                        <div class="item-icon">🗑️</div>
                        <div class="item-info">
                            <div class="item-title">Clear All Data</div>
                            <div class="item-subtitle">Delete all local data</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                </div>
                
                <!-- Account Section -->
                <div class="settings-section">
                    <h3>Account</h3>
                    <div class="settings-item" id="disconnectBtn">
                        <div class="item-icon">🔌</div>
                        <div class="item-info">
                            <div class="item-title">Disconnect Wallet</div>
                            <div class="item-subtitle">${state.address?.slice(0, 10)}...</div>
                        </div>
                        <div class="item-arrow">›</div>
                    </div>
                </div>
                
                <!-- About Section -->
                <div class="settings-section">
                    <h3>About</h3>
                    <div class="settings-item">
                        <div class="item-icon">ℹ️</div>
                        <div class="item-info">
                            <div class="item-title">MumbleChat</div>
                            <div class="item-subtitle">Version 1.0.0</div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    `;
    
    setupSettingsListeners();
}

/**
 * Setup event listeners
 */
function setupSettingsListeners() {
    document.getElementById('backBtn')?.addEventListener('click', () => {
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'chats' } }));
    });
    
    document.getElementById('profileBtn')?.addEventListener('click', () => {
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'profile' } }));
    });
    
    document.getElementById('relayBtn')?.addEventListener('click', () => {
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'relay' } }));
    });
    
    document.getElementById('rotateKeysBtn')?.addEventListener('click', async () => {
        const confirmed = await showConfirm('Rotate encryption keys?', 
            'This will generate new keys and update them on the blockchain.');
        if (confirmed) {
            try {
                await rotateKeys();
                showToast('Keys rotated successfully', 'success');
                renderSettingsView(document.getElementById('mainView'));
            } catch (error) {
                showToast('Failed to rotate keys: ' + error.message, 'error');
            }
        }
    });
    
    document.getElementById('blockedBtn')?.addEventListener('click', () => {
        showToast('Blocked contacts view not yet implemented', 'info');
    });
    
    document.getElementById('backupBtn')?.addEventListener('click', () => {
        backupData();
    });
    
    document.getElementById('clearDataBtn')?.addEventListener('click', async () => {
        const confirmed = await showConfirm('Clear all data?', 
            'This will delete all contacts, messages, and settings. This cannot be undone.');
        if (confirmed) {
            localStorage.clear();
            showToast('All data cleared', 'success');
            setTimeout(() => location.reload(), 1000);
        }
    });
    
    document.getElementById('disconnectBtn')?.addEventListener('click', async () => {
        const confirmed = await showConfirm('Disconnect wallet?', 
            'You will need to reconnect to use MumbleChat.');
        if (confirmed) {
            disconnectWallet();
            location.reload();
        }
    });
}

/**
 * Backup data
 */
function backupData() {
    const backup = {
        contacts: state.contacts,
        groups: state.groups,
        settings: state.settings,
        exportedAt: new Date().toISOString()
    };
    
    const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `mumblechat-backup-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
    
    showToast('Backup downloaded', 'success');
}

/**
 * Get styles for settings view
 */
export function getSettingsStyles() {
    return `
        .settings-view {
            height: 100%;
            display: flex;
            flex-direction: column;
            background: linear-gradient(180deg, #0f1f34 0%, #0c1729 100%);
        }
        
        .settings-header {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 16px;
            border-bottom: 1px solid var(--border);
        }
        
        .settings-header h2 {
            margin: 0;
            font-size: 20px;
            font-weight: 700;
        }
        
        .settings-content {
            flex: 1;
            overflow-y: auto;
            padding: 8px;
        }
        
        .settings-section {
            margin-bottom: 24px;
        }
        
        .settings-section h3 {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.5px;
            padding: 0 16px 8px;
            margin: 0 0 8px;
        }
        
        .settings-item {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 14px 16px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 12px;
            margin: 8px;
            cursor: pointer;
            transition: background 0.2s;
        }
        
        .settings-item:hover {
            background: var(--bg-soft);
        }
        
        .settings-item.danger .item-title {
            color: var(--error);
        }
        
        .item-icon {
            font-size: 24px;
            width: 40px;
            height: 40px;
            display: flex;
            align-items: center;
            justify-content: center;
            background: var(--bg-soft);
            border-radius: 10px;
        }
        
        .item-info {
            flex: 1;
        }
        
        .item-title {
            font-weight: 600;
            font-size: 15px;
            color: var(--text);
            margin-bottom: 2px;
        }
        
        .item-subtitle {
            font-size: 13px;
            color: var(--text-secondary);
        }
        
        .item-arrow {
            font-size: 24px;
            color: var(--text-secondary);
        }
    `;
}
