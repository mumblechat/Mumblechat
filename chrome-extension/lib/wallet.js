/**
 * RamaPay Chrome Extension - Core Wallet Module
 * Handles HD wallet creation, key management, and encryption
 */

import { ethers } from 'ethers';

// Ramestta Network Configurations
export const NETWORKS = {
  ramestta_mainnet: {
    chainId: 1370,
    chainIdHex: '0x55a',
    name: 'Ramestta Mainnet',
    symbol: 'RAMA',
    decimals: 18,
    rpcUrl: 'https://blockchain.ramestta.com',
    rpcUrls: [
      'https://blockchain.ramestta.com',
      'https://blockchain2.ramestta.com',
      'https://blockchain.rfrm.io'
    ],
    explorerUrl: 'https://ramascan.com',
    isTestnet: false
  },
  ramestta_testnet: {
    chainId: 1377,
    chainIdHex: '0x561',
    name: 'Ramestta Testnet (Pingaksha)',
    symbol: 'RAMA',
    decimals: 18,
    rpcUrl: 'https://testnet.ramestta.com',
    rpcUrls: [
      'https://testnet.ramestta.com',
      'https://testnet.rfrm.io'
    ],
    explorerUrl: 'https://pingaksha.ramascan.com',
    isTestnet: true
  },
  ethereum_mainnet: {
    chainId: 1,
    chainIdHex: '0x1',
    name: 'Ethereum Mainnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://eth.llamarpc.com',
    rpcUrls: ['https://eth.llamarpc.com', 'https://ethereum.publicnode.com'],
    explorerUrl: 'https://etherscan.io',
    isTestnet: false
  }
};

// BIP44 derivation path for Ethereum-compatible chains
const DERIVATION_PATH = "m/44'/60'/0'/0";

/**
 * Wallet Manager Class
 * Handles all wallet operations including creation, import, and transactions
 */
export class WalletManager {
  constructor() {
    this.currentWallet = null;
    this.currentNetwork = NETWORKS.ramestta_mainnet;
    this.provider = null;
    this.accounts = [];
  }

  /**
   * Initialize provider for the current network
   */
  async initProvider() {
    const rpcUrl = this.currentNetwork.rpcUrl;
    this.provider = new ethers.JsonRpcProvider(rpcUrl, {
      chainId: this.currentNetwork.chainId,
      name: this.currentNetwork.name
    });
    return this.provider;
  }

  /**
   * Create a new HD wallet with mnemonic
   * @returns {Object} Wallet data including mnemonic
   */
  async createNewWallet() {
    const mnemonic = ethers.Mnemonic.entropyToPhrase(ethers.randomBytes(16));
    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic, '', DERIVATION_PATH);
    const wallet = hdNode.deriveChild(0);

