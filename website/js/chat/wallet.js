/**
 * MumbleChat Wallet Connection
 * Handles MetaMask and Web3 wallet interactions
 */

import { RAMESTTA_CONFIG, CONTRACTS, REGISTRY_ABI } from './config.js';
import { state, updateState, saveUserData, clearAllData } from './state.js';

// Store the selected provider (set by LoginView when user selects a wallet)
let selectedProvider = null;

/**
 * Set the provider to use for wallet operations
 * Called from LoginView when user selects a specific wallet via EIP-6963
 */
export function setWalletProvider(provider) {
    selectedProvider = provider;
    console.log('🔐 Wallet provider set:', provider ? 'custom' : 'default');
}

/**
 * Get the current provider (selected or fallback to window.ethereum)
 */
function getProvider() {
    return selectedProvider || window.ethereum;
}

/**
 * Setup wallet event listeners
 */
export function setupWalletListeners() {
    const provider = getProvider();
    if (provider) {
        provider.on('accountsChanged', handleAccountChange);
        provider.on('chainChanged', () => location.reload());
    }
}

/**
 * Check if wallet is already connected
 */
export async function checkWalletConnection() {
    const provider = getProvider();
    if (!provider) {
        return { connected: false, error: 'No wallet found' };
    }

    try {
        const accounts = await provider.request({ method: 'eth_accounts' });
        if (accounts.length > 0 && state.username) {
            state.address = accounts[0];
            return { connected: true, address: accounts[0] };
        }
        return { connected: false };
    } catch (error) {
        console.error('Check wallet error:', error);
        return { connected: false, error: error.message };
    }
}

/**
 * Connect wallet and initialize provider
 */
export async function connectWallet() {
    const provider = getProvider();
    if (!provider) {
        throw new Error('Please install MetaMask or another Web3 wallet');
    }

    // Request accounts from the SELECTED provider
    const accounts = await provider.request({
        method: 'eth_requestAccounts'
    });

    state.address = accounts[0];
    state.wallet = new ethers.BrowserProvider(provider);
    state.signer = await state.wallet.getSigner();
    
    // Initialize contract
    state.contract = new ethers.Contract(CONTRACTS.registry, REGISTRY_ABI, state.signer);

    // Check and switch network
    await ensureCorrectNetwork();

    return state.address;
}

/**
 * Ensure connected to Ramestta network
 */
export async function ensureCorrectNetwork() {
    const provider = getProvider();
    const chainId = await provider.request({ method: 'eth_chainId' });
    
    if (chainId !== RAMESTTA_CONFIG.chainId) {
        try {
            await provider.request({
                method: 'wallet_switchEthereumChain',
                params: [{ chainId: RAMESTTA_CONFIG.chainId }]
            });
        } catch (switchError) {
            if (switchError.code === 4902) {
                await provider.request({
                    method: 'wallet_addEthereumChain',
                    params: [RAMESTTA_CONFIG]
                });
            } else {
                throw switchError;
            }
        }
    }
}

/**
 * Check if address is registered on contract
 */
export async function checkContractRegistration(address = state.address) {
    if (!state.contract || !address) return null;

    try {
        const identity = await state.contract.identities(address);
        
        if (identity && identity.isActive) {
            return {
                isActive: true,
                displayName: identity.displayName || '',
                publicKeyX: identity.publicKeyX,
                publicKeyY: identity.publicKeyY,
                registeredAt: Number(identity.registeredAt),
                lastUpdated: Number(identity.lastUpdated),
                keyVersion: Number(identity.keyVersion || 0)
            };
        }
        return null;
    } catch (error) {
        console.error('Check registration error:', error);
        return null;
    }
}

/**
 * Register on smart contract
 */
export async function registerOnContract(displayName) {
    if (!state.contract) {
        throw new Error('Contract not initialized');
    }

    // Generate public key (in production, use actual X25519 key)
    const publicKeyX = ethers.id('mumblechat-' + state.address).substring(0, 66);

    const tx = await state.contract.register(publicKeyX, displayName);
    const receipt = await tx.wait();

    state.isOnChainRegistered = true;
    state.displayName = displayName;
    state.registeredAt = Date.now();
    state.lastUpdated = Date.now();
    state.publicKey = publicKeyX;
    
    saveUserData();

    return receipt;
}

/**
 * Update display name on contract
 */
export async function updateDisplayName(newDisplayName) {
    if (!state.contract || !state.isOnChainRegistered) {
        throw new Error('Not registered on contract');
    }

    const tx = await state.contract.updateDisplayName(newDisplayName);
    await tx.wait();

    state.displayName = newDisplayName;
    state.lastUpdated = Date.now();
    saveUserData();

    return true;
}

/**
 * Rotate identity keys (security feature)
 */
export async function rotateKeys() {
    if (!state.contract || !state.isOnChainRegistered) {
        throw new Error('Not registered on contract');
    }

    // Generate new keypair
    const newPublicKeyX = ethers.id('mumblechat-v' + (state.keyVersion + 1) + '-' + state.address).substring(0, 66);
    const newPublicKeyY = ethers.id('mumblechat-y' + (state.keyVersion + 1) + '-' + state.address).substring(0, 66);
    const newKeyVersion = state.keyVersion + 1;

    const tx = await state.contract.updateIdentity(newPublicKeyX, newPublicKeyY, newKeyVersion);
    await tx.wait();

    state.publicKey = newPublicKeyX;
    state.keyVersion = newKeyVersion;
    state.lastUpdated = Date.now();
    saveUserData();

    return { keyVersion: newKeyVersion };
}

/**
 * Handle account change event
 */
function handleAccountChange(accounts) {
    if (accounts.length === 0) {
        disconnectWallet();
    } else {
        state.address = accounts[0];
        updateState('address', accounts[0]);
    }
}

/**
 * Disconnect wallet and clear data
 */
export function disconnectWallet() {
    if (state.relaySocket) {
        state.relaySocket.close();
    }
    clearAllData();
}

/**
 * Get wallet balance
 */
export async function getWalletBalance() {
    if (!state.wallet || !state.address) return '0';
    
    try {
        const balance = await state.wallet.getBalance(state.address);
        return ethers.formatEther(balance);
    } catch (error) {
        console.error('Get balance error:', error);
        return '0';
    }
}

/**
 * Shorten address for display
 */
export function shortenAddress(address) {
    if (!address) return '';
    return address.slice(0, 6) + '...' + address.slice(-4);
}
