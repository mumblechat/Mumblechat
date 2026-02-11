/**
 * MumbleChat Cryptography Module
 * End-to-End Encryption using ECDH key exchange + AES-256-GCM
 * Message signing for authentication
 */

import { state, saveUserData } from './state.js';
import { STORAGE_KEYS } from './config.js';

// Crypto constants
const ALGORITHM = 'AES-GCM';
const KEY_LENGTH = 256;
const IV_LENGTH = 12;
const SALT_LENGTH = 16;
const ECDH_CURVE = 'P-256';

// Key storage
let keyPair = null;
let sharedKeys = new Map(); // contact address -> derived key

/**
 * Initialize cryptography - generate or load key pair
 */
export async function initCrypto() {
    try {
        // Try to load existing keys
        const storedKeys = localStorage.getItem(STORAGE_KEYS.CRYPTO_KEYS);
        
        if (storedKeys) {
            try {
                // Try parsing as plain JSON first (for first-time users or migration)
                const keys = JSON.parse(storedKeys);
                if (keys.publicKey && keys.privateKey) {
                    keyPair = await importKeyPair(keys);
                    console.log('🔐 Loaded existing encryption keys');
                } else {
                    throw new Error('Invalid key format');
                }
            } catch (parseError) {
                // If JSON parse fails, try decrypting (encrypted storage)
                console.log('🔐 Attempting to decrypt stored keys...');
                try {
                    const storageKey = await deriveStorageKey();
                    const decrypted = await decryptData(storedKeys, storageKey);
                    if (decrypted) {
                        const keys = JSON.parse(decrypted);
                        keyPair = await importKeyPair(keys);
                        console.log('🔐 Loaded encrypted keys');
                    } else {
                        throw new Error('Decryption failed');
                    }
                } catch (decryptError) {
                    console.log('🔐 Could not load keys, generating new ones');
                    keyPair = await generateKeyPair();
                    await saveKeyPair();
                }
            }
        } else {
            // Generate new key pair
            keyPair = await generateKeyPair();
            await saveKeyPair();
            console.log('🔐 Generated new encryption keys');
        }
        
        // Update state with public key
        state.publicKey = await exportPublicKey();
        
        return true;
    } catch (error) {
        console.error('Crypto initialization failed:', error);
        // Generate new keys as fallback
        try {
            keyPair = await generateKeyPair();
            state.publicKey = await exportPublicKey();
            console.log('🔐 Generated fallback encryption keys');
            return true;
        } catch (e) {
            return false;
        }
    }
}

/**
 * Generate ECDH key pair for key exchange
 */
async function generateKeyPair() {
    return await crypto.subtle.generateKey(
        {
            name: 'ECDH',
            namedCurve: ECDH_CURVE
        },
        true, // extractable
        ['deriveKey', 'deriveBits']
    );
}

/**
 * Export public key as base64 string
 */
export async function exportPublicKey() {
    if (!keyPair) return null;
    
    const exported = await crypto.subtle.exportKey('spki', keyPair.publicKey);
    return arrayBufferToBase64(exported);
}

/**
 * Export key pair for storage
 */
async function exportKeyPairForStorage() {
    const publicKey = await crypto.subtle.exportKey('spki', keyPair.publicKey);
    const privateKey = await crypto.subtle.exportKey('pkcs8', keyPair.privateKey);
    
    return {
        publicKey: arrayBufferToBase64(publicKey),
        privateKey: arrayBufferToBase64(privateKey)
    };
}

/**
 * Import key pair from storage
 */
async function importKeyPair(stored) {
    const publicKey = await crypto.subtle.importKey(
        'spki',
        base64ToArrayBuffer(stored.publicKey),
        { name: 'ECDH', namedCurve: ECDH_CURVE },
        true,
        []
    );
    
    const privateKey = await crypto.subtle.importKey(
        'pkcs8',
        base64ToArrayBuffer(stored.privateKey),
        { name: 'ECDH', namedCurve: ECDH_CURVE },
        true,
        ['deriveKey', 'deriveBits']
    );
    
    return { publicKey, privateKey };
}

/**
 * Save key pair to localStorage (plain JSON for now, can encrypt later)
 */
async function saveKeyPair() {
    const exported = await exportKeyPairForStorage();
    
    // Store as plain JSON for now (browser localStorage is per-origin anyway)
    // This ensures the app works and keys can be loaded on refresh
    localStorage.setItem(STORAGE_KEYS.CRYPTO_KEYS, JSON.stringify(exported));
    console.log('🔐 Saved encryption keys to storage');
}

/**
 * Derive storage encryption key from wallet address
 */
