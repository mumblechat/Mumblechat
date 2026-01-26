const { contextBridge, ipcRenderer } = require('electron');

// Expose safe APIs to renderer
contextBridge.exposeInMainWorld('electronAPI', {
    // Configuration
    getConfig: () => ipcRenderer.invoke('getConfig'),
    setConfig: (config) => ipcRenderer.invoke('setConfig', config),
    
    // Relay control
    startRelay: () => ipcRenderer.invoke('startRelay'),
    stopRelay: () => ipcRenderer.invoke('stopRelay'),
    
    // Rewards
    claimRewards: () => ipcRenderer.invoke('claimRewards'),
    refreshTier: () => ipcRenderer.invoke('refreshTier'),
    
    // External links
    openExternal: (url) => ipcRenderer.invoke('openExternal', url),
    
    // System info
    getSystemInfo: () => ipcRenderer.invoke('getSystemInfo'),
    
    // Event listeners
    onStatsUpdate: (callback) => {
        ipcRenderer.on('statsUpdate', (event, data) => callback(data));
    },
    
    onConnectionChange: (callback) => {
        ipcRenderer.on('connectionChange', (event, connected) => callback(connected));
    },
    
    onTierUpdate: (callback) => {
        ipcRenderer.on('tierUpdate', (event, tierInfo) => callback(tierInfo));
    },
    
    onRewardsUpdate: (callback) => {
        ipcRenderer.on('rewardsUpdate', (event, rewards) => callback(rewards));
    },
    
    onTunnelUpdate: (callback) => {
        ipcRenderer.on('tunnelUpdate', (event, tunnelInfo) => callback(tunnelInfo));
    },
    
    onLog: (callback) => {
        ipcRenderer.on('log', (event, [message, type]) => callback(message, type));
    },
    
    // Remove listeners
    removeAllListeners: (channel) => {
        ipcRenderer.removeAllListeners(channel);
    }
});

// Log that preload has loaded
console.log('MumbleChat Relay preload script loaded');
