const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    getConfig: () => ipcRenderer.invoke('get-config'),
    saveWallet: (wallet) => ipcRenderer.invoke('save-wallet', wallet),
    startRelay: () => ipcRenderer.invoke('start-relay'),
    stopRelay: () => ipcRenderer.invoke('stop-relay'),
    setAutoStart: (enabled) => ipcRenderer.invoke('set-auto-start', enabled),
    openExternal: (url) => ipcRenderer.invoke('open-external', url),
    getSystemInfo: () => ipcRenderer.invoke('get-system-info'),
    
    // Event listeners
    onRelayStatus: (callback) => ipcRenderer.on('relay-status', (event, data) => callback(data)),
    onRelayError: (callback) => ipcRenderer.on('relay-error', (event, data) => callback(data)),
    onRelayRegistered: (callback) => ipcRenderer.on('relay-registered', (event, data) => callback(data)),
    onMessageRelayed: (callback) => ipcRenderer.on('message-relayed', (event, data) => callback(data)),
    onEarningsUpdate: (callback) => ipcRenderer.on('earnings-update', (event, data) => callback(data)),
    onUptimeUpdate: (callback) => ipcRenderer.on('uptime-update', (event, data) => callback(data))
});
