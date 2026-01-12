/**
 * MumbleChat Contacts Management
 * Handles contact list operations with public key storage for E2EE
 */

import { state, saveContacts, isBlocked, blockContact, unblockContact } from './state.js';
import { checkContractRegistration, shortenAddress } from './wallet.js';
import { sendToRelay } from './relay.js';
import { HUB_CONFIG, STORAGE_KEYS } from './config.js';

// Public key storage
const publicKeyStore = new Map();

/**
 * Load public keys from storage
 */
export function loadPublicKeys() {
    try {
        const stored = localStorage.getItem(STORAGE_KEYS.PUBLIC_KEYS);
        if (stored) {
            const keys = JSON.parse(stored);
            for (const [addr, key] of Object.entries(keys)) {
                publicKeyStore.set(addr.toLowerCase(), key);
            }
            console.log(`🔐 Loaded ${publicKeyStore.size} public keys`);
        }
    } catch (error) {
        console.error('Failed to load public keys:', error);
    }
}

/**
 * Save public keys to storage
 */
function savePublicKeys() {
    try {
        const keys = {};
        for (const [addr, key] of publicKeyStore) {
            keys[addr] = key;
        }
        localStorage.setItem(STORAGE_KEYS.PUBLIC_KEYS, JSON.stringify(keys));
    } catch (error) {
        console.error('Failed to save public keys:', error);
    }
}

/**
 * Store a contact's public key
 */
export function storeContactPublicKey(address, publicKey) {
    if (!address || !publicKey) return;
    publicKeyStore.set(address.toLowerCase(), publicKey);
    savePublicKeys();
    console.log(`🔑 Stored public key for ${address.slice(0, 8)}...`);
}

/**
 * Get a contact's public key
 */
export function getContactPublicKey(address) {
    if (!address) return null;
    return publicKeyStore.get(address.toLowerCase()) || null;
}

/**
 * Check if we have a public key for a contact
 */
export function hasContactPublicKey(address) {
    if (!address) return false;
    return publicKeyStore.has(address.toLowerCase());
}

// Initialize on module load
loadPublicKeys();

/**
 * Check if a user is online via Hub API (cross-node support)
 */
export async function checkUserOnlineStatus(address) {
    try {
        const response = await fetch(`${HUB_CONFIG.apiUrl}/api/user/${address.toLowerCase()}`, {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        });
        if (response.ok) {
            const data = await response.json();
            return data.online === true;
        }
    } catch (error) {
        console.warn('Could not check user online status:', error.message);
    }
    return false;
}

/**
 * Refresh online status for all contacts via Hub API
 */
export async function refreshAllContactsOnlineStatus() {
    for (const contact of state.contacts) {
        const online = await checkUserOnlineStatus(contact.address);
        if (contact.online !== online) {
            contact.online = online;
            window.dispatchEvent(new CustomEvent('contactStatusChanged', { 
                detail: { address: contact.address, online } 
            }));
        }
    }
    saveContacts();
}

/**
 * Add a new contact
 */
export async function addContact(address, name = null) {
    // Validate address
    if (!/^0x[a-fA-F0-9]{40}$/.test(address)) {
        throw new Error('Invalid wallet address');
    }

    // Normalize address
    address = address.toLowerCase();

    // Check if self
    if (address === state.address?.toLowerCase()) {
        throw new Error('Cannot add yourself as a contact');
    }

    // Check if already exists
    if (state.contacts.some(c => c.address.toLowerCase() === address)) {
        throw new Error('Contact already exists');
    }

    // Check if registered on contract
    const registration = await checkContractRegistration(address);
    
    // Check if user is online via Hub API (cross-node support)
    const isOnline = await checkUserOnlineStatus(address);
    
    const contact = {
        id: Date.now(),
        name: name || registration?.displayName || address.slice(0, 8),
        address: address,
        displayName: registration?.displayName || null,
        isRegistered: !!registration,
        lastMessage: 'Start chatting...',
        unread: 0,
        online: isOnline,
        lastMessageTime: null,
        isPinned: false,
        isMuted: false,
        isArchived: false,
        addedAt: Date.now()
    };

    state.contacts.push(contact);
    state.messages[address] = [];
    
    saveContacts();

    // Notify relay about new contact
    sendToRelay({
        type: 'add_contact',
        address: address,
        name: contact.name
    });

    return contact;
}

/**
 * Remove a contact
 */
export function removeContact(address) {
    address = address.toLowerCase();
    
    state.contacts = state.contacts.filter(c => c.address !== address);
    delete state.messages[address];
    
    saveContacts();
    
    if (state.activeChat === address) {
        state.activeChat = null;
    }
}

