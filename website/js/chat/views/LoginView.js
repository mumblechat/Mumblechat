/**
 * MumbleChat Login View
 * Wallet connection and registration screen
 */

import { state, loadPersistedData, saveUserData } from '../state.js';
import { connectWallet, checkContractRegistration, registerOnContract, shortenAddress, setWalletProvider } from '../wallet.js';
import { RELAY_DEFAULTS, RAMESTTA_CONFIG } from '../config.js';
import { showLoading, hideLoading, showToast } from '../ui.js';
import { fetchOnlineRelayNodes, getBestRelayNode, testRelayConnection } from '../relayService.js';
import { detectWallets, getRecommendedWallets, connectToWallet, hasAnyWallet, isMobile, getPlatform, initEIP6963 } from '../walletDetection.js';

// Cached relay nodes
let cachedNodes = { desktop: [], mobile: [], hub: [], all: [] };
let nodesLoading = false;
let selectedWalletId = null;
let detectedWalletsList = [];

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
                    <!-- Wallet Selection -->
                    <div class="form-group">
                        <label>Connect Wallet</label>
                        <div id="walletOptions" class="wallet-options">
                            <div class="relay-loading">
                                <div class="spinner-small"></div>
                                <span>Detecting wallets...</span>
                            </div>
                        </div>
                    </div>
                    
                    <!-- Relay Server Selection -->
                    <div class="form-group">
                        <label>Relay Server</label>
                        <div id="relayOptions" class="relay-options">
                            <div class="relay-loading">
                                <div class="spinner-small"></div>
                                <span>Finding online relay nodes...</span>
                            </div>
                        </div>
                        <input type="text" id="customRelayUrl" class="form-input" 
                            placeholder="ws://relay.example.com:19371"
                            style="display: none; margin-top: 10px;"
                            value="">
                    </div>
                    
                    <button id="connectBtn" class="btn-connect" disabled>
                        🔗 Connect Wallet
                    </button>
                    
                    <p class="login-hint" id="loginHint">Select a wallet to continue</p>
                </div>

                <div id="spinner" class="login-spinner" style="display: none;">
                    <div class="spinner"></div>
                    <p id="spinnerText">Connecting...</p>
                </div>
                
                <a href="index.html" class="back-link">← Back to MumbleChat Home</a>
            </div>
        </div>
    `;
    
    setupLoginListeners();
    detectAndShowWallets();
    loadRelayNodes();
}

/**
 * Setup login event listeners
 */
function setupLoginListeners() {
    const connectBtn = document.getElementById('connectBtn');
    connectBtn.addEventListener('click', handleConnect);
}

/**
 * Detect and display available wallets using EIP-6963
 */
async function detectAndShowWallets() {
    const container = document.getElementById('walletOptions');
    const connectBtn = document.getElementById('connectBtn');
    const hint = document.getElementById('loginHint');
    
    // Initialize EIP-6963 discovery first
    initEIP6963();
    
    // Show loading state
    container.innerHTML = `
        <div class="relay-loading">
            <div class="spinner-small"></div>
            <span>Detecting installed wallets...</span>
        </div>
    `;
    
    // Detect installed wallets (async - waits for EIP-6963 announcements)
    detectedWalletsList = await detectWallets();
    const recommendedWallets = getRecommendedWallets();
    const platform = getPlatform();
    const mobile = isMobile();
    
    let html = '';
    
    if (detectedWalletsList.length > 0) {
        // Show detected/installed wallets
        detectedWalletsList.forEach((wallet, index) => {
            const isFirst = index === 0;
            // Use wallet icon (data URL from EIP-6963) or fallback to emoji
            const iconDisplay = wallet.icon 
                ? `<img src="${wallet.icon}" alt="${wallet.name}" class="wallet-icon-img" onerror="this.outerHTML='${wallet.iconEmoji || '🔐'}'">`
                : wallet.iconEmoji || '🔐';
            
            html += `
                <div class="wallet-option ${isFirst ? 'selected' : ''}" 
                     data-wallet-id="${wallet.id}"
                     style="--wallet-color: ${wallet.color}">
                    <div class="wallet-option-icon">${iconDisplay}</div>
                    <div class="wallet-option-info">
                        <div class="wallet-option-title">${wallet.name}</div>
                        <div class="wallet-option-status installed">
                            <span class="status-dot online"></span>
                            Installed ${wallet.method === 'eip6963' ? '(EIP-6963)' : ''}
                        </div>
                    </div>
                    ${isFirst ? '<div class="wallet-option-check">✓</div>' : ''}
                </div>
            `;
            
            // Auto-select first wallet
            if (isFirst) {
                selectedWalletId = wallet.id;
            }
        });
        
        // Show recommended wallets to download
        if (recommendedWallets.length > 0 && !mobile) {
            html += `<div class="wallet-divider">Or get a wallet</div>`;
            recommendedWallets.slice(0, 2).forEach(wallet => {
                html += `
                    <div class="wallet-option download" 
                         data-download-url="${wallet.downloadUrl}"
                         style="--wallet-color: ${wallet.color}">
                        <div class="wallet-option-icon">${wallet.icon}</div>
                        <div class="wallet-option-info">
                            <div class="wallet-option-title">${wallet.name}</div>
                            <div class="wallet-option-status">
                                <span style="color: var(--primary);">↓ Install</span>
                            </div>
                        </div>
                    </div>
                `;
            });
        }
        
        hint.textContent = `${detectedWalletsList[0].name} detected • Click to connect`;
        connectBtn.disabled = false;
    } else {
        // No wallets detected - show different UI for mobile vs desktop
        if (mobile) {
            // Mobile: Show deep link options to open wallet apps
            // NOTE: On mobile, the dApp must run INSIDE the wallet's browser
            // There's no way to connect from Chrome and return - that's how mobile Web3 works
            html = `
                <div class="no-wallet-message">
                    <div class="no-wallet-icon">📱</div>
                    <div class="no-wallet-text">Open in Wallet Browser</div>
                    <div class="no-wallet-hint">Mobile wallets require using their built-in browser</div>
                </div>
            `;
            
            // Show wallet app options with deep links
            // These open the CURRENT PAGE inside the wallet's browser
            const currentUrl = window.location.href;
            const walletApps = [
                { 
                    name: 'MetaMask', 
                    icon: '🦊', 
                    color: '#f6851b', 
                    deepLink: 'https://metamask.app.link/dapp/' + window.location.host + window.location.pathname,
                    desc: 'Opens MumbleChat in MetaMask'
                },
                { 
                    name: 'Trust Wallet', 
                    icon: '🛡️', 
                    color: '#3375bb', 
                    deepLink: 'https://link.trustwallet.com/open_url?coin_id=60&url=' + encodeURIComponent(currentUrl),
                    desc: 'Opens MumbleChat in Trust Wallet'
                },
                { 
                    name: 'Coinbase Wallet', 
                    icon: '🔵', 
                    color: '#0052ff', 
                    deepLink: 'https://go.cb-w.com/dapp?cb_url=' + encodeURIComponent(currentUrl),
                    desc: 'Opens MumbleChat in Coinbase'
                }
            ];
            
            walletApps.forEach(wallet => {
                html += `
                    <a href="${wallet.deepLink}" class="wallet-download-btn" style="--wallet-color: ${wallet.color}">
                        <span class="wallet-download-icon">${wallet.icon}</span>
                        <div class="wallet-download-info">
                            <div class="wallet-download-name">Open in ${wallet.name}</div>
                            <div class="wallet-download-desc">${wallet.desc}</div>
                        </div>
                        <span class="wallet-download-arrow">→</span>
                    </a>
                `;
            });
            
            // Info message
            html += `
                <div class="wallet-info-box">
                    <p>💡 <strong>How it works:</strong></p>
                    <p>Click above to open MumbleChat inside your wallet app. You'll use the chat directly in the wallet's browser - this is how mobile Web3 apps work!</p>
                </div>
            `;
            
            // Also show download links
            html += `<div class="wallet-divider">Don't have a wallet?</div>`;
            
            const downloadLinks = platform === 'ios' ? [
                { name: 'MetaMask', icon: '🦊', url: 'https://apps.apple.com/app/metamask/id1438144202' },
                { name: 'Trust Wallet', icon: '🛡️', url: 'https://apps.apple.com/app/trust-crypto-bitcoin-wallet/id1288339409' }
            ] : [
                { name: 'MetaMask', icon: '🦊', url: 'https://play.google.com/store/apps/details?id=io.metamask' },
                { name: 'Trust Wallet', icon: '🛡️', url: 'https://play.google.com/store/apps/details?id=com.wallet.crypto.trustapp' }
            ];
            
            downloadLinks.forEach(wallet => {
                html += `
                    <a href="${wallet.url}" target="_blank" class="wallet-download-btn secondary">
                        <span class="wallet-download-icon">${wallet.icon}</span>
                        <div class="wallet-download-info">
                            <div class="wallet-download-name">Get ${wallet.name}</div>
                        </div>
                        <span class="wallet-download-arrow">↓</span>
                    </a>
                `;
            });
            
            hint.innerHTML = `<span style="color: #f59e0b;">📱 Open this page in your wallet's browser</span>`;
        } else {
            // Desktop: Show install options
            html = `
                <div class="no-wallet-message">
                    <div class="no-wallet-icon">🔐</div>
                    <div class="no-wallet-text">No wallet detected</div>
                    <div class="no-wallet-hint">Install a Web3 wallet to continue</div>
                </div>
            `;
            
            // Show download options
            const walletsToShow = [
                { id: 'ramaPay', name: 'RamaPay', icon: '💎', color: '#6366f1', desc: 'Recommended for Ramestta' },
                { id: 'metamask', name: 'MetaMask', icon: '🦊', color: '#f6851b', desc: 'Most popular wallet' }
            ];
            
            walletsToShow.forEach(wallet => {
                const downloadUrl = `https://chrome.google.com/webstore/detail/${wallet.id === 'ramaPay' ? 'ramapay' : 'metamask/nkbihfbeogaeaoehlefnkodbefgpgknn'}`;
                
                html += `
                    <a href="${downloadUrl}" target="_blank" class="wallet-download-btn" style="--wallet-color: ${wallet.color}">
                        <span class="wallet-download-icon">${wallet.icon}</span>
                        <div class="wallet-download-info">
                            <div class="wallet-download-name">Get ${wallet.name}</div>
                            <div class="wallet-download-desc">${wallet.desc}</div>
                        </div>
                        <span class="wallet-download-arrow">→</span>
                    </a>
                `;
            });
            
            hint.innerHTML = `<span style="color: #f59e0b;">⚠️ Install a wallet to use MumbleChat</span>`;
        }
        
        connectBtn.disabled = true;
        connectBtn.textContent = '📲 Install Wallet First';
    }
    
    container.innerHTML = html;
    
    // Setup click handlers for wallet options
    document.querySelectorAll('.wallet-option:not(.download)').forEach(option => {
        option.addEventListener('click', () => {
            // Deselect all
            document.querySelectorAll('.wallet-option').forEach(o => {
                o.classList.remove('selected');
                const check = o.querySelector('.wallet-option-check');
                if (check) check.remove();
            });
            
            // Select this one
            option.classList.add('selected');
            option.insertAdjacentHTML('beforeend', '<div class="wallet-option-check">✓</div>');
            
            selectedWalletId = option.dataset.walletId;
            const walletName = option.querySelector('.wallet-option-title').textContent;
            hint.textContent = `${walletName} selected • Click to connect`;
        });
    });
    
    // Setup download links
    document.querySelectorAll('.wallet-option.download').forEach(option => {
        option.addEventListener('click', () => {
            window.open(option.dataset.downloadUrl, '_blank');
        });
    });
}

