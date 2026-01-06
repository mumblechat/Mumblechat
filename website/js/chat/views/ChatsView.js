/**
 * MumbleChat Chats View
 * Main conversation list view
 */

import { state, saveContacts } from '../state.js';
import { getSortedContacts, searchContacts, clearUnread } from '../contacts.js';
import { shortenAddress, getInitials, getAvatarColor, formatTime, debounce } from '../ui.js';

/**
 * Render chats list view
 */
export function renderChatsView(container) {
    container.innerHTML = `
        <div class="sidebar">
            <div class="sidebar-header">
                <div class="header-top">
                    <a href="index.html" class="back-arrow" title="Back to Website">←</a>
                    <h1>Chats</h1>
                    <span class="badge-pill" id="conversationCount">${state.contacts.length}</span>
                    <button class="header-btn" id="newChatBtn" title="New Chat">➕</button>
                </div>
            </div>

            <div class="search-container">
                <input type="text" class="search-box" id="searchInput" placeholder="Search contacts...">
            </div>

            <div class="contacts-list" id="contactsList"></div>
        </div>
    `;
    
    renderContactsList();
    setupChatsListeners();
}

/**
 * Render contacts list
 */
export function renderContactsList() {
    const list = document.getElementById('contactsList');
    if (!list) return;
    
    const contacts = getSortedContacts();
    
    // Update count
    const countBadge = document.getElementById('conversationCount');
    if (countBadge) countBadge.textContent = contacts.length;
    
    if (contacts.length === 0) {
        list.innerHTML = `
            <div class="empty-contacts">
                <p>No contacts yet</p>
                <p class="hint">Click + to add a contact</p>
            </div>
        `;
        return;
    }
    
    list.innerHTML = contacts.map(contact => `
        <div class="contact-item ${state.activeChat === contact.address ? 'active' : ''}" 
             data-address="${contact.address}">
            <div class="contact-avatar" style="background: ${getAvatarColor(contact.address)}">
                ${contact.online ? '<span class="online-dot"></span>' : ''}
                ${getInitials(contact.name)}
            </div>
            <div class="contact-info">
                <div class="contact-name">
                    ${contact.isPinned ? '📌 ' : ''}${escapeHtml(contact.name)}
                </div>
                <div class="contact-preview">${escapeHtml(contact.lastMessage || 'Start chatting...')}</div>
            </div>
            <div class="contact-meta">
                <div class="contact-time">${contact.lastMessageTime || ''}</div>
                ${contact.unread > 0 ? `<div class="unread-badge">${contact.unread}</div>` : ''}
                ${contact.isMuted ? '<span class="muted-icon">🔕</span>' : ''}
            </div>
        </div>
    `).join('');
}

/**
 * Setup event listeners for chats view
 */
function setupChatsListeners() {
    // Search input
    const searchInput = document.getElementById('searchInput');
    if (searchInput) {
        searchInput.addEventListener('input', debounce((e) => {
            filterContactsList(e.target.value);
        }, 300));
    }
    
    // Contact items
    const contactsList = document.getElementById('contactsList');
    if (contactsList) {
        contactsList.addEventListener('click', (e) => {
            const contactItem = e.target.closest('.contact-item');
            if (contactItem) {
                const address = contactItem.dataset.address;
                openChat(address);
            }
        });
        
        // Long press for context menu
        let longPressTimer;
        contactsList.addEventListener('touchstart', (e) => {
            const contactItem = e.target.closest('.contact-item');
            if (contactItem) {
                longPressTimer = setTimeout(() => {
                    showContactContextMenu(contactItem.dataset.address, e);
                }, 500);
            }
        });
        
        contactsList.addEventListener('touchend', () => {
            clearTimeout(longPressTimer);
        });
        
        contactsList.addEventListener('contextmenu', (e) => {
            const contactItem = e.target.closest('.contact-item');
            if (contactItem) {
                e.preventDefault();
                showContactContextMenu(contactItem.dataset.address, e);
            }
        });
    }
    
    // New chat button
    const newChatBtn = document.getElementById('newChatBtn');
    if (newChatBtn) {
        newChatBtn.addEventListener('click', () => {
            window.dispatchEvent(new CustomEvent('navigateTo', { detail: { view: 'new' } }));
        });
    }
}