/**
 * Update contact info
 */
export function updateContact(address, updates) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    
    if (contact) {
        Object.assign(contact, updates);
        saveContacts();
    }
    
    return contact;
}

/**
 * Pin/unpin a contact
 */
export function togglePinContact(address) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    
    if (contact) {
        contact.isPinned = !contact.isPinned;
        saveContacts();
    }
    
    return contact?.isPinned;
}

/**
 * Mute/unmute a contact
 */
export function toggleMuteContact(address) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    
    if (contact) {
        contact.isMuted = !contact.isMuted;
        saveContacts();
    }
    
    return contact?.isMuted;
}

/**
 * Archive/unarchive a contact
 */
export function toggleArchiveContact(address) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    
    if (contact) {
        contact.isArchived = !contact.isArchived;
        saveContacts();
    }
    
    return contact?.isArchived;
}

/**
 * Block a contact
 */
export function blockContactByAddress(address) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    const name = contact?.name || address.slice(0, 8);
    
    blockContact(address, name);
    
    // Optionally remove from contacts
    // removeContact(address);
}

/**
 * Unblock a contact
 */
export function unblockContactByAddress(address) {
    unblockContact(address);
}

/**
 * Update contact online status
 */
export function updateContactStatus(address, online) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    
    if (contact) {
        contact.online = online;
        contact.lastSeen = online ? null : Date.now();
        
        // Trigger UI update
        window.dispatchEvent(new CustomEvent('contactStatusChanged', { 
            detail: { address, online } 
        }));
    }
}

/**
 * Get contact by address
 */
export function getContact(address) {
    return state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
}

/**
 * Get sorted contacts list
 * Sorts: pinned first, then unread, then by newest message (like WhatsApp/Telegram)
 */
export function getSortedContacts(includeArchived = false) {
    let contacts = [...state.contacts];
    
    // Filter archived
    if (!includeArchived) {
        contacts = contacts.filter(c => !c.isArchived);
    }
    
    // Filter blocked
    contacts = contacts.filter(c => !isBlocked(c.address));
    
    // Sort: pinned first, then unread messages, then by newest message timestamp
    return contacts.sort((a, b) => {
        // Pinned contacts always first
        if (a.isPinned && !b.isPinned) return -1;
        if (!a.isPinned && b.isPinned) return 1;
        
        // Among pinned (or non-pinned), unread messages come first
        if (a.unread > 0 && b.unread === 0) return -1;
        if (a.unread === 0 && b.unread > 0) return 1;
        
        // Finally sort by timestamp - newest message first (like WhatsApp)
        const timeA = a.lastMessageTimestamp || a.addedAt || 0;
        const timeB = b.lastMessageTimestamp || b.addedAt || 0;
        
        return timeB - timeA; // Descending - newest first
    });
}

/**
 * Search contacts
 */
export function searchContacts(query) {
    const lowerQuery = query.toLowerCase();
    
    return state.contacts.filter(contact => {
        if (isBlocked(contact.address)) return false;
        
        return contact.name.toLowerCase().includes(lowerQuery) ||
               contact.address.toLowerCase().includes(lowerQuery) ||
               (contact.displayName && contact.displayName.toLowerCase().includes(lowerQuery));
    });
}

/**
 * Clear unread count for a contact
 */
export function clearUnread(address) {
    const contact = state.contacts.find(c => c.address.toLowerCase() === address.toLowerCase());
    
    if (contact) {
        contact.unread = 0;
        saveContacts();
    }
}

/**
 * Export contact info for sharing
 */
export function exportContactInfo(address) {
    const contact = getContact(address);
    if (!contact) return null;
    
    return {
        address: contact.address,
        name: contact.name,
        displayName: contact.displayName,
        isRegistered: contact.isRegistered
    };
}

/**
 * Clear chat history with a contact
 */
export function clearChatHistory(address) {
    address = address.toLowerCase();
    state.messages[address] = [];
    
    const contact = getContact(address);
    if (contact) {
        contact.lastMessage = '';
        contact.lastMessageTime = null;
        saveContacts();
    }
    
    import('./state.js').then(({ saveMessages }) => saveMessages());
}

/**
 * Export chat history with a contact
 */
export function exportChatHistory(address) {
    address = address.toLowerCase();
    const messages = state.messages[address] || [];
    const contact = getContact(address);
    
    const exportData = {
        contact: {
            name: contact?.name || address,
            address: address
        },
        exportedAt: new Date().toISOString(),
        messages: messages.map(msg => ({
            text: msg.text,
            sent: msg.sent,
            time: msg.time,
            status: msg.status
        }))
    };
    
    return JSON.stringify(exportData, null, 2);
}