/**
 * Load relay nodes from blockchain
 */
async function loadRelayNodes() {
    if (nodesLoading) return;
    nodesLoading = true;
    
    try {
        cachedNodes = await fetchOnlineRelayNodes(); console.log("Fetched nodes from hub:", JSON.stringify(cachedNodes));
        renderRelayOptions();
    } catch (error) {
        console.error('Failed to load relay nodes:', error);
        renderRelayOptionsFallback();
    } finally {
        nodesLoading = false;
    }
}

/**
 * Render relay options based on fetched nodes
 */
function renderRelayOptions() {
    const container = document.getElementById('relayOptions');
    const connectBtn = document.getElementById('connectBtn');
    const customInput = document.getElementById('customRelayUrl');
    
    const hubCount = cachedNodes.hub?.length || 0;
    const desktopCount = cachedNodes.desktop?.length || 0;
    const mobileCount = cachedNodes.mobile?.length || 0;
    const totalOnline = cachedNodes.all?.length || 0;
    
    console.log('🔍 renderRelayOptions called - hub:', hubCount, 'desktop:', desktopCount, 'total:', totalOnline);
    console.log('🔍 cachedNodes:', JSON.stringify(cachedNodes));
    
    let html = '';
    
    if (totalOnline > 0) {
        // Show hub relay nodes first (most reliable)
        if (hubCount > 0) {
            const bestNode = cachedNodes.hub[0];
            html += `
                <div class="relay-option selected available" 
                     data-type="hub" 
                     data-url="${bestNode.wsUrl}">
                    <div class="relay-option-icon">🌐</div>
                    <div class="relay-option-info">
                        <div class="relay-option-title">Hub Relay Network</div>
                        <div class="relay-option-status online">
                            <span class="status-dot online"></span>
                            ${hubCount} node${hubCount > 1 ? 's' : ''} online
                        </div>
                    </div>
                    <div class="relay-option-check">✓</div>
                </div>
            `;
            
            // Show individual hub nodes if more than 1
            if (hubCount > 1) {
                cachedNodes.hub.forEach((node, i) => {
                    html += `
                        <div class="relay-option available hub-node" 
                             data-type="hub-node" 
                             data-url="${node.wsUrl}"
                             data-tunnel="${node.tunnelId}">
                            <div class="relay-option-icon">📡</div>
                            <div class="relay-option-info">
                                <div class="relay-option-title">Node ${node.tunnelId.slice(0, 8)}</div>
                                <div class="relay-option-status online">
                                    <span class="status-dot online"></span>
                                    ${node.connectedUsers || 0} users • ${node.messagesRelayed || 0} msgs
                                </div>
                            </div>
                        </div>
                    `;
                });
            }
        }
        
        // Desktop nodes (blockchain registered)
        if (desktopCount > 0) {
            const desktopNode = cachedNodes.desktop[0];
            html += `
                <div class="relay-option ${hubCount === 0 ? 'selected' : ''} available"
                     data-type="desktop" 
                     data-url="${desktopNode.wsUrl}">
                    <div class="relay-option-icon">🖥️</div>
                    <div class="relay-option-info">
                        <div class="relay-option-title">Desktop Nodes</div>
                        <div class="relay-option-status online">
                            <span class="status-dot online"></span>
                            ${desktopCount} online
                        </div>
                    </div>
                    ${hubCount === 0 ? '<div class="relay-option-check">✓</div>' : ''}
                </div>
            `;
        }
        
        // Mobile nodes
        if (mobileCount > 0) {
            const mobileNode = cachedNodes.mobile[0];
            html += `
                <div class="relay-option available"
                     data-type="mobile" 
                     data-url="${mobileNode.wsUrl}">
                    <div class="relay-option-icon">📱</div>
                    <div class="relay-option-info">
                        <div class="relay-option-title">Mobile Nodes</div>
                        <div class="relay-option-status online">
                            <span class="status-dot online"></span>
                            ${mobileCount} online
                        </div>
                    </div>
                </div>
            `;
        }
    } else {
        // No online nodes - show fallback with local options
        html += `
            <div class="relay-option selected" data-type="desktop" data-url="${RELAY_DEFAULTS.desktop}">
                <div class="relay-option-icon">🖥️</div>
                <div class="relay-option-info">
                    <div class="relay-option-title">Desktop Relay (Local)</div>
                    <div class="relay-option-status offline">
                        <span class="status-dot offline"></span>
                        localhost:19371
                    </div>
                </div>
            </div>
            <div class="relay-option" data-type="mobile" data-url="${RELAY_DEFAULTS.mobile}">
                <div class="relay-option-icon">📱</div>
                <div class="relay-option-info">
                    <div class="relay-option-title">Mobile Relay (Local)</div>
                    <div class="relay-option-status offline">
                        <span class="status-dot offline"></span>
                        localhost:8444
                    </div>
                </div>
            </div>
            <div class="relay-no-nodes-hint">
                ⚠️ No online relay nodes found. Using local fallback.
            </div>
        `;
    }
    
    // Custom option always available
    html += `
        <div class="relay-option" data-type="custom">
            <div class="relay-option-icon">⚙️</div>
            <div class="relay-option-info">
                <div class="relay-option-title">Custom Relay URL</div>
                <div class="relay-option-status">Enter your own relay</div>
            </div>
        </div>
    `;
    
    container.innerHTML = html;
    
    // Enable connect button
    connectBtn.disabled = false;
    
    // Setup click handlers for options
    document.querySelectorAll('.relay-option').forEach(option => {
        option.addEventListener('click', () => {
            if (option.dataset.disabled === 'true') return;
            
            // Deselect all
            document.querySelectorAll('.relay-option').forEach(o => {
                o.classList.remove('selected');
                const check = o.querySelector('.relay-option-check');
                if (check) check.remove();
            });
            
            // Select this one
            option.classList.add('selected');
            if (option.dataset.type !== 'custom' && option.classList.contains('available')) {
                option.insertAdjacentHTML('beforeend', '<div class="relay-option-check">✓</div>');
            }
            
            // Show/hide custom input
            if (option.dataset.type === 'custom') {
                customInput.style.display = 'block';
                customInput.focus();
            } else {
                customInput.style.display = 'none';
            }
        });
    });
}

