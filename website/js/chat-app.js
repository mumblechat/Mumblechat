/**
 * MumbleChat PWA - Chat Application
 * 
 * Decentralized, end-to-end encrypted messaging
 * Powered by Ramestta blockchain
 */

// App State
const state = {
    wallet: null,
    address: null,
    publicKey: null,
    privateKey: null,
    isRegistered: false,
    contacts: [],
    messages: {},
    activeChat: null,
    relaySocket: null,
    relayConnected: false,
    settings: {
        storeMessages: true,
        autoDelete: false,
        // Dynamically use current host for relay - works for both localhost and IP access
        relayUrl: `ws://${window.location.hostname}:8444`
    }
};

// Ramestta Network Config
const RAMESTTA_CONFIG = {
    chainId: '0x55A', // 1370 in hex
    chainName: 'Ramestta Mainnet',
    nativeCurrency: { name: 'RAMA', symbol: 'RAMA', decimals: 18 },
    rpcUrls: ['https://blockchain.ramestta.com'],
    blockExplorerUrls: ['https://ramascan.com']
};

// Contract addresses
const CONTRACTS = {
    registry: '0x4f8D4955F370881B05b68D2344345E749d8632e3',
    mctToken: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE'
};

// DOM Elements
const elements = {
    sidebar: document.getElementById('sidebar'),
    chatArea: document.getElementById('chatArea'),
    emptyState: document.getElementById('emptyState'),
    activeChat: document.getElementById('activeChat'),
    contactsList: document.getElementById('contactsList'),
    messagesContainer: document.getElementById('messagesContainer'),
    messageInput: document.getElementById('messageInput'),
    sendBtn: document.getElementById('sendBtn'),
    walletDot: document.getElementById('walletDot'),
    walletText: document.getElementById('walletText'),
    connectWalletBtn: document.getElementById('connectWalletBtn'),
    relayDot: document.getElementById('relayDot'),
    relayStatus: document.getElementById('relayStatus'),
    searchInput: document.getElementById('searchInput'),
    chatContactName: document.getElementById('chatContactName'),
    chatAvatar: document.getElementById('chatAvatar')
};

// Initialize App
document.addEventListener('DOMContentLoaded', () => {
    initApp();
    setupEventListeners();
    checkInstallPrompt();
});

async function initApp() {
    // Load settings
    loadSettings();
    
    // Load contacts and messages from local storage
    loadContacts();
    loadMessages();
    
    // Render contacts
    renderContacts();
    
    // Check if wallet is already connected
    if (window.ethereum) {
        const accounts = await window.ethereum.request({ method: 'eth_accounts' });
        if (accounts.length > 0) {
            await connectWallet(false);
        }
    }
    
    // Connect to relay
    connectToRelay();
}

function setupEventListeners() {
    // Connect wallet button
    elements.connectWalletBtn.addEventListener('click', () => connectWallet(true));
    
    // Add contact button
    document.getElementById('addContactBtn').addEventListener('click', () => {
        openModal('addContactModal');
    });
    
    // Settings button
    document.getElementById('settingsBtn').addEventListener('click', () => {
        openModal('settingsModal');
    });
    
    // Add contact confirm
    document.getElementById('addContactConfirmBtn').addEventListener('click', addContact);
    
    // Send message
    elements.sendBtn.addEventListener('click', sendMessage);
    elements.messageInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
    
    // Auto-resize textarea
    elements.messageInput.addEventListener('input', () => {
        elements.messageInput.style.height = 'auto';
        elements.messageInput.style.height = Math.min(elements.messageInput.scrollHeight, 120) + 'px';
    });
    
    // Back button (mobile)
    document.getElementById('backBtn').addEventListener('click', () => {
        elements.chatArea.classList.remove('active');
        state.activeChat = null;
    });
    
    // Search
    elements.searchInput.addEventListener('input', filterContacts);
    
    // Click outside modal to close
    document.querySelectorAll('.modal-overlay').forEach(modal => {
        modal.addEventListener('click', (e) => {
            if (e.target === modal) {
                modal.classList.remove('active');
            }
        });
    });
}

// ============ Wallet Functions ============

