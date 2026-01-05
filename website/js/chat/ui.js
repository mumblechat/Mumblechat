/**
 * MumbleChat UI Utilities
 * Common UI helper functions
 */

import { state } from './state.js';

/**
 * Show a toast notification
 */
export function showToast(message, type = 'info', duration = 3000) {
    // Remove existing toast
    const existingToast = document.querySelector('.toast-notification');
    if (existingToast) {
        existingToast.remove();
    }
    
    const toast = document.createElement('div');
    toast.className = `toast-notification toast-${type}`;
    toast.textContent = message;
    
    document.body.appendChild(toast);
    
    // Animate in
    setTimeout(() => toast.classList.add('show'), 10);
    
    // Remove after duration
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, duration);
}

/**
 * Show a modal dialog
 */
export function showModal(options) {
    const { title, content, buttons = [], onClose } = options;
    
    // Remove existing modal
    const existingModal = document.querySelector('.modal-overlay');
    if (existingModal) {
        existingModal.remove();
    }
    
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    
    const modal = document.createElement('div');
    modal.className = 'modal-dialog';
    
    modal.innerHTML = `
        <div class="modal-header">
            <h3>${title}</h3>
            <button class="modal-close">&times;</button>
        </div>
        <div class="modal-content">
            ${typeof content === 'string' ? content : ''}
        </div>
        <div class="modal-footer">
            ${buttons.map(btn => `
                <button class="btn ${btn.primary ? 'btn-primary' : 'btn-secondary'}" data-action="${btn.action}">
                    ${btn.text}
                </button>
            `).join('')}
        </div>
    `;
    
    // If content is an element, append it
    if (typeof content !== 'string' && content instanceof Element) {
        modal.querySelector('.modal-content').appendChild(content);
    }
    
    overlay.appendChild(modal);
    document.body.appendChild(overlay);
    
    // Event listeners
    const closeBtn = modal.querySelector('.modal-close');
    closeBtn.addEventListener('click', () => {
        closeModal();
        if (onClose) onClose();
    });
    
    overlay.addEventListener('click', (e) => {
        if (e.target === overlay) {
            closeModal();
            if (onClose) onClose();
        }
    });
    
    buttons.forEach(btn => {
        const button = modal.querySelector(`[data-action="${btn.action}"]`);
        if (button && btn.onClick) {
            button.addEventListener('click', () => {
                btn.onClick();
                if (btn.closeOnClick !== false) {
                    closeModal();
                }
            });
        }
    });
    
    // Animate in
    setTimeout(() => overlay.classList.add('show'), 10);
    
    return {
        close: closeModal,
        setContent: (newContent) => {
            modal.querySelector('.modal-content').innerHTML = newContent;
        }
    };
}

/**
 * Close modal
 */
export function closeModal() {
    const overlay = document.querySelector('.modal-overlay');
    if (overlay) {
        overlay.classList.remove('show');
        setTimeout(() => overlay.remove(), 300);
    }
}

/**
 * Show confirmation dialog
 */
export function showConfirm(title, message, onConfirm, onCancel) {
    return showModal({
        title,
        content: `<p>${message}</p>`,
        buttons: [
            { text: 'Cancel', action: 'cancel', onClick: onCancel },
            { text: 'Confirm', action: 'confirm', primary: true, onClick: onConfirm }
        ]
    });
}

/**
 * Show prompt dialog
 */
export function showPrompt(title, placeholder, onSubmit, defaultValue = '') {
    const content = document.createElement('div');
    content.innerHTML = `
        <input type="text" class="prompt-input" placeholder="${placeholder}" value="${defaultValue}">
    `;
    
    const input = content.querySelector('input');
    
    const modal = showModal({
        title,
        content,
        buttons: [
            { text: 'Cancel', action: 'cancel' },
            { 
                text: 'OK', 
                action: 'ok', 
                primary: true, 
                onClick: () => onSubmit(input.value)
            }
        ]
    });
    
    // Focus input
    setTimeout(() => input.focus(), 100);
    
    // Enter key to submit
    input.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            onSubmit(input.value);
            modal.close();
        }
    });
    
    return modal;
}

/**
 * Show loading overlay
 */
export function showLoading(message = 'Loading...') {
    const existingLoading = document.querySelector('.loading-overlay');
    if (existingLoading) {
        existingLoading.querySelector('.loading-message').textContent = message;
        return;
    }
    
    const overlay = document.createElement('div');
    overlay.className = 'loading-overlay';
    overlay.innerHTML = `
        <div class="loading-spinner"></div>
        <p class="loading-message">${message}</p>
    `;
    
    document.body.appendChild(overlay);
    setTimeout(() => overlay.classList.add('show'), 10);
}

/**
 * Hide loading overlay
 */
export function hideLoading() {
    const overlay = document.querySelector('.loading-overlay');
    if (overlay) {
        overlay.classList.remove('show');
        setTimeout(() => overlay.remove(), 300);
    }
}

/**
 * Format time for display
 */
export function formatTime(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now - date;
    
    // Today
    if (date.toDateString() === now.toDateString()) {
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    
    // Yesterday
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    if (date.toDateString() === yesterday.toDateString()) {
        return 'Yesterday';
    }
    
    // This week
    if (diff < 7 * 24 * 60 * 60 * 1000) {
        return date.toLocaleDateString([], { weekday: 'short' });
    }
    
    // Older
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

/**
 * Format file size
 */
export function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/**
 * Shorten address for display
 */
export function shortenAddress(address) {
    if (!address) return '';
    return address.slice(0, 6) + '...' + address.slice(-4);
}

/**
 * Get initials from name
 */
export function getInitials(name) {
    if (!name) return '?';
    return name.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2);
}

/**
 * Generate avatar color from address
 */
export function getAvatarColor(address) {
    if (!address) return '#1b8cff';
    
    const colors = [
        '#1b8cff', '#4bc0c8', '#2dd4bf', '#a855f7', 
        '#f43f5e', '#f97316', '#84cc16', '#06b6d4'
    ];
    
    const hash = address.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0);
    return colors[hash % colors.length];
}

/**
 * Copy text to clipboard
 */
export async function copyToClipboard(text) {
    try {
        await navigator.clipboard.writeText(text);
        showToast('Copied to clipboard', 'success');
        return true;
    } catch (error) {
        console.error('Copy failed:', error);
        showToast('Failed to copy', 'error');
        return false;
    }
}

/**
 * Debounce function
 */
export function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

/**
 * Throttle function
 */
export function throttle(func, limit) {
    let inThrottle;
    return function(...args) {
        if (!inThrottle) {
            func.apply(this, args);
            inThrottle = true;
            setTimeout(() => inThrottle = false, limit);
        }
    };
}