    return {
      mnemonic: mnemonic,
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.publicKey,
      derivationPath: `${DERIVATION_PATH}/0`
    };
  }

  /**
   * Import wallet from mnemonic phrase
   * @param {string} mnemonic - 12 or 24 word seed phrase
   * @param {number} accountIndex - Account index to derive
   * @returns {Object} Wallet data
   */
  async importFromMnemonic(mnemonic, accountIndex = 0) {
    if (!ethers.Mnemonic.isValidMnemonic(mnemonic)) {
      throw new Error('Invalid mnemonic phrase');
    }

    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic.trim(), '', DERIVATION_PATH);
    const wallet = hdNode.deriveChild(accountIndex);

    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.publicKey,
      derivationPath: `${DERIVATION_PATH}/${accountIndex}`,
      accountIndex: accountIndex
    };
  }

  /**
   * Import wallet from private key
   * @param {string} privateKey - Private key hex string
   * @returns {Object} Wallet data
   */
  async importFromPrivateKey(privateKey) {
    let key = privateKey.trim();
    if (!key.startsWith('0x')) {
      key = '0x' + key;
    }

    const wallet = new ethers.Wallet(key);

    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.signingKey.publicKey,
      type: 'privateKey'
    };
  }

  /**
   * Import wallet from keystore JSON
   * @param {string} keystore - Keystore JSON string
   * @param {string} password - Keystore password
   * @returns {Object} Wallet data
   */
  async importFromKeystore(keystore, password) {
    const wallet = await ethers.Wallet.fromEncryptedJson(keystore, password);

    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.signingKey.publicKey,
      type: 'keystore'
    };
  }

  /**
   * Derive multiple accounts from mnemonic
   * @param {string} mnemonic - Seed phrase
   * @param {number} count - Number of accounts to derive
   * @returns {Array} Array of wallet data
   */
  async deriveAccounts(mnemonic, count = 1) {
    const accounts = [];
    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic.trim(), '', DERIVATION_PATH);

    for (let i = 0; i < count; i++) {
      const wallet = hdNode.deriveChild(i);
      accounts.push({
        address: wallet.address,
        privateKey: wallet.privateKey,
        publicKey: wallet.publicKey,
        derivationPath: `${DERIVATION_PATH}/${i}`,
        accountIndex: i,
        name: `Account ${i + 1}`
      });
    }

    return accounts;
  }

  /**
   * Get balance for an address
   * @param {string} address - Wallet address
   * @returns {Object} Balance info
   */
  async getBalance(address) {
    if (!this.provider) {
      await this.initProvider();
    }

    const balance = await this.provider.getBalance(address);
    return {
      wei: balance.toString(),
      ether: ethers.formatEther(balance),
      symbol: this.currentNetwork.symbol
    };
  }

  /**
   * Get ERC20 token balance
   * @param {string} address - Wallet address
   * @param {string} tokenAddress - Token contract address
   * @returns {Object} Token balance info
   */
  async getTokenBalance(address, tokenAddress) {
    if (!this.provider) {
      await this.initProvider();
    }

    const erc20Abi = [
      'function balanceOf(address) view returns (uint256)',
      'function decimals() view returns (uint8)',
      'function symbol() view returns (string)',
      'function name() view returns (string)'
    ];

    const contract = new ethers.Contract(tokenAddress, erc20Abi, this.provider);
    const [balance, decimals, symbol, name] = await Promise.all([
      contract.balanceOf(address),
      contract.decimals(),
      contract.symbol(),
      contract.name()
    ]);

    return {
      balance: balance.toString(),
      formatted: ethers.formatUnits(balance, decimals),
      decimals: Number(decimals),
      symbol: symbol,
      name: name
    };
  }

  /**
   * Get ERC20 token info (without balance)
   * @param {string} tokenAddress - Token contract address
   * @returns {Object} Token info
   */
  async getTokenInfo(tokenAddress) {
    if (!this.provider) {
      await this.initProvider();
    }

    const erc20Abi = [
      'function decimals() view returns (uint8)',
      'function symbol() view returns (string)',
      'function name() view returns (string)',
      'function totalSupply() view returns (uint256)'
    ];

    const contract = new ethers.Contract(tokenAddress, erc20Abi, this.provider);
    const [decimals, symbol, name, totalSupply] = await Promise.all([
      contract.decimals(),
      contract.symbol(),
      contract.name(),
      contract.totalSupply().catch(() => BigInt(0))
    ]);

    return {
      address: tokenAddress,
      decimals: Number(decimals),
      symbol: symbol,
      name: name,
      totalSupply: totalSupply.toString()
    };
  }

  /**
   * Send native token transaction
   * @param {string} privateKey - Sender's private key
   * @param {string} to - Recipient address
   * @param {string} amount - Amount in ether
   * @returns {Object} Transaction receipt
   */
  async sendTransaction(privateKey, to, amount) {
    if (!this.provider) {
      await this.initProvider();
    }

    const wallet = new ethers.Wallet(privateKey, this.provider);
    
    const tx = await wallet.sendTransaction({
      to: to,
      value: ethers.parseEther(amount)
    });

    return await tx.wait();
  }

  /**
   * Send ERC20 token transaction
   * @param {string} privateKey - Sender's private key
   * @param {string} tokenAddress - Token contract address
   * @param {string} to - Recipient address
   * @param {string} amount - Amount to send
   * @returns {Object} Transaction receipt
   */
  async sendToken(privateKey, tokenAddress, to, amount) {
    if (!this.provider) {
      await this.initProvider();
    }

    const wallet = new ethers.Wallet(privateKey, this.provider);
    
    const erc20Abi = [
      'function transfer(address to, uint256 amount) returns (bool)',
      'function decimals() view returns (uint8)'
    ];

    const contract = new ethers.Contract(tokenAddress, erc20Abi, wallet);
    const decimals = await contract.decimals();
    const parsedAmount = ethers.parseUnits(amount, decimals);

    const tx = await contract.transfer(to, parsedAmount);
    return await tx.wait();
  }

  /**
   * Estimate gas for a transaction
   * @param {string} from - Sender address
   * @param {string} to - Recipient address
   * @param {string} amount - Amount in ether
   * @returns {Object} Gas estimate info
   */
  async estimateGas(from, to, amount) {
    if (!this.provider) {
      await this.initProvider();
    }

    const gasEstimate = await this.provider.estimateGas({
      from: from,
      to: to,
      value: ethers.parseEther(amount)
    });

    const feeData = await this.provider.getFeeData();

    return {
      gasLimit: gasEstimate.toString(),
      gasPrice: feeData.gasPrice?.toString() || '0',
      maxFeePerGas: feeData.maxFeePerGas?.toString() || '0',
      maxPriorityFeePerGas: feeData.maxPriorityFeePerGas?.toString() || '0'
    };
  }

  /**
   * Sign a message
   * @param {string} privateKey - Private key
   * @param {string} message - Message to sign
   * @returns {string} Signature
   */
  async signMessage(privateKey, message) {
    const wallet = new ethers.Wallet(privateKey);
    return await wallet.signMessage(message);
  }

  /**
   * Sign typed data (EIP-712)
   * @param {string} privateKey - Private key
   * @param {Object} domain - Domain separator
   * @param {Object} types - Type definitions
   * @param {Object} value - Data to sign
   * @returns {string} Signature
   */
  async signTypedData(privateKey, domain, types, value) {
    const wallet = new ethers.Wallet(privateKey);
    return await wallet.signTypedData(domain, types, value);
  }

  /**
   * Switch network
   * @param {string} networkKey - Network identifier
   */
  switchNetwork(networkKey) {
    if (NETWORKS[networkKey]) {
      this.currentNetwork = NETWORKS[networkKey];
      this.provider = null; // Reset provider to reinitialize with new network
    } else {
      throw new Error(`Unknown network: ${networkKey}`);
    }
  }

  /**
   * Add custom network
   * @param {Object} networkConfig - Network configuration
   */
  addCustomNetwork(networkConfig) {
    const key = `custom_${networkConfig.chainId}`;
    NETWORKS[key] = networkConfig;
    return key;
  }

  /**
   * Get transaction history (basic - from explorer API if available)
   * @param {string} address - Wallet address
   * @returns {Array} Transaction list
   */
  async getTransactionHistory(address) {
    // This would typically use an indexer or explorer API
    // For Ramestta, we'd use the ramascan API
    const explorerApiUrl = `${this.currentNetwork.explorerUrl}/api`;
    
    try {
      const response = await fetch(
        `${explorerApiUrl}?module=account&action=txlist&address=${address}&sort=desc`
      );
      const data = await response.json();
      return data.result || [];
    } catch (error) {
      console.warn('Transaction history fetch failed:', error);
      return [];
    }
  }
}

