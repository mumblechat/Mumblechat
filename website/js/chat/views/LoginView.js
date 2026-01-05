/**
 * MumbleChat Login View
 * Wallet connection and registration screen
 */

import { state, loadPersistedData, saveUserData } from '../state.js';
import { connectWallet, checkContractRegistration, registerOnContract, shortenAddress } from '../wallet.js';
import { RELAY_DEFAULTS } from '../config.js';
import { showLoading, hideLoading, showToast } from '../ui.js';

/**
 * Render login screen
 */
export function renderLoginView() {
    const savedRelayUrl = state.settings.relayUrl || RELAY_DEFAULTS.default;
    
    document.body.innerHTML = `
        <div class="login-container">
            <div class="login-card">
                <div class="login-logo">💬</div>
                <h1 class="login-title">MumbleChat</h1>
                <p class="login-subtitle">Decentralized, end-to-end encrypted messaging on Ramestta</p>
                
                <div id="walletConnect" class="login-form">
                    <div class="form-group">
                        <label>Relay Server</label>
                        <select id="relaySelect" class="form-select">
                            <option value="${RELAY_DEFAULTS.desktop}" ${savedRelayUrl === RELAY_DEFAULTS.desktop ? 'selected' : ''}>
                                Desktop Relay (localhost:19371)
                            </option>
                            <option value="${RELAY_DEFAULTS.mobile}" ${savedRelayUrl === RELAY_DEFAULTS.mobile ? 'selected' : ''}>
                                Mobile Relay (localhost:8444)
                            </option>
                            <option value="custom" ${!Object.values(RELAY_DEFAULTS).includes(savedRelayUrl) ? 'selected' : ''}>
                                Custom Relay URL...
                            </option>
                        </select>
                        <input type="text" id="customRelayUrl" class="form-input" 
                            placeholder="ws://relay.example.com:19371"
                            style="display: ${!Object.values(RELAY_DEFAULTS).includes(savedRelayUrl) ? 'block' : 'none'}; margin-top: 10px;"
                            value="${!Object.values(RELAY_DEFAULTS).includes(savedRelayUrl) ? savedRelayUrl : ''}">
                    </div>
                    
                    <button id="connectBtn" class="btn-connect">
                        🔗 Connect Wallet
                    </button>
                    
                    <p class="login-hint">Works with MetaMask and compatible wallets</p>
                </div>

                <div id="spinner" class="login-spinner" style="display: none;">
                    <div class="spinner"></div>
                    <p>Connecting...</p>
                </div>
                
                <a href="index.html" class="back-link">← Back to MumbleChat Home</a>
            </div>
        </div>
    `;
    
    setupLoginListeners();
}

/**
 * Setup login event listeners
 */
function setupLoginListeners() {
    const relaySelect = document.getElementById('relaySelect');
    const customRelayUrl = document.getElementById('customRelayUrl');
    const connectBtn = document.getElementById('connectBtn');
    
    relaySelect.addEventListener('change', function() {
        customRelayUrl.style.display = this.value === 'custom' ? 'block' : 'none';
    });
    
    connectBtn.addEventListener('click', handleConnect);
}

/**
 * Handle wallet connection
 */
async function handleConnect() {
    const relaySelect = document.getElementById('relaySelect');
    const customRelayUrl = document.getElementById('customRelayUrl');
    const walletConnect = document.getElementById('walletConnect');
    const spinner = document.getElementById('spinner');
    
    // Get relay URL
    if (relaySelect.value === 'custom' && customRelayUrl.value) {
        state.settings.relayUrl = customRelayUrl.value;
    } else if (relaySelect.value !== 'custom') {
        state.settings.relayUrl = relaySelect.value;
    }
    
    // Save relay preference
    localStorage.setItem('mumblechat_relay_url', state.settings.relayUrl);
    
    // Show loading
    walletConnect.style.display = 'none';
    spinner.style.display = 'block';
    
    try {
        await connectWallet();
        
        // Check if already registered
        const registration = await checkContractRegistration();
        
        if (registration) {
            // Already registered - load chat
            state.isOnChainRegistered = true;
            state.displayName = registration.displayName;
            state.registeredAt = registration.registeredAt;
            state.lastUpdated = registration.lastUpdated;
            state.publicKey = registration.publicKeyX;
            state.keyVersion = registration.keyVersion;
            state.username = state.address.slice(0, 6);
            state.isRegistered = true;
            
            saveUserData();
            
            // Redirect to main app
            window.dispatchEvent(new CustomEvent('userAuthenticated'));
        } else {
            // Not registered - show registration prompt
            spinner.style.display = 'none';
            renderRegistrationView();
        }
    } catch (error) {
        console.error('Connection failed:', error);
        showToast('Connection failed: ' + error.message, 'error');
        walletConnect.style.display = 'block';
        spinner.style.display = 'none';
    }
}

/**
 * Render registration screen
 */
