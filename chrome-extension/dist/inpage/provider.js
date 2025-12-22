/**
 * RamaPay Chrome Extension - In-Page Provider
 * EIP-1193 compliant Ethereum provider injected into web pages
 * Enables dApps to interact with RamaPay wallet
 */

class RamaPayProvider {
  constructor() {
    this.isRamaPay = true;
    this.isMetaMask = true; // For compatibility with dApps that check for MetaMask
    this.networkVersion = '1370';
    this.chainId = '0x55a';
    this.selectedAddress = null;
    this._events = {};
    this._requestId = 0;
    this._pendingRequests = new Map();
    this._isConnected = false;

    // Listen for responses from content script
    window.addEventListener('message', this._handleMessage.bind(this));
    
    // Listen for state changes
    window.addEventListener('message', (event) => {
      if (event.data.type === 'RAMAPAY_STATE_CHANGE') {
        this._handleStateChange(event.data.data);
      }
    });
  }

  /**
   * Handle incoming messages from content script
   */
  _handleMessage(event) {
    if (event.source !== window) return;
    
    if (event.data.type === 'RAMAPAY_RESPONSE') {
      const { id, result, error } = event.data;
      const pending = this._pendingRequests.get(id);
      
      if (pending) {
        this._pendingRequests.delete(id);
        
        if (error) {
          pending.reject(new Error(error));
        } else {
          pending.resolve(result);
        }
      }
    }
  }

  /**
   * Handle wallet state changes
   */
  _handleStateChange(data) {
    if (data.accounts) {
      this.selectedAddress = data.accounts[0] || null;
      this._emit('accountsChanged', data.accounts);
    }
    
    if (data.chainId) {
      this.chainId = data.chainId;
      this.networkVersion = String(parseInt(data.chainId, 16));
      this._emit('chainChanged', data.chainId);
    }

    if (data.connected !== undefined) {
      if (data.connected && !this._isConnected) {
        this._isConnected = true;
        this._emit('connect', { chainId: this.chainId });
      } else if (!data.connected && this._isConnected) {
        this._isConnected = false;
        this._emit('disconnect', { code: 4900, message: 'Disconnected' });
      }
    }
  }

  /**
   * EIP-1193 request method
   */
  async request({ method, params = [] }) {
    return new Promise((resolve, reject) => {
      const id = ++this._requestId;
      
      this._pendingRequests.set(id, { resolve, reject });
      
      window.postMessage({
        type: 'RAMAPAY_REQUEST',
        id: id,
        method: method,
        params: params
      }, '*');

      // Timeout after 60 seconds
      setTimeout(() => {
        if (this._pendingRequests.has(id)) {
          this._pendingRequests.delete(id);
          reject(new Error('Request timeout'));
        }
      }, 60000);
    });
  }

  /**
   * Legacy send method (deprecated but still used by some dApps)
   */
  send(methodOrPayload, paramsOrCallback) {
    // Handle legacy send(method, params) format
    if (typeof methodOrPayload === 'string') {
      return this.request({
        method: methodOrPayload,
        params: paramsOrCallback || []
      });
    }

    // Handle legacy send(payload, callback) format
    if (typeof paramsOrCallback === 'function') {
      this.request(methodOrPayload)
        .then(result => paramsOrCallback(null, { result }))
        .catch(error => paramsOrCallback(error));
      return;
    }

    // Handle legacy synchronous methods
    const { method } = methodOrPayload;
    
    switch (method) {
      case 'eth_accounts':
        return { result: this.selectedAddress ? [this.selectedAddress] : [] };
      case 'eth_coinbase':
        return { result: this.selectedAddress };
      case 'net_version':
        return { result: this.networkVersion };
      case 'eth_chainId':
        return { result: this.chainId };
      default:
        throw new Error(`Synchronous method not supported: ${method}`);
    }
  }

  /**
   * Legacy sendAsync method (deprecated)
   */
  sendAsync(payload, callback) {
    this.request(payload)
      .then(result => callback(null, { id: payload.id, jsonrpc: '2.0', result }))
      .catch(error => callback(error));
  }

  /**
   * Enable method (legacy, same as eth_requestAccounts)
   */
  async enable() {
    const accounts = await this.request({ method: 'eth_requestAccounts' });
    return accounts;
  }

  /**
   * Check if connected
   */
  isConnected() {
    return this._isConnected;
  }

  /**
   * Event emitter methods
   */
  on(eventName, callback) {
    if (!this._events[eventName]) {
      this._events[eventName] = [];
    }
    this._events[eventName].push(callback);
    return this;
  }

  once(eventName, callback) {
    const wrapper = (...args) => {
      this.removeListener(eventName, wrapper);
      callback(...args);
    };
    return this.on(eventName, wrapper);
  }

  removeListener(eventName, callback) {
    if (this._events[eventName]) {
      this._events[eventName] = this._events[eventName].filter(cb => cb !== callback);
    }
    return this;
  }

  removeAllListeners(eventName) {
    if (eventName) {
      delete this._events[eventName];
    } else {
      this._events = {};
    }
    return this;
  }

  _emit(eventName, data) {
    if (this._events[eventName]) {
      this._events[eventName].forEach(callback => {
        try {
          callback(data);
        } catch (error) {
          console.error('RamaPay event callback error:', error);
        }
      });
    }
  }

  /**
   * Get provider info (EIP-6963)
   */
  getProviderInfo() {
    return {
      uuid: 'ramapay-wallet',
      name: 'RamaPay',
      icon: 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNjQiIGhlaWdodD0iNjQiIHZpZXdCb3g9IjAgMCA2NCA2NCIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHJlY3Qgd2lkdGg9IjY0IiBoZWlnaHQ9IjY0IiByeD0iMTYiIGZpbGw9IiM2MzY2RjEiLz4KPHBhdGggZD0iTTMyIDEyTDQ4IDI0VjQ0TDMyIDU2TDE2IDQ0VjI0TDMyIDEyWiIgc3Ryb2tlPSJ3aGl0ZSIgc3Ryb2tlLXdpZHRoPSIzIiBmaWxsPSJub25lIi8+CjxjaXJjbGUgY3g9IjMyIiBjeT0iMzIiIHI9IjgiIGZpbGw9IndoaXRlIi8+Cjwvc3ZnPgo=',
      rdns: 'com.ramapay'
    };
  }
}

// Create provider instance
const ramaPayProvider = new RamaPayProvider();

// Inject provider
if (typeof window.ethereum === 'undefined') {
  window.ethereum = ramaPayProvider;
} else {
  // If another wallet is present, add RamaPay as an alternative
  window.ramapay = ramaPayProvider;
  
  // EIP-6963: Announce provider
  window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
    detail: {
      info: ramaPayProvider.getProviderInfo(),
      provider: ramaPayProvider
    }
  }));
}

// Also expose as window.ramapay for explicit access
window.ramapay = ramaPayProvider;

// Announce provider on load (EIP-6963)
window.addEventListener('eip6963:requestProvider', () => {
  window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {
    detail: {
      info: ramaPayProvider.getProviderInfo(),
      provider: ramaPayProvider
    }
  }));
});

console.log('🔷 RamaPay Wallet Provider Injected');
