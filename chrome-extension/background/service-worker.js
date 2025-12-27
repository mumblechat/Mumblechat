/**
 * RamaPay Chrome Extension - Background Service Worker
 * Handles extension lifecycle, message passing, and Web3 provider coordination
 */

import { ethers } from 'ethers';
import { WalletManager, StorageManager, NETWORKS, generateQRCode, ALL_NETWORKS, DEFAULT_ENABLED_NETWORKS, enableNetwork, disableNetwork, setEnabledNetworks, getEnabledNetworkKeys } from '../lib/wallet.js';

const walletManager = new WalletManager();
const storageManager = new StorageManager();

// Session state (cleared when extension reloads)
let isUnlocked = false;
let sessionPassword = null;
let currentWalletData = null;
let connectedSites = new Set();

// Auto-lock settings (in minutes)
const AUTO_LOCK_OPTIONS = {
  '5': 5,
  '15': 15,
  '30': 30,
  '60': 60,
  '120': 120,
  'never': 0
};
let autoLockMinutes = 30; // Default: 30 minutes
let lastActivityTime = Date.now();

// Enabled networks list (persisted in wallet data)
let enabledNetworks = [...DEFAULT_ENABLED_NETWORKS];

// Pending dApp connection requests
let pendingDappRequests = new Map();

/**
 * Save session password to chrome.storage.session (persists across service worker restarts)
 */
async function saveSessionPassword(password) {
  try {
    await chrome.storage.session.set({ ramapay_session_password: password });
    console.log('Session password saved to storage.session');
  } catch (error) {
    console.error('Failed to save session password:', error);
  }
}

/**
 * Get session password from chrome.storage.session
 */
async function getSessionPassword() {
  try {
    const result = await chrome.storage.session.get(['ramapay_session_password']);
    return result.ramapay_session_password || null;
  } catch (error) {
    console.error('Failed to get session password:', error);
    return null;
  }
}

/**
 * Clear session password from chrome.storage.session
 */
async function clearSessionPassword() {
  try {
    await chrome.storage.session.remove(['ramapay_session_password']);
    console.log('Session password cleared from storage.session');
  } catch (error) {
    console.error('Failed to clear session password:', error);
  }
}

/**
 * Ensure wallet is loaded and unlocked for internal operations
 * This auto-restores the session from storage.session if available
 * Used for internal operations like importing accounts that should work without manual unlock
 */
async function ensureWalletLoaded() {
  // If already unlocked and loaded, return true
  if (isUnlocked && currentWalletData && sessionPassword) {
    return true;
  }

  // Try to restore session from storage.session
  const storedPassword = await getSessionPassword();
  if (!storedPassword) {
    return false;
  }

  try {
    const walletData = await storageManager.loadWallet(storedPassword);
    if (!walletData) {
      return false;
    }

    // Restore session state
    isUnlocked = true;
    sessionPassword = storedPassword;
    currentWalletData = walletData;
    
    // Load custom networks from wallet data
    loadCustomNetworksFromWallet();
    
    console.log('Session restored from storage.session');
    return true;
  } catch (error) {
    console.error('Failed to restore session:', error);
    return false;
  }
}

/**
 * Initialize the extension
 */
async function init() {
  console.log('RamaPay Extension initialized');
  
  // Load preferences
  const prefs = await storageManager.loadPreferences();
  if (prefs.network && NETWORKS[prefs.network]) {
    walletManager.switchNetwork(prefs.network);
  }
  
  // Load auto-lock setting
  if (prefs.autoLockMinutes !== undefined) {
    autoLockMinutes = prefs.autoLockMinutes;
  }

  // Check for existing wallet
  const hasWallet = await storageManager.hasWallet();
  console.log('Wallet exists:', hasWallet);
  
  // Try to restore session from storage.session (browser session persistence)
  if (hasWallet) {
    await ensureWalletLoaded();
  }
  
  // Set up auto-lock alarm (checks every minute)
  setupAutoLockAlarm();
}

/**
 * Set up auto-lock alarm - DISABLED
 * Wallet only locks when browser restarts
 */
function setupAutoLockAlarm() {
  // Auto-lock during usage is disabled
  // Wallet will only lock when browser/service worker restarts
  chrome.alarms.clear('autoLockCheck');
}

/**
 * Handle alarm events
 */
chrome.alarms.onAlarm.addListener((alarm) => {
  // Auto-lock alarm disabled
  // if (alarm.name === 'autoLockCheck') {
  //   checkAutoLock();
  // }
});

/**
 * Check if wallet should be auto-locked - DISABLED
 */
function checkAutoLock() {
  // Disabled - wallet only locks on browser restart
  return;
}

/**
 * Update last activity time (call this on user actions)
 */
function updateActivity() {
  lastActivityTime = Date.now();
}

init();

/**
 * Message handler for popup and content scripts
 */
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  handleMessage(request, sender)
    .then(sendResponse)
    .catch(error => sendResponse({ success: false, error: error.message }));
  return true; // Keep message channel open for async response
});

/**
 * Process incoming messages
 */
async function handleMessage(request, sender) {
  const { action, data } = request;
  
  // Update activity on every user action (except status checks)
  if (isUnlocked && !['getWalletStatus', 'getAutoLockSettings'].includes(action)) {
    updateActivity();
  }

  switch (action) {
    // Wallet Management
    case 'createWallet':
      return await createWallet(data);
    case 'importWallet':
      return await importWallet(data);
    case 'unlockWallet':
      return await unlockWallet(data);
    case 'lockWallet':
      return lockWallet();
    case 'getWalletStatus':
      return getWalletStatus();
    case 'generateQRCode':
      return await handleGenerateQRCode(data);
    case 'getAccounts':
      return await getAccounts();
    case 'getNextAccountIndex':
      return getNextAccountIndex();
    case 'addAccount':
      return await addAccount(data);
    case 'createHDWallet':
      return await createHDWallet(data);
    case 'getMasterWallets':
      return await getMasterWallets();
    case 'addAccountToMaster':
      return await addAccountToMaster(data);
    case 'bulkAddToMaster':
      return await bulkAddToMaster(data);
    case 'renameMasterWallet':
      return await renameMasterWallet(data);
    case 'switchAccount':
      return await switchAccount(data);
    case 'renameAccount':
      return await renameAccount(data);
    case 'removeAccount':
      return await removeAccount(data);
    case 'importPrivateKeyAccount':
      return await importPrivateKeyAccount(data);
    case 'bulkAddAccounts':
      return await bulkAddAccounts(data);
    case 'importSeedPhraseAccounts':
      return await importSeedPhraseAccounts(data);
    case 'addWatchWallet':
      return await addWatchWallet(data);
    case 'recoverAccountByIndex':
      return await recoverAccountByIndex(data);

    // Balance & Transactions
    case 'getBalance':
      return await getBalance(data);
    case 'getTokenBalance':
      return await getTokenBalance(data);
    case 'sendTransaction':
      return await sendTransaction(data);
    case 'sendToken':
      return await sendToken(data);
    case 'estimateGas':
      return await estimateGas(data);
    case 'getTransactionHistory':
      return await getTransactionHistory(data);

    // Signing
    case 'signMessage':
      return await signMessage(data);
    case 'signTypedData':
      return await signTypedData(data);

    // Network
    case 'switchNetwork':
      return switchNetwork(data);
    case 'getNetworks':
      return { success: true, networks: NETWORKS };
    case 'getCurrentNetwork':
      return { success: true, network: walletManager.currentNetwork };
    case 'addCustomNetwork':
      return await addCustomNetwork(data);
    case 'removeCustomNetwork':
      return await removeCustomNetwork(data);
    case 'getCustomNetworks':
      return await getCustomNetworks();
    case 'getEnabledNetworks':
      return getEnabledNetworks();
    case 'enableBuiltinNetwork':
      return await enableBuiltinNetwork(data);
    case 'disableBuiltinNetwork':
      return await disableBuiltinNetwork(data);
    case 'getAllAvailableNetworks':
      return { success: true, networks: ALL_NETWORKS };

    // Security
    case 'changePassword':
      return await changePassword(data);
    case 'verifyPassword':
      return await verifyPassword(data);
    case 'exportPrivateKey':
      return await exportPrivateKey(data);
    case 'exportRecoveryPhrase':
      return await exportRecoveryPhrase(data);
    case 'getConnectedSites':
      return getConnectedSites();
    case 'getAutoLockSettings':
      return getAutoLockSettings();
    case 'setAutoLockTimeout':
      return await setAutoLockTimeout(data);

    // dApp Connection
    case 'connectSite':
      return await connectSite(data, sender);
    case 'disconnectSite':
      return disconnectSite(data);
    case 'isConnected':
      return { success: true, connected: connectedSites.has(data.origin) };

    // Token Management
    case 'addToken':
      return await addCustomToken(data);
    case 'removeToken':
      return await removeCustomToken(data);
    case 'getTokens':
      return await getCustomTokens(data);
    case 'getTokenInfo':
      return await getTokenInfo(data);
    case 'scanTokenAllNetworks':
      return await scanTokenAllNetworks(data);
    case 'autoFetchTokens':
      return await autoFetchTokens(data);
    case 'getPendingDappRequest':
      return getPendingDappRequest();
    case 'approveDappConnection':
      return await approveDappConnection(data);
    case 'rejectDappConnection':
      return rejectDappConnection(data);

    // Price Data
    case 'getPrice':
      return await getTokenPrice(data);
    case 'getPrices':
      return await getAllPrices(data);

    // Web3 Provider Requests (from content script)
    case 'web3Request':
      return await handleWeb3Request(data, sender);

    default:
      return { success: false, error: `Unknown action: ${action}` };
  }
}

/**
 * Create a new wallet
 */