/**
 * Storage Manager - Handles encrypted storage operations
 */
export class StorageManager {
  constructor() {
    this.storageKey = 'ramapay_wallet_data';
  }

  /**
   * Encrypt data with password
   * @param {Object} data - Data to encrypt
   * @param {string} password - Encryption password
   * @returns {string} Encrypted data
   */
  async encrypt(data, password) {
    const encoder = new TextEncoder();
    const dataStr = JSON.stringify(data);
    
    // Derive key from password
    const keyMaterial = await crypto.subtle.importKey(
      'raw',
      encoder.encode(password),
      'PBKDF2',
      false,
      ['deriveBits', 'deriveKey']
    );

    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));

    const key = await crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: salt,
        iterations: 100000,
        hash: 'SHA-256'
      },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt']
    );

    const encrypted = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv },
      key,
      encoder.encode(dataStr)
    );

    // Combine salt + iv + encrypted data
    const combined = new Uint8Array(salt.length + iv.length + encrypted.byteLength);
    combined.set(salt, 0);
    combined.set(iv, salt.length);
    combined.set(new Uint8Array(encrypted), salt.length + iv.length);

    return btoa(String.fromCharCode(...combined));
  }

  /**
   * Decrypt data with password
   * @param {string} encryptedData - Encrypted data
   * @param {string} password - Decryption password
   * @returns {Object} Decrypted data
   */
  async decrypt(encryptedData, password) {
    const encoder = new TextEncoder();
    const decoder = new TextDecoder();
    
    const combined = Uint8Array.from(atob(encryptedData), c => c.charCodeAt(0));
    
    const salt = combined.slice(0, 16);
    const iv = combined.slice(16, 28);
    const encrypted = combined.slice(28);

    const keyMaterial = await crypto.subtle.importKey(
      'raw',
      encoder.encode(password),
      'PBKDF2',
      false,
      ['deriveBits', 'deriveKey']
    );

    const key = await crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: salt,
        iterations: 100000,
        hash: 'SHA-256'
      },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['decrypt']
    );

    const decrypted = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: iv },
      key,
      encrypted
    );

    return JSON.parse(decoder.decode(decrypted));
  }

  /**
   * Save encrypted wallet data to Chrome storage
   * @param {Object} walletData - Wallet data to save
   * @param {string} password - Encryption password
   */
  async saveWallet(walletData, password) {
    const encrypted = await this.encrypt(walletData, password);
    return new Promise((resolve, reject) => {
      chrome.storage.local.set({ [this.storageKey]: encrypted }, () => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
        } else {
          resolve();
        }
      });
    });
  }

  /**
   * Load and decrypt wallet data from Chrome storage
   * @param {string} password - Decryption password
   * @returns {Object} Wallet data
   */
  async loadWallet(password) {
    return new Promise((resolve, reject) => {
      chrome.storage.local.get([this.storageKey], async (result) => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
          return;
        }

        const encrypted = result[this.storageKey];
        if (!encrypted) {
          resolve(null);
          return;
        }

        try {
          const decrypted = await this.decrypt(encrypted, password);
          resolve(decrypted);
        } catch (error) {
          reject(new Error('Invalid password'));
        }
      });
    });
  }

  /**
   * Check if wallet exists
   * @returns {boolean}
   */
  async hasWallet() {
    return new Promise((resolve) => {
      chrome.storage.local.get([this.storageKey], (result) => {
        resolve(!!result[this.storageKey]);
      });
    });
  }

  /**
   * Clear all wallet data
   */
  async clearWallet() {
    return new Promise((resolve, reject) => {
      chrome.storage.local.remove([this.storageKey], () => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
        } else {
          resolve();
        }
      });
    });
  }

  /**
   * Save user preferences (non-sensitive)
   * @param {Object} preferences - User preferences
   */
  async savePreferences(preferences) {
    return new Promise((resolve, reject) => {
      chrome.storage.local.set({ ramapay_preferences: preferences }, () => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
        } else {
          resolve();
        }
      });
    });
  }

  /**
   * Load user preferences
   * @returns {Object} User preferences
   */
  async loadPreferences() {
    return new Promise((resolve) => {
      chrome.storage.local.get(['ramapay_preferences'], (result) => {
        resolve(result.ramapay_preferences || {
          network: 'ramestta_mainnet',
          currency: 'USD',
          theme: 'dark'
        });
      });
    });
  }
}

// Export singleton instances
export const walletManager = new WalletManager();
export const storageManager = new StorageManager();