/**
 * Filter contacts list by search query
 */
function filterContactsList(query) {
    if (!query) {
        renderContactsList();
        return;
    }
    
    const filtered = searchContacts(query);
    const list = document.getElementById('contactsList');
    
    if (filtered.length === 0) {
        list.innerHTML = `
            <div class="empty-contacts">
                <p>No contacts found</p>
            </div>
        `;
        return;
    }
    
    list.innerHTML = filtered.map(contact => `
        <div class="contact-item ${state.activeChat === contact.address ? 'active' : ''}" 
             data-address="${contact.address}">
            <div class="contact-avatar" style="background: ${getAvatarColor(contact.address)}">
                ${contact.online ? '<span class="online-dot"></span>' : ''}
                ${getInitials(contact.name)}
            </div>
            <div class="contact-info">
                <div class="contact-name">${escapeHtml(contact.name)}</div>
                <div class="contact-preview">${escapeHtml(contact.lastMessage || 'Start chatting...')}</div>
            </div>
            <div class="contact-meta">
                <div class="contact-time">${contact.lastMessageTime || ''}</div>
                ${contact.unread > 0 ? `<div class="unread-badge">${contact.unread}</div>` : ''}
            </div>
        </div>
    `).join('');
}

/**
 * Open a chat conversation
 */
function openChat(address) {
    state.activeChat = address;
    clearUnread(address);
    saveContacts();
    
    // Dispatch event to show conversation view
    window.dispatchEvent(new CustomEvent('openConversation', { detail: { address } }));
    
    // Update UI
    renderContactsList();
}

/**
 * Show contact context menu
 */
function showContactContextMenu(address, event) {
    const contact = state.contacts.find(c => c.address === address);
    if (!contact) return;
    
    const menu = document.createElement('div');
    menu.className = 'context-menu';
    menu.innerHTML = `
        <div class="context-menu-item" data-action="pin">
            ${contact.isPinned ? '📌 Unpin' : '📌 Pin to top'}
        </div>
        <div class="context-menu-item" data-action="mute">
            ${contact.isMuted ? '🔔 Unmute' : '🔕 Mute'}
        </div>
        <div class="context-menu-item" data-action="archive">
            ${contact.isArchived ? '📥 Unarchive' : '📦 Archive'}
        </div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item danger" data-action="block">🚫 Block</div>
        <div class="context-menu-item danger" data-action="delete">🗑️ Delete</div>
    `;
    
    // Position menu
    menu.style.position = 'fixed';
    menu.style.left = event.pageX + 'px';
    menu.style.top = event.pageY + 'px';
    
    document.body.appendChild(menu);
    
    // Handle menu clicks
    menu.addEventListener('click', (e) => {
        const action = e.target.dataset.action;
        if (action) {
            handleContextMenuAction(action, address);
            menu.remove();
        }
    });
    
    // Close on outside click
    setTimeout(() => {
        document.addEventListener('click', function closeMenu() {
            menu.remove();
            document.removeEventListener('click', closeMenu);
        });
    }, 100);
}

/**
 * Handle context menu action
 */
function handleContextMenuAction(action, address) {
    import('../contacts.js').then(({ 
        togglePinContact, 
        toggleMuteContact, 
        toggleArchiveContact, 
        blockContactByAddress, 
        removeContact 
    }) => {
        switch (action) {
            case 'pin':
                togglePinContact(address);
                break;
            case 'mute':
                toggleMuteContact(address);
                break;
            case 'archive':
                toggleArchiveContact(address);
                break;
            case 'block':
                if (confirm('Block this contact?')) {
                    blockContactByAddress(address);
                }
                break;
            case 'delete':
                if (confirm('Delete this conversation?')) {
                    removeContact(address);
                }
                break;
        }
        renderContactsList();
    });
}

/**
 * Escape HTML
 */
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Get chats view styles
 */