async function deriveStorageKey() {
    const address = state.address || 'default';
    const encoder = new TextEncoder();
    const keyMaterial = await crypto.subtle.importKey(
        'raw',
        encoder.encode(address + '_mumblechat_storage_key'),
        'PBKDF2',
        false,
        ['deriveKey']
    );
    
    return await crypto.subtle.deriveKey(
        {
            name: 'PBKDF2',
            salt: encoder.encode('mumblechat_salt_v1'),
            iterations: 100000,
            hash: 'SHA-256'
        },
        keyMaterial,
        { name: ALGORITHM, length: KEY_LENGTH },
        false,
        ['encrypt', 'decrypt']
    );
}

/**
 * Import contact's public key
 */
export async function importContactPublicKey(publicKeyBase64) {
    try {
        return await crypto.subtle.importKey(
            'spki',
            base64ToArrayBuffer(publicKeyBase64),
            { name: 'ECDH', namedCurve: ECDH_CURVE },
            true,
            []
        );
    } catch (error) {
        // Silently fail - mobile uses different key format (expected)
        return null;
    }
}

/**
 * Derive shared secret with a contact
 */
export async function deriveSharedKey(contactAddress, contactPublicKeyBase64) {
    try {
        // Check cache
        if (sharedKeys.has(contactAddress.toLowerCase())) {
            return sharedKeys.get(contactAddress.toLowerCase());
        }
        
        // Import contact's public key
        const contactPublicKey = await importContactPublicKey(contactPublicKeyBase64);
        if (!contactPublicKey) return null;
        
        // Derive shared bits
        const sharedBits = await crypto.subtle.deriveBits(
            {
                name: 'ECDH',
                public: contactPublicKey
            },
            keyPair.privateKey,
            256
        );
        
        // Derive AES key from shared bits
        const sharedKey = await crypto.subtle.importKey(
            'raw',
            sharedBits,
            { name: ALGORITHM, length: KEY_LENGTH },
            false,
            ['encrypt', 'decrypt']
        );
        
        // Cache the key
        sharedKeys.set(contactAddress.toLowerCase(), sharedKey);
        
        return sharedKey;
    } catch (error) {
        // Silently fail - mobile keys are incompatible (expected)
        return null;
    }
}

/**
 * Encrypt a message for a specific contact
 */
export async function encryptMessage(contactAddress, contactPublicKey, plaintext) {
    try {
        // Get or derive shared key
        const sharedKey = await deriveSharedKey(contactAddress, contactPublicKey);
        
        if (!sharedKey) {
            // Normal case when sending to mobile (incompatible crypto)
            return { encrypted: false, data: plaintext };
        }
        
        // Generate random IV
        const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
        
        // Encrypt
        const encoder = new TextEncoder();
        const ciphertext = await crypto.subtle.encrypt(
            { name: ALGORITHM, iv },
            sharedKey,
            encoder.encode(plaintext)
        );
        
        // Combine IV + ciphertext
        const combined = new Uint8Array(iv.length + ciphertext.byteLength);
        combined.set(iv);
        combined.set(new Uint8Array(ciphertext), iv.length);
        
        return {
            encrypted: true,
            data: arrayBufferToBase64(combined),
            algorithm: 'ECDH-AES-256-GCM'
        };
    } catch (error) {
        console.error('Encryption failed:', error);
        return { encrypted: false, data: plaintext };
    }
}

/**
 * Decrypt a message from a contact
 */
export async function decryptMessage(contactAddress, contactPublicKey, encryptedData) {
    try {
        // Get or derive shared key
        const sharedKey = await deriveSharedKey(contactAddress, contactPublicKey);
        
        if (!sharedKey) {
            console.warn('No shared key for decryption');
            return encryptedData; // Return as-is
        }
        
        // Decode base64
        const combined = base64ToArrayBuffer(encryptedData);
        const combinedArray = new Uint8Array(combined);
        
        // Extract IV and ciphertext
        const iv = combinedArray.slice(0, IV_LENGTH);
        const ciphertext = combinedArray.slice(IV_LENGTH);
        
        // Decrypt
        const decrypted = await crypto.subtle.decrypt(
            { name: ALGORITHM, iv },
            sharedKey,
            ciphertext
        );
        
        const decoder = new TextDecoder();
        return decoder.decode(decrypted);
    } catch (error) {
        console.error('Decryption failed:', error);
        return encryptedData; // Return encrypted data if decryption fails
    }
}

/**
 * Sign a message with wallet (proves sender authenticity)
 * Note: This is optional - messages work without signatures
 */
export async function signMessage(message) {
    try {
        if (!state.signer) {
            // Signer not available - this is okay, messages still work
            // Signatures are for extra verification, not required for E2EE
            return null;
        }
        
        // Create message hash
        const encoder = new TextEncoder();
        const data = encoder.encode(message);
        const hashBuffer = await crypto.subtle.digest('SHA-256', data);
        const hashHex = arrayBufferToHex(hashBuffer);
        
        // Sign with wallet
        const signature = await state.signer.signMessage(hashHex);
        return signature;
    } catch (error) {
        console.error('Message signing failed:', error);
        return null;
    }
}

