(function() {
const __addressHex = "%1$s";
const __rpcURL = "%2$s";
const __chainID = "%3$s";

function executeCallback (id, error, value) {
  AlphaWallet.executeCallback(id, error, value)
}

window.AlphaWallet.init(__rpcURL, {
  getAccounts: function (cb) { cb(null, [__addressHex]) },
  processTransaction: function (tx, cb){
    console.log('signing a transaction', tx)
    const { id = 8888 } = tx
    AlphaWallet.addCallback(id, cb)

    var gasLimit = tx.gasLimit || tx.gas || null;
    var gasPrice = tx.gasPrice || null;
    var data = tx.data || null;
    var nonce = tx.nonce || -1;
    alpha.signTransaction(id, tx.to || null, tx.value, nonce, gasLimit, gasPrice, data);
  },
  signMessage: function (msgParams, cb) {
      console.log('signMessage', msgParams)
      const { data, chainType } = msgParams
      const { id = 8888 } = msgParams
    AlphaWallet.addCallback(id, cb)
    alpha.signMessage(id, data);
  },
  signPersonalMessage: function (msgParams, cb) {
      console.log('signPersonalMessage', msgParams)
      const { data, chainType } = msgParams
      const { id = 8888 } = msgParams
    AlphaWallet.addCallback(id, cb)
    alpha.signPersonalMessage(id, data);
  },
  signTypedMessage: function (msgParams, cb) {
    console.log('signTypedMessage ', msgParams)
    const { data } = msgParams
    const { id = 8888 } = msgParams
    AlphaWallet.addCallback(id, cb)
    alpha.signTypedMessage(id, JSON.stringify(msgParams))
  },
  ethCall: function (msgParams, cb) {
    console.log("eth_call", msgParams)
    const data = msgParams
    const { id = Math.floor((Math.random() * 100000) + 1) } = msgParams
    AlphaWallet.addCallback(id, cb)
    alpha.ethCall(id, JSON.stringify(msgParams));
    //alpha.ethCall(id, msgParams.to, msgParams.data, msgParams.value);
  },
  walletAddEthereumChain: function (msgParams, cb) {
    const data = msgParams
    const { id = Math.floor((Math.random() * 100000) + 1) } = msgParams
    console.log("walletAddEthereumChain", msgParams)
    AlphaWallet.addCallback(id, cb)
    alpha.walletAddEthereumChain(id, JSON.stringify(msgParams));
    //webkit.messageHandlers.walletAddEthereumChain.postMessage({"name": "walletAddEthereumChain", "object": data, id: id})
  },
  walletSwitchEthereumChain: function (msgParams, cb) {
    const data = msgParams
    const { id = Math.floor((Math.random() * 100000) + 1) } = msgParams
    console.log("walletSwitchEthereumChain", msgParams)
    AlphaWallet.addCallback(id, cb)
    alpha.walletSwitchEthereumChain(id, JSON.stringify(msgParams));
    //webkit.messageHandlers.walletSwitchEthereumChain.postMessage({"name": "walletSwitchEthereumChain", "object": data, id: id})
  },
  requestAccounts: function(cb) {
      id = Math.floor((Math.random() * 100000) + 1)
      console.log("requestAccounts", id)
      AlphaWallet.addCallback(id, cb)
      alpha.requestAccounts(id);
  },
  enable: function() {
      return new Promise(function(resolve, reject) {
          //send back the coinbase account as an array of one
          resolve([__addressHex])
      })
  }
}, {
    address: __addressHex,
    //networkVersion: __chainID
    networkVersion: "0x" + parseInt(__chainID).toString(16) || null
})

window.web3.setProvider = function () {
  console.debug('Alpha Wallet - overrode web3.setProvider')
}

window.web3.version.getNetwork = function(cb) {
    cb(null, __chainID)
}
window.web3.eth.getCoinbase = function(cb) {
    return cb(null, __addressHex)
}
window.web3.eth.defaultAccount = __addressHex

window.ethereum = web3.currentProvider

// EIP-6963: Multi Injected Provider Discovery
// This allows DApps to auto-detect RamaPay wallet
const ramaPayIcon = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMTI4IiBoZWlnaHQ9IjEyOCIgdmlld0JveD0iMCAwIDEyOCAxMjgiIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+PGNpcmNsZSBjeD0iNjQiIGN5PSI2NCIgcj0iNjQiIGZpbGw9IiNGRkQ3MDAiLz48dGV4dCB4PSI2NCIgeT0iNzYiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSI0OCIgZm9udC13ZWlnaHQ9ImJvbGQiIGZpbGw9IiMwMDAiIHRleHQtYW5jaG9yPSJtaWRkbGUiPlI8L3RleHQ+PC9zdmc+';

const ramaPayProviderInfo = {
  uuid: 'e5b0a9c1-8d2f-4a3b-9c6e-7f8d9a0b1c2d',
  name: 'RamaPay',
  icon: ramaPayIcon,
  rdns: 'io.ramestta.wallet'
};

// Create the provider detail object
const ramaPayProviderDetail = {
  info: ramaPayProviderInfo,
  provider: window.ethereum
};

// Function to announce the provider
function announceProvider() {
  const event = new CustomEvent('eip6963:announceProvider', {
    detail: Object.freeze(ramaPayProviderDetail)
  });
  window.dispatchEvent(event);
}

// Listen for DApp requests for providers
window.addEventListener('eip6963:requestProvider', () => {
  announceProvider();
});

// Announce on load
announceProvider();

// Also set provider identification flags
if (window.ethereum) {
  window.ethereum.isRamaPay = true;
  window.ethereum.isAlphaWallet = true;
}

})();
