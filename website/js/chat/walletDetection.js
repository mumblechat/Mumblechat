/**
 * MumbleChat Wallet Detection
 * Uses EIP-6963 (Multi Injected Provider Discovery) for modern wallet detection
 * Falls back to legacy window.ethereum detection for older wallets
 */

// Store for EIP-6963 discovered wallets
const eip6963Wallets = new Map();
let eip6963Initialized = false;

/**
 * Initialize EIP-6963 wallet discovery
 * This listens for wallet announcements from all installed wallets
 */
export function initEIP6963() {
    if (eip6963Initialized) return;
    eip6963Initialized = true;
    
    // Listen for wallet announcements (EIP-6963)
    window.addEventListener('eip6963:announceProvider', (event) => {
        const { info, provider } = event.detail;
        console.log('🔐 EIP-6963 Wallet detected:', info.name, info.rdns);
        
        eip6963Wallets.set(info.rdns, {
            info,
            provider,
            id: info.rdns,
            name: info.name,
            icon: info.icon, // Data URL or SVG
            uuid: info.uuid
        });
        
        // Dispatch custom event for UI update
        window.dispatchEvent(new CustomEvent('walletsUpdated', { 
            detail: { wallets: Array.from(eip6963Wallets.values()) }
        }));
    });
    
    // Request wallets to announce themselves
    window.dispatchEvent(new Event('eip6963:requestProvider'));
}

/**
 * Get all EIP-6963 discovered wallets
 */
export function getEIP6963Wallets() {
    return Array.from(eip6963Wallets.values());
}

// Legacy wallet provider definitions (fallback)
const LEGACY_WALLET_PROVIDERS = {
    ramaPay: {
        id: 'io.ramestta.ramapay',
        name: 'RamaPay',
        icon: '💎',
        color: '#6366f1',
        check: () => {
            if (window.ramaPay) return true;
            if (window.ramestta) return true;
            if (window.ethereum?.isRamaPay) return true;
            if (window.ethereum?.providers) {
                return window.ethereum.providers.some(p => p.isRamaPay);
            }
            return false;
        },
        getProvider: () => {
            if (window.ramaPay) return window.ramaPay;
            if (window.ramestta) return window.ramestta;
            if (window.ethereum?.isRamaPay) return window.ethereum;
            if (window.ethereum?.providers) {
                const ramaPay = window.ethereum.providers.find(p => p.isRamaPay);
                if (ramaPay) return ramaPay;
            }
            return null;
        },
        downloadUrl: {
            android: 'https://play.google.com/store/apps/details?id=io.ramestta.ramapay',
            ios: 'https://apps.apple.com/app/ramapay/id123456789',
            chrome: 'https://chrome.google.com/webstore/detail/ramapay'
        }
    },
    metamask: {
        id: 'io.metamask',
        name: 'MetaMask',
        icon: '🦊',
        color: '#f6851b',
        check: () => {
            if (window.ethereum?.isMetaMask) {
                if (window.ethereum?.providers) {
                    return window.ethereum.providers.some(p => p.isMetaMask && !p.isRamaPay);
                }
                return !window.ethereum.isRamaPay;
            }
            return false;
        },
        getProvider: () => {
            if (window.ethereum?.providers) {
                const metamask = window.ethereum.providers.find(p => p.isMetaMask && !p.isRamaPay);
                if (metamask) return metamask;
            }
            if (window.ethereum?.isMetaMask && !window.ethereum?.isRamaPay) {
                return window.ethereum;
            }
            return null;
        },
        downloadUrl: {
            android: 'https://play.google.com/store/apps/details?id=io.metamask',
            ios: 'https://apps.apple.com/app/metamask/id1438144202',
            chrome: 'https://chrome.google.com/webstore/detail/metamask/nkbihfbeogaeaoehlefnkodbefgpgknn'
        }
    },
    trustWallet: {
        id: 'com.trustwallet.app',
        name: 'Trust Wallet',
        icon: '🛡️',
        color: '#3375bb',
        check: () => window.ethereum?.isTrust || window.trustwallet,
        getProvider: () => window.trustwallet?.ethereum || (window.ethereum?.isTrust ? window.ethereum : null),
        downloadUrl: {
            android: 'https://play.google.com/store/apps/details?id=com.wallet.crypto.trustapp',
            ios: 'https://apps.apple.com/app/trust-crypto-bitcoin-wallet/id1288339409',
            chrome: 'https://chrome.google.com/webstore/detail/trust-wallet'
        }
    },
    coinbase: {
        id: 'com.coinbase.wallet',
        name: 'Coinbase Wallet',
        icon: '🔵',
        color: '#0052ff',
        check: () => window.ethereum?.isCoinbaseWallet || window.coinbaseWalletExtension,
        getProvider: () => window.coinbaseWalletExtension || (window.ethereum?.isCoinbaseWallet ? window.ethereum : null),
        downloadUrl: {
            android: 'https://play.google.com/store/apps/details?id=org.toshi',
            ios: 'https://apps.apple.com/app/coinbase-wallet/id1278383455',
            chrome: 'https://chrome.google.com/webstore/detail/coinbase-wallet-extension/hnfanknocfeofbddgcijnmhnfnkdnaad'
        }
    }
};

