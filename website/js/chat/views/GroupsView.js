/**
 * MumbleChat Groups View (Placeholder)
 */

import { showToast } from '../ui.js';

/**
 * Render groups view
 */
export function renderGroupsView(container) {
    container.innerHTML = `
        <div class="groups-view">
            <div class="groups-header">
                <h2>Groups</h2>
                <button class="header-btn" id="newGroupBtn" title="Create Group">➕</button>
            </div>
            
            <div class="empty-state-container">
                <div class="empty-icon">👥</div>
                <h3>No groups yet</h3>
                <p>Create a group to chat with multiple people</p>
                <button class="btn-primary" id="createGroupBtn">Create Group</button>
            </div>
        </div>
    `;
    
    setupGroupsListeners();
}

/**
 * Setup event listeners
 */
function setupGroupsListeners() {
    const createGroupBtn = document.getElementById('createGroupBtn');
    const newGroupBtn = document.getElementById('newGroupBtn');
    
    const handler = () => showToast('Group creation coming soon!', 'info');
    
    createGroupBtn?.addEventListener('click', handler);
    newGroupBtn?.addEventListener('click', handler);
}

/**
 * Get styles for groups view
 */
export function getGroupsStyles() {
    return `
        .groups-view {
            height: 100%;
            display: flex;
            flex-direction: column;
            background: linear-gradient(180deg, #0f1f34 0%, #0c1729 100%);
        }
        
        .groups-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 16px;
            border-bottom: 1px solid var(--border);
        }
        
        .groups-header h2 {
            margin: 0;
            font-size: 20px;
            font-weight: 700;
        }
        
        .empty-state-container {
            flex: 1;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 40px;
            text-align: center;
        }
        
        .empty-state-container .empty-icon {
            font-size: 64px;
            margin-bottom: 20px;
        }
        
        .empty-state-container h3 {
            font-size: 22px;
            margin: 0 0 8px;
            color: var(--text);
        }
        
        .empty-state-container p {
            color: var(--text-secondary);
            margin: 0 0 24px;
        }
    `;
}