function renderRelayOptionsFallback() {
    const container = document.getElementById('relayOptions');
    const connectBtn = document.getElementById('connectBtn');
    
    container.innerHTML = `
        <div class="relay-option selected" data-type="desktop" data-url="${RELAY_DEFAULTS.desktop}">
            <div class="relay-option-icon">🖥️</div>
            <div class="relay-option-info">
                <div class="relay-option-title">Desktop Relay (Local)</div>
                <div class="relay-option-status">localhost:19371</div>
            </div>
        </div>
        <div class="relay-option" data-type="mobile" data-url="${RELAY_DEFAULTS.mobile}">
            <div class="relay-option-icon">📱</div>
            <div class="relay-option-info">
                <div class="relay-option-title">Mobile Relay (Local)</div>
                <div class="relay-option-status">localhost:8444</div>
            </div>
        </div>
        <div class="relay-option" data-type="custom">
            <div class="relay-option-icon">⚙️</div>
            <div class="relay-option-info">
                <div class="relay-option-title">Custom Relay URL</div>
                <div class="relay-option-status">Enter your own relay</div>
            </div>
        </div>
    `;
    
    connectBtn.disabled = false;
    
    // Setup click handlers
    document.querySelectorAll('.relay-option').forEach(option => {
        option.addEventListener('click', () => {
            document.querySelectorAll('.relay-option').forEach(o => o.classList.remove('selected'));
            option.classList.add('selected');
            
            const customInput = document.getElementById('customRelayUrl');
            if (option.dataset.type === 'custom') {
                customInput.style.display = 'block';
                customInput.focus();
            } else {
                customInput.style.display = 'none';
            }
        });
    });
}

