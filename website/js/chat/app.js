/**
 * MumbleChat Main Application
 * Initializes the app and manages view routing
 * WITH END-TO-END ENCRYPTION
 */

import { state, loadPersistedData } from './state.js';
import { setupWalletListeners, checkWalletConnection } from './wallet.js';
import { connectToRelay, updateRelayStatus } from './relay.js';
import { refreshAllContactsOnlineStatus, loadPublicKeys } from './contacts.js';
import { initCrypto } from './crypto.js';
import { renderLoginView, getLoginStyles } from './views/LoginView.js';
import { renderChatsView, getChatsStyles } from './views/ChatsView.js';
import { renderConversationView, getConversationStyles } from './views/ConversationView.js';
import { getNewChatStyles } from './views/NewChatView.js';
import { getSettingsStyles } from './views/SettingsView.js';
import { getGroupsStyles } from './views/GroupsView.js';
import { getProfileStyles } from './views/ProfileView.js';
import { getRelayStyles } from './views/RelayView.js';

// Current view state
let currentView = null;
let mainContainer = null;
let chatArea = null;

// Inject component styles
function injectStyles() {
    const styleId = 'mumblechat-component-styles';
    if (document.getElementById(styleId)) return;
    
    const style = document.createElement('style');
    style.id = styleId;
    style.textContent = `
        ${getLoginStyles()}
        ${getChatsStyles()}
        ${getConversationStyles()}
        ${getNewChatStyles()}
        ${getSettingsStyles()}
        ${getGroupsStyles()}
        ${getProfileStyles()}
        ${getRelayStyles()}
    `;
    document.head.appendChild(style);
}

injectStyles();

/**
 * Initialize the application
 */
export async function initializeApp() {
    console.log('🚀 Initializing MumbleChat...');
    
    // Load persisted data
    loadPersistedData();
    
    // Load public keys for E2EE
    loadPublicKeys();
    
    // Setup wallet listeners
    setupWalletListeners();
    
    // Check if user is authenticated
    const walletCheck = await checkWalletConnection();
    
    // Initialize cryptography if wallet connected
    if (walletCheck && state.address) {
        console.log('🔐 Initializing E2E encryption...');
        await initCrypto();
    }
    
    if (walletCheck.connected && state.isRegistered) {
        // User is authenticated - show main app
        renderMainApp();
        connectToRelay();
        
        // Start periodic online status check (every 15 seconds)
        setInterval(() => {
            refreshAllContactsOnlineStatus();
        }, 15000);
        
        // Initial check after 2 seconds
        setTimeout(() => {
            refreshAllContactsOnlineStatus();
        }, 2000);
    } else {
        // Show login screen
        renderLoginView();
    }
    
    // Setup global event listeners
    setupEventListeners();
}

/**
 * Render main application UI
 */
function renderMainApp() {
    document.body.innerHTML = `
        <div class="app-container">
            <!-- Sidebar / Main View -->
            <div class="main-view" id="mainView"></div>
            
            <!-- Chat Area -->
            <div class="chat-area" id="chatArea">
                <div class="top-bar">
                    <div class="brand">
                        <div class="brand-logo">💬</div>
                        <div class="brand-text">
                            <h2>MumbleChat</h2>
                            <p>Decentralized messaging</p>
                        </div>
                    </div>
                </div>
                <div class="empty-state">
                    <div class="empty-icon">
                        <span>💬</span>
                    </div>
                    <div>
                        <h2>No conversation selected</h2>
                        <p>Select a conversation or start a new one.</p>
                    </div>
                    <div class="empty-actions">
                        <button class="btn-primary" id="emptyNewChatBtn">Create a new direct message</button>
                    </div>
                </div>
            </div>
        </div>

        <!-- Bottom Navigation -->
        <div class="bottom-nav">
            <div class="nav-item active" data-view="chats">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                </svg>
                <span>Chats</span>
            </div>
            <div class="nav-item" data-view="new">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="12" y1="5" x2="12" y2="19"></line>
                    <line x1="5" y1="12" x2="19" y2="12"></line>
                </svg>
                <span>New</span>
            </div>
            <div class="nav-item" data-view="groups">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path>
                    <circle cx="9" cy="7" r="4"></circle>
                    <path d="M23 21v-2a4 4 0 0 0-3-3.87"></path>
                    <path d="M16 3.13a4 4 0 0 1 0 7.75"></path>
                </svg>
                <span>Groups</span>
            </div>
            <div class="nav-item" data-view="settings">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="3"></circle>
                    <path d="M12 1v6m0 6v6m9-9h-6m-6 0H3"></path>
                </svg>
                <span>Settings</span>
            </div>
        </div>
    `;
    
    mainContainer = document.getElementById('mainView');
    chatArea = document.getElementById('chatArea');
    
    // Show default view (chats)
    navigateTo('chats');
    
    // Setup bottom nav
    setupBottomNav();
    
    // Empty state button
    document.getElementById('emptyNewChatBtn')?.addEventListener('click', () => {
        navigateTo('new');
    });
}

