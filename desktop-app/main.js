const { app, BrowserWindow, Tray, Menu, ipcMain, shell, nativeImage } = require('electron');
const path = require('path');
const WebSocket = require('ws');
const Store = require('electron-store');
const os = require('os');
const crypto = require('crypto');

// Initialize store for persistent data
const store = new Store();

// Global references
let mainWindow = null;
let tray = null;
let ws = null;
let isRelaying = false;
let stats = {
    messagesRelayed: 0,
    uptime: 0,
    connected: false,
    earnings: 0
};

const HUB_URL = 'wss://hub.mumblechat.com';

// Get machine ID
function getMachineId() {
    const cached = store.get('machineId');
    if (cached) return cached;
    
    const id = crypto.randomBytes(16).toString('hex');
    store.set('machineId', id);
    return id;
}

// Create main window
function createWindow() {
    mainWindow = new BrowserWindow({
        width: 480,
        height: 720,
        minWidth: 400,
        minHeight: 600,
        resizable: true,
        frame: true,
        titleBarStyle: 'hiddenInset',
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        },
        icon: path.join(__dirname, 'assets', 'icon.png'),
        backgroundColor: '#0a0a0f'
    });

    mainWindow.loadFile('index.html');

    mainWindow.on('close', (event) => {
        if (!app.isQuitting) {
            event.preventDefault();
            mainWindow.hide();
        }
    });
}

// Create system tray
function createTray() {
    const iconPath = path.join(__dirname, 'assets', 'tray-icon.png');
    
    // Create a simple tray icon if file doesn't exist
    let trayIcon;
    try {
        trayIcon = nativeImage.createFromPath(iconPath);
    } catch (e) {
        // Create a simple colored icon
        trayIcon = nativeImage.createEmpty();
    }
    
    tray = new Tray(trayIcon.isEmpty() ? nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAABHNCSVQICAgIfAhkiAAAAAlwSFlzAAAAdgAAAHYBTnsmCAAAABl0RVh0U29mdHdhcmUAd3d3Lmlua3NjYXBlLm9yZ5vuPBoAAADPSURBVDiNpdMxTsNAEAXQNxs7UlqUNByBS3AKLsMROAKUKRIp0nAAbkBBl9iOd4cCydoQJ2L/bv7M6M/o/42klFJEJAEg2l1V24zY2X3dGgT4Y4AIwMzWEfFyu2xmPyLi0cxWu9f+AhARK+fcg4g4AICIYGY7EflYVtXzqmX2dyMDwANwLyLuOefceZ73x+P5vN/r9Y4ODg4ut3O+7e6A2wNm9jcN/g0AMDOz1yRJXiVJcuGcO+3G9ntXBQJgY2Y3t7e3P+fz+cdOp7MZBqvlYDC4Ho+5/gTr5ky9k0xMdwAAAABJRU5ErkJggg==') : trayIcon);
    
    const contextMenu = Menu.buildFromTemplate([
        { 
            label: 'Open MumbleChat Relay', 
            click: () => mainWindow.show() 
        },
        { type: 'separator' },
        { 
            label: isRelaying ? '🟢 Relaying Active' : '🔴 Not Relaying',
            enabled: false
        },
        { 
            label: `Messages: ${stats.messagesRelayed}`,
            enabled: false
        },
        { type: 'separator' },
        { 
            label: isRelaying ? 'Stop Relaying' : 'Start Relaying',
            click: () => {
                if (isRelaying) {
                    stopRelay();
                } else {
                    startRelay();
                }
                updateTrayMenu();
            }
        },
        { type: 'separator' },
        { 
            label: 'Quit', 
            click: () => {
                app.isQuitting = true;
                app.quit();
            }
        }
    ]);
    
    tray.setToolTip('MumbleChat Relay');
    tray.setContextMenu(contextMenu);
    
    tray.on('click', () => {
        mainWindow.isVisible() ? mainWindow.hide() : mainWindow.show();
    });
}

function updateTrayMenu() {
    if (!tray) return;
    
    const contextMenu = Menu.buildFromTemplate([
        { label: 'Open MumbleChat Relay', click: () => mainWindow.show() },
        { type: 'separator' },
        { label: isRelaying ? '🟢 Relaying Active' : '🔴 Not Relaying', enabled: false },
        { label: `Messages: ${stats.messagesRelayed}`, enabled: false },
        { type: 'separator' },
        { 
            label: isRelaying ? 'Stop Relaying' : 'Start Relaying',
            click: () => {
                isRelaying ? stopRelay() : startRelay();
                updateTrayMenu();
            }
        },
        { type: 'separator' },
        { label: 'Quit', click: () => { app.isQuitting = true; app.quit(); }}
    ]);
    
    tray.setContextMenu(contextMenu);
}

// WebSocket connection to hub
function startRelay() {
    const wallet = store.get('wallet');
    if (!wallet) {
        mainWindow.webContents.send('relay-error', 'Please set up your wallet first');
        return;
    }
    
    if (ws && ws.readyState === WebSocket.OPEN) {
        return;
    }
    
    ws = new WebSocket(HUB_URL);
    
    ws.on('open', () => {
        isRelaying = true;
        stats.connected = true;
        
        ws.send(JSON.stringify({
            type: 'relay_register',
            wallet: wallet,
            machineId: getMachineId(),
            nodeId: store.get('nodeId') || 'desktop-' + crypto.randomBytes(4).toString('hex'),
            version: '4.0.0',
            platform: process.platform
        }));
        
        mainWindow.webContents.send('relay-status', { connected: true, stats });
        updateTrayMenu();
    });
    
    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString());
            
            if (msg.type === 'relay_registered') {
                store.set('nodeId', msg.nodeId);
                mainWindow.webContents.send('relay-registered', msg);
            } else if (msg.type === 'relay_message') {
                stats.messagesRelayed++;
                mainWindow.webContents.send('message-relayed', stats);
            } else if (msg.type === 'ping') {
                ws.send(JSON.stringify({ type: 'pong' }));
            } else if (msg.type === 'earnings_update') {
                stats.earnings = msg.earnings;
                mainWindow.webContents.send('earnings-update', stats);
            }
        } catch (e) {}
    });
    
    ws.on('close', () => {
        isRelaying = false;
        stats.connected = false;
        mainWindow.webContents.send('relay-status', { connected: false, stats });
        updateTrayMenu();
        
        // Auto-reconnect
        if (store.get('autoReconnect', true)) {
            setTimeout(() => {
                if (!isRelaying) startRelay();
            }, 5000);
        }
    });
    
    ws.on('error', (err) => {
        mainWindow.webContents.send('relay-error', err.message);
    });
}