export function renderRegistrationView() {
    document.body.innerHTML = `
        <div class="login-container">
            <div class="login-card">
                <div class="login-logo">📝</div>
                <h1 class="login-title">Set Display Name</h1>
                <p class="login-subtitle">Choose how others will see you</p>
                
                <div id="registrationForm" class="login-form">
                    <div class="form-group">
                        <input type="text" id="displayNameInput" class="form-input" 
                            placeholder="Enter display name" autofocus>
                    </div>
                    
                    <button id="registerBtn" class="btn-connect">
                        Register & Enter Chat
                    </button>
                </div>
                
                <div id="regSpinner" class="login-spinner" style="display: none;">
                    <div class="spinner"></div>
                    <p>Registering on blockchain...</p>
                </div>
            </div>
        </div>
    `;
    
    const displayNameInput = document.getElementById('displayNameInput');
    const registerBtn = document.getElementById('registerBtn');
    
    registerBtn.addEventListener('click', handleRegistration);
    displayNameInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') handleRegistration();
    });
}

/**
 * Handle registration submission
 */
async function handleRegistration() {
    const displayNameInput = document.getElementById('displayNameInput');
    const registrationForm = document.getElementById('registrationForm');
    const regSpinner = document.getElementById('regSpinner');
    
    const displayName = displayNameInput.value.trim();
    
    if (!displayName) {
        showToast('Please enter a display name', 'error');
        return;
    }
    
    if (displayName.length < 2) {
        showToast('Display name must be at least 2 characters', 'error');
        return;
    }
    
    // Show loading
    registrationForm.style.display = 'none';
    regSpinner.style.display = 'block';
    
    try {
        await registerOnContract(displayName);
        
        state.username = state.address.slice(0, 6);
        state.isRegistered = true;
        
        saveUserData();
        
        showToast('Registration successful!', 'success');
        
        // Redirect to main app
        setTimeout(() => {
            window.dispatchEvent(new CustomEvent('userAuthenticated'));
        }, 1000);
    } catch (error) {
        console.error('Registration failed:', error);
        showToast('Registration failed: ' + error.message, 'error');
        registrationForm.style.display = 'block';
        regSpinner.style.display = 'none';
    }
}

/**
 * Get login styles
 */
export function getLoginStyles() {
    return `
        .login-container {
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            background: linear-gradient(135deg, #0c1729 0%, #1a3050 100%);
            padding: 20px;
        }
        
        .login-card {
            background: rgba(19, 40, 66, 0.9);
            padding: 40px;
            border-radius: 20px;
            max-width: 420px;
            width: 100%;
            text-align: center;
            backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.1);
            box-shadow: 0 20px 60px rgba(0,0,0,0.5);
        }
        
        .login-logo {
            font-size: 48px;
            margin-bottom: 16px;
        }
        
        .login-title {
            font-size: 28px;
            font-weight: 700;
            color: var(--text);
            margin-bottom: 8px;
        }
        
        .login-subtitle {
            color: var(--text-secondary);
            font-size: 14px;
            margin-bottom: 24px;
        }
        
        .login-form {
            text-align: left;
        }
        
        .form-group {
            margin-bottom: 16px;
        }
        
        .form-group label {
            display: block;
            font-size: 13px;
            margin-bottom: 8px;
            color: var(--text-secondary);
        }
        
        .form-select,
        .form-input {
            width: 100%;
            padding: 12px;
            background: rgba(15, 31, 52, 0.8);
            color: var(--text);
            border: 1px solid rgba(255,255,255,0.1);
            border-radius: 8px;
            font-size: 14px;
        }
        
        .form-select:focus,
        .form-input:focus {
            outline: none;
            border-color: var(--primary);
        }
        
        .btn-connect {
            width: 100%;
            padding: 14px;
            background: linear-gradient(135deg, #1b8cff 0%, #4bc0c8 100%);
            color: white;
            border: none;
            border-radius: 10px;
            font-weight: 600;
            cursor: pointer;
            font-size: 15px;
            box-shadow: 0 10px 30px rgba(27, 140, 255, 0.35);
            transition: transform 0.2s, opacity 0.2s;
        }
        
        .btn-connect:hover {
            transform: translateY(-2px);
            opacity: 0.95;
        }
        
        .btn-connect:active {
            transform: translateY(0);
        }
        
        .login-hint {
            text-align: center;
            margin-top: 16px;
            font-size: 12px;
            color: var(--text-secondary);
            opacity: 0.6;
        }
        
        .login-spinner {
            text-align: center;
            padding: 20px;
        }
        
        .spinner {
            width: 36px;
            height: 36px;
            border: 3px solid rgba(255,255,255,0.3);
            border-radius: 50%;
            border-top-color: var(--primary);
            animation: spin 1s linear infinite;
            margin: 0 auto 15px;
        }
        
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
        
        .back-link {
            display: block;
            margin-top: 24px;
            font-size: 12px;
            color: var(--primary);
            text-decoration: none;
            opacity: 0.7;
            transition: opacity 0.2s;
        }
        
        .back-link:hover {
            opacity: 1;
        }
    `;
}
