/**
 * RamaPay Chrome Extension - Background Service Worker
 * Handles extension lifecycle, message passing, and Web3 provider coordination
 */

import { WalletManager, StorageManager, NETWORKS } from '../lib/wallet.js';

const walletManager = new WalletManager();
const storageManager = new StorageManager();

// Session state (cleared when extension reloads)
let isUnlocked = false;
let sessionPassword = null;
let currentWalletData = null;
let connectedSites = new Set();

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

  // Check for existing wallet
  const hasWallet = await storageManager.hasWallet();
  console.log('Wallet exists:', hasWallet);
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
    case 'getAccounts':
      return getAccounts();
    case 'addAccount':
      return await addAccount(data);
    case 'switchAccount':
      return await switchAccount(data);
    case 'renameAccount':
      return await renameAccount(data);
    case 'removeAccount':
      return await removeAccount(data);
    case 'importPrivateKeyAccount':
      return await importPrivateKeyAccount(data);

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
    
    const fullData = {
      mnemonic: walletData.mnemonic,
      accounts: [{
        address: walletData.address,
        privateKey: walletData.privateKey,
        name: 'Account 1',
        derivationPath: walletData.derivationPath,
        accountIndex: 0
      }],
      activeAccountIndex: 0
    };

    await storageManager.saveWallet(fullData, password);
    
    // Auto-unlock after creation
    isUnlocked = true;
    sessionPassword = password;
    currentWalletData = fullData;

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
      fullData = {
        mnemonic: mnemonic.trim(),
        accounts: [{
          address: walletData.address,
          privateKey: walletData.privateKey,
          name: 'Account 1',
          derivationPath: walletData.derivationPath,
          accountIndex: 0
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

    // Load custom networks from wallet data
    loadCustomNetworksFromWallet();

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
function lockWallet() {
  isUnlocked = false;
  sessionPassword = null;
  currentWalletData = null;
  return { success: true };
}

/**
 * Get wallet status
 */
function getWalletStatus() {
  return {
    success: true,
    hasWallet: currentWalletData !== null || isUnlocked,
    isUnlocked: isUnlocked,
    address: isUnlocked && currentWalletData 
      ? currentWalletData.accounts[currentWalletData.activeAccountIndex].address 
      : null,
    network: walletManager.currentNetwork
  };
}

/**
 * Get all accounts
 */
function getAccounts() {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  const accounts = currentWalletData.accounts.map((acc, idx) => ({
    address: acc.address,
    name: acc.name,
    type: acc.type || 'derived', // 'derived' for HD derived, 'imported' for private key import
    isActive: idx === currentWalletData.activeAccountIndex,
    accountIndex: acc.accountIndex
  }));

  return { success: true, accounts };
}

/**
 * Add a new account (derive from mnemonic)
 */
async function addAccount({ name }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  if (!currentWalletData.mnemonic) {
    return { success: false, error: 'Cannot derive accounts from imported private key wallet' };
  }

  try {
    const newIndex = currentWalletData.accounts.length;
    const accounts = await walletManager.deriveAccounts(currentWalletData.mnemonic, newIndex + 1);
    const newAccount = accounts[newIndex];
    newAccount.name = name || `Account ${newIndex + 1}`;

    currentWalletData.accounts.push(newAccount);
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { success: true, address: newAccount.address, accountIndex: newIndex };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Switch to a different account
 */
async function switchAccount({ accountIndex }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
    await storageManager.saveWallet(currentWalletData, sessionPassword);

    return { 
      success: true, 
      address: newAccount.address,
      accountIndex: currentWalletData.accounts.length - 1
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

    const balance = await walletManager.getBalance(targetAddress);
    return { success: true, balance };
  } catch (error) {
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    const receipt = await walletManager.sendTransaction(activeAccount.privateKey, to, amount);
    
    return { 
      success: true, 
      txHash: receipt.hash,
      receipt 
    };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Send token transaction
 */
async function sendToken({ tokenAddress, to, amount }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    const receipt = await walletManager.sendToken(
      activeAccount.privateKey, 
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
    return { success: false, error: error.message };
  }
}

/**
 * Estimate gas
 */
async function estimateGas({ to, amount }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
    
    const history = await walletManager.getTransactionHistory(targetAddress);
    return { success: true, history };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Sign message
 */
async function signMessage({ message }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    const signature = await walletManager.signMessage(activeAccount.privateKey, message);
    return { success: true, signature };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Sign typed data (EIP-712)
 */
async function signTypedData({ domain, types, value }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  try {
    const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
    const signature = await walletManager.signTypedData(
      activeAccount.privateKey, 
      domain, 
      types, 
      value
    );
    return { success: true, signature };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Switch network
 */
function switchNetwork({ networkKey }) {
  try {
    // networkKey can be either a network key (like 'ramestta_mainnet') or a chainId (like '0x55a')
    let network = null;

    // First check if it's a chainId
    if (networkKey.startsWith('0x')) {
      // Find network by chainId in built-in networks
      for (const [key, net] of Object.entries(NETWORKS)) {
        if (net.chainId === networkKey) {
          network = net;
          walletManager.currentNetwork = network;
          walletManager.provider = null;
          break;
        }
      }
      
      // Check custom networks if not found in built-in
      if (!network) {
        const customKey = `custom_${networkKey}`;
        if (NETWORKS[customKey]) {
          network = NETWORKS[customKey];
          walletManager.currentNetwork = network;
          walletManager.provider = null;
        }
      }
    } else {
      // It's a network key
      walletManager.switchNetwork(networkKey);
      network = walletManager.currentNetwork;
    }

    if (!network) {
      return { success: false, error: 'Network not found' };
    }
    
    // Save preference
    storageManager.loadPreferences().then(prefs => {
      prefs.network = networkKey;
      storageManager.savePreferences(prefs);
    });

    return { success: true, network: walletManager.currentNetwork };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

/**
 * Connect dApp site
 */
async function connectSite({ origin }, sender) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  const siteOrigin = origin || new URL(sender.tab?.url || '').origin;
  connectedSites.add(siteOrigin);

  const activeAccount = currentWalletData.accounts[currentWalletData.activeAccountIndex];
  
  return { 
    success: true, 
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
      if (!isUnlocked) {
        return { success: false, error: 'Wallet is locked' };
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
      return await signMessage({ message: params[0] });

    case 'eth_signTypedData_v4':
      if (!connectedSites.has(origin)) {
        return { success: false, error: 'Site not connected' };
      }
      const typedData = JSON.parse(params[1]);
      return await signTypedData(typedData);

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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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

// ============================================
// PRICE DATA
// ============================================

// Price cache
const priceCache = {
  data: {},
  lastUpdated: 0,
  cacheDuration: 60000 // 1 minute cache
};

// Token ID mappings for price APIs
const COINGECKO_IDS = {
  'RAMA': 'ramestta',
  'ETH': 'ethereum',
  'USDT': 'tether',
  'USDC': 'usd-coin',
  'BTC': 'bitcoin',
  'BNB': 'binancecoin',
  'MATIC': 'matic-network',
  'AVAX': 'avalanche-2'
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
 * Get prices for all tokens in wallet
 */
async function getAllPrices({ symbols, currency = 'usd' }) {
  try {
    // Default symbols if none provided
    const tokenSymbols = symbols || ['RAMA', 'ETH'];
    
    // Add custom token symbols
    if (currentWalletData?.customTokens) {
      currentWalletData.customTokens.forEach(t => {
        if (!tokenSymbols.includes(t.symbol)) {
          tokenSymbols.push(t.symbol);
        }
      });
    }

    const prices = await fetchPrices(tokenSymbols, currency);
    
    return { 
      success: true, 
      prices,
      currency: currency.toUpperCase(),
      lastUpdated: priceCache.lastUpdated
    };
  } catch (error) {
    return { success: false, error: error.message, prices: {} };
  }
}

/**
 * Fetch prices from CoinGecko API
 */
async function fetchPrices(symbols, currency = 'usd') {
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
    .filter(Boolean)
    .join(',');

  if (!ids) {
    // Return zeros for unknown tokens
    const result = {};
    symbols.forEach(s => result[s.toUpperCase()] = 0);
    return result;
  }

  try {
    const response = await fetch(
      `https://api.coingecko.com/api/v3/simple/price?ids=${ids}&vs_currencies=${currency}`
    );
    
    if (!response.ok) {
      throw new Error('Price API request failed');
    }

    const data = await response.json();
    
    // Map back to symbols and update cache
    const prices = {};
    priceCache.lastUpdated = now;

    for (const symbol of symbols) {
      const id = COINGECKO_IDS[symbol.toUpperCase()];
      if (id && data[id]) {
        const price = data[id][currency] || 0;
        prices[symbol.toUpperCase()] = price;
        priceCache.data[`${symbol.toUpperCase()}_${currency}`] = price;
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
async function removeCustomNetwork({ networkKey }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
  }

  try {
    // Can only remove custom networks
    if (!networkKey.startsWith('custom_')) {
      return { success: false, error: 'Cannot remove built-in networks' };
    }

    if (!NETWORKS[networkKey]) {
      return { success: false, error: 'Network not found' };
    }

    // Remove from NETWORKS
    delete NETWORKS[networkKey];

    // Remove from wallet data
    if (currentWalletData.customNetworks) {
      delete currentWalletData.customNetworks[networkKey];
      await storageManager.saveWallet(currentWalletData, sessionPassword);
    }

    // Switch to default if currently on removed network
    if (walletManager.currentNetwork.chainId === parseInt(networkKey.replace('custom_', ''))) {
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
  const customNetworks = {};
  Object.keys(NETWORKS).forEach(key => {
    if (NETWORKS[key].isCustom) {
      customNetworks[key] = NETWORKS[key];
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
}

// ============================================
// SECURITY FEATURES
// ============================================

/**
 * Change wallet password
 */
async function changePassword({ currentPassword, newPassword }) {
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
  if (!isUnlocked || !currentWalletData) {
    return { success: false, error: 'Wallet is locked' };
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