// Detect if running on mobile
export function isMobile() {
    return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
}

// Detect if running in a wallet's in-app browser
export function isInWalletBrowser() {
    return window.ethereum && (
        window.ethereum.isRamaPay ||
        window.ethereum.isMetaMask ||
        window.ethereum.isTrust ||
        window.ethereum.isCoinbaseWallet
    );
}

/**
 * Detect all available wallets using EIP-6963 + legacy fallback
 * @returns {Promise<Array>} List of detected wallet providers
 */
export async function detectWallets() {
    const detected = [];
    const seenIds = new Set();
    
    // Initialize EIP-6963 discovery
    initEIP6963();
    
    // Wait a bit for EIP-6963 announcements
    await new Promise(resolve => setTimeout(resolve, 100));
    
    // First, add EIP-6963 discovered wallets (modern method)
    for (const wallet of eip6963Wallets.values()) {
        detected.push({
            id: wallet.id,
            name: wallet.name,
            icon: wallet.info.icon, // This is usually a data URL or SVG
            iconEmoji: getEmojiForWallet(wallet.id),
            color: getColorForWallet(wallet.id),
            provider: wallet.provider,
            installed: true,
            method: 'eip6963',
            priority: getPriorityForWallet(wallet.id)
        });
        seenIds.add(wallet.id);
    }
    
    // Then check legacy methods for wallets not discovered via EIP-6963
    for (const [key, wallet] of Object.entries(LEGACY_WALLET_PROVIDERS)) {
        if (seenIds.has(wallet.id)) continue;
        
        try {
            if (wallet.check()) {
                detected.push({
                    id: wallet.id,
                    legacyId: key,
                    name: wallet.name,
                    icon: null,
                    iconEmoji: wallet.icon,
                    color: wallet.color,
                    provider: wallet.getProvider(),
                    installed: true,
                    method: 'legacy',
                    priority: getPriorityForWallet(wallet.id),
                    downloadUrl: wallet.downloadUrl
                });
                seenIds.add(wallet.id);
            }
        } catch (e) {
            console.warn('Error checking wallet:', key, e);
        }
    }
    
    // Sort by priority (RamaPay first, then others)
    detected.sort((a, b) => a.priority - b.priority);
    
    return detected;
}

/**
 * Get emoji icon for known wallets
 */
function getEmojiForWallet(rdns) {
    const emojiMap = {
        'io.ramestta.ramapay': '💎',
        'io.ramestta': '💎',
        'io.metamask': '🦊',
        'com.trustwallet.app': '🛡️',
        'com.coinbase.wallet': '🔵',
        'me.rainbow': '🌈',
        'app.phantom': '👻',
        'com.brave.wallet': '🦁',
        'io.zerion.wallet': '⚡',
        'com.okex.wallet': '⭕'
    };
    return emojiMap[rdns] || '🔐';
}

/**
 * Get brand color for known wallets
 */
function getColorForWallet(rdns) {
    const colorMap = {
        'io.ramestta.ramapay': '#6366f1',
        'io.ramestta': '#6366f1',
        'io.metamask': '#f6851b',
        'com.trustwallet.app': '#3375bb',
        'com.coinbase.wallet': '#0052ff',
        'me.rainbow': '#001e59',
        'app.phantom': '#ab9ff2',
        'com.brave.wallet': '#fb542b',
        'io.zerion.wallet': '#2962ef'
    };
    return colorMap[rdns] || '#888888';
}

/**
 * Get priority for wallet sorting (lower = higher priority)
 */
function getPriorityForWallet(rdns) {
    const priorityMap = {
        'io.ramestta.ramapay': 0,
        'io.ramestta': 0,
        'io.metamask': 1,
        'com.trustwallet.app': 2,
        'com.coinbase.wallet': 3
    };
    return priorityMap[rdns] ?? 10;
}