async function createWallet({ password }) {
  try {
    const walletData = await walletManager.createNewWallet();
    
    // Generate master wallet ID for proper grouping
    const masterWalletId = generateWalletId();
    
    const fullData = {
      mnemonic: walletData.mnemonic,
      masterWallets: [{
        id: masterWalletId,
        name: 'Master Wallet 1',
        mnemonic: walletData.mnemonic,
        createdAt: Date.now(),
        type: 'master'
      }],
      accounts: [{
        address: walletData.address,
        privateKey: walletData.privateKey,
        name: 'Account 1',
        type: 'derived',
        derivationPath: walletData.derivationPath,
        accountIndex: 0,
        masterWalletId: masterWalletId
      }],
      activeAccountIndex: 0
    };

    await storageManager.saveWallet(fullData, password);
    
    // Auto-unlock after creation
    isUnlocked = true;
    sessionPassword = password;
    currentWalletData = fullData;
    
    // Save session password for persistence across service worker restarts
    await saveSessionPassword(password);

    return { 
      success: true, 
      mnemonic: walletData.mnemonic,
      address: walletData.address 
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Import wallet from mnemonic or private key
 */
async function importWallet({ password, mnemonic, privateKey, type }) {
  try {
    let walletData;
    let fullData;

    if (type === 'mnemonic' && mnemonic) {
      walletData = await walletManager.importFromMnemonic(mnemonic);
      
      // Generate master wallet ID for proper grouping
      const masterWalletId = generateWalletId();
      
      fullData = {
        mnemonic: mnemonic.trim(),
        masterWallets: [{
          id: masterWalletId,
          name: 'Master Wallet 1',
          mnemonic: mnemonic.trim(),
          createdAt: Date.now(),
          type: 'master'
        }],
        accounts: [{
          address: walletData.address,
          privateKey: walletData.privateKey,
          name: 'Account 1',
          type: 'derived',
          derivationPath: walletData.derivationPath,
          accountIndex: 0,
          masterWalletId: masterWalletId
        }],
        activeAccountIndex: 0
      };
    } else if (type === 'privateKey' && privateKey) {
      walletData = await walletManager.importFromPrivateKey(privateKey);
      fullData = {
        accounts: [{
          address: walletData.address,
          privateKey: walletData.privateKey,
          name: 'Account 1',
          type: 'imported'
        }],
        activeAccountIndex: 0
      };
    } else {
      throw new Error('Invalid import type or missing data');
    }

    await storageManager.saveWallet(fullData, password);
    
    isUnlocked = true;
    sessionPassword = password;
    currentWalletData = fullData;
    
    // Save session password for persistence across service worker restarts
    await saveSessionPassword(password);

    return { success: true, address: walletData.address };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Unlock wallet with password
 */
async function unlockWallet({ password }) {
  try {
    const walletData = await storageManager.loadWallet(password);
    if (!walletData) {
      return { success: false, error: 'No wallet found' };
    }

    isUnlocked = true;
    sessionPassword = password;
    currentWalletData = walletData;
    
    // Save session password for persistence across service worker restarts
    await saveSessionPassword(password);
    
    // Reset activity timer on unlock
    updateActivity();

    // Load custom networks from wallet data
    loadCustomNetworksFromWallet();
    
    // Load auto-lock preference
    const prefs = await storageManager.loadPreferences();
    if (prefs.autoLockMinutes !== undefined) {
      autoLockMinutes = prefs.autoLockMinutes;
    }
    
    // Run migration on unlock to ensure accounts are properly linked
    const needsSave = initializeMasterWallets();
    if (needsSave) {
      await storageManager.saveWallet(currentWalletData, password);
      console.log('Migrated wallet data: linked accounts to master wallets');
    }

    return { 
      success: true, 
      address: walletData.accounts[walletData.activeAccountIndex].address 
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Lock wallet
 */
async function lockWallet() {
  isUnlocked = false;
  sessionPassword = null;
  currentWalletData = null;
  
  // Clear session password from storage.session
  await clearSessionPassword();
  
  return { success: true };
}

/**
 * Get auto-lock settings
 */
function getAutoLockSettings() {
  return {
    success: true,
    autoLockMinutes: autoLockMinutes,
    options: AUTO_LOCK_OPTIONS,
    lastActivityTime: lastActivityTime
  };
}

/**
 * Set auto-lock timeout
 */
async function setAutoLockTimeout({ minutes }) {
  // Validate the value
  const validValues = Object.values(AUTO_LOCK_OPTIONS);
  if (!validValues.includes(minutes)) {
    return { success: false, error: 'Invalid auto-lock timeout value' };
  }
  
  autoLockMinutes = minutes;
  
  // Save to preferences
  const prefs = await storageManager.loadPreferences();
  prefs.autoLockMinutes = minutes;
  await storageManager.savePreferences(prefs);
  
  // Reset activity time when changing setting
  updateActivity();
  
  return { success: true, autoLockMinutes: minutes };
}

/**
 * Get wallet status
 */
async function getWalletStatus() {
  // Try to auto-restore session from storage.session if not already unlocked
  if (!isUnlocked) {
    await ensureWalletLoaded();
  }
  
  // Update activity on status check (popup is open)
  if (isUnlocked) {
    updateActivity();
  }
  
  return {
    success: true,
    hasWallet: currentWalletData !== null || isUnlocked,
    isUnlocked: isUnlocked,
    hasMnemonic: isUnlocked && currentWalletData ? !!currentWalletData.mnemonic : false,
    address: isUnlocked && currentWalletData 
      ? currentWalletData.accounts[currentWalletData.activeAccountIndex].address 
      : null,
    network: walletManager.currentNetwork
  };
}

/**
 * Generate QR code for address
 */
async function handleGenerateQRCode({ text }) {
  try {
    console.log('Service worker generating QR for:', text);
    const dataUrl = await generateQRCode(text);
    console.log('QR dataUrl generated:', dataUrl ? 'success' : 'null');
    if (dataUrl) {
      return { success: true, dataUrl };
    } else {
      return { success: false, error: 'QR generation returned null' };
    }
  } catch (error) {
    console.error('QR generation error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Get all accounts with hierarchical structure
 */
async function getAccounts() {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      // No wallet data loaded - return empty
      return { success: false, error: 'No wallet data', accounts: [], masterWallets: [] };
    }
  }
  
  // Initialize master wallets if needed and save if migration occurred
  const needsSave = initializeMasterWallets();
  if (needsSave && sessionPassword) {
    await storageManager.saveWallet(currentWalletData, sessionPassword);
  }
  
  // Get master wallets info
  const masterWallets = (currentWalletData.masterWallets || []).map(mw => ({
    id: mw.id,
    name: mw.name,
    type: 'master'
  }));
  
  // Map accounts with master wallet info
  const accounts = currentWalletData.accounts.map((acc, idx) => {
    const masterWallet = acc.masterWalletId 
      ? masterWallets.find(mw => mw.id === acc.masterWalletId)
      : null;
    
    return {
      address: acc.address,
      name: acc.name,
      type: acc.type || 'derived',
      isActive: idx === currentWalletData.activeAccountIndex,
      accountIndex: acc.accountIndex,
      masterWalletId: acc.masterWalletId || null,
      masterWalletName: masterWallet?.name || null,
      index: idx
    };
  });

  console.log('getAccounts - total accounts:', accounts.length);
  console.log('getAccounts - watch accounts:', accounts.filter(a => a.type === 'watch').length);
  console.log('getAccounts - master wallets:', masterWallets.length);

  return { 
    success: true, 
    accounts,
    masterWallets,
    hasMnemonic: !!currentWalletData.mnemonic || (currentWalletData.masterWallets?.length > 0)
  };
}

/**
 * Get the next available account index for derived accounts
 */
async function getNextAccountIndex() {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  // Find the highest existing account index from derived accounts
  let maxIndex = -1;
  currentWalletData.accounts.forEach(acc => {
    if (acc.type === 'derived' && acc.accountIndex !== null && acc.accountIndex !== undefined) {
      maxIndex = Math.max(maxIndex, acc.accountIndex);
    }
  });
  
  const nextIndex = maxIndex + 1;
  
  return { 
    success: true, 
    nextIndex: nextIndex,
    nextAccountNumber: nextIndex + 1 // Human-readable (1-based)
  };
}

/**
 * Add a new account (derive from mnemonic)
 */
async function addAccount({ name }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  if (!currentWalletData.mnemonic) {
    return { success: false, error: 'Cannot derive accounts from imported private key wallet' };
  }

  try {
    // Find the highest existing account index from derived accounts
    let maxIndex = -1;
    currentWalletData.accounts.forEach(acc => {
      if (acc.type === 'derived' && acc.accountIndex !== null && acc.accountIndex !== undefined) {
        maxIndex = Math.max(maxIndex, acc.accountIndex);
      }
    });
    
    const newIndex = maxIndex + 1;
    const walletData = await walletManager.deriveAccount(currentWalletData.mnemonic, newIndex);
    
    const newAccount = {
      address: walletData.address,
      privateKey: walletData.privateKey,
      name: name || `Account ${newIndex + 1}`,
      type: 'derived',
      accountIndex: newIndex
    };

    currentWalletData.accounts.push(newAccount);
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { success: true, address: newAccount.address, accountIndex: newIndex };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Create a new Master HD Wallet
 * Users can have multiple Master Wallets, each with their own seed phrase
 */
async function createHDWallet({ name }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Initialize masterWallets array if not present
    if (!currentWalletData.masterWallets) {
      currentWalletData.masterWallets = [];
      
      // Migrate existing mnemonic to first master wallet if present
      if (currentWalletData.mnemonic) {
        const existingMasterWallet = {
          id: generateWalletId(),
          name: 'Master Wallet 1',
          mnemonic: currentWalletData.mnemonic,
          createdAt: Date.now(),
          type: 'master'
        };
        currentWalletData.masterWallets.push(existingMasterWallet);
        
        // Update existing derived accounts to reference this master wallet
        currentWalletData.accounts.forEach(acc => {
          if (acc.type === 'derived' && !acc.masterWalletId) {
            acc.masterWalletId = existingMasterWallet.id;
          }
        });
      }
    }

    // Generate new mnemonic for new master wallet
    const walletData = await walletManager.createNewWallet();
    const masterWalletId = generateWalletId();
    const masterWalletNumber = currentWalletData.masterWallets.length + 1;
    
    const newMasterWallet = {
      id: masterWalletId,
      name: name || `Master Wallet ${masterWalletNumber}`,
      mnemonic: walletData.mnemonic,
      createdAt: Date.now(),
      type: 'master'
    };
    
    currentWalletData.masterWallets.push(newMasterWallet);
    
    // Also set as primary mnemonic if this is the first one
    if (!currentWalletData.mnemonic) {
      currentWalletData.mnemonic = walletData.mnemonic;
    }
    
    // Derive first account from this master wallet
    const derivedAccount = await walletManager.deriveAccount(walletData.mnemonic, 0);
    
    // Check if this address already exists
    const exists = currentWalletData.accounts.some(
      acc => acc.address.toLowerCase() === derivedAccount.address.toLowerCase()
    );
    
    let newAccountIndex = null;
    if (!exists) {
      const newAccount = {
        address: derivedAccount.address,
        privateKey: derivedAccount.privateKey,
        name: 'Account 1',
        type: 'derived',
        accountIndex: 0,
        masterWalletId: masterWalletId
      };
      
      currentWalletData.accounts.push(newAccount);
      newAccountIndex = currentWalletData.accounts.length - 1;
      
      // Switch to the newly created account
      currentWalletData.activeAccountIndex = newAccountIndex;
    }
    
    await storageManager.saveWallet(currentWalletData, sessionPassword);
    
    return { 
      success: true, 
      masterWalletId,
      masterWalletName: newMasterWallet.name,
      mnemonic: walletData.mnemonic,
      hasMnemonic: true,
      address: derivedAccount.address,
      activeAccountIndex: newAccountIndex
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Generate unique wallet ID
 */
function generateWalletId() {
  return 'mw_' + Date.now().toString(36) + '_' + Math.random().toString(36).substr(2, 9);
}

/**
 * Get all master wallets
 */
async function getMasterWallets() {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }
  
  // Initialize or migrate master wallets and save if needed
  const needsSave = initializeMasterWallets();
  if (needsSave && sessionPassword) {
    await storageManager.saveWallet(currentWalletData, sessionPassword);
  }
  
  const masterWallets = (currentWalletData.masterWallets || []).map(mw => {
    // Find the first account (Account 1 / index 0) for this master wallet
    const accounts = currentWalletData.accounts.filter(a => a.masterWalletId === mw.id);
    const firstAccount = accounts.find(a => a.accountIndex === 0) || accounts[0];
    
    return {
      id: mw.id,
      name: mw.name,
      createdAt: mw.createdAt,
      accountCount: accounts.length,
      firstAddress: firstAccount?.address || null
    };
  });
  
  return { success: true, masterWallets };
}

/**
 * Initialize master wallets (migrate from legacy structure if needed)
 * Returns true if migration was needed and data should be saved
 */
function initializeMasterWallets() {
  if (!currentWalletData) return false;
  
  let needsSave = false;
  
  // Initialize masterWallets array if not present
  if (!currentWalletData.masterWallets) {
    currentWalletData.masterWallets = [];
    needsSave = true;
  }
  
  // If we have a mnemonic but no master wallets, create one
  if (currentWalletData.mnemonic && currentWalletData.masterWallets.length === 0) {
    const masterId = generateWalletId();
    currentWalletData.masterWallets.push({
      id: masterId,
      name: 'Master Wallet 1',
      mnemonic: currentWalletData.mnemonic,
      createdAt: Date.now(),
      type: 'master'
    });
    needsSave = true;
    
    // Update ALL accounts that don't have masterWalletId (except imported/watch)
    currentWalletData.accounts.forEach(acc => {
      // Skip explicitly imported or watch accounts
      if (acc.type === 'imported' || acc.type === 'imported-seed' || acc.type === 'watch') {
        return;
      }
      
      // All other accounts should be linked to this master wallet
      if (!acc.masterWalletId) {
        acc.masterWalletId = masterId;
        acc.type = 'derived';
        // Set accountIndex if not present (assume first account is index 0)
        if (acc.accountIndex === undefined || acc.accountIndex === null) {
          acc.accountIndex = 0;
        }
      }
    });
  }
  
  // If we have master wallets, ensure all unlinked accounts are linked
  if (currentWalletData.masterWallets.length > 0) {
    const firstMasterId = currentWalletData.masterWallets[0].id;
    
    currentWalletData.accounts.forEach(acc => {
      // Skip explicitly imported or watch accounts
      if (acc.type === 'imported' || acc.type === 'imported-seed' || acc.type === 'watch') {
        return;
      }
      
      // Any account without masterWalletId should be linked
      if (!acc.masterWalletId) {
        acc.masterWalletId = firstMasterId;
        acc.type = 'derived';
        needsSave = true;
        
        // Set accountIndex if not present
        if (acc.accountIndex === undefined || acc.accountIndex === null) {
          acc.accountIndex = 0;
        }
      }
    });
  }
  
  return needsSave;
}

/**
 * Add account to a specific master wallet
 */
async function addAccountToMaster({ masterWalletId, name }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }
  
  initializeMasterWallets();
  
  const masterWallet = currentWalletData.masterWallets.find(mw => mw.id === masterWalletId);
  if (!masterWallet) {
    return { success: false, error: 'Master wallet not found' };
  }
  
  try {
    // Find the highest account index for this master wallet
    let maxIndex = -1;
    currentWalletData.accounts.forEach(acc => {
      if (acc.masterWalletId === masterWalletId && acc.accountIndex !== null && acc.accountIndex !== undefined) {
        maxIndex = Math.max(maxIndex, acc.accountIndex);
      }
    });
    
    const newIndex = maxIndex + 1;
    const derivedAccount = await walletManager.deriveAccount(masterWallet.mnemonic, newIndex);
    
    // Check if address already exists
    const exists = currentWalletData.accounts.some(
      acc => acc.address.toLowerCase() === derivedAccount.address.toLowerCase()
    );
    
    if (exists) {
      return { success: false, error: 'Account already exists' };
    }
    
    const newAccount = {
      address: derivedAccount.address,
      privateKey: derivedAccount.privateKey,
      name: name || `Account ${newIndex + 1}`,
      type: 'derived',
      accountIndex: newIndex,
      masterWalletId: masterWalletId
    };
    
    currentWalletData.accounts.push(newAccount);
    
    // Switch to the newly created account
    const newAccountIdx = currentWalletData.accounts.length - 1;
    currentWalletData.activeAccountIndex = newAccountIdx;
    
    await storageManager.saveWallet(currentWalletData, sessionPassword);
    
    return { 
      success: true, 
      address: newAccount.address,
      name: newAccount.name,
      accountIndex: newIndex,
      activeAccountIndex: newAccountIdx
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Rename a master wallet
 */
async function renameMasterWallet({ masterWalletId, newName }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }
  
  initializeMasterWallets();
  
  if (!newName || !newName.trim()) {
    return { success: false, error: 'New name is required' };
  }
  
  const masterWallet = currentWalletData.masterWallets.find(mw => mw.id === masterWalletId);
  if (!masterWallet) {
    return { success: false, error: 'Master wallet not found' };
  }
  
  try {
    const oldName = masterWallet.name;
    masterWallet.name = newName.trim();
    
    await storageManager.saveWallet(currentWalletData, sessionPassword);
    
    console.log('renameMasterWallet - renamed from:', oldName, 'to:', masterWallet.name);
    
    return { 
      success: true, 
      masterWalletId: masterWalletId,
      oldName: oldName,
      newName: masterWallet.name
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Bulk add accounts to a specific master wallet
 */
async function bulkAddToMaster({ masterWalletId, count }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }
  
  initializeMasterWallets();
  
  const masterWallet = currentWalletData.masterWallets.find(mw => mw.id === masterWalletId);
  if (!masterWallet) {
    return { success: false, error: 'Master wallet not found' };
  }
  
  try {
    const addedAccounts = [];
    let firstNewAccountIndex = null;
    
    // Find highest index for this master wallet
    let maxIndex = -1;
    currentWalletData.accounts.forEach(acc => {
      if (acc.masterWalletId === masterWalletId && acc.accountIndex !== null) {
        maxIndex = Math.max(maxIndex, acc.accountIndex);
      }
    });
    
    for (let i = 0; i < count; i++) {
      const newIndex = maxIndex + 1 + i;
      const derivedAccount = await walletManager.deriveAccount(masterWallet.mnemonic, newIndex);
      
      const exists = currentWalletData.accounts.some(
        acc => acc.address.toLowerCase() === derivedAccount.address.toLowerCase()
      );
      
      if (!exists) {
        const newAccount = {
          address: derivedAccount.address,
          privateKey: derivedAccount.privateKey,
          name: `Account ${newIndex + 1}`,
          type: 'derived',
          accountIndex: newIndex,
          masterWalletId: masterWalletId
        };
        
        currentWalletData.accounts.push(newAccount);
        
        // Track the first added account index
        if (firstNewAccountIndex === null) {
          firstNewAccountIndex = currentWalletData.accounts.length - 1;
        }
        
        addedAccounts.push(newAccount);
      }
    }
    
    // Switch to the first newly added account
    if (firstNewAccountIndex !== null) {
      currentWalletData.activeAccountIndex = firstNewAccountIndex;
    }
    
    console.log('bulkAddToMaster - added accounts:', addedAccounts.length);
    console.log('bulkAddToMaster - total accounts now:', currentWalletData.accounts.length);
    console.log('bulkAddToMaster - switched to account index:', firstNewAccountIndex);
    await storageManager.saveWallet(currentWalletData, sessionPassword);
    
    return { 
      success: true, 
      addedCount: addedAccounts.length,
      accounts: addedAccounts.map(a => ({ address: a.address, name: a.name })),
      activeAccountIndex: firstNewAccountIndex
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Switch to a different account
 */
async function switchAccount({ accountIndex }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  if (accountIndex < 0 || accountIndex >= currentWalletData.accounts.length) {
    return { success: false, error: 'Invalid account index' };
  }

  currentWalletData.activeAccountIndex = accountIndex;
  await storageManager.saveWallet(currentWalletData, sessionPassword);

  const activeAccount = currentWalletData.accounts[accountIndex];
  return { 
    success: true, 
    address: activeAccount.address,
    name: activeAccount.name,
    accountIndex: accountIndex
  };
}

/**
 * Rename an account
 */
async function renameAccount({ accountIndex, newName }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  if (accountIndex < 0 || accountIndex >= currentWalletData.accounts.length) {
    return { success: false, error: 'Invalid account index' };
  }

  currentWalletData.accounts[accountIndex].name = newName;
  await storageManager.saveWallet(currentWalletData, sessionPassword);

  return { success: true };
}

/**
 * Remove an account (cannot remove last account or Account 1 if it's HD derived)
 */
async function removeAccount({ accountIndex }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  if (currentWalletData.accounts.length <= 1) {
    return { success: false, error: 'Cannot remove the last account' };
  }

  if (accountIndex < 0 || accountIndex >= currentWalletData.accounts.length) {
    return { success: false, error: 'Invalid account index' };
  }

  // Don't allow removing account 0 if it's the HD root
  if (accountIndex === 0 && currentWalletData.mnemonic) {
    return { success: false, error: 'Cannot remove the primary HD account' };
  }

  currentWalletData.accounts.splice(accountIndex, 1);
  
  // Adjust active account index if needed
  if (currentWalletData.activeAccountIndex >= currentWalletData.accounts.length) {
    currentWalletData.activeAccountIndex = currentWalletData.accounts.length - 1;
  }

  await storageManager.saveWallet(currentWalletData, sessionPassword);

  return { success: true };
}

/**
 * Import an additional account via private key
 */
async function importPrivateKeyAccount({ privateKey, name }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    const walletData = await walletManager.importFromPrivateKey(privateKey);
    
    // Check if account already exists
    const exists = currentWalletData.accounts.some(
      acc => acc.address.toLowerCase() === walletData.address.toLowerCase()
    );
    
    if (exists) {
      return { success: false, error: 'Account already exists' };
    }

    const newAccount = {
      address: walletData.address,
      privateKey: walletData.privateKey,
      name: name || `Imported ${currentWalletData.accounts.length + 1}`,
      type: 'imported',
      accountIndex: null // Not derived from HD
    };

    currentWalletData.accounts.push(newAccount);
    
    // Switch to the newly imported account
    const newAccountIndex = currentWalletData.accounts.length - 1;
    currentWalletData.activeAccountIndex = newAccountIndex;
    
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { 
      success: true, 
      address: newAccount.address,
      accountIndex: newAccountIndex
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Bulk add multiple derived accounts at once
 */
async function bulkAddAccounts({ count, startIndex }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  if (!currentWalletData.mnemonic) {
    return { success: false, error: 'No seed phrase available - only HD wallets can derive accounts' };
  }

  try {
    const addedAccounts = [];
    
    // Find the highest existing account index from derived accounts
    let maxIndex = -1;
    currentWalletData.accounts.forEach(acc => {
      if (acc.type === 'derived' && acc.accountIndex !== null && acc.accountIndex !== undefined) {
        maxIndex = Math.max(maxIndex, acc.accountIndex);
      }
    });
    
    const actualStartIndex = startIndex !== undefined ? startIndex : maxIndex + 1;
    
    for (let i = 0; i < count; i++) {
      const newIndex = actualStartIndex + i;
      const walletData = await walletManager.deriveAccount(currentWalletData.mnemonic, newIndex);
      
      // Check if this address already exists
      const exists = currentWalletData.accounts.some(
        acc => acc.address.toLowerCase() === walletData.address.toLowerCase()
      );
      
      if (!exists) {
        const newAccount = {
          address: walletData.address,
          privateKey: walletData.privateKey,
          name: `Account ${newIndex + 1}`,
          type: 'derived',
          accountIndex: newIndex
        };
        
        currentWalletData.accounts.push(newAccount);
        addedAccounts.push({
          address: newAccount.address,
          name: newAccount.name,
          accountIndex: newIndex
        });
      }
    }

    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { 
      success: true, 
      addedCount: addedAccounts.length,
      accounts: addedAccounts
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Import accounts from an external seed phrase - Creates a NEW Master Wallet
 * This allows users to have multiple HD wallets from different seed phrases
 */
async function importSeedPhraseAccounts({ seedPhrase, mnemonic, count = 1, name }) {
  console.log('importSeedPhraseAccounts - called with count:', count, 'name:', name);
  
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    console.log('importSeedPhraseAccounts - currentWalletData is null, restoring session...');
    const restored = await ensureWalletLoaded();
    if (!restored) {
      console.log('importSeedPhraseAccounts - failed to restore session');
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
    console.log('importSeedPhraseAccounts - session restored successfully');
  }

  // Accept both seedPhrase and mnemonic parameters
  const phrase = (seedPhrase || mnemonic || '').trim();
  
  console.log('importSeedPhraseAccounts - phrase word count:', phrase.split(/\s+/).length);
  
  try {
    // Validate mnemonic
    const isValid = ethers.Mnemonic.isValidMnemonic(phrase);
    if (!isValid) {
      return { success: false, error: 'Invalid seed phrase' };
    }

    // Initialize master wallets array if needed
    if (!currentWalletData.masterWallets) {
      currentWalletData.masterWallets = [];
    }

    // Create a new Master Wallet for this seed phrase
    const masterWalletId = generateWalletId();
    const masterWalletNumber = currentWalletData.masterWallets.length + 1;
    const masterWalletName = name || `Master Wallet ${masterWalletNumber}`;
    
    const newMasterWallet = {
      id: masterWalletId,
      name: masterWalletName,
      mnemonic: phrase,
      createdAt: Date.now(),
      type: 'master'
    };
    
    currentWalletData.masterWallets.push(newMasterWallet);
    
    const addedAccounts = [];
    let firstAccountIndex = null;
    
    // Derive accounts under this new master wallet
    for (let i = 0; i < count; i++) {
      const walletData = await walletManager.deriveAccount(phrase, i);
      
      // Check if this address already exists
      const exists = currentWalletData.accounts.some(
        acc => acc.address.toLowerCase() === walletData.address.toLowerCase()
      );
      
      if (!exists) {
        const accountName = `Account ${i + 1}`;
          
        const newAccount = {
          address: walletData.address,
          privateKey: walletData.privateKey,
          name: accountName,
          type: 'derived',
          accountIndex: i,
          masterWalletId: masterWalletId
        };
        
        currentWalletData.accounts.push(newAccount);
        
        // Track the first account index for switching
        if (firstAccountIndex === null) {
          firstAccountIndex = currentWalletData.accounts.length - 1;
        }
        
        addedAccounts.push({
          address: newAccount.address,
          name: newAccount.name,
          accountIndex: i
        });
      }
    }

    // Switch to the first newly added account
    if (firstAccountIndex !== null) {
      currentWalletData.activeAccountIndex = firstAccountIndex;
    }

    console.log('importSeedPhraseAccounts - created master wallet:', masterWalletName);
    console.log('importSeedPhraseAccounts - added accounts:', addedAccounts.length);
    console.log('importSeedPhraseAccounts - switched to account index:', firstAccountIndex);

    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { 
      success: true, 
      addedCount: addedAccounts.length,
      accounts: addedAccounts,
      masterWalletId: masterWalletId,
      masterWalletName: masterWalletName,
      activeAccountIndex: firstAccountIndex
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Add a watch-only wallet (no private key)
 */
async function addWatchWallet({ address, name }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Validate address
    if (!ethers.isAddress(address)) {
      return { success: false, error: 'Invalid Ethereum address' };
    }

    // Check if address already exists
    const exists = currentWalletData.accounts.some(
      acc => acc.address.toLowerCase() === address.toLowerCase()
    );

    if (exists) {
      return { success: false, error: 'This address is already in your wallet' };
    }

    const newAccount = {
      address: ethers.getAddress(address), // Checksum address
      privateKey: null, // Watch-only, no private key
      name: name || 'Watch Wallet',
      type: 'watch',
      accountIndex: null,
      isWatchOnly: true
    };

    currentWalletData.accounts.push(newAccount);
    
    // Switch to the newly added watch wallet
    const newAccountIndex = currentWalletData.accounts.length - 1;
    currentWalletData.activeAccountIndex = newAccountIndex;
    
    console.log('addWatchWallet - added account:', newAccount.name, newAccount.type, newAccount.address);
    console.log('addWatchWallet - switched to account index:', newAccountIndex);
    console.log('addWatchWallet - total accounts now:', currentWalletData.accounts.length);
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { 
      success: true, 
      address: newAccount.address,
      name: newAccount.name,
      activeAccountIndex: newAccountIndex
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Recover a specific account by index from a master wallet
 */
async function recoverAccountByIndex({ masterWalletId, index }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  initializeMasterWallets();

  const masterWallet = currentWalletData.masterWallets?.find(mw => mw.id === masterWalletId);
  if (!masterWallet) {
    return { success: false, error: 'Master wallet not found' };
  }

  try {
    const derivedAccount = await walletManager.deriveAccount(masterWallet.mnemonic, index);

    // Check if address already exists
    const exists = currentWalletData.accounts.some(
      acc => acc.address.toLowerCase() === derivedAccount.address.toLowerCase()
    );

    if (exists) {
      return { success: false, error: 'This account already exists in your wallet' };
    }

    const newAccount = {
      address: derivedAccount.address,
      privateKey: derivedAccount.privateKey,
      name: `Account ${index + 1}`,
      type: 'derived',
      accountIndex: index,
      masterWalletId: masterWalletId
    };

    currentWalletData.accounts.push(newAccount);
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { 
      success: true, 
      address: newAccount.address,
      name: newAccount.name,
      accountIndex: index
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Get balance for current account
 */
async function getBalance({ address }) {
  try {
    const targetAddress = address || 
      (currentWalletData?.accounts[currentWalletData.activeAccountIndex]?.address);
    
    if (!targetAddress) {
      return { success: false, error: 'No address provided' };
    }

    console.log('getBalance called for:', targetAddress, 'on network:', walletManager.currentNetwork?.name);
    const balance = await walletManager.getBalance(targetAddress);
    console.log('Balance result:', balance);
    return { success: true, balance };
  } catch (error) {
    console.error('getBalance error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Get token balance
 */
async function getTokenBalance({ address, tokenAddress }) {
  try {
    const balance = await walletManager.getTokenBalance(address, tokenAddress);
    return { success: true, balance };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Send transaction
 */
async function sendTransaction({ to, amount }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    
    if (!activeAccount) {
      return { success: false, error: 'No active account found' };
    }
    
    if (!activeAccount.privateKey) {
      return { success: false, error: 'Private key not available for this account' };
    }
    
    // Ensure private key has 0x prefix
    let privateKey = activeAccount.privateKey;
    if (!privateKey.startsWith('0x')) {
      privateKey = '0x' + privateKey;
    }
    
    const receipt = await walletManager.sendTransaction(privateKey, to, amount);
    
    return { 
      success: true, 
      txHash: receipt.hash,
      receipt 
    };
  } catch (error) {
    console.error('sendTransaction error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Send token transaction
 */
async function sendToken({ tokenAddress, to, amount }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    
    if (!activeAccount) {
      return { success: false, error: 'No active account found' };
    }
    
    if (!activeAccount.privateKey) {
      return { success: false, error: 'Private key not available for this account' };
    }
    
    // Ensure private key has 0x prefix
    let privateKey = activeAccount.privateKey;
    if (!privateKey.startsWith('0x')) {
      privateKey = '0x' + privateKey;
    }
    
    const receipt = await walletManager.sendToken(
      privateKey, 
      tokenAddress, 
      to, 
      amount
    );
    
    return { 
      success: true, 
      txHash: receipt.hash,
      receipt 
    };
  } catch (error) {
    console.error('sendToken error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Estimate gas
 */
async function estimateGas({ to, amount }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    const gasInfo = await walletManager.estimateGas(activeAccount.address, to, amount);
    return { success: true, gasInfo };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Get transaction history
 */
async function getTransactionHistory({ address }) {
  try {
    const targetAddress = address || 
      (currentWalletData?.accounts[currentWalletData.activeAccountIndex]?.address);
    
    console.log('getTransactionHistory called for address:', targetAddress);
    console.log('Current network:', walletManager.currentNetwork?.name, 'chainId:', walletManager.currentNetwork?.chainId);
    
    const history = await walletManager.getTransactionHistory(targetAddress);
    console.log('Transaction history fetched, count:', history?.length || 0);
    
    return { success: true, history };
  } catch (error) {
    console.error('getTransactionHistory error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Sign message
 */
async function signMessage({ message }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    
    if (!activeAccount) {
      return { success: false, error: 'No active account found' };
    }
    
    if (!activeAccount.privateKey) {
      return { success: false, error: 'Private key not available for this account' };
    }
    
    // Ensure private key has 0x prefix
    let privateKey = activeAccount.privateKey;
    if (!privateKey.startsWith('0x')) {
      privateKey = '0x' + privateKey;
    }
    
    console.log('signMessage: Signing for address:', activeAccount.address);
    console.log('signMessage: Message type:', typeof message);
    console.log('signMessage: Message preview:', typeof message === 'string' ? message.substring(0, 100) : message);
    
    const wallet = new ethers.Wallet(privateKey);
    const signature = await wallet.signMessage(message);
    
    console.log('signMessage: Signature generated:', signature.substring(0, 20) + '...');
    console.log('signMessage: Signature length:', signature.length);
    
    // Verify the signature recovers to the correct address
    const recoveredAddress = ethers.verifyMessage(message, signature);
    console.log('signMessage: Recovered address:', recoveredAddress);
    console.log('signMessage: Expected address:', activeAccount.address);
    console.log('signMessage: Address match:', recoveredAddress.toLowerCase() === activeAccount.address.toLowerCase());
    
    return { success: true, signature };
  } catch (error) {
    console.error('signMessage error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Sign typed data (EIP-712)
 */
async function signTypedData({ domain, types, value }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    
    if (!activeAccount) {
      return { success: false, error: 'No active account found' };
    }
    
    if (!activeAccount.privateKey) {
      return { success: false, error: 'Private key not available for this account' };
    }
    
    // Ensure private key has 0x prefix
    let privateKey = activeAccount.privateKey;
    if (!privateKey.startsWith('0x')) {
      privateKey = '0x' + privateKey;
    }
    
    const signature = await walletManager.signTypedData(
      privateKey, 
      domain, 
      types, 
      value
    );
    return { success: true, signature };
  } catch (error) {
    console.error('signTypedData error:', error);
    return { success: false, error: error.message };
  }
}

/**
 * Switch network
 */
function switchNetwork({ networkKey, customRpcUrl }) {
  try {
    // networkKey can be either a network key (like 'ramestta_mainnet') or a chainId (like '0x55a')
    let network = null;

    // First check if it's a chainId (hex string)
    if (networkKey.startsWith('0x')) {
      const targetChainId = parseInt(networkKey, 16);
      
      // Find network by chainId in enabled NETWORKS
      for (const [key, net] of Object.entries(NETWORKS)) {
        if (net.chainId === targetChainId || net.chainIdHex === networkKey) {
          network = { ...net };
          break;
        }
      }
      
      // Check ALL_NETWORKS if not found in enabled (for backwards compatibility)
      if (!network) {
        for (const [key, net] of Object.entries(ALL_NETWORKS)) {
          if (net.chainId === targetChainId || net.chainIdHex === networkKey) {
            network = { ...net };
            break;
          }
        }
      }
      
      // Apply custom RPC URL if provided
      if (network && customRpcUrl) {
        network.rpcUrl = customRpcUrl;
      }
      
      if (network) {
        walletManager.currentNetwork = network;
        walletManager.provider = null;
      }
    } else {
      // It's a network key
      if (NETWORKS[networkKey]) {
        walletManager.switchNetwork(networkKey);
        network = walletManager.currentNetwork;
      } else if (ALL_NETWORKS[networkKey]) {
        // Enable the network first if it exists in ALL_NETWORKS
        enableNetwork(networkKey);
        walletManager.currentNetwork = ALL_NETWORKS[networkKey];
        walletManager.provider = null;
        network = walletManager.currentNetwork;
      }
    }

    if (!network) {
      return { success: false, error: 'Network not found' };
    }
    
    // Save preference
    storageManager.loadPreferences().then(prefs => {
      prefs.network = networkKey;
      if (customRpcUrl) {
        prefs.customRpcUrl = customRpcUrl;
      } else {
        delete prefs.customRpcUrl;
      }
      storageManager.savePreferences(prefs);
    });

    return { success: true, network: walletManager.currentNetwork };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Request dApp connection - opens popup for user approval
 */
async function requestDappConnection(origin, sender) {
  return new Promise((resolve) => {
    const requestId = Date.now().toString();
    
    // Store the pending request
    pendingDappRequests.set(requestId, {
      origin,
      tabId: sender.tab?.id,
      resolve,
      timestamp: Date.now()
    });

    // Open popup for user approval
    chrome.action.openPopup().catch(() => {
      // If popup can't be opened (e.g., user gesture required), use a notification window
      chrome.windows.create({
        url: chrome.runtime.getURL(`popup/popup.html?dappRequest=${requestId}&origin=${encodeURIComponent(origin)}`),
        type: 'popup',
        width: 375,
        height: 600,
        focused: true
      });
    });

    // Timeout after 2 minutes
    setTimeout(() => {
      if (pendingDappRequests.has(requestId)) {
        pendingDappRequests.delete(requestId);
        resolve({ success: false, error: 'User rejected the request' });
      }
    }, 120000);
  });
}

/**
 * Get pending dApp connection request
 */
function getPendingDappRequest() {
  // Get the most recent pending request
  const entries = Array.from(pendingDappRequests.entries());
  if (entries.length === 0) {
    return { success: true, request: null };
  }
  
  const [requestId, request] = entries[entries.length - 1];
  return {
    success: true,
    request: {
      id: requestId,
      origin: request.origin,
      timestamp: request.timestamp
    }
  };
}

/**
 * Approve dApp connection
 */
async function approveDappConnection({ requestId }) {
  const pendingRequest = pendingDappRequests.get(requestId);
  
  if (!pendingRequest) {
    return { success: false, error: 'Request not found or expired' };
  }

  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  // Add to connected sites
  connectedSites.add(pendingRequest.origin);
  
  const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
  const result = {
    success: true,
    result: [activeAccount.address],
    accounts: [activeAccount.address],
    chainId: walletManager.currentNetwork.chainIdHex
  };

  // Resolve the pending promise
  pendingRequest.resolve(result);
  pendingDappRequests.delete(requestId);

  // Notify the content script about the connection
  if (pendingRequest.tabId) {
    chrome.tabs.sendMessage(pendingRequest.tabId, {
      type: 'RAMAPAY_STATE_CHANGE',
      data: {
        accounts: [activeAccount.address],
        chainId: walletManager.currentNetwork.chainIdHex,
        connected: true
      }
    }).catch(() => {});
  }

  return result;
}

/**
 * Reject dApp connection
 */
function rejectDappConnection({ requestId }) {
  const pendingRequest = pendingDappRequests.get(requestId);
  
  if (!pendingRequest) {
    return { success: false, error: 'Request not found or expired' };
  }

  pendingRequest.resolve({ success: false, error: 'User rejected the request' });
  pendingDappRequests.delete(requestId);

  return { success: true };
}

/**
 * Connect dApp site (when already unlocked and approved)
 */
async function connectSite({ origin }, sender) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  const siteOrigin = origin || new URL(sender.tab?.url || '').origin;
  connectedSites.add(siteOrigin);

  const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
  
  return { 
    success: true, 
    result: [activeAccount.address],
    accounts: [activeAccount.address],
    chainId: walletManager.currentNetwork.chainIdHex
  };
}

/**
 * Disconnect site
 */
function disconnectSite({ origin }) {
  connectedSites.delete(origin);
  return { success: true };
}

/**
 * Handle Web3 provider requests from dApps
 */
async function handleWeb3Request({ method, params }, sender) {
  const origin = sender.tab?.url ? new URL(sender.tab.url).origin : '';

  switch (method) {
    case 'eth_requestAccounts':
      // If wallet is locked or site not connected, open popup for user approval
      if (!isUnlocked || !connectedSites.has(origin)) {
        return await requestDappConnection(origin, sender);
      }
      return await connectSite({ origin }, sender);

    case 'eth_accounts':
      if (!isUnlocked || !connectedSites.has(origin)) {
        return { success: true, result: [] };
      }
      return { 
        success: true, 
        result: [currentWalletData.accounts[currentWalletData.activeAccountIndex].address]
      };

    case 'eth_chainId':
      return { success: true, result: walletManager.currentNetwork.chainIdHex };

    case 'net_version':
      return { success: true, result: String(walletManager.currentNetwork.chainId) };

    case 'eth_getBalance':
      const balanceResult = await getBalance({ address: params[0] });
      if (balanceResult.success) {
        return { success: true, result: '0x' + BigInt(balanceResult.balance.wei).toString(16) };
      }
      return balanceResult;

    case 'personal_sign':
      if (!connectedSites.has(origin)) {
        return { success: false, error: 'Site not connected' };
      }
      // personal_sign params: [message, address] - message can be hex or string
      let messageToSign = params[0];
      // If message is hex-encoded (starts with 0x), convert to string
      if (typeof messageToSign === 'string' && messageToSign.startsWith('0x')) {
        try {
          // Convert hex to UTF-8 string
          messageToSign = ethers.toUtf8String(messageToSign);
        } catch (e) {
          // If conversion fails, use as-is (might be raw bytes)
          console.log('personal_sign: Using hex message as-is');
        }
      }
      console.log('personal_sign: Signing message:', messageToSign?.substring(0, 100) + '...');
      const signResult = await signMessage({ message: messageToSign });
      if (signResult.success) {
        console.log('personal_sign: Signature generated:', signResult.signature?.substring(0, 20) + '...');
        return { success: true, result: signResult.signature };
      }
      console.error('personal_sign: Error:', signResult.error);
      return signResult;

    case 'eth_signTypedData_v4':
      if (!connectedSites.has(origin)) {
        return { success: false, error: 'Site not connected' };
      }
      const typedData = JSON.parse(params[1]);
      const typedSignResult = await signTypedData(typedData);
      if (typedSignResult.success) {
        return { success: true, result: typedSignResult.signature };
      }
      return typedSignResult;

    case 'eth_sendTransaction':
      if (!connectedSites.has(origin)) {
        return { success: false, error: 'Site not connected' };
      }
      const txParams = params[0];
      // This would typically show a confirmation popup
      return await sendTransaction({ 
        to: txParams.to, 
        amount: txParams.value ? (BigInt(txParams.value) / BigInt(10**18)).toString() : '0'
      });

    case 'wallet_switchEthereumChain':
      const chainId = parseInt(params[0].chainId, 16);
      const networkKey = Object.keys(NETWORKS).find(k => NETWORKS[k].chainId === chainId);
      if (networkKey) {
        return switchNetwork({ networkKey });
      }
      return { success: false, error: 'Unknown chain' };

    default:
      return { success: false, error: `Method not supported: ${method}` };
  }
}

// ============================================
// TOKEN MANAGEMENT
// ============================================

/**
 * Add custom token to wallet
 */
async function addCustomToken({ tokenAddress, chainId }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Get token info from blockchain
    const tokenInfo = await walletManager.getTokenInfo(tokenAddress);
    
    const token = {
      address: tokenAddress.toLowerCase(),
      chainId: chainId || walletManager.currentNetwork.chainId,
      symbol: tokenInfo.symbol,
      name: tokenInfo.name,
      decimals: tokenInfo.decimals,
      addedAt: Date.now()
    };

    // Initialize tokens array if needed
    if (!currentWalletData.customTokens) {
      currentWalletData.customTokens = [];
    }

    // Check if already exists
    const exists = currentWalletData.customTokens.some(
      t => t.address === token.address && t.chainId === token.chainId
    );

    if (exists) {
      return { success: false, error: 'Token already added' };
    }

    currentWalletData.customTokens.push(token);
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { success: true, token };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Remove custom token from wallet
 */
async function removeCustomToken({ tokenAddress, chainId }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    if (!currentWalletData.customTokens) {
      return { success: false, error: 'No custom tokens found' };
    }

    const targetChainId = chainId || walletManager.currentNetwork.chainId;
    currentWalletData.customTokens = currentWalletData.customTokens.filter(
      t => !(t.address.toLowerCase() === tokenAddress.toLowerCase() && t.chainId === targetChainId)
    );

    await storageManager.saveWallet(currentWalletData, sessionPassword);
    return { success: true };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Get all custom tokens for current network
 */
async function getCustomTokens({ chainId }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked', tokens: [] };
    }
  }

  const targetChainId = chainId || walletManager.currentNetwork.chainId;
  const tokens = (currentWalletData.customTokens || []).filter(
    t => t.chainId === targetChainId
  );

  // Get balances for each token
  const tokensWithBalances = await Promise.all(
    tokens.map(async (token) => {
      try {
        const balance = await walletManager.getTokenBalance(
          currentWalletData.accounts[currentWalletData.activeAccountIndex].address,
          token.address
        );
        return { ...token, balance: balance.formatted };
      } catch {
        return { ...token, balance: '0' };
      }
    })
  );

  return { success: true, tokens: tokensWithBalances };
}

/**
 * Get token info from blockchain
 */
async function getTokenInfo({ tokenAddress }) {
  try {
    const info = await walletManager.getTokenInfo(tokenAddress);
    return { success: true, token: info };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Scan token across all available networks
 * Like the Android app, this checks all networks to find where the token exists
 */
async function scanTokenAllNetworks({ tokenAddress }) {
  if (!tokenAddress || !tokenAddress.startsWith('0x') || tokenAddress.length !== 42) {
    return { success: false, error: 'Invalid token address' };
  }

  const foundTokens = [];
  const errors = [];

  // Get all networks to scan
  const networksToScan = Object.entries(ALL_NETWORKS);
  
  // Create ethers provider for each network and check token
  const scanPromises = networksToScan.map(async ([networkKey, network]) => {
    try {
      const provider = new ethers.JsonRpcProvider(network.rpcUrl);
      
      // Set a timeout for slow networks
      const timeoutPromise = new Promise((_, reject) => 
        setTimeout(() => reject(new Error('Timeout')), 10000)
      );
      
      const erc20Abi = [
        'function decimals() view returns (uint8)',
        'function symbol() view returns (string)',
        'function name() view returns (string)',
        'function totalSupply() view returns (uint256)'
      ];

      const contract = new ethers.Contract(tokenAddress, erc20Abi, provider);
      
      const result = await Promise.race([
        Promise.all([
          contract.decimals(),
          contract.symbol(),
          contract.name()
        ]),
        timeoutPromise
      ]);

      const [decimals, symbol, name] = result;
      
      // Valid ERC20 token found on this network
      return {
        found: true,
        networkKey,
        network: network.name,
        chainId: network.chainId,
        chainIdHex: network.chainIdHex,
        symbol: network.symbol,
        token: {
          address: tokenAddress,
          name: name || 'Unknown',
          symbol: symbol || 'TOKEN',
          decimals: Number(decimals) || 18,
          network: network.name,
          networkKey,
          chainId: network.chainId,
          chainIdHex: network.chainIdHex,
          networkSymbol: network.symbol
        }
      };
    } catch (error) {
      // Token not found on this network or RPC error
      return { found: false, networkKey, error: error.message };
    }
  });

  // Wait for all scans to complete
  const results = await Promise.allSettled(scanPromises);
  
  results.forEach((result) => {
    if (result.status === 'fulfilled' && result.value.found) {
      foundTokens.push(result.value);
    }
  });

  if (foundTokens.length === 0) {
    return { 
      success: false, 
      error: 'Token not found on any network',
      scannedNetworks: networksToScan.length
    };
  }

  // Sort with Ramestta networks first, then by network name
  foundTokens.sort((a, b) => {
    if (a.networkKey.startsWith('ramestta') && !b.networkKey.startsWith('ramestta')) return -1;
    if (!a.networkKey.startsWith('ramestta') && b.networkKey.startsWith('ramestta')) return 1;
    return a.network.localeCompare(b.network);
  });

  return { 
    success: true, 
    tokens: foundTokens.map(f => f.token),
    scannedNetworks: networksToScan.length,
    foundCount: foundTokens.length
  };
}

/**
 * Auto-fetch tokens with balance for current wallet on current network
 * Scans common token addresses to find tokens with non-zero balance
 */
async function autoFetchTokens({ address }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked' };
    }
  }

  const walletAddress = address || currentWalletData.accounts[currentWalletData.activeAccountIndex].address;
  const network = walletManager.currentNetwork;
  
  if (!network) {
    return { success: false, error: 'No network selected' };
  }

  // Common token addresses for different networks
  const COMMON_TOKENS = {
    // Ramestta Mainnet tokens
    '1370': [
      { address: '0x1234567890123456789012345678901234567890', symbol: 'WRAMA' }, // Wrapped RAMA (placeholder)
    ],
    // Ethereum Mainnet tokens
    '1': [
      { address: '0xdAC17F958D2ee523a2206206994597C13D831ec7', symbol: 'USDT' },
      { address: '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48', symbol: 'USDC' },
      { address: '0x6B175474E89094C44Da98b954EescdeCB5BB8fD6', symbol: 'DAI' },
      { address: '0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599', symbol: 'WBTC' },
      { address: '0x514910771AF9Ca656af840dff83E8264EcF986CA', symbol: 'LINK' },
      { address: '0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984', symbol: 'UNI' },
      { address: '0x7Fc66500c84A76Ad7e9c93437bFc5Ac33E2DDaE9', symbol: 'AAVE' },
    ],
    // Polygon Mainnet tokens
    '137': [
      { address: '0xc2132D05D31c914a87C6611C10748AEb04B58e8F', symbol: 'USDT' },
      { address: '0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174', symbol: 'USDC' },
      { address: '0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063', symbol: 'DAI' },
      { address: '0x1BFD67037B42Cf73acF2047067bd4F2C47D9BfD6', symbol: 'WBTC' },
      { address: '0x53E0bca35eC356BD5ddDFebbD1Fc0fD03FaBad39', symbol: 'LINK' },
      { address: '0xb33EaAd8d922B1083446DC23f610c2567fB5180f', symbol: 'UNI' },
      { address: '0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270', symbol: 'WMATIC' },
    ],
    // BNB Smart Chain tokens
    '56': [
      { address: '0x55d398326f99059fF775485246999027B3197955', symbol: 'USDT' },
      { address: '0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d', symbol: 'USDC' },
      { address: '0x1AF3F329e8BE154074D8769D1FFa4eE058B1DBc3', symbol: 'DAI' },
      { address: '0x7130d2A12B9BCbFAe4f2634d864A1Ee1Ce3Ead9c', symbol: 'BTCB' },
      { address: '0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c', symbol: 'WBNB' },
      { address: '0x0E09FaBB73Bd3Ade0a17ECC321fD13a19e81cE82', symbol: 'CAKE' },
    ],
    // Arbitrum One tokens  
    '42161': [
      { address: '0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9', symbol: 'USDT' },
      { address: '0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8', symbol: 'USDC' },
      { address: '0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1', symbol: 'DAI' },
      { address: '0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f', symbol: 'WBTC' },
    ],
    // Optimism tokens
    '10': [
      { address: '0x94b008aA00579c1307B0EF2c499aD98a8ce58e58', symbol: 'USDT' },
      { address: '0x7F5c764cBc14f9669B88837ca1490cCa17c31607', symbol: 'USDC' },
      { address: '0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1', symbol: 'DAI' },
    ],
  };

  const chainId = network.chainId.toString();
  const tokensToScan = COMMON_TOKENS[chainId] || [];
  
  if (tokensToScan.length === 0) {
    return { success: true, tokensFound: 0, message: 'No common tokens configured for this network' };
  }

  const foundTokens = [];
  const erc20Abi = [
    'function decimals() view returns (uint8)',
    'function symbol() view returns (string)',
    'function name() view returns (string)',
    'function balanceOf(address) view returns (uint256)'
  ];

  const provider = new ethers.JsonRpcProvider(network.rpcUrl);

  // Scan each token
  await Promise.all(tokensToScan.map(async (tokenInfo) => {
    try {
      const contract = new ethers.Contract(tokenInfo.address, erc20Abi, provider);
      
      const [balance, symbol, name, decimals] = await Promise.all([
        contract.balanceOf(walletAddress),
        contract.symbol(),
        contract.name(),
        contract.decimals()
      ]);
      
      // Only add if balance > 0
      if (balance > 0n) {
        // Check if token already exists
        const exists = (currentWalletData.customTokens || []).some(
          t => t.address.toLowerCase() === tokenInfo.address.toLowerCase() && t.chainId === network.chainId
        );
        
        if (!exists) {
          const newToken = {
            address: tokenInfo.address,
            symbol: symbol || tokenInfo.symbol,
            name: name || symbol || 'Unknown',
            decimals: Number(decimals) || 18,
            chainId: network.chainId,
            addedAt: Date.now()
          };
          
          if (!currentWalletData.customTokens) {
            currentWalletData.customTokens = [];
          }
          currentWalletData.customTokens.push(newToken);
          foundTokens.push(newToken);
        }
      }
    } catch (error) {
      // Token contract not found or error - skip
      console.log(`Token scan error for ${tokenInfo.symbol}:`, error.message);
    }
  }));

  // Save if any tokens were added
  if (foundTokens.length > 0) {
    await storageManager.saveWallet(currentWalletData, sessionPassword);
  }

  return { 
    success: true, 
    tokensFound: foundTokens.length,
    tokens: foundTokens.map(t => t.symbol)
  };
}

// ============================================
// PRICE DATA
// ============================================

// Price cache
const priceCache = {
  data: {},
  lastUpdated: 0,
  cacheDuration: 60000 // 1 minute cache
};

// Token ID mappings for CoinGecko API
// Updated from https://api.coingecko.com/api/v3/coins/list
const COINGECKO_IDS = {
  // Native tokens for each chain
  'RAMA': 'ramestta',
  'ETH': 'ethereum',
  'BNB': 'binancecoin',
  'MATIC': 'matic-network',
  'POL': 'matic-network',  // Polygon renamed to POL
  'AVAX': 'avalanche-2',
  'FTM': 'fantom',
  'XDAI': 'xdai',
  'ETC': 'ethereum-classic',
  'KLAY': 'klay-token',
  'IOTX': 'iotex',
  'CRO': 'crypto-com-chain',
  'OKB': 'okb',
  'RBTC': 'rootstock',
  'MNT': 'mantle',
  'ADA': 'cardano',
  'ARB': 'ethereum',  // Arbitrum uses ETH
  'OP': 'ethereum',   // Optimism uses ETH
  'LINEA': 'ethereum', // Linea uses ETH
  'BASE': 'base',
  
  // Stablecoins
  'USDT': 'tether',
  'USDC': 'usd-coin',
  'DAI': 'dai',
  'BUSD': 'binance-usd',
  'TUSD': 'true-usd',
  'USDP': 'paxos-standard',
  'FRAX': 'frax',
  'LUSD': 'liquity-usd',
  'USDD': 'usdd',
  
  // Major cryptocurrencies
  'BTC': 'bitcoin',
  'WBTC': 'wrapped-bitcoin',
  'WETH': 'weth',
  'stETH': 'staked-ether',
  'rETH': 'rocket-pool-eth',
  'cbETH': 'coinbase-wrapped-staked-eth',
  
  // Popular DeFi tokens
  'UNI': 'uniswap',
  'AAVE': 'aave',
  'LINK': 'chainlink',
  'CRV': 'curve-dao-token',
  'MKR': 'maker',
  'SNX': 'synthetix-network-token',
  'COMP': 'compound-governance-token',
  'SUSHI': 'sushi',
  'YFI': 'yearn-finance',
  '1INCH': '1inch',
  'BAL': 'balancer',
  'LDO': 'lido-dao',
  'RPL': 'rocket-pool',
  'GMX': 'gmx',
  
  // Other popular tokens
  'SHIB': 'shiba-inu',
  'DOGE': 'dogecoin',
  'APE': 'apecoin',
  'SAND': 'the-sandbox',
  'MANA': 'decentraland',
  'AXS': 'axie-infinity',
  'GALA': 'gala',
  'ENS': 'ethereum-name-service',
  'LRC': 'loopring',
  'IMX': 'immutable-x',
  'PEPE': 'pepe',
  'FLOKI': 'floki',
  
  // Wrapped versions
  'WBNB': 'wbnb',
  'WMATIC': 'wmatic',
  'WAVAX': 'wrapped-avax',
  'WFTM': 'wrapped-fantom',
  
  // Aurora ecosystem
  'AURORA': 'aurora',
  'NEAR': 'near'
};

// Chain ID to CoinGecko API name mapping (for token price lookups on specific chains)
// Updated from https://api.coingecko.com/api/v3/asset_platforms
const CHAIN_ID_TO_COINGECKO_PLATFORM = {
  1: 'ethereum',
  10: 'optimistic-ethereum',
  56: 'binance-smart-chain',
  61: 'ethereum-classic',
  100: 'xdai',
  137: 'polygon-pos',
  250: 'fantom',
  321: 'kucoin-community-chain',
  1370: 'ramestta',  // Ramestta Mainnet
  8453: 'base',
  42161: 'arbitrum-one',
  42220: 'celo',
  43114: 'avalanche',
  59144: 'linea',
  5000: 'mantle',
  25: 'cronos',
  30: 'rootstock'
};

// Chain ID to native token CoinGecko ID mapping
// Updated from https://api.coingecko.com/api/v3/coins/list
const CHAIN_ID_TO_NATIVE_TOKEN = {
  1: 'ethereum',
  10: 'ethereum',  // Optimism uses ETH
  56: 'binancecoin',
  61: 'ethereum-classic',
  100: 'xdai',
  137: 'matic-network',
  250: 'fantom',
  1370: 'ramestta',  // Ramestta native token
  8453: 'ethereum',  // Base uses ETH
  42161: 'ethereum', // Arbitrum uses ETH
  42220: 'celo',
  43114: 'avalanche-2',
  59144: 'ethereum', // Linea uses ETH
  5000: 'mantle',
  25: 'crypto-com-chain',
  30: 'rootstock'
};

/**
 * Get price for a specific token
 */
async function getTokenPrice({ symbol, currency = 'usd' }) {
  try {
    const prices = await fetchPrices([symbol], currency);
    const price = prices[symbol.toUpperCase()] || 0;
    
    return { 
      success: true, 
      price,
      symbol: symbol.toUpperCase(),
      currency: currency.toUpperCase()
    };
  } catch (error) {
    return { success: false, error: error.message, price: 0 };
  }
}

/**
 * Get native token price for the current network
 */
async function getNativeTokenPrice(chainId, currency = 'usd') {
  const coinGeckoId = CHAIN_ID_TO_NATIVE_TOKEN[chainId];
  if (!coinGeckoId) return 0;
  
  const cacheKey = `native_${chainId}_${currency}`;
  const now = Date.now();
  
  // Check cache
  if (priceCache.data[cacheKey] && now - priceCache.lastUpdated < priceCache.cacheDuration) {
    return priceCache.data[cacheKey];
  }
  
  try {
    const response = await fetch(
      `https://api.coingecko.com/api/v3/simple/price?ids=${coinGeckoId}&vs_currencies=${currency}&include_24hr_change=true`
    );
    
    if (!response.ok) return 0;
    
    const data = await response.json();
    const price = data[coinGeckoId]?.[currency] || 0;
    const change24h = data[coinGeckoId]?.[`${currency}_24h_change`] || 0;
    
    priceCache.data[cacheKey] = price;
    priceCache.data[`${cacheKey}_change`] = change24h;
    priceCache.lastUpdated = now;
    
    return price;
  } catch (error) {
    console.error('Error fetching native token price:', error);
    return 0;
  }
}

/**
 * Get prices for all tokens in wallet
 */
async function getAllPrices({ symbols, currency = 'usd', chainId }) {
  try {
    // Get chain ID from current network if not provided
    const currentChainId = chainId || walletManager.currentNetwork?.chainId || 1370;
    
    // Default symbols if none provided - include current network's native token
    const tokenSymbols = symbols ? [...symbols] : [];
    
    // Add the current network's native token symbol
    const nativeSymbol = walletManager.currentNetwork?.symbol || 'RAMA';
    if (!tokenSymbols.includes(nativeSymbol)) {
      tokenSymbols.unshift(nativeSymbol);
    }
    
    // Add common tokens
    const commonTokens = ['ETH', 'BTC', 'USDT', 'USDC'];
    commonTokens.forEach(t => {
      if (!tokenSymbols.includes(t)) {
        tokenSymbols.push(t);
      }
    });
    
    // Add custom token symbols
    if (currentWalletData?.customTokens) {
      currentWalletData.customTokens.forEach(t => {
        if (!tokenSymbols.includes(t.symbol)) {
          tokenSymbols.push(t.symbol);
        }
      });
    }

    const prices = await fetchPrices(tokenSymbols, currency, currentChainId);
    
    return { 
      success: true, 
      prices,
      currency: currency.toUpperCase(),
      lastUpdated: priceCache.lastUpdated,
      nativeSymbol
    };
  } catch (error) {
    return { success: false, error: error.message, prices: {} };
  }
}

/**
 * Fetch prices from CoinGecko API
 */
async function fetchPrices(symbols, currency = 'usd', chainId = null) {
  const now = Date.now();
  
  // Check cache
  if (now - priceCache.lastUpdated < priceCache.cacheDuration) {
    const cachedPrices = {};
    let allCached = true;
    
    for (const symbol of symbols) {
      const key = `${symbol.toUpperCase()}_${currency}`;
      if (priceCache.data[key] !== undefined) {
        cachedPrices[symbol.toUpperCase()] = priceCache.data[key];
      } else {
        allCached = false;
      }
    }
    
    if (allCached) {
      return cachedPrices;
    }
  }

  // Build CoinGecko API request
  const ids = symbols
    .map(s => COINGECKO_IDS[s.toUpperCase()])
    .filter(Boolean);
  
  // Remove duplicates
  const uniqueIds = [...new Set(ids)].join(',');

  if (!uniqueIds) {
    // Return zeros for unknown tokens
    const result = {};
    symbols.forEach(s => result[s.toUpperCase()] = 0);
    return result;
  }

  try {
    const response = await fetch(
      `https://api.coingecko.com/api/v3/simple/price?ids=${uniqueIds}&vs_currencies=${currency}&include_24hr_change=true`,
      { signal: AbortSignal.timeout(10000) } // 10 second timeout
    );
    
    if (!response.ok) {
      // Don't throw error, just log and return cached/zero values
      console.warn('Price API returned non-ok status:', response.status);
      const result = {};
      symbols.forEach(s => {
        const key = `${s.toUpperCase()}_${currency}`;
        result[s.toUpperCase()] = priceCache.data[key] || 0;
      });
      return result;
    }

    const data = await response.json();
    
    // Map back to symbols and update cache
    const prices = {};
    priceCache.lastUpdated = now;

    for (const symbol of symbols) {
      const id = COINGECKO_IDS[symbol.toUpperCase()];
      if (id && data[id]) {
        const price = data[id][currency] || 0;
        const change24h = data[id][`${currency}_24h_change`] || 0;
        prices[symbol.toUpperCase()] = price;
        priceCache.data[`${symbol.toUpperCase()}_${currency}`] = price;
        priceCache.data[`${symbol.toUpperCase()}_${currency}_change`] = change24h;
      } else {
        prices[symbol.toUpperCase()] = 0;
      }
    }

    return prices;
  } catch (error) {
    console.error('Price fetch error:', error);
    
    // Return cached data if available, otherwise zeros
    const result = {};
    symbols.forEach(s => {
      const key = `${s.toUpperCase()}_${currency}`;
      result[s.toUpperCase()] = priceCache.data[key] || 0;
    });
    return result;
  }
}

/**
 * Fetch price from alternative sources (fallback)
 */
async function fetchPriceFromRamascan(symbol) {
  // Ramestta-specific price source
  if (symbol.toUpperCase() === 'RAMA') {
    try {
      const response = await fetch('https://ramascan.com/api/v1/stats');
      const data = await response.json();
      return data.price || 0;
    } catch {
      return 0;
    }
  }
  return 0;
}

// ============================================
// CUSTOM NETWORK MANAGEMENT
// ============================================

/**
 * Add a custom network
 */
async function addCustomNetwork({ name, rpcUrl, chainId, symbol, explorerUrl }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Validate required fields
    if (!name || !rpcUrl || !chainId || !symbol) {
      return { success: false, error: 'Missing required network fields' };
    }

    // Validate chainId is a number
    const chainIdNum = parseInt(chainId);
    if (isNaN(chainIdNum)) {
      return { success: false, error: 'Chain ID must be a number' };
    }

    // Check if network already exists
    const existingKey = Object.keys(NETWORKS).find(k => NETWORKS[k].chainId === chainIdNum);
    if (existingKey) {
      return { success: false, error: 'Network with this Chain ID already exists' };
    }

    // Test RPC connection
    try {
      const response = await fetch(rpcUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          method: 'eth_chainId',
          params: [],
          id: 1
        })
      });
      const data = await response.json();
      if (data.error) {
        return { success: false, error: 'Invalid RPC URL: ' + data.error.message };
      }
    } catch (e) {
      return { success: false, error: 'Cannot connect to RPC URL' };
    }

    const network = {
      chainId: chainIdNum,
      chainIdHex: '0x' + chainIdNum.toString(16),
      name: name,
      symbol: symbol.toUpperCase(),
      decimals: 18,
      rpcUrl: rpcUrl,
      rpcUrls: [rpcUrl],
      explorerUrl: explorerUrl || '',
      isTestnet: false,
      isCustom: true
    };

    // Add to NETWORKS
    const networkKey = `custom_${chainIdNum}`;
    NETWORKS[networkKey] = network;

    // Save to wallet data
    if (!currentWalletData.customNetworks) {
      currentWalletData.customNetworks = {};
    }
    currentWalletData.customNetworks[networkKey] = network;
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { success: true, network, networkKey };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Remove a custom network
 */
async function removeCustomNetwork({ networkKey, chainId }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Determine the network key to remove
    let keyToRemove = networkKey;
    
    // If chainId is provided, find the network key
    if (!keyToRemove && chainId) {
      const targetChainId = typeof chainId === 'string' && chainId.startsWith('0x') 
        ? parseInt(chainId, 16) 
        : chainId;
      
      for (const [key, net] of Object.entries(NETWORKS)) {
        if (net.isCustom && (net.chainId === targetChainId || net.chainIdHex === chainId)) {
          keyToRemove = key;
          break;
        }
      }
    }
    
    if (!keyToRemove) {
      return { success: false, error: 'Network not found' };
    }
    
    // Can only remove custom networks
    if (!keyToRemove.startsWith('custom_') && !NETWORKS[keyToRemove]?.isCustom) {
      return { success: false, error: 'Cannot remove built-in networks' };
    }

    if (!NETWORKS[keyToRemove]) {
      return { success: false, error: 'Network not found' };
    }

    const removedNetwork = NETWORKS[keyToRemove];
    
    // Remove from NETWORKS
    delete NETWORKS[keyToRemove];

    // Remove from wallet data
    if (currentWalletData.customNetworks) {
      delete currentWalletData.customNetworks[keyToRemove];
      await storageManager.saveWallet(currentWalletData, sessionPassword);
    }

    // Switch to default if currently on removed network
    if (walletManager.currentNetwork.chainId === removedNetwork.chainId) {
      walletManager.switchNetwork('ramestta_mainnet');
    }

    return { success: true };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Get all custom networks
 */
async function getCustomNetworks() {
  const customNetworks = [];
  Object.entries(NETWORKS).forEach(([key, network]) => {
    if (network.isCustom) {
      customNetworks.push({ key, ...network });
    }
  });
  return { success: true, networks: customNetworks };
}

/**
 * Load custom networks from storage on unlock
 */
function loadCustomNetworksFromWallet() {
  if (currentWalletData?.customNetworks) {
    Object.keys(currentWalletData.customNetworks).forEach(key => {
      NETWORKS[key] = currentWalletData.customNetworks[key];
    });
  }
  // Load enabled networks
  if (currentWalletData?.enabledNetworks) {
    enabledNetworks = currentWalletData.enabledNetworks;
    setEnabledNetworks(enabledNetworks);
  } else {
    enabledNetworks = [...DEFAULT_ENABLED_NETWORKS];
    setEnabledNetworks(enabledNetworks);
  }
}

// ============================================
// ENABLED NETWORK MANAGEMENT
// ============================================

/**
 * Get list of enabled network keys
 */
function getEnabledNetworks() {
  return { success: true, enabledNetworks };
}

/**
 * Enable a built-in network
 */
async function enableBuiltinNetwork({ networkKey }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked' };
    }
  }

  try {
    // Check if network exists in ALL_NETWORKS
    if (!ALL_NETWORKS[networkKey]) {
      return { success: false, error: 'Network not found' };
    }

    // Check if already enabled
    if (enabledNetworks.includes(networkKey)) {
      return { success: true, enabledNetworks };
    }

    // Add to enabled list
    enabledNetworks.push(networkKey);
    
    // Update NETWORKS object
    enableNetwork(networkKey);

    // Save to wallet data
    currentWalletData.enabledNetworks = enabledNetworks;
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { success: true, enabledNetworks };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Disable a built-in network
 */
async function disableBuiltinNetwork({ networkKey }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked' };
    }
  }

  try {
    // Don't allow disabling Ramestta mainnet
    if (networkKey === 'ramestta_mainnet') {
      return { success: false, error: 'Cannot disable primary network' };
    }

    // Check if network is enabled
    const index = enabledNetworks.indexOf(networkKey);
    if (index === -1) {
      return { success: true, enabledNetworks };
    }

    // Remove from enabled list
    enabledNetworks.splice(index, 1);
    
    // Update NETWORKS object
    disableNetwork(networkKey);

    // If currently on this network, switch to Ramestta mainnet
    if (walletManager.currentNetwork.chainId === ALL_NETWORKS[networkKey]?.chainId) {
      walletManager.switchNetwork('ramestta_mainnet');
    }

    // Save to wallet data
    currentWalletData.enabledNetworks = enabledNetworks;
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { success: true, enabledNetworks };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

// ============================================
// SECURITY FEATURES
// ============================================

/**
 * Change wallet password
 */
async function changePassword({ currentPassword, newPassword }) {
  // Auto-restore session if needed
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked' };
    }
  }

  try {
    // Verify current password
    if (currentPassword !== sessionPassword) {
      return { success: false, error: 'Current password is incorrect' };
    }

    // Validate new password
    if (!newPassword || newPassword.length < 8) {
      return { success: false, error: 'New password must be at least 8 characters' };
    }

    // Re-encrypt wallet with new password
    await storageManager.saveWallet(currentWalletData, newPassword);
    sessionPassword = newPassword;

    return { success: true };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Verify password (for security operations)
 */
async function verifyPassword({ password }) {
  // Auto-restore session if needed
  if (!isUnlocked || !currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked' };
    }
  }

  return { 
    success: true, 
    verified: password === sessionPassword 
  };
}

/**
 * Export private key for current account
 */
async function exportPrivateKey({ password, accountIndex }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Verify password
    if (password !== sessionPassword) {
      return { success: false, error: 'Incorrect password' };
    }

    const index = accountIndex ?? currentWalletData.activeAccountIndex;
    const account = currentWalletData.accounts[index];

    if (!account) {
      return { success: false, error: 'Account not found' };
    }

    return { 
      success: true, 
      privateKey: account.privateKey,
      address: account.address
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Export recovery phrase (mnemonic)
 */
async function exportRecoveryPhrase({ password }) {
  // Auto-restore session if needed for internal operations
  if (!currentWalletData) {
    const restored = await ensureWalletLoaded();
    if (!restored) {
      return { success: false, error: 'Wallet not unlocked. Please unlock your wallet first.' };
    }
  }

  try {
    // Verify password
    if (password !== sessionPassword) {
      return { success: false, error: 'Incorrect password' };
    }

    if (!currentWalletData.mnemonic) {
      return { success: false, error: 'This wallet was imported via private key and has no recovery phrase' };
    }

    return { 
      success: true, 
      mnemonic: currentWalletData.mnemonic 
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Get list of connected sites
 */
function getConnectedSites() {
  return { 
    success: true, 
    sites: Array.from(connectedSites) 
  };
}

// Handle extension icon clicks
chrome.action.onClicked.addListener((tab) => {
  // Opens popup by default due to manifest configuration
});

// Clean up on extension unload
chrome.runtime.onSuspend.addListener(() => {
  lockWallet();
});

// Periodic price updates (every 5 minutes when extension is active)
setInterval(async () => {
  if (isUnlocked) {
    try {
      await fetchPrices(['RAMA', 'ETH'], 'usd');
    } catch (e) {
      console.log('Background price update failed:', e);
    }
  }
}, 300000);