/**
 * Navigate to a view
 */
export function navigateTo(view, data = {}) {
    if (!mainContainer) return;
    
    currentView = view;
    
    // Update bottom nav
    document.querySelectorAll('.nav-item').forEach(item => {
        item.classList.toggle('active', item.dataset.view === view);
    });
    
    // Clear chat area on view change (except conversation)
    if (view !== 'conversation' && chatArea) {
        chatArea.innerHTML = `
            <div class="top-bar">
                <div class="brand">
                    <div class="brand-logo">💬</div>
                    <div class="brand-text">
                        <h2>MumbleChat</h2>
                        <p>Decentralized messaging</p>
                    </div>
                </div>
            </div>
            <div class="empty-state">
                <div class="empty-icon"><span>💬</span></div>
                <div>
                    <h2>No conversation selected</h2>
                    <p>Select a conversation or start a new one.</p>
                </div>
                <div class="empty-actions">
                    <button class="btn-primary" onclick="window.dispatchEvent(new CustomEvent('navigateTo', {detail: {view: 'new'}}))">
                        Create a new direct message
                    </button>
                </div>
            </div>
        `;
    }
    
    // Render appropriate view
    switch (view) {
        case 'chats':
            renderChatsView(mainContainer);
            break;
            
        case 'conversation':
            if (data.address) {
                renderConversationView(chatArea, data.address);
                // Hide main view on mobile
                if (window.innerWidth <= 768) {
                    mainContainer.style.display = 'none';
                    chatArea.style.display = 'flex';
                }
            }
            break;
            
        case 'new':
            import('./views/NewChatView.js').then(({ renderNewChatView }) => {
                renderNewChatView(mainContainer);
            });
            break;
            
        case 'groups':
            import('./views/GroupsView.js').then(({ renderGroupsView }) => {
                renderGroupsView(mainContainer);
            });
            break;
            
        case 'settings':
            import('./views/SettingsView.js').then(({ renderSettingsView }) => {
                renderSettingsView(mainContainer);
            });
            break;
            
        case 'profile':
            import('./views/ProfileView.js').then(({ renderProfileView }) => {
                renderProfileView(mainContainer);
            });
            break;
            
        case 'relay':
            import('./views/RelayView.js').then(({ renderRelayView }) => {
                renderRelayView(mainContainer);
            });
            break;
            
        default:
            mainContainer.innerHTML = '<p>View not found</p>';
    }
}

/**
 * Setup bottom navigation
 */
function setupBottomNav() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => {
            const view = item.dataset.view;
            navigateTo(view);
        });
    });
}

/**
 * Setup global event listeners
 */
function setupEventListeners() {
    // User authenticated
    window.addEventListener('userAuthenticated', () => {
        renderMainApp();
        connectToRelay();
    });
    
    // Navigate to view
    window.addEventListener('navigateTo', (e) => {
        navigateTo(e.detail.view, e.detail);
    });
    
    // Open conversation
    window.addEventListener('openConversation', (e) => {
        navigateTo('conversation', { address: e.detail.address });
    });
    
    // Contacts updated
    window.addEventListener('contactsUpdated', () => {
        if (currentView === 'chats') {
            renderContactsList();
        }
    });
    
    // Handle back button on mobile
    if (window.innerWidth <= 768) {
        window.addEventListener('popstate', () => {
            if (currentView === 'conversation') {
                navigateTo('chats');
                if (mainContainer) mainContainer.style.display = 'flex';
                if (chatArea) chatArea.style.display = 'none';
            }
        });
    }
}

/**
 * Register service worker for PWA
 */
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/sw-chat.js')
            .then(registration => console.log('✅ Service Worker registered'))
            .catch(err => console.error('❌ Service Worker registration failed:', err));
    });
}

// Initialize app on load
document.addEventListener('DOMContentLoaded', initializeApp);