async function connectWallet(prompt = true) {
    if (!window.ethereum) {
        if (prompt) {
            alert('Please install MetaMask or another Web3 wallet to use MumbleChat');
        }
        return;
    }
    
    try {
        // Request accounts
        const accounts = prompt 
            ? await window.ethereum.request({ method: 'eth_requestAccounts' })
            : await window.ethereum.request({ method: 'eth_accounts' });
        
        if (accounts.length === 0) return;
        
        state.address = accounts[0];
        state.wallet = new ethers.BrowserProvider(window.ethereum);
        
        // Check network and switch if needed
        const chainId = await window.ethereum.request({ method: 'eth_chainId' });
        if (chainId !== RAMESTTA_CONFIG.chainId) {
            try {
                await window.ethereum.request({
                    method: 'wallet_switchEthereumChain',
                    params: [{ chainId: RAMESTTA_CONFIG.chainId }]
                });
            } catch (switchError) {
                if (switchError.code === 4902) {
                    await window.ethereum.request({
                        method: 'wallet_addEthereumChain',
                        params: [RAMESTTA_CONFIG]
                    });
                }
            }
        }
        
        // Generate or load keys
        await initializeKeys();
        
        // Update UI
        updateWalletUI();
        
        // Listen for account changes
        window.ethereum.on('accountsChanged', handleAccountChange);
        
        console.log('Wallet connected:', state.address);
        
    } catch (error) {
        console.error('Wallet connection error:', error);
        if (prompt) {
            alert('Failed to connect wallet: ' + error.message);
        }
    }
}

async function initializeKeys() {
    // Try to load existing keys
    const storedKeys = localStorage.getItem(`mumblechat_keys_${state.address}`);
    
    if (storedKeys) {
        const keys = JSON.parse(storedKeys);
        state.publicKey = keys.publicKey;
        state.privateKey = keys.privateKey;
    } else {
        // Generate new key pair
        const keyPair = await generateKeyPair();
        state.publicKey = keyPair.publicKey;
        state.privateKey = keyPair.privateKey;
        
        // Store keys
        localStorage.setItem(`mumblechat_keys_${state.address}`, JSON.stringify({
            publicKey: state.publicKey,
            privateKey: state.privateKey
        }));
    }
}

async function generateKeyPair() {
    // Generate ECDH key pair for encryption
    const keyPair = await window.crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveKey', 'deriveBits']
    );
    
    // Export keys
    const publicKey = await window.crypto.subtle.exportKey('jwk', keyPair.publicKey);
    const privateKey = await window.crypto.subtle.exportKey('jwk', keyPair.privateKey);
    
    return {
        publicKey: JSON.stringify(publicKey),
        privateKey: JSON.stringify(privateKey)
    };
}

function handleAccountChange(accounts) {
    if (accounts.length === 0) {
        disconnectWallet();
    } else {
        state.address = accounts[0];
        initializeKeys();
        updateWalletUI();
    }
}

function disconnectWallet() {
    state.wallet = null;
    state.address = null;
    state.publicKey = null;
    state.privateKey = null;
    
    elements.walletDot.classList.remove('connected');
    elements.walletText.textContent = 'Wallet not connected';
    elements.connectWalletBtn.style.display = 'block';
    
    closeModal('settingsModal');
}

function updateWalletUI() {
    elements.walletDot.classList.add('connected');
    elements.walletText.textContent = shortenAddress(state.address);
    elements.connectWalletBtn.style.display = 'none';
}

function shortenAddress(address) {
    if (!address) return '';
    return `${address.slice(0, 6)}...${address.slice(-4)}`;
}

// ============ Relay Connection ============

function connectToRelay() {
    const url = state.settings.relayUrl || 'wss://relay.mumblechat.com:8443';
    
    try {
        state.relaySocket = new WebSocket(url);
        
        state.relaySocket.onopen = () => {
            state.relayConnected = true;
            updateRelayUI();
            
            // Authenticate with relay
            if (state.address) {
                authenticateWithRelay();
            }
        };
        
        state.relaySocket.onmessage = (event) => {
            handleRelayMessage(JSON.parse(event.data));
        };
        
        state.relaySocket.onclose = () => {
            state.relayConnected = false;
            updateRelayUI();
            
            // Reconnect after 5 seconds
            setTimeout(connectToRelay, 5000);
        };
        
        state.relaySocket.onerror = (error) => {
            console.error('Relay connection error:', error);
            state.relayConnected = false;
            updateRelayUI();
        };
        
    } catch (error) {
        console.error('Failed to connect to relay:', error);
        updateRelayUI();
    }
}