/**
 * Handle wallet connection
 */
async function handleConnect() {
    const customRelayUrl = document.getElementById('customRelayUrl');
    const selectedOption = document.querySelector('.relay-option.selected');
    const walletConnect = document.getElementById('walletConnect');
    const spinner = document.getElementById('spinner');
    const spinnerText = document.getElementById('spinnerText');
    
    // Check if wallet is selected
    if (!selectedWalletId && !hasAnyWallet()) {
        showToast('Please install a wallet first', 'error');
        return;
    }
    
    // Get relay URL from selected option
    if (selectedOption) {
        if (selectedOption.dataset.type === 'custom' && customRelayUrl.value) {
            state.settings.relayUrl = customRelayUrl.value;
        } else if (selectedOption.dataset.url) {
            state.settings.relayUrl = selectedOption.dataset.url;
        }
    }
    
    // Fallback to default
    if (!state.settings.relayUrl) {
        state.settings.relayUrl = RELAY_DEFAULTS.default;
    }
    
    // Save relay preference
    localStorage.setItem('mumblechat_relay_url', state.settings.relayUrl);
    
    console.log('📡 Connecting to relay:', state.settings.relayUrl);
    console.log('🔐 Using wallet:', selectedWalletId || 'default');
    
    // Show loading
    walletConnect.style.display = 'none';
    spinner.style.display = 'block';
    if (spinnerText) spinnerText.textContent = 'Connecting wallet...';
    
    try {
        // Connect using the selected wallet
        if (selectedWalletId) {
            const result = await connectToWallet(selectedWalletId);
            console.log('Connected via', result.walletName, ':', result.address);
            
            // Set the provider for wallet.js to use
            setWalletProvider(result.provider);
        }
        
        // Check and switch to Ramestta network BEFORE wallet connection
        if (spinnerText) spinnerText.textContent = 'Checking network...';
        await checkAndSwitchNetwork();
        
        // Standard wallet connection (handles network switching, etc.)
        await connectWallet();
        
        if (spinnerText) spinnerText.textContent = 'Checking registration...';
        
        // Check if already registered on-chain (optional - not required to use chat)
        const registration = await checkContractRegistration();
        
        if (registration && registration.isRegistered) {
            // Already registered on-chain - use that data
            state.isOnChainRegistered = true;
            state.displayName = registration.displayName;
            state.registeredAt = registration.registeredAt;
            state.lastUpdated = registration.lastUpdated;
            state.publicKey = registration.publicKeyX;
            state.keyVersion = registration.keyVersion;
            state.username = registration.displayName || state.address.slice(0, 6);
            state.isRegistered = true;
            
            saveUserData();
            
            // Go to main app
            window.dispatchEvent(new CustomEvent('userAuthenticated'));
        } else {
            // Not registered on-chain - show registration dialog
            state.isOnChainRegistered = false;
            console.log('ℹ️ User not registered on-chain, showing registration dialog');
            
            // Hide spinner and show registration form
            spinner.style.display = 'none';
            renderRegistrationView();
        }
    } catch (error) {
        console.error('Connection failed:', error);
        
        // Check if user rejected the network switch
        if (error.code === 4001) {
            showToast('You need to switch to Ramestta network to use MumbleChat', 'error');
        } else {
            showToast('Connection failed: ' + error.message, 'error');
        }
        
        walletConnect.style.display = 'block';
        spinner.style.display = 'none';
    }
}