/**
 * Get recommended wallets for download
 * @returns {Array} List of recommended wallets to download
 */
export function getRecommendedWallets() {
    const platform = getPlatform();
    const installedIds = new Set([...eip6963Wallets.keys()]);
    
    // Also check legacy detection
    for (const [key, wallet] of Object.entries(LEGACY_WALLET_PROVIDERS)) {
        try {
            if (wallet.check()) {
                installedIds.add(wallet.id);
            }
        } catch (e) {}
    }
    
    const recommended = [];
    
    // Always recommend RamaPay first if not installed
    if (!installedIds.has('io.ramestta.ramapay') && !installedIds.has('io.ramestta')) {
        const ramaPay = LEGACY_WALLET_PROVIDERS.ramaPay;
        recommended.push({
            id: ramaPay.id,
            name: ramaPay.name,
            icon: ramaPay.icon,
            color: ramaPay.color,
            description: 'Recommended for Ramestta',
            downloadUrl: ramaPay.downloadUrl[platform] || ramaPay.downloadUrl.android
        });
    }
    
    // Then MetaMask
    if (!installedIds.has('io.metamask')) {
        const metamask = LEGACY_WALLET_PROVIDERS.metamask;
        recommended.push({
            id: metamask.id,
            name: metamask.name,
            icon: metamask.icon,
            color: metamask.color,
            description: 'Most popular wallet',
            downloadUrl: metamask.downloadUrl[platform] || metamask.downloadUrl.chrome
        });
    }
    
    return recommended;
}

/**
 * Get current platform
 */
export function getPlatform() {
    const ua = navigator.userAgent.toLowerCase();
    if (/android/i.test(ua)) return 'android';
    if (/iphone|ipad|ipod/i.test(ua)) return 'ios';
    return 'chrome';
}

/**
 * Connect to a specific wallet by its ID (rdns)
 * @param {string} walletId - The wallet ID (rdns) to connect to
 * @returns {Promise<Object>} Connected address and provider
 */
export async function connectToWallet(walletId) {
    let provider = null;
    let walletName = 'Wallet';
    
    // First check EIP-6963 wallets
    const eip6963Wallet = eip6963Wallets.get(walletId);
    if (eip6963Wallet) {
        provider = eip6963Wallet.provider;
        walletName = eip6963Wallet.name;
    }
    
    // Fallback to legacy detection
    if (!provider) {
        for (const [key, wallet] of Object.entries(LEGACY_WALLET_PROVIDERS)) {
            if (wallet.id === walletId || key === walletId) {
                provider = wallet.getProvider();
                walletName = wallet.name;
                break;
            }
        }
    }
    
    // Last resort: use window.ethereum
    if (!provider && window.ethereum) {
        provider = window.ethereum;
        walletName = 'Browser Wallet';
    }
    
    if (!provider) {
        throw new Error(`Wallet not found: ${walletId}`);
    }
    
    // Request accounts
    const accounts = await provider.request({
        method: 'eth_requestAccounts'
    });
    
    if (!accounts || accounts.length === 0) {
        throw new Error('No accounts found');
    }
    
    return {
        address: accounts[0],
        provider: provider,
        walletId: walletId,
        walletName: walletName
    };
}

/**
 * Check if any wallet is available
 */
export function hasAnyWallet() {
    return typeof window.ethereum !== 'undefined' || eip6963Wallets.size > 0;
}

/**
 * Open wallet app on mobile via deep link
 * @param {string} walletId - The wallet ID
 * @param {string} wcUri - WalletConnect URI (optional)
 */
export function openWalletApp(walletId, wcUri = '') {
    const deepLinks = {
        'io.ramestta.ramapay': 'ramapay://wc?uri=',
        'io.metamask': 'metamask://wc?uri=',
        'com.trustwallet.app': 'trust://wc?uri=',
        'com.coinbase.wallet': 'cbwallet://wc?uri='
    };
    
    const deepLink = deepLinks[walletId];
    if (!deepLink) return false;
    
    window.location.href = deepLink + encodeURIComponent(wcUri);
    return true;
}

/**
 * Get wallet info by ID
 */
export function getWalletInfo(walletId) {
    // Check EIP-6963 first
    const eip6963Wallet = eip6963Wallets.get(walletId);
    if (eip6963Wallet) return eip6963Wallet;
    
    // Fallback to legacy
    for (const [key, wallet] of Object.entries(LEGACY_WALLET_PROVIDERS)) {
        if (wallet.id === walletId || key === walletId) {
            return wallet;
        }
    }
    
    return null;
}