function stopRelay() {
    if (ws) {
        ws.close();
        ws = null;
    }
    isRelaying = false;
    stats.connected = false;
    mainWindow.webContents.send('relay-status', { connected: false, stats });
    updateTrayMenu();
}

// IPC handlers
ipcMain.handle('get-config', () => {
    return {
        wallet: store.get('wallet'),
        nodeId: store.get('nodeId'),
        machineId: getMachineId(),
        autoStart: store.get('autoStart', false),
        autoReconnect: store.get('autoReconnect', true),
        isRelaying,
        stats
    };
});

ipcMain.handle('save-wallet', (event, wallet) => {
    store.set('wallet', wallet);
    return true;
});

ipcMain.handle('start-relay', () => {
    startRelay();
    return true;
});

ipcMain.handle('stop-relay', () => {
    stopRelay();
    return true;
});

ipcMain.handle('set-auto-start', (event, enabled) => {
    store.set('autoStart', enabled);
    app.setLoginItemSettings({
        openAtLogin: enabled,
        openAsHidden: true
    });
    return true;
});

ipcMain.handle('open-external', (event, url) => {
    shell.openExternal(url);
});

ipcMain.handle('get-system-info', () => {
    return {
        platform: process.platform,
        arch: process.arch,
        cpus: os.cpus().length,
        memory: Math.round(os.totalmem() / 1024 / 1024 / 1024),
        hostname: os.hostname()
    };
});

// App lifecycle
app.whenReady().then(() => {
    createWindow();
    createTray();
    
    // Auto-start relay if configured
    if (store.get('wallet') && store.get('autoStart', false)) {
        setTimeout(startRelay, 2000);
    }
    
    // Track uptime
    setInterval(() => {
        if (isRelaying) {
            stats.uptime++;
            mainWindow.webContents.send('uptime-update', stats);
        }
    }, 1000);
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
    } else {
        mainWindow.show();
    }
});

app.on('before-quit', () => {
    app.isQuitting = true;
    stopRelay();
});
