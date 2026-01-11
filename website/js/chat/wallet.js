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
        try {
            provider.on('accountsChanged', handleAccountChange);
            provider.on('chainChanged', () => location.reload());
        } catch (e) {
            console.warn('Could not setup wallet listeners:', e);
        }
    }
}

/**
 * Check if wallet is already connected - with fast timeout
 */
export async function checkWalletConnection() {
    const provider = getProvider();
    if (!provider) {
        console.log('No wallet provider found');
        return { connected: false, error: 'No wallet found' };
    }

    try {
        // Fast timeout to prevent hanging on wallet check
        const timeoutPromise = new Promise((resolve) => 
            setTimeout(() => resolve([]), 2000)
        );
        
        const accountsPromise = provider.request({ method: 'eth_accounts' });
        const accounts = await Promise.race([accountsPromise, timeoutPromise]);
        
        if (accounts && accounts.length > 0 && state.username) {
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
                    params: [{
                        chainId: RAMESTTA_CONFIG.chainId,
                        chainName: RAMESTTA_CONFIG.chainName,
                        nativeCurrency: RAMESTTA_CONFIG.nativeCurrency,
                        rpcUrls: RAMESTTA_CONFIG.rpcUrls,
                        blockExplorerUrls: RAMESTTA_CONFIG.blockExplorerUrls
                    }]
                });
            } else {
                throw switchError;
            }
        }
    }
}

/**
 * Handle account changes
 */
function handleAccountChange(accounts) {
    if (accounts.length === 0) {
        clearAllData();
        location.reload();
    } else if (accounts[0] !== state.address) {
        state.address = accounts[0];
        location.reload();
    }
}

/**
 * Disconnect wallet
 */
export function disconnectWallet() {
    clearAllData();
    selectedProvider = null;
    location.reload();
}

/**
 * Check if user is registered on chain
 */
export async function checkRegistration(address = null) {
    const addr = address || state.address;
    if (!addr) return { isRegistered: false };
    try {
        const provider = new ethers.JsonRpcProvider(RAMESTTA_CONFIG.rpcUrls[0]);
        const contract = new ethers.Contract(CONTRACTS.registry, REGISTRY_ABI, provider);
        const identity = await contract.identities(addr);
        
        return {
            isRegistered: identity.isActive,
            displayName: identity.displayName || '',
            publicKeyX: identity.publicKeyX
        };
    } catch (error) {
        console.error('Check registration error:', error);
        return { isRegistered: false };
    }
}

/**
 * Register user on chain
 */
export async function registerUser(displayName) {
    if (!state.contract || !state.signer) {
        throw new Error('Wallet not connected');
    }

    // Generate a placeholder public key for registration
    const publicKeyX = ethers.keccak256(ethers.toUtf8Bytes(state.address + Date.now()));
    
    const tx = await state.contract.register(publicKeyX, displayName);
    await tx.wait();
    
    state.isRegistered = true;
    state.username = displayName;
    saveUserData();
    
    return tx;
}

/**
 * Update display name on chain
 */
export async function updateDisplayName(newName) {
    if (!state.contract) {
        throw new Error('Contract not initialized');
    }

    const tx = await state.contract.updateDisplayName(newName);
    await tx.wait();
    
    state.username = newName;
    saveUserData();
    
    return tx;
}

/**
 * Alias for checkRegistration (for backward compatibility)
 */
export const checkContractRegistration = checkRegistration;

/**
 * Shorten an Ethereum address for display
 */
export function shortenAddress(address, chars = 4) {
    if (!address) return '';
    return `${address.slice(0, chars + 2)}...${address.slice(-chars)}`;
}

/**
 * Alias for registerUser (for backward compatibility)
 */
export const registerOnContract = registerUser;

/**
 * Rotate encryption keys (placeholder for future implementation)
 */
export async function rotateKeys() {
    // TODO: Implement key rotation
    console.log('Key rotation not yet implemented');
    throw new Error('Key rotation not yet implemented');
}
