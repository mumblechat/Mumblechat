/**
 * MumbleChat Relay View (Placeholder)
 */

import { state } from '../state.js';
import { showToast } from '../ui.js';

/**
 * Render relay view
 */
export function renderRelayView(container) {
    container.innerHTML = `
        <div class="relay-view">
            <div class="relay-header">
                <button class="back-btn" id="backBtn">←</button>
                <h2>Relay Node</h2>
            </div>
            
            <div class="relay-content">
                <div class="relay-status-card">
                    <div class="status-indicator ${state.relayConnected ? 'connected' : 'disconnected'}"></div>
                    <div class="status-text">
                        <h3>${state.relayConnected ? 'Connected' : 'Disconnected'}</h3>
                        <p>${state.settings.relayUrl || 'No relay configured'}</p>
                    </div>
                </div>
                
                <div class="info-section">
                    <h4>Relay Configuration</h4>
                    <p class="info-text">
                        Configure your relay node settings. Relay nodes help route messages 
                        when peers are not directly connected.
                    </p>
                    
                    <div class="relay-tiers">
                        <h4>Available Tiers</h4>
                        <div class="tier-card">
                            <div class="tier-name">🥉 Bronze</div>
                            <div class="tier-info">1000 MCT stake • 10 GB storage</div>
                        </div>
                        <div class="tier-card">
                            <div class="tier-name">🥈 Silver</div>
                            <div class="tier-info">5000 MCT stake • 50 GB storage</div>
                        </div>
                        <div class="tier-card">
                            <div class="tier-name">🥇 Gold</div>
                            <div class="tier-info">10000 MCT stake • 100 GB storage</div>
                        </div>
                        <div class="tier-card">
                            <div class="tier-name">💎 Platinum</div>
                            <div class="tier-info">25000 MCT stake • 500 GB storage</div>
                        </div>
                    </div>
                    
                    <button class="btn-primary" id="activateRelayBtn">
                        Activate Relay Node
                    </button>
                </div>
            </div>
        </div>
    `;
    
    setupRelayListeners();
}

/**
 * Setup event listeners
 */
function setupRelayListeners() {
    document.getElementById('backBtn')?.addEventListener('click', () => {
        window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'settings' } }));
    });
    
    document.getElementById('activateRelayBtn')?.addEventListener('click', () => {
        showToast('Relay node activation coming soon!', 'info');
    });
}

/**
 * Get styles for relay view
 */
export function getRelayStyles() {
    return `
        .relay-view {
            height: 100%;
            display: flex;
            flex-direction: column;
            background: linear-gradient(180deg, #0f1f34 0%, #0c1729 100%);
        }
        
        .relay-header {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 16px;
            border-bottom: 1px solid var(--border);
        }
        
        .relay-header h2 {
            margin: 0;
            font-size: 20px;
            font-weight: 700;
        }
        
        .relay-content {
            flex: 1;
            overflow-y: auto;
            padding: 24px;
        }
        
        .relay-status-card {
            display: flex;
            align-items: center;
            gap: 16px;
            padding: 20px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 12px;
            margin-bottom: 24px;
        }
        
        .status-indicator {
            width: 20px;
            height: 20px;
            border-radius: 50%;
            background: var(--error);
        }
        
        .status-indicator.connected {
            background: var(--success);
            box-shadow: 0 0 10px rgba(51, 214, 159, 0.5);
        }
        
        .status-text h3 {
            margin: 0 0 4px;
            font-size: 18px;
            color: var(--text);
        }
        
        .status-text p {
            margin: 0;
            font-size: 13px;
            color: var(--text-secondary);
        }
        
        .info-section {
            max-width: 600px;
        }
        
        .info-section h4 {
            font-size: 16px;
            margin: 0 0 12px;
            color: var(--text);
        }
        
        .info-text {
            color: var(--text-secondary);
            font-size: 14px;
            line-height: 1.6;
            margin-bottom: 24px;
        }
        
        .relay-tiers {
            margin-bottom: 24px;
        }
        
        .tier-card {
            padding: 14px 16px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 10px;
            margin-bottom: 10px;
        }
        
        .tier-name {
            font-weight: 600;
            font-size: 15px;
            color: var(--text);
            margin-bottom: 4px;
        }
        
        .tier-info {
            font-size: 13px;
            color: var(--text-secondary);
        }
    `;
}