export function getChatsStyles() {
    return `
        .sidebar {
            width: 100%;
            height: 100%;
            background: linear-gradient(180deg, #0f1f34 0%, #0c1729 100%);
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }
        
        .sidebar-header {
            padding: 16px;
            border-bottom: 1px solid var(--border);
            background: linear-gradient(180deg, rgba(15, 31, 52, 0.95) 0%, rgba(12, 23, 41, 0.85) 100%);
        }
        
        .header-top {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        
        .header-top h1 {
            flex: 1;
            font-size: 22px;
            font-weight: 700;
            margin: 0;
            color: var(--text);
        }
        
        .header-btn {
            width: 36px;
            height: 36px;
            border-radius: 12px;
            background: var(--bg-soft);
            border: 1px solid var(--border);
            cursor: pointer;
            font-size: 16px;
            color: var(--text);
            display: flex;
            align-items: center;
            justify-content: center;
            transition: background 0.2s;
        }
        
        .header-btn:hover {
            background: #17345a;
        }
        
        .back-arrow {
            width: 32px;
            height: 32px;
            border-radius: 10px;
            background: rgba(255, 255, 255, 0.06);
            border: 1px solid rgba(255, 255, 255, 0.08);
            cursor: pointer;
            font-size: 14px;
            color: var(--text-secondary);
            display: flex;
            align-items: center;
            justify-content: center;
            transition: all 0.2s;
            text-decoration: none;
        }
        
        .back-arrow:hover {
            background: rgba(255, 255, 255, 0.1);
            color: var(--text);
        }
        
        .search-container {
            padding: 8px 16px 12px;
        }
        
        .search-box {
            width: 100%;
            padding: 12px 16px;
            background: var(--bg-card);
            border: 1px solid var(--border);
            color: var(--text);
            border-radius: 14px;
            font-size: 14px;
        }
        
        .contacts-list {
            flex: 1;
            overflow-y: auto;
            padding: 4px 8px 16px;
        }
        
        .contact-item {
            padding: 10px 12px;
            cursor: pointer;
            transition: background 0.15s;
            display: flex;
            align-items: center;
            gap: 12px;
            margin: 6px 4px;
            border-radius: 14px;
            background: linear-gradient(145deg, rgba(23, 43, 70, 0.9), rgba(15, 31, 52, 0.9));
            border: 1px solid var(--border);
        }
        
        .contact-item:hover {
            background: #1a3050;
        }
        
        .contact-item.active {
            background: linear-gradient(135deg, rgba(27, 140, 255, 0.18), rgba(45, 212, 191, 0.16));
            border-color: rgba(27, 140, 255, 0.35);
        }
        
        .contact-avatar {
            width: 48px;
            height: 48px;
            border-radius: 16px;
            color: white;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
            font-size: 18px;
            flex-shrink: 0;
            position: relative;
        }
        
        .online-dot {
            position: absolute;
            bottom: 2px;
            right: 2px;
            width: 12px;
            height: 12px;
            background: var(--success);
            border: 2px solid var(--bg-panel);
            border-radius: 50%;
        }
        
        .contact-info {
            flex: 1;
            min-width: 0;
        }
        
        .contact-name {
            font-weight: 600;
            font-size: 15px;
            margin-bottom: 4px;
            color: var(--text);
        }
        
        .contact-preview {
            font-size: 13px;
            color: var(--text-secondary);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        
        .contact-meta {
            text-align: right;
        }
        
        .contact-time {
            font-size: 12px;
            color: var(--text-secondary);
            margin-bottom: 4px;
        }
        
        .unread-badge {
            background: var(--primary);
            color: white;
            border-radius: 12px;
            min-width: 20px;
            height: 20px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            font-size: 11px;
            font-weight: 600;
            padding: 0 6px;
        }
        
        .muted-icon {
            font-size: 12px;
            margin-left: 4px;
        }
        
        .empty-contacts {
            padding: 40px 20px;
            text-align: center;
            color: var(--text-secondary);
        }
        
        .empty-contacts .hint {
            font-size: 12px;
            margin-top: 8px;
            opacity: 0.7;
        }
        
        .context-menu {
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 8px 0;
            min-width: 180px;
            z-index: 1000;
            box-shadow: 0 10px 40px rgba(0,0,0,0.5);
        }
        
        .context-menu-item {
            padding: 10px 16px;
            cursor: pointer;
            font-size: 14px;
            transition: background 0.15s;
        }
        
        .context-menu-item:hover {
            background: var(--bg-soft);
        }
        
        .context-menu-item.danger {
            color: #f43f5e;
        }
        
        .context-menu-divider {
            height: 1px;
            background: var(--border);
            margin: 8px 0;
        }
    `;
}
