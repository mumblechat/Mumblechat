/**
 * MumbleChat State Management
 * Centralized application state with reactive updates
 */

import { STORAGE_KEYS, RELAY_DEFAULTS } from './config.js';

// Application State
export const state = {
    // Wallet
    wallet: null,
    signer: null,
    contract: null,
    address: null,
    
    // User
    username: null,
    displayName: null,
    publicKey: null,
    registeredAt: null,
    lastUpdated: null,
    keyVersion: 0,
    isRegistered: false,
    isOnChainRegistered: false,
    
    // Relay
    relaySocket: null,
    relayConnected: false,
    relayUrl: RELAY_DEFAULTS.default,
    
    // Data
    contacts: [],
    messages: {},
    groups: [],
    blockedContacts: [],
    
    // UI
    activeView: 'chats',
    activeChat: null,
    activeGroup: null,
    
    // Settings
    settings: {
        relayUrl: RELAY_DEFAULTS.default,
        notifications: true,
        sounds: true,
        readReceipts: true,
        typingIndicators: true,
        autoDownloadMedia: true,
        theme: 'dark'
    }
};

// State change listeners
const listeners = new Map();

/**
 * Subscribe to state changes
 */
export function subscribe(key, callback) {
    if (!listeners.has(key)) {
        listeners.set(key, new Set());
    }
    listeners.get(key).add(callback);
    
    return () => listeners.get(key).delete(callback);
}

/**
 * Update state and notify listeners
 */
export function updateState(key, value) {
    state[key] = value;
    
    if (listeners.has(key)) {
        listeners.get(key).forEach(callback => callback(value));
    }
}

/**
 * Load persisted data from localStorage
 */
export function loadPersistedData() {
    // Load user data
    const userData = localStorage.getItem(STORAGE_KEYS.USER);
    if (userData) {
        const parsed = JSON.parse(userData);
        state.username = parsed.username;
        state.address = parsed.address;
        state.displayName = parsed.displayName || parsed.username;
        state.publicKey = parsed.publicKey;
        state.registeredAt = parsed.registeredAt;
        state.lastUpdated = parsed.lastUpdated;
        state.keyVersion = parsed.keyVersion || 0;
        state.isRegistered = true;
    }
    
    // Load contacts
    const contacts = localStorage.getItem(STORAGE_KEYS.CONTACTS);
    if (contacts) {
        state.contacts = JSON.parse(contacts);
    }
    
    // Load messages
    const messages = localStorage.getItem(STORAGE_KEYS.MESSAGES);
    if (messages) {
        state.messages = JSON.parse(messages);
    }
    
    // Load groups
    const groups = localStorage.getItem(STORAGE_KEYS.GROUPS);
    if (groups) {
        state.groups = JSON.parse(groups);
    }
    
    // Load blocked contacts
    const blocked = localStorage.getItem(STORAGE_KEYS.BLOCKED);
    if (blocked) {
        state.blockedContacts = JSON.parse(blocked);
    }
    
    // Load settings
    const settings = localStorage.getItem(STORAGE_KEYS.SETTINGS);
    if (settings) {
        state.settings = { ...state.settings, ...JSON.parse(settings) };
    }
    
    // Load relay URL
    const relayUrl = localStorage.getItem(STORAGE_KEYS.RELAY_URL);
    if (relayUrl) {
        state.settings.relayUrl = relayUrl;
        state.relayUrl = relayUrl;
    }
}

/**
 * Save user data to localStorage
 */
export function saveUserData() {
    const userData = {
        username: state.username,
        address: state.address,
        displayName: state.displayName,
        publicKey: state.publicKey,
        registeredAt: state.registeredAt,
        lastUpdated: state.lastUpdated,
        keyVersion: state.keyVersion
    };
    localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(userData));
}

/**
 * Save contacts to localStorage
 */
export function saveContacts() {
    localStorage.setItem(STORAGE_KEYS.CONTACTS, JSON.stringify(state.contacts));
}

/**
 * Save messages to localStorage
 */
export function saveMessages() {
    localStorage.setItem(STORAGE_KEYS.MESSAGES, JSON.stringify(state.messages));
}

/**
 * Save groups to localStorage
 */
export function saveGroups() {
    localStorage.setItem(STORAGE_KEYS.GROUPS, JSON.stringify(state.groups));
}

/**
 * Save blocked contacts to localStorage
 */
export function saveBlockedContacts() {
    localStorage.setItem(STORAGE_KEYS.BLOCKED, JSON.stringify(state.blockedContacts));
}

/**
 * Save settings to localStorage
 */
export function saveSettings() {
    localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(state.settings));
    localStorage.setItem(STORAGE_KEYS.RELAY_URL, state.settings.relayUrl);
}

/**
 * Clear all user data (logout)
 */
export function clearAllData() {
    Object.values(STORAGE_KEYS).forEach(key => {
        localStorage.removeItem(key);
    });
    
    // Reset state
    state.wallet = null;
    state.signer = null;
    state.contract = null;
    state.address = null;
    state.username = null;
    state.displayName = null;
    state.isRegistered = false;
    state.isOnChainRegistered = false;
    state.contacts = [];
    state.messages = {};
    state.groups = [];
    state.blockedContacts = [];
    state.activeChat = null;
    state.activeGroup = null;
}

/**
 * Check if an address is blocked
 */
export function isBlocked(address) {
    return state.blockedContacts.some(
        b => b.address.toLowerCase() === address.toLowerCase()
    );
}

/**
 * Add contact to blocked list
 */
export function blockContact(address, name) {
    if (!isBlocked(address)) {
        state.blockedContacts.push({
            address: address.toLowerCase(),
            name,
            blockedAt: Date.now()
        });
        saveBlockedContacts();
    }
}

/**
 * Remove contact from blocked list
 */
export function unblockContact(address) {
    state.blockedContacts = state.blockedContacts.filter(
        b => b.address.toLowerCase() !== address.toLowerCase()
    );
    saveBlockedContacts();
}
