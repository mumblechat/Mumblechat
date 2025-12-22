/**
 * RamaPay Chrome Extension - Content Script
 * Injects the Web3 provider into web pages for dApp connectivity
 */

// Inject the provider script into the page
function injectScript() {
  const script = document.createElement('script');
  script.src = chrome.runtime.getURL('inpage/provider.js');
  script.type = 'module';
  script.onload = function() {
    this.remove();
  };
  (document.head || document.documentElement).appendChild(script);
}

// Inject immediately
injectScript();

// Set up message relay between page and extension
window.addEventListener('message', async (event) => {
  // Only accept messages from the same window
  if (event.source !== window) return;
  
  // Only handle RamaPay messages
  if (event.data.type !== 'RAMAPAY_REQUEST') return;

  const { id, method, params } = event.data;

  try {
    // Forward to background script
    const response = await chrome.runtime.sendMessage({
      action: 'web3Request',
      data: { method, params }
    });

    // Send response back to page
    window.postMessage({
      type: 'RAMAPAY_RESPONSE',
      id: id,
      result: response.success ? response.result : undefined,
      error: response.success ? undefined : response.error
    }, '*');
  } catch (error) {
    window.postMessage({
      type: 'RAMAPAY_RESPONSE',
      id: id,
      error: error.message
    }, '*');
  }
});

// Notify page that RamaPay is available
window.postMessage({ type: 'RAMAPAY_INIT' }, '*');

// Listen for extension state changes
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === 'RAMAPAY_STATE_CHANGE') {
    window.postMessage({
      type: 'RAMAPAY_STATE_CHANGE',
      data: message.data
    }, '*');
  }
  sendResponse({ received: true });
  return true;
});