/**
 * Check if connected to Ramestta network and prompt to add/switch if not
 */
async function checkAndSwitchNetwork() {
    const provider = window.ethereum;
    if (!provider) return;
    
    try {
        const chainId = await provider.request({ method: 'eth_chainId' });
        const ramesttaChainId = RAMESTTA_CONFIG.chainId;
        
        console.log('🌐 Current chain:', chainId, '| Required:', ramesttaChainId);
        
        if (chainId !== ramesttaChainId) {
            console.log('🔄 Switching to Ramestta network...');
            
            try {
                // Try to switch to Ramestta
                await provider.request({
                    method: 'wallet_switchEthereumChain',
                    params: [{ chainId: ramesttaChainId }]
                });
                console.log('✅ Switched to Ramestta network');
            } catch (switchError) {
                // Network doesn't exist - need to add it
                if (switchError.code === 4902 || switchError.message?.includes('Unrecognized chain')) {
                    console.log('📝 Adding Ramestta network...');
                    
                    await provider.request({
                        method: 'wallet_addEthereumChain',
                        params: [{
                            chainId: ramesttaChainId,
                            chainName: RAMESTTA_CONFIG.chainName,
                            nativeCurrency: RAMESTTA_CONFIG.nativeCurrency,
                            rpcUrls: RAMESTTA_CONFIG.rpcUrls,
                            blockExplorerUrls: RAMESTTA_CONFIG.blockExplorerUrls
                        }]
                    });
                    console.log('✅ Ramestta network added');
                } else {
                    throw switchError;
                }
            }
        } else {
            console.log('✅ Already on Ramestta network');
        }
    } catch (error) {
        console.error('Network check failed:', error);
        throw error;
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
                <h1 class="login-title">Complete Registration</h1>
                <p class="login-subtitle">Register on blockchain to use MumbleChat</p>
                
                <div class="wallet-info" style="background: rgba(99, 102, 241, 0.1); padding: 12px; border-radius: 8px; margin-bottom: 20px;">
                    <small style="color: #94a3b8;">Wallet Connected</small>
                    <p style="font-family: monospace; font-size: 12px; color: #e2e8f0; margin: 4px 0;">${state.address}</p>
                </div>
                
                <div id="registrationForm" class="login-form">
                    <div class="form-group">
                        <label style="color: #94a3b8; font-size: 13px; margin-bottom: 8px; display: block;">Display Name</label>
                        <input type="text" id="displayNameInput" class="form-input" 
                            placeholder="Enter your display name" autofocus
                            style="width: 100%; padding: 14px; border-radius: 8px; border: 1px solid #374151; background: #1f2937; color: white; font-size: 16px;">
                    </div>
                    
                    <button id="registerBtn" class="btn-connect" style="width: 100%; padding: 14px; border-radius: 8px; background: linear-gradient(135deg, #6366f1, #8b5cf6); color: white; font-weight: 600; border: none; cursor: pointer; margin-top: 16px;">
                        🚀 Register on Blockchain
                    </button>
                    
                    <button id="skipBtn" class="btn-skip" style="width: 100%; padding: 12px; border-radius: 8px; background: transparent; color: #94a3b8; border: 1px solid #374151; cursor: pointer; margin-top: 12px; font-size: 14px;">
                        Skip for now (limited features)
                    </button>
                </div>
                
                <div id="regSpinner" class="login-spinner" style="display: none; text-align: center; padding: 40px;">
                    <div class="spinner" style="border: 3px solid #374151; border-top: 3px solid #6366f1; border-radius: 50%; width: 40px; height: 40px; animation: spin 1s linear infinite; margin: 0 auto;"></div>
                    <p style="color: #94a3b8; margin-top: 16px;">Registering on blockchain...</p>
                    <p style="color: #64748b; font-size: 12px;">Please confirm the transaction in your wallet</p>
                </div>
            </div>
        </div>
        <style>
            @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
            .form-input:focus { outline: none; border-color: #6366f1; }
            .btn-connect:hover { opacity: 0.9; transform: translateY(-1px); }
            .btn-skip:hover { background: rgba(255,255,255,0.05); }
        </style>
    `;
    
    const displayNameInput = document.getElementById('displayNameInput');
    const registerBtn = document.getElementById('registerBtn');
    const skipBtn = document.getElementById('skipBtn');
    
    registerBtn.addEventListener('click', handleRegistration);
    displayNameInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') handleRegistration();
    });
    
    // Skip registration for testing
    skipBtn.addEventListener('click', () => {
        state.displayName = shortenAddress(state.address);
        state.username = state.address.slice(0, 6);
        state.isRegistered = true;
        state.isOnChainRegistered = false;
        saveUserData();
        showToast('Continuing without on-chain registration', 'info');
        setTimeout(() => {
            window.dispatchEvent(new CustomEvent('userAuthenticated'));
        }, 500);
    });
}
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
        html, body {
            overflow-y: auto !important;
            overflow-x: hidden !important;
            height: auto !important;
            min-height: 100% !important;
        }
        
        .login-container {
            min-height: 100vh;
            min-height: 100dvh;
            width: 100%;
            display: block;
            background: linear-gradient(135deg, #0c1729 0%, #1a3050 100%);
            padding: 20px;
            padding-top: env(safe-area-inset-top, 20px);
            padding-bottom: calc(env(safe-area-inset-bottom, 20px) + 40px);
            overflow-y: auto;
            overflow-x: hidden;
            -webkit-overflow-scrolling: touch;
        }
        
        .login-card {
            background: rgba(19, 40, 66, 0.9);
            padding: 24px;
            border-radius: 20px;
            max-width: 420px;
            width: 100%;
            margin: 20px auto;
            text-align: center;
            backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.1);
            box-shadow: 0 20px 60px rgba(0,0,0,0.5);
        }
        
        @media (max-width: 480px) {
            .login-card {
                padding: 16px 14px;
                margin: 10px auto;
                border-radius: 16px;
            }
            .login-logo {
                font-size: 36px !important;
                margin-bottom: 10px !important;
            }
            .login-title {
                font-size: 22px !important;
                margin-bottom: 6px !important;
            }
            .login-subtitle {
                font-size: 12px !important;
                margin-bottom: 16px !important;
            }
            .btn-connect {
                padding: 12px !important;
                font-size: 14px !important;
            }
            .login-hint {
                font-size: 11px !important;
                margin-top: 12px !important;
            }
            .form-group {
                margin-bottom: 12px !important;
            }
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
        
        /* Relay Options Styles */
        .relay-options {
            display: flex;
            flex-direction: column;
            gap: 8px;
            margin-bottom: 8px;
        }
        
        .relay-loading {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 10px;
            padding: 20px;
            color: var(--text-secondary);
            font-size: 13px;
        }
        
        .spinner-small {
            width: 16px;
            height: 16px;
            border: 2px solid rgba(255,255,255,0.2);
            border-radius: 50%;
            border-top-color: var(--primary);
            animation: spin 1s linear infinite;
        }
        
        .relay-option {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 12px 14px;
            background: rgba(15, 31, 52, 0.6);
            border: 2px solid rgba(255,255,255,0.08);
            border-radius: 10px;
            cursor: pointer;
            transition: all 0.2s;
        }
        
        .relay-option:hover {
            background: rgba(15, 31, 52, 0.9);
            border-color: rgba(255,255,255,0.15);
        }
        
        .relay-option.selected {
            border-color: var(--primary);
            background: rgba(99, 102, 241, 0.1);
        }
        
        .relay-option[data-disabled="true"] {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .relay-option-icon {
            font-size: 24px;
            width: 40px;
            height: 40px;
            display: flex;
            align-items: center;
            justify-content: center;
            background: rgba(255,255,255,0.05);
            border-radius: 8px;
        }
        
        .relay-option-info {
            flex: 1;
        }
        
        .relay-option-title {
            font-weight: 600;
            font-size: 14px;
            color: var(--text);
            margin-bottom: 2px;
        }
        
        .relay-option-status {
            font-size: 12px;
            color: var(--text-secondary);
            display: flex;
            align-items: center;
            gap: 6px;
        }
        
        .relay-option-status.online {
            color: #10b981;
        }
        
        .relay-option-status.offline {
            color: var(--text-secondary);
        }
        
        .status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: var(--text-secondary);
        }
        
        .status-dot.online {
            background: #10b981;
            box-shadow: 0 0 8px rgba(16, 185, 129, 0.5);
        }
        
        .status-dot.offline {
            background: #6b7280;
        }
        
        .relay-option-check,
        .wallet-option-check {
            color: var(--primary);
            font-size: 18px;
            font-weight: bold;
        }
        
        .relay-no-nodes-hint {
            font-size: 11px;
            color: #f59e0b;
            text-align: center;
            padding: 8px;
            background: rgba(245, 158, 11, 0.1);
            border-radius: 6px;
            margin-top: 4px;
        }
        
        /* Wallet Options Styles */
        .wallet-options {
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin-bottom: 8px;
        }
        
        .wallet-option {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 10px 12px;
            background: rgba(15, 31, 52, 0.6);
            border: 2px solid rgba(255,255,255,0.08);
            border-radius: 10px;
            cursor: pointer;
            transition: all 0.2s;
        }
        
        @media (max-width: 480px) {
            .wallet-option {
                padding: 8px 10px;
                gap: 8px;
            }
            .wallet-option-icon {
                font-size: 20px !important;
                width: 32px !important;
                height: 32px !important;
            }
            .wallet-option-title {
                font-size: 13px !important;
            }
            .wallet-option-status {
                font-size: 11px !important;
            }
            .relay-option {
                padding: 8px 10px !important;
            }
            .relay-option-icon {
                font-size: 20px !important;
                width: 32px !important;
                height: 32px !important;
            }
            .relay-option-title {
                font-size: 13px !important;
            }
            .form-group label {
                font-size: 12px !important;
                margin-bottom: 6px !important;
            }
        }
        
        .wallet-option:hover {
            background: rgba(15, 31, 52, 0.9);
            border-color: rgba(255,255,255,0.15);
        }
        
        .wallet-option.selected {
            border-color: var(--primary);
            background: rgba(99, 102, 241, 0.1);
        }
        
        .wallet-option.download {
            opacity: 0.7;
            border-style: dashed;
        }
        
        .wallet-option.download:hover {
            opacity: 1;
            border-color: var(--wallet-color, var(--primary));
        }
        
        .wallet-icon-img {
            width: 24px;
            height: 24px;
            object-fit: contain;
        }
        
        .wallet-option-icon {
            font-size: 24px;
            width: 40px;
            height: 40px;
            display: flex;
            align-items: center;
            justify-content: center;
            background: rgba(255,255,255,0.05);
            border-radius: 8px;
        }
        
        .wallet-option-info {
            flex: 1;
        }
        
        .wallet-option-title {
            font-weight: 600;
            font-size: 14px;
            color: var(--text);
            margin-bottom: 2px;
        }
        
        .wallet-option-status {
            font-size: 12px;
            color: var(--text-secondary);
            display: flex;
            align-items: center;
            gap: 6px;
        }
        
        .wallet-option-status.installed {
            color: #10b981;
        }
        
        .wallet-divider {
            font-size: 11px;
            color: var(--text-secondary);
            text-align: center;
            padding: 8px 0;
            opacity: 0.7;
        }
        
        .no-wallet-message {
            text-align: center;
            padding: 16px;
            margin-bottom: 12px;
        }
        
        .no-wallet-icon {
            font-size: 40px;
            margin-bottom: 8px;
        }
        
        .no-wallet-text {
            font-weight: 600;
            font-size: 15px;
            color: var(--text);
            margin-bottom: 4px;
        }
        
        .no-wallet-hint {
            font-size: 12px;
            color: var(--text-secondary);
        }
        
        .wallet-download-btn {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 14px 16px;
            background: linear-gradient(135deg, rgba(99, 102, 241, 0.2), rgba(139, 92, 246, 0.2));
            border: 2px solid var(--wallet-color, var(--primary));
            border-radius: 12px;
            text-decoration: none;
            color: var(--text);
            transition: all 0.2s;
            margin-bottom: 8px;
        }
        
        .wallet-download-btn:hover {
            background: linear-gradient(135deg, rgba(99, 102, 241, 0.3), rgba(139, 92, 246, 0.3));
            transform: translateY(-2px);
        }
        
        .wallet-download-icon {
            font-size: 28px;
        }
        
        .wallet-download-info {
            flex: 1;
        }
        
        .wallet-download-name {
            font-weight: 600;
            font-size: 15px;
            margin-bottom: 2px;
        }
        
        .wallet-download-desc {
            font-size: 12px;
            color: var(--text-secondary);
        }
        
        .wallet-download-arrow {
            font-size: 20px;
            color: var(--primary);
        }
    `;
}