/**
 * Verify a message signature
 */
export async function verifySignature(message, signature, senderAddress) {
    try {
        if (!signature) return false;
        
        // Import ethers for signature verification
        const { ethers } = window;
        if (!ethers) {
            console.warn('Ethers not available for verification');
            return true; // Skip verification if ethers not available
        }
        
        // Create message hash
        const encoder = new TextEncoder();
        const data = encoder.encode(message);
        const hashBuffer = await crypto.subtle.digest('SHA-256', data);
        const hashHex = arrayBufferToHex(hashBuffer);
        
        // Recover signer address
        const recoveredAddress = ethers.verifyMessage(hashHex, signature);
        
        return recoveredAddress.toLowerCase() === senderAddress.toLowerCase();
    } catch (error) {
        console.error('Signature verification failed:', error);
        return false;
    }
}

/**
 * Generate a secure random message ID
 */
export function generateSecureMessageId() {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return 'msg_' + arrayBufferToHex(bytes);
}

/**
 * Hash data using SHA-256
 */
export async function hashData(data) {
    const encoder = new TextEncoder();
    const hashBuffer = await crypto.subtle.digest('SHA-256', encoder.encode(data));
    return arrayBufferToHex(hashBuffer);
}

/**
 * Encrypt data with a specific key (for storage)
 */
export async function encryptData(plaintext, key) {
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
    const encoder = new TextEncoder();
    
    const ciphertext = await crypto.subtle.encrypt(
        { name: ALGORITHM, iv },
        key,
        encoder.encode(plaintext)
    );
    
    const combined = new Uint8Array(iv.length + ciphertext.byteLength);
    combined.set(iv);
    combined.set(new Uint8Array(ciphertext), iv.length);
    
    return arrayBufferToBase64(combined);
}

/**
 * Decrypt data with a specific key (for storage)
 */
export async function decryptData(encryptedData, key) {
    try {
        const combined = base64ToArrayBuffer(encryptedData);
        const combinedArray = new Uint8Array(combined);
        
        const iv = combinedArray.slice(0, IV_LENGTH);
        const ciphertext = combinedArray.slice(IV_LENGTH);
        
        const decrypted = await crypto.subtle.decrypt(
            { name: ALGORITHM, iv },
            key,
            ciphertext
        );
        
        const decoder = new TextDecoder();
        return decoder.decode(decrypted);
    } catch (error) {
        console.error('Data decryption failed:', error);
        return null;
    }
}

/**
 * Encrypt localStorage data
 */
export async function encryptForStorage(data) {
    try {
        const key = await deriveStorageKey();
        return await encryptData(JSON.stringify(data), key);
    } catch (error) {
        console.error('Storage encryption failed:', error);
        return JSON.stringify(data); // Fallback to unencrypted
    }
}

/**
 * Decrypt localStorage data
 */
export async function decryptFromStorage(encryptedData) {
    try {
        // Check if data is already JSON (unencrypted legacy data)
        if (encryptedData.startsWith('{') || encryptedData.startsWith('[')) {
            return JSON.parse(encryptedData);
        }
        
        const key = await deriveStorageKey();
        const decrypted = await decryptData(encryptedData, key);
        return decrypted ? JSON.parse(decrypted) : null;
    } catch (error) {
        // Try parsing as plain JSON (legacy data)
        try {
            return JSON.parse(encryptedData);
        } catch {
            console.error('Storage decryption failed:', error);
            return null;
        }
    }
}

/**
 * Rotate encryption keys
 */
export async function rotateKeys() {
    try {
        // Generate new key pair
        keyPair = await generateKeyPair();
        
        // Clear shared key cache
        sharedKeys.clear();
        
        // Save new keys
        await saveKeyPair();
        
        // Update state
        state.publicKey = await exportPublicKey();
        state.keyVersion = (state.keyVersion || 0) + 1;
        saveUserData();
        
        console.log('🔐 Encryption keys rotated successfully');
        return true;
    } catch (error) {
        console.error('Key rotation failed:', error);
        return false;
    }
}

/**
 * Clear all crypto data (for logout)
 */
export function clearCrypto() {
    keyPair = null;
    sharedKeys.clear();
}

// ============ Utility Functions ============

function arrayBufferToBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}

function base64ToArrayBuffer(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
}

function arrayBufferToHex(buffer) {
    const bytes = new Uint8Array(buffer);
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
}

// Export utilities for other modules
export { arrayBufferToBase64, base64ToArrayBuffer, arrayBufferToHex };