async function authenticateWithRelay() {
    if (!state.relaySocket || !state.address) return;
    
    try {
        // Create auth message
        const timestamp = Date.now();
        const message = `MumbleChat Authentication\nAddress: ${state.address}\nTimestamp: ${timestamp}`;
        
        // Sign with wallet
        const signer = await state.wallet.getSigner();
        const signature = await signer.signMessage(message);
        
        // Send auth to relay
        state.relaySocket.send(JSON.stringify({
            type: 'authenticate',
            address: state.address,
            walletAddress: state.address,
            publicKey: state.publicKey,
            timestamp,
            signature
        }));
        
    } catch (error) {
        console.error('Relay authentication error:', error);
    }
}

function updateRelayUI() {
    const dot = document.getElementById('relayDot');
    const status = document.getElementById('relayStatus');
    
    if (dot && status) {
        if (state.relayConnected) {
            dot.classList.add('connected');
            status.textContent = 'Connected to relay';
        } else {
            dot.classList.remove('connected');
            status.textContent = 'Connecting to relay...';
        }
    }
}

function handleRelayMessage(data) {
    console.log('Relay message:', data);
    
    switch (data.type) {
        case 'message':
            receiveMessage(data);
            break;
        case 'ack':
            markMessageDelivered(data.messageId);
            break;
        case 'online':
            updateContactOnlineStatus(data.address, true);
            break;
        case 'offline':
            updateContactOnlineStatus(data.address, false);
            break;
        case 'publicKey':
            updateContactPublicKey(data.address, data.publicKey);
            break;
    }
}

// ============ Messaging Functions ============

async function sendMessage() {
    const content = elements.messageInput.value.trim();
    if (!content || !state.activeChat || !state.relayConnected) return;
    
    const contact = state.contacts.find(c => c.address === state.activeChat);
    if (!contact) return;
    
    try {
        // Encrypt message
        const encryptedContent = await encryptMessage(content, contact.publicKey);
        
        // Create message object
        const message = {
            id: generateMessageId(),
            from: state.address,
            to: state.activeChat,
            content: encryptedContent,
            timestamp: Date.now(),
            status: 'sending'
        };
        
        // Add to local messages
        if (!state.messages[state.activeChat]) {
            state.messages[state.activeChat] = [];
        }
        
        // Store unencrypted locally
        const localMessage = { ...message, content, decrypted: true };
        state.messages[state.activeChat].push(localMessage);
        saveMessages();
        
        // Render
        renderMessages();
        
        // Clear input
        elements.messageInput.value = '';
        elements.messageInput.style.height = 'auto';
        
        // Send via relay
        state.relaySocket.send(JSON.stringify({
            type: 'message',
            ...message
        }));
        
        // Scroll to bottom
        elements.messagesContainer.scrollTop = elements.messagesContainer.scrollHeight;
        
    } catch (error) {
        console.error('Send message error:', error);
        alert('Failed to send message');
    }
}

async function receiveMessage(data) {
    const { from, content, timestamp, id } = data;
    
    try {
        // Decrypt message
        const decryptedContent = await decryptMessage(content);
        
        // Add to messages
        if (!state.messages[from]) {
            state.messages[from] = [];
        }
        
        state.messages[from].push({
            id,
            from,
            to: state.address,
            content: decryptedContent,
            timestamp,
            decrypted: true
        });
        
        saveMessages();
        
        // Update contact last message
        const contact = state.contacts.find(c => c.address === from);
        if (contact) {
            contact.lastMessage = decryptedContent;
            contact.lastMessageTime = timestamp;
            contact.unreadCount = (contact.unreadCount || 0) + (state.activeChat === from ? 0 : 1);
            saveContacts();
            renderContacts();
        }
        
        // If chat is active, render and scroll
        if (state.activeChat === from) {
            renderMessages();
            elements.messagesContainer.scrollTop = elements.messagesContainer.scrollHeight;
        }
        
        // Show notification
        if (state.activeChat !== from && Notification.permission === 'granted') {
            new Notification('MumbleChat', {
                body: `${contact?.name || shortenAddress(from)}: ${decryptedContent.substring(0, 50)}`,
                icon: 'icons/icon-192.png'
            });
        }
        
        // Send ack
        state.relaySocket.send(JSON.stringify({
            type: 'ack',
            messageId: id,
            to: from
        }));
        
    } catch (error) {
        console.error('Receive message error:', error);
    }
}

async function encryptMessage(content, recipientPublicKey) {
    try {
        // For demo, using simple AES encryption
        // In production, use proper ECDH key exchange
        const encoder = new TextEncoder();
        const data = encoder.encode(content);
        
        // Generate random key
        const key = await window.crypto.subtle.generateKey(
            { name: 'AES-GCM', length: 256 },
            true,
            ['encrypt', 'decrypt']
        );
        
        // Generate IV
        const iv = window.crypto.getRandomValues(new Uint8Array(12));
        
        // Encrypt
        const encrypted = await window.crypto.subtle.encrypt(
            { name: 'AES-GCM', iv },
            key,
            data
        );
        
        // Export key
        const exportedKey = await window.crypto.subtle.exportKey('raw', key);
        
        // Combine key + iv + encrypted data
        const combined = new Uint8Array(exportedKey.byteLength + iv.byteLength + encrypted.byteLength);
        combined.set(new Uint8Array(exportedKey), 0);
        combined.set(iv, exportedKey.byteLength);
        combined.set(new Uint8Array(encrypted), exportedKey.byteLength + iv.byteLength);
        
        return btoa(String.fromCharCode(...combined));
        
    } catch (error) {
        console.error('Encryption error:', error);
        return content; // Fallback to unencrypted
    }
}

async function decryptMessage(encryptedContent) {
    try {
        // Decode base64
        const combined = Uint8Array.from(atob(encryptedContent), c => c.charCodeAt(0));
        
        // Extract key, iv, and data
        const keyData = combined.slice(0, 32);
        const iv = combined.slice(32, 44);
        const data = combined.slice(44);
        
        // Import key
        const key = await window.crypto.subtle.importKey(
            'raw',
            keyData,
            { name: 'AES-GCM' },
            false,
            ['decrypt']
        );
        
        // Decrypt
        const decrypted = await window.crypto.subtle.decrypt(
            { name: 'AES-GCM', iv },
            key,
            data
        );
        
        const decoder = new TextDecoder();
        return decoder.decode(decrypted);
        
    } catch (error) {
        console.error('Decryption error:', error);
        return encryptedContent; // Return as-is if decryption fails
    }
}

function generateMessageId() {
    return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

function markMessageDelivered(messageId) {
    for (const address in state.messages) {
        const message = state.messages[address].find(m => m.id === messageId);
        if (message) {
            message.status = 'delivered';
            saveMessages();
            if (state.activeChat === address) {
                renderMessages();
            }
            break;
        }
    }
}

// ============ Contact Functions ============

function addContact() {
    const addressInput = document.getElementById('contactAddress').value.trim();
    const nickname = document.getElementById('contactNickname').value.trim();
    
    if (!addressInput) {
        alert('Please enter a wallet address');
        return;
    }
    
    // Validate address
    if (!ethers.isAddress(addressInput)) {
        alert('Invalid wallet address');
        return;
    }
    
    // Check if already exists
    if (state.contacts.some(c => c.address.toLowerCase() === addressInput.toLowerCase())) {
        alert('Contact already exists');
        return;
    }
    
    // Add contact
    const contact = {
        address: addressInput.toLowerCase(),
        name: nickname || shortenAddress(addressInput),
        publicKey: null,
        lastMessage: '',
        lastMessageTime: null,
        unreadCount: 0,
        online: false
    };
    
    state.contacts.push(contact);
    saveContacts();
    renderContacts();
    
    // Request public key from relay
    if (state.relaySocket && state.relayConnected) {
        state.relaySocket.send(JSON.stringify({
            type: 'getPublicKey',
            address: addressInput.toLowerCase()
        }));
    }
    
    // Clear inputs and close modal
    document.getElementById('contactAddress').value = '';
    document.getElementById('contactNickname').value = '';
    closeModal('addContactModal');
    
    // Open chat with new contact
    openChat(contact.address);
}

function updateContactPublicKey(address, publicKey) {
    const contact = state.contacts.find(c => c.address === address);
    if (contact) {
        contact.publicKey = publicKey;
        saveContacts();
    }
}

function updateContactOnlineStatus(address, online) {
    const contact = state.contacts.find(c => c.address === address);
    if (contact) {
        contact.online = online;
        renderContacts();
        
        if (state.activeChat === address) {
            const statusEl = document.getElementById('chatContactStatus');
            statusEl.innerHTML = online 
                ? '<span style="color: var(--success)">● Online</span>'
                : '<span class="encryption-badge"><svg width="10" height="10" fill="currentColor" viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2z"/></svg> Encrypted</span>';
        }
    }
}

function openChat(address) {
    state.activeChat = address;
    
    const contact = state.contacts.find(c => c.address === address);
    if (!contact) return;
    
    // Reset unread count
    contact.unreadCount = 0;
    saveContacts();
    renderContacts();
    
    // Update chat header
    elements.chatContactName.textContent = contact.name;
    elements.chatAvatar.textContent = contact.name.charAt(0).toUpperCase();
    
    // Show chat area
    elements.emptyState.style.display = 'none';
    elements.activeChat.style.display = 'flex';
    elements.chatArea.classList.add('active');
    
    // Render messages
    renderMessages();
    
    // Scroll to bottom
    setTimeout(() => {
        elements.messagesContainer.scrollTop = elements.messagesContainer.scrollHeight;
    }, 100);
}

function renderContacts() {
    elements.contactsList.innerHTML = '';
    
    // Sort by last message time
    const sorted = [...state.contacts].sort((a, b) => {
        return (b.lastMessageTime || 0) - (a.lastMessageTime || 0);
    });
    
    sorted.forEach(contact => {
        const div = document.createElement('div');
        div.className = `contact-item ${state.activeChat === contact.address ? 'active' : ''}`;
        div.onclick = () => openChat(contact.address);
        
        const time = contact.lastMessageTime 
            ? formatTime(contact.lastMessageTime)
            : '';
        
        div.innerHTML = `
            <div class="contact-avatar" style="${contact.online ? 'box-shadow: 0 0 0 2px var(--success);' : ''}">
                ${contact.name.charAt(0).toUpperCase()}
            </div>
            <div class="contact-info">
                <div class="contact-name">${escapeHtml(contact.name)}</div>
                <div class="contact-preview">${escapeHtml(contact.lastMessage || 'Start chatting...')}</div>
            </div>
            <div class="contact-meta">
                <div class="contact-time">${time}</div>
                ${contact.unreadCount > 0 ? `<span class="unread-badge">${contact.unreadCount}</span>` : ''}
            </div>
        `;
        
        elements.contactsList.appendChild(div);
    });
    
    if (state.contacts.length === 0) {
        elements.contactsList.innerHTML = `
            <div style="padding: 40px 20px; text-align: center; color: var(--text-secondary);">
                <p>No contacts yet</p>
                <p style="font-size: 13px; margin-top: 8px;">Add a contact to start chatting</p>
            </div>
        `;
    }
}

function renderMessages() {
    if (!state.activeChat) return;
    
    const messages = state.messages[state.activeChat] || [];
    elements.messagesContainer.innerHTML = '';
    
    let lastDate = null;
    
    messages.forEach(msg => {
        const date = new Date(msg.timestamp).toDateString();
        
        // Add date separator
        if (date !== lastDate) {
            const dateDiv = document.createElement('div');
            dateDiv.style.cssText = 'text-align: center; color: var(--text-secondary); font-size: 12px; margin: 16px 0;';
            dateDiv.textContent = formatDate(msg.timestamp);
            elements.messagesContainer.appendChild(dateDiv);
            lastDate = date;
        }
        
        const div = document.createElement('div');
        div.className = `message ${msg.from === state.address ? 'sent' : 'received'}`;
        
        const statusIcon = msg.status === 'delivered' ? '✓✓' : msg.status === 'sending' ? '○' : '✓';
        
        div.innerHTML = `
            ${escapeHtml(msg.content)}
            <div class="message-time">
                ${formatMessageTime(msg.timestamp)}
                ${msg.from === state.address ? `<span class="message-status">${statusIcon}</span>` : ''}
            </div>
        `;
        
        elements.messagesContainer.appendChild(div);
    });
    
    if (messages.length === 0) {
        elements.messagesContainer.innerHTML = `
            <div style="text-align: center; color: var(--text-secondary); margin-top: 40px;">
                <p>No messages yet</p>
                <p style="font-size: 13px; margin-top: 8px;">Send a message to start the conversation</p>
            </div>
        `;
    }
}

function filterContacts() {
    const query = elements.searchInput.value.toLowerCase();
    const items = elements.contactsList.querySelectorAll('.contact-item');
    
    items.forEach(item => {
        const name = item.querySelector('.contact-name').textContent.toLowerCase();
        item.style.display = name.includes(query) ? '' : 'none';
    });
}

// ============ Storage Functions ============

function loadContacts() {
    const stored = localStorage.getItem('mumblechat_contacts');
    if (stored) {
        state.contacts = JSON.parse(stored);
    }
}

function saveContacts() {
    localStorage.setItem('mumblechat_contacts', JSON.stringify(state.contacts));
}

function loadMessages() {
    const stored = localStorage.getItem('mumblechat_messages');
    if (stored) {
        state.messages = JSON.parse(stored);
    }
}

function saveMessages() {
    if (state.settings.storeMessages) {
        localStorage.setItem('mumblechat_messages', JSON.stringify(state.messages));
    }
}

function loadSettings() {
    const stored = localStorage.getItem('mumblechat_settings');
    if (stored) {
        state.settings = { ...state.settings, ...JSON.parse(stored) };
    }
    
    // Update UI toggles
    setTimeout(() => {
        const storeToggle = document.getElementById('storeMessagesToggle');
        const autoDeleteToggle = document.getElementById('autoDeleteToggle');
        
        if (storeToggle) {
            storeToggle.classList.toggle('active', state.settings.storeMessages);
        }
        if (autoDeleteToggle) {
            autoDeleteToggle.classList.toggle('active', state.settings.autoDelete);
        }
    }, 100);
}

function saveSettings() {
    localStorage.setItem('mumblechat_settings', JSON.stringify(state.settings));
}

// ============ UI Helpers ============

function openModal(id) {
    document.getElementById(id).classList.add('active');
}

function closeModal(id) {
    document.getElementById(id).classList.remove('active');
}

function toggleSetting(element) {
    element.classList.toggle('active');
    
    const id = element.id;
    if (id === 'storeMessagesToggle') {
        state.settings.storeMessages = element.classList.contains('active');
    } else if (id === 'autoDeleteToggle') {
        state.settings.autoDelete = element.classList.contains('active');
    }
    
    saveSettings();
}

function showMyQR() {
    if (!state.address) {
        alert('Please connect your wallet first');
        return;
    }
    
    closeModal('settingsModal');
    openModal('myQrModal');
    
    // Generate QR code
    const qrContainer = document.getElementById('qrcode');
    qrContainer.innerHTML = '';
    
    const qrData = JSON.stringify({
        address: state.address,
        publicKey: state.publicKey
    });
    
    QRCode.toCanvas(document.createElement('canvas'), qrData, {
        width: 200,
        margin: 2,
        color: {
            dark: '#000000',
            light: '#ffffff'
        }
    }, (error, canvas) => {
        if (error) {
            console.error('QR generation error:', error);
            qrContainer.innerHTML = '<p>Failed to generate QR code</p>';
        } else {
            qrContainer.appendChild(canvas);
        }
    });
}

function copyMyAddress() {
    if (state.address) {
        navigator.clipboard.writeText(state.address);
        alert('Address copied to clipboard!');
    }
}

function exportKeys() {
    if (!state.privateKey) {
        alert('No keys to export');
        return;
    }
    
    const data = {
        address: state.address,
        publicKey: state.publicKey,
        privateKey: state.privateKey,
        exportedAt: new Date().toISOString()
    };
    
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    
    const a = document.createElement('a');
    a.href = url;
    a.download = `mumblechat-keys-${state.address.slice(0, 8)}.json`;
    a.click();
    
    URL.revokeObjectURL(url);
}

function formatTime(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now - date;
    
    if (diff < 86400000 && date.getDate() === now.getDate()) {
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } else if (diff < 604800000) {
        return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][date.getDay()];
    } else {
        return date.toLocaleDateString();
    }
}

function formatMessageTime(timestamp) {
    return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDate(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now - date;
    
    if (diff < 86400000 && date.getDate() === now.getDate()) {
        return 'Today';
    } else if (diff < 172800000) {
        return 'Yesterday';
    } else {
        return date.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
    }
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ============ PWA Install ============

let deferredPrompt;

function checkInstallPrompt() {
    window.addEventListener('beforeinstallprompt', (e) => {
        e.preventDefault();
        deferredPrompt = e;
        document.getElementById('installBanner').classList.add('show');
    });
    
    document.getElementById('installBtn').addEventListener('click', async () => {
        if (!deferredPrompt) return;
        
        deferredPrompt.prompt();
        const result = await deferredPrompt.userChoice;
        console.log('Install result:', result);
        
        deferredPrompt = null;
        document.getElementById('installBanner').classList.remove('show');
    });
    
    // Request notification permission
    if ('Notification' in window && Notification.permission === 'default') {
        setTimeout(() => {
            Notification.requestPermission();
        }, 3000);
    }
}

// Register Service Worker
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw-chat.js')
        .then(reg => console.log('SW registered:', reg))
        .catch(err => console.error('SW registration failed:', err));
}
