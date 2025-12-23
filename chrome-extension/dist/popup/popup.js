/**
 * RamaPay Chrome Extension - Popup Script
 * Handles all UI interactions and communication with background service worker
 */

// State
let currentScreen = 'loading';
let currentWalletAddress = null;
let currentNetwork = null;
let importType = 'mnemonic';
let pendingDappRequest = null;

// DOM Elements cache
const screens = {};
const elements = {};

/**
 * Initialize the popup
 */
document.addEventListener('DOMContentLoaded', async () => {
  cacheElements();
  initializeIcons();
  setupEventListeners();
  
  // Check for dApp connection request from URL params
  const urlParams = new URLSearchParams(window.location.search);
  const dappRequestId = urlParams.get('dappRequest');
  const dappOrigin = urlParams.get('origin');
  
  if (dappRequestId && dappOrigin) {
    pendingDappRequest = { id: dappRequestId, origin: decodeURIComponent(dappOrigin) };
  }
  
  await checkWalletStatus();
});

/**
 * Initialize icons with proper extension URLs
 * This converts data-icon attributes to proper chrome-extension:// URLs
 */
function initializeIcons() {
  const ramaIcon = chrome.runtime.getURL('icons/rama.png');
  
  // Set icon src for elements with data-icon="rama"
  document.querySelectorAll('[data-icon="rama"]').forEach(img => {
    img.src = ramaIcon;
  });
  
  // Add error handlers for all network icons
  document.querySelectorAll('.network-option-icon, .network-btn-icon').forEach(img => {
    img.addEventListener('error', function() {
      // Use fallback from data attribute or rama icon
      this.src = this.dataset.fallback || ramaIcon;
    });
  });
}

/**
 * Cache DOM elements for performance
 */
function cacheElements() {
  // Screens
  document.querySelectorAll('.screen').forEach(screen => {
    screens[screen.id.replace('-screen', '')] = screen;
  });

  // Common elements
  elements.toast = document.getElementById('toast');
  elements.networkSelect = document.getElementById('network-select');
  elements.balanceValue = document.getElementById('balance-value');
  elements.balanceSymbol = document.getElementById('balance-symbol');
  elements.balanceUsd = document.getElementById('balance-usd');
  elements.walletAddress = document.getElementById('wallet-address');
  elements.accountName = document.getElementById('account-name');
}

// Store the current mnemonic for verification
let currentMnemonic = null;
let selectedVerifyWords = [];

/**
 * Set up all event listeners
 */
function setupEventListeners() {
  // Password visibility toggles
  document.querySelectorAll('.password-toggle').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const targetId = btn.dataset.target;
      const input = document.getElementById(targetId);
      if (input) {
        const isPassword = input.type === 'password';
        input.type = isPassword ? 'text' : 'password';
        btn.classList.toggle('active', isPassword);
      }
    });
  });

  // Password strength indicator
  document.getElementById('create-password')?.addEventListener('input', (e) => {
    updatePasswordStrength(e.target.value);
  });

  // Welcome screen
  document.getElementById('btn-create-wallet')?.addEventListener('click', () => showScreen('create'));
  document.getElementById('btn-import-wallet')?.addEventListener('click', () => showScreen('import'));

  // Create wallet
  document.getElementById('btn-generate-wallet')?.addEventListener('click', handleCreateWallet);
  document.getElementById('seed-saved')?.addEventListener('change', (e) => {
    document.getElementById('btn-seed-continue').disabled = !e.target.checked;
  });
  document.getElementById('btn-copy-seed')?.addEventListener('click', handleCopySeed);
  document.getElementById('btn-seed-continue')?.addEventListener('click', () => {
    setupSeedVerification();
    showScreen('verify-seed');
  });

  // Import wallet
  document.querySelectorAll('#import-screen .tab').forEach(tab => {
    tab.addEventListener('click', (e) => {
      const tabType = e.target.dataset.tab;
      importType = tabType;
      document.querySelectorAll('#import-screen .tab').forEach(t => t.classList.remove('active'));
      e.target.classList.add('active');
      document.querySelectorAll('#import-screen .tab-content').forEach(c => c.classList.remove('active'));
      document.getElementById(`import-${tabType}`)?.classList.add('active');
    });
  });
  document.getElementById('btn-import')?.addEventListener('click', handleImportWallet);

  // Unlock
  document.getElementById('btn-unlock')?.addEventListener('click', handleUnlock);
  document.getElementById('unlock-password')?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') handleUnlock();
  });

  // Main screen
  document.getElementById('network-select')?.addEventListener('change', handleNetworkChange);
  
  // Custom network dropdown
  const networkDropdownBtn = document.getElementById('network-dropdown-btn');
  const networkDropdownMenu = document.getElementById('network-dropdown-menu');
  
  if (networkDropdownBtn && networkDropdownMenu) {
    networkDropdownBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      networkDropdownBtn.classList.toggle('open');
      networkDropdownMenu.classList.toggle('show');
      
      // Check RPC status when dropdown opens
      if (networkDropdownMenu.classList.contains('show')) {
        checkRpcStatus();
      }
    });
    
    // Close dropdown when clicking outside
    document.addEventListener('click', (e) => {
      if (!e.target.closest('.network-selector')) {
        networkDropdownBtn.classList.remove('open');
        networkDropdownMenu.classList.remove('show');
      }
    });
    
    // Handle network option clicks using event delegation
    // This works for both static and dynamically added network options
    networkDropdownMenu.addEventListener('click', async (e) => {
      const option = e.target.closest('.network-option:not(.disabled)');
      if (!option) return;
      
      e.stopPropagation();
      
      const networkKey = option.dataset.networkKey;
      const chainId = option.dataset.chainId;
      
      console.log('Network option clicked:', { networkKey, chainId });
      
      // Close dropdown immediately for better UX
      networkDropdownBtn.classList.remove('open');
      networkDropdownMenu.classList.remove('show');
      
      // Handle RPC2 selection
      if (networkKey === 'ramestta_mainnet_rpc2') {
        await switchToRpc2();
      } else if (chainId || networkKey) {
        await handleNetworkOptionClick(chainId, networkKey);
      }
    });
  }
  
  document.getElementById('btn-copy-address')?.addEventListener('click', handleCopyAddress);
  document.getElementById('btn-send')?.addEventListener('click', () => showScreen('send'));
  document.getElementById('btn-receive')?.addEventListener('click', async () => {
    showScreen('receive');
    await generateQRCode();
  });
  document.getElementById('btn-swap')?.addEventListener('click', () => showToast('Swap coming soon!', 'info'));
  document.getElementById('btn-settings')?.addEventListener('click', async () => {
    showScreen('settings');
    await loadAutoLockDisplay();
  });

  // Tabs on main screen
  document.querySelectorAll('#main-screen .tabs .tab').forEach(tab => {
    tab.addEventListener('click', (e) => {
      const tabName = e.target.dataset.tab;
      document.querySelectorAll('#main-screen .tabs .tab').forEach(t => t.classList.remove('active'));
      e.target.classList.add('active');
      document.querySelectorAll('#main-screen .tab-panel').forEach(p => p.classList.remove('active'));
      document.getElementById(`${tabName}-tab`)?.classList.add('active');
      
      // Auto-refresh activity when Activity tab is clicked
      if (tabName === 'activity') {
        loadTransactionHistory();
      }
    });
  });

  // Activity refresh button
  document.getElementById('btn-refresh-activity')?.addEventListener('click', () => {
    loadTransactionHistory();
  });

  // Send screen
  document.getElementById('btn-max')?.addEventListener('click', handleMaxAmount);
  document.getElementById('btn-send-confirm')?.addEventListener('click', handleSendReview);
  document.getElementById('send-amount')?.addEventListener('input', updateGasEstimate);

  // Confirm screen
  document.getElementById('btn-send-final')?.addEventListener('click', handleSendTransaction);

  // Receive screen
  document.getElementById('btn-copy-receive-address')?.addEventListener('click', handleCopyAddress);

  // Settings
  document.getElementById('btn-lock')?.addEventListener('click', handleLock);
  document.getElementById('btn-reset-wallet')?.addEventListener('click', handleResetWallet);
  document.getElementById('btn-export-key')?.addEventListener('click', () => showScreen('export-key'));
  document.getElementById('btn-export-seed')?.addEventListener('click', () => showScreen('export-seed'));
  document.getElementById('btn-auto-lock')?.addEventListener('click', () => {
    console.log('Auto-lock clicked');
    showAutoLockModal();
  });
  
  // Auto-Lock Modal
  document.getElementById('close-auto-lock-modal')?.addEventListener('click', () => {
    document.getElementById('auto-lock-modal').classList.remove('show');
  });
  document.getElementById('btn-save-auto-lock')?.addEventListener('click', saveAutoLockSetting);
  
  // Reset Wallet Modal
  document.getElementById('close-reset-wallet-modal')?.addEventListener('click', () => {
    document.getElementById('reset-wallet-modal').classList.remove('show');
  });
  document.getElementById('btn-cancel-reset')?.addEventListener('click', () => {
    document.getElementById('reset-wallet-modal').classList.remove('show');
  });
  document.getElementById('reset-confirm-text')?.addEventListener('input', (e) => {
    const btn = document.getElementById('btn-confirm-reset');
    if (btn) {
      btn.disabled = e.target.value.trim() !== 'RESET';
    }
  });
  document.getElementById('btn-confirm-reset')?.addEventListener('click', confirmResetWallet);
  
  document.getElementById('btn-add-account')?.addEventListener('click', () => {
    loadAccountsList();
    showScreen('accounts');
  });
  document.getElementById('btn-auto-fetch-tokens')?.addEventListener('click', handleAutoFetchTokens);
  document.getElementById('btn-manage-networks')?.addEventListener('click', () => {
    loadNetworksList();
    showScreen('networks');
  });
  document.getElementById('btn-add-network')?.addEventListener('click', () => showScreen('add-network'));
  document.getElementById('btn-add-network-from-list')?.addEventListener('click', () => showScreen('add-network'));
  document.getElementById('btn-change-password')?.addEventListener('click', () => showScreen('change-password'));

  // Account/Wallet Management
  document.getElementById('btn-account-selector')?.addEventListener('click', () => {
    loadAccountsList();
    showScreen('accounts');
  });
  
  // Add Account button - show master wallet selection
  document.getElementById('btn-add-account-select')?.addEventListener('click', showAddAccountModal);
  
  // Create Master Wallet button
  document.getElementById('btn-create-master-wallet')?.addEventListener('click', () => {
    document.getElementById('create-master-wallet-modal').classList.add('show');
  });
  
  // Import Private Key button
  document.getElementById('btn-import-key-account')?.addEventListener('click', () => {
    document.getElementById('import-wallet-modal').classList.add('show');
    // Switch to private key tab
    document.querySelectorAll('#import-wallet-modal .tab').forEach(t => t.classList.remove('active'));
    document.querySelector('#import-wallet-modal .tab[data-tab="key"]')?.classList.add('active');
    document.querySelectorAll('#import-wallet-modal .tab-content').forEach(c => c.classList.remove('active'));
    document.getElementById('import-tab-key')?.classList.add('active');
  });
  
  // Import Seed Phrase button
  document.getElementById('btn-import-seed-account')?.addEventListener('click', () => {
    document.getElementById('import-wallet-modal').classList.add('show');
    // Switch to seed phrase tab
    document.querySelectorAll('#import-wallet-modal .tab').forEach(t => t.classList.remove('active'));
    document.querySelector('#import-wallet-modal .tab[data-tab="seed"]')?.classList.add('active');
    document.querySelectorAll('#import-wallet-modal .tab-content').forEach(c => c.classList.remove('active'));
    document.getElementById('import-tab-seed')?.classList.add('active');
  });
  
  // Select Master Wallet Modal
  document.getElementById('close-select-master-modal')?.addEventListener('click', () => {
    document.getElementById('select-master-modal').classList.remove('show');
  });
  
  // Watch Wallet button
  document.getElementById('btn-add-watch-wallet')?.addEventListener('click', () => {
    document.getElementById('watch-wallet-modal').classList.add('show');
  });
  
  // Search wallet by address functionality
  const searchWalletInput = document.getElementById('search-wallet-input');
  const clearSearchBtn = document.getElementById('clear-search-btn');
  
  if (searchWalletInput) {
    searchWalletInput.addEventListener('input', (e) => {
      filterWalletsByAddress(e.target.value);
    });
  }
  
  if (clearSearchBtn) {
    clearSearchBtn.addEventListener('click', () => {
      searchWalletInput.value = '';
      filterWalletsByAddress('');
      clearSearchBtn.style.display = 'none';
    });
  }
  
  // Legacy watch wallet button (for backwards compatibility)
  document.getElementById('opt-watch-wallet')?.addEventListener('click', () => {
    document.getElementById('watch-wallet-modal').classList.add('show');
  });
  
  // Create Master Wallet Modal
  document.getElementById('close-create-master-wallet-modal')?.addEventListener('click', () => {
    document.getElementById('create-master-wallet-modal').classList.remove('show');
  });
  document.getElementById('btn-confirm-create-master-wallet')?.addEventListener('click', handleCreateMasterWallet);
  
  // Import Wallet Modal
  document.getElementById('close-import-wallet-modal')?.addEventListener('click', () => {
    document.getElementById('import-wallet-modal').classList.remove('show');
  });
  
  // Import wallet tabs
  document.querySelectorAll('#import-wallet-modal .tab').forEach(tab => {
    tab.addEventListener('click', (e) => {
      const tabType = e.target.dataset.tab;
      document.querySelectorAll('#import-wallet-modal .tab').forEach(t => t.classList.remove('active'));
      e.target.classList.add('active');
      document.querySelectorAll('#import-wallet-modal .tab-content').forEach(c => c.classList.remove('active'));
      document.getElementById(`import-tab-${tabType}`)?.classList.add('active');
    });
  });
  
  document.getElementById('btn-confirm-import-wallet')?.addEventListener('click', handleImportWalletFromModal);
  
  // Watch Wallet Modal
  document.getElementById('close-watch-modal')?.addEventListener('click', () => {
    document.getElementById('watch-wallet-modal').classList.remove('show');
  });
  document.getElementById('btn-confirm-watch')?.addEventListener('click', handleAddWatchWallet);
  
  // Bulk Add Modal
  document.getElementById('close-bulk-add-master-modal')?.addEventListener('click', () => {
    document.getElementById('bulk-add-master-modal').classList.remove('show');
  });
  document.getElementById('btn-cancel-bulk')?.addEventListener('click', () => {
    document.getElementById('bulk-add-master-modal').classList.remove('show');
  });
  document.getElementById('bulk-add-master-count')?.addEventListener('input', (e) => {
    const count = parseInt(e.target.value) || 5;
    document.getElementById('btn-confirm-bulk-add-master').textContent = `Add ${count} Wallets`;
  });
  document.getElementById('btn-confirm-bulk-add-master')?.addEventListener('click', confirmBulkAddToMaster);
  
  // Recover Account Modal
  document.getElementById('close-recover-modal')?.addEventListener('click', () => {
    document.getElementById('recover-account-modal').classList.remove('show');
  });
  document.getElementById('btn-confirm-recover')?.addEventListener('click', handleRecoverAccount);
  
  // Modal backdrop click handlers
  const modalIds = [
    'select-master-modal',
    'create-master-wallet-modal', 
    'import-wallet-modal',
    'watch-wallet-modal',
    'bulk-add-master-modal',
    'recover-account-modal'
  ];
  
  modalIds.forEach(modalId => {
    document.getElementById(modalId)?.addEventListener('click', (e) => {
      if (e.target.id === modalId) {
        document.getElementById(modalId).classList.remove('show');
      }
    });
  });

  // Export Private Key Screen
  document.getElementById('btn-reveal-key')?.addEventListener('click', handleRevealPrivateKey);
  document.getElementById('btn-copy-key')?.addEventListener('click', handleCopyPrivateKey);

  // Export Recovery Phrase Screen
  document.getElementById('btn-reveal-seed')?.addEventListener('click', handleRevealRecoveryPhrase);
  document.getElementById('btn-copy-seed-export')?.addEventListener('click', handleCopyRecoveryPhrase);

  // Add Network Screen
  document.getElementById('btn-save-network')?.addEventListener('click', handleSaveNetwork);

  // Change Password Screen
  document.getElementById('btn-save-password')?.addEventListener('click', handleChangePassword);

  // Token Management
  document.getElementById('btn-add-token')?.addEventListener('click', openAddTokenModal);
  document.getElementById('close-token-modal')?.addEventListener('click', closeAddTokenModal);
  document.getElementById('token-address')?.addEventListener('input', handleTokenAddressInput);
  document.getElementById('btn-confirm-add-token')?.addEventListener('click', handleAddToken);

  // Close modal on outside click
  document.getElementById('add-token-modal')?.addEventListener('click', (e) => {
    if (e.target.id === 'add-token-modal') closeAddTokenModal();
  });

  // Back buttons
  document.querySelectorAll('.btn-back').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const target = e.target.dataset.back;
      showScreen(target);
    });
  });
}

/**
 * Send message to background script
 */
async function sendMessage(action, data = {}, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error('Message timeout'));
    }, timeoutMs);
    
    chrome.runtime.sendMessage({ action, data }, (response) => {
      clearTimeout(timeout);
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else {
        resolve(response || {});
      }
    });
  });
}

/**
 * Check wallet status and show appropriate screen
 */
async function checkWalletStatus() {
  try {
    // Check if we have stored wallet data
    const result = await chrome.storage.local.get(['ramapay_wallet_data']);
    const hasWallet = !!result.ramapay_wallet_data;

    if (!hasWallet) {
      showScreen('welcome');
      return;
    }

    // Try to get status from background with timeout handling
    try {
      const status = await sendMessage('getWalletStatus');
      
      // If wallet data exists but not loaded in memory, show lock screen to enter password
      if (!status.hasWallet || !status.address) {
        showScreen('lock');
      } else {
        currentWalletAddress = status.address;
        currentNetwork = status.network;
        await loadMainScreen();
        
        // Check for pending dApp connection requests
        if (pendingDappRequest) {
          showDappConnectionRequest(pendingDappRequest);
        } else {
          // Also check if there's a pending request from background
          const pendingResult = await sendMessage('getPendingDappRequest');
          if (pendingResult.success && pendingResult.request) {
            pendingDappRequest = pendingResult.request;
            showDappConnectionRequest(pendingDappRequest);
          }
        }
        
        showScreen('main');
      }
    } catch (bgError) {
      console.warn('Background not ready, showing lock screen:', bgError);
      showScreen('lock');
    }
  } catch (error) {
    console.error('Error checking wallet status:', error);
    showScreen('welcome');
  }
}

/**
 * Show a specific screen
 */
function showScreen(screenName) {
  // Simply show the requested screen - no lock checks during normal usage
  Object.values(screens).forEach(screen => screen.classList.remove('active'));
  if (screens[screenName]) {
    screens[screenName].classList.add('active');
    currentScreen = screenName;
  }
}

/**
 * Check if wallet data is loaded
 * Returns true if wallet data is available, false otherwise
 */
async function ensureWalletLoaded() {
  try {
    const status = await sendMessage('getWalletStatus');
    if (!status.address) {
      showScreen('lock');
      return false;
    }
    return true;
  } catch (error) {
    showScreen('lock');
    return false;
  }
}

/**
 * Show toast notification
 */
function showToast(message, type = 'info') {
  const toast = elements.toast;
  toast.textContent = message;
  toast.className = `toast ${type} show`;
  setTimeout(() => {
    toast.classList.remove('show');
  }, 3000);
}

/**
 * Handle wallet creation
 */
async function handleCreateWallet() {
  const password = document.getElementById('create-password').value;
  const confirmPassword = document.getElementById('confirm-password').value;
  const agreed = document.getElementById('agree-terms').checked;

  if (password.length < 8) {
    showToast('Password must be at least 8 characters', 'error');
    return;
  }

  if (password !== confirmPassword) {
    showToast('Passwords do not match', 'error');
    return;
  }

  if (!agreed) {
    showToast('Please accept the terms', 'error');
    return;
  }

  // Show progress
  const btn = document.getElementById('btn-generate-wallet');
  const btnText = btn.querySelector('.btn-text');
  const btnLoader = btn.querySelector('.btn-loader');
  const progress = document.getElementById('create-progress');
  const progressFill = document.getElementById('create-progress-fill');
  const progressText = document.getElementById('create-progress-text');

  btnText?.classList.add('hidden');
  btnLoader?.classList.remove('hidden');
  progress?.classList.remove('hidden');
  btn.disabled = true;

  // Animate progress
  const progressSteps = [
    { percent: 20, text: 'Generating entropy...' },
    { percent: 40, text: 'Creating HD wallet...' },
    { percent: 60, text: 'Deriving keys...' },
    { percent: 80, text: 'Encrypting wallet...' },
    { percent: 100, text: 'Complete!' }
  ];

  try {
    // Animate progress bar
    for (const step of progressSteps.slice(0, 3)) {
      progressFill.style.width = step.percent + '%';
      progressText.textContent = step.text;
      await new Promise(r => setTimeout(r, 300));
    }

    const result = await sendMessage('createWallet', { password });
    
    // Complete progress
    progressFill.style.width = '100%';
    progressText.textContent = 'Complete!';
    await new Promise(r => setTimeout(r, 300));

    if (result.success) {
      currentWalletAddress = result.address;
      currentMnemonic = result.mnemonic;
      displaySeedPhrase(result.mnemonic);
      showScreen('seed');
    } else {
      showToast(result.error || 'Failed to create wallet', 'error');
    }
  } catch (error) {
    showToast('Error creating wallet: ' + error.message, 'error');
  } finally {
    // Reset button
    btnText?.classList.remove('hidden');
    btnLoader?.classList.add('hidden');
    progress?.classList.add('hidden');
    progressFill.style.width = '0%';
    btn.disabled = false;
  }
}

/**
 * Display seed phrase in grid
 */
function displaySeedPhrase(mnemonic) {
  const words = mnemonic.split(' ');
  const container = document.getElementById('seed-phrase-display');
  container.innerHTML = '';

  words.forEach((word, index) => {
    const wordDiv = document.createElement('div');
    wordDiv.className = 'seed-word';
    wordDiv.innerHTML = `<span class="seed-word-num">${index + 1}</span>${word}`;
    container.appendChild(wordDiv);
  });

  // Store temporarily for copy functionality
  container.dataset.mnemonic = mnemonic;
}

/**
 * Handle copy seed phrase
 */
function handleCopySeed() {
  // Try to get from dataset first, then from currentMnemonic, then from sessionStorage
  let mnemonic = document.getElementById('seed-phrase-display')?.dataset?.mnemonic;
  
  if (!mnemonic) {
    mnemonic = currentMnemonic;
  }
  
  if (!mnemonic) {
    mnemonic = sessionStorage.getItem('temp_mnemonic');
  }
  
  if (mnemonic && mnemonic !== 'undefined') {
    navigator.clipboard.writeText(mnemonic);
    showToast('Recovery phrase copied!', 'success');
  } else {
    showToast('No recovery phrase to copy', 'error');
  }
}

/**
 * Setup seed verification screen
 */
// Verification state
let verifyPositions = [];  // 4 random positions to verify
let verifyCurrentStep = 0;  // Current step (0-3)

function setupSeedVerification() {
  if (!currentMnemonic) return;
  
  const words = currentMnemonic.split(' ');
  
  // ALWAYS reset state at the start
  verifyCurrentStep = 0;
  verifyPositions = [];
  
  // Pick 4 random positions (0-indexed internally, 1-indexed for display)
  const allPositions = Array.from({length: words.length}, (_, i) => i);
  verifyPositions = allPositions.sort(() => Math.random() - 0.5).slice(0, 4).sort((a, b) => a - b);
  
  console.log('setupSeedVerification - positions to verify:', verifyPositions);
  console.log('setupSeedVerification - verifyCurrentStep:', verifyCurrentStep);
  
  showVerifyStep();
}

/**
 * Show current verification step
 */
function showVerifyStep() {
  console.log('showVerifyStep - current step:', verifyCurrentStep, 'of 4');
  console.log('showVerifyStep - positions:', verifyPositions);
  
  if (verifyCurrentStep >= 4) {
    // All verified!
    console.log('showVerifyStep - all 4 steps complete, calling handleVerifyComplete');
    handleVerifyComplete();
    return;
  }
  
  // Safety check - ensure verifyPositions has enough items
  if (!verifyPositions || verifyPositions.length < 4) {
    console.error('showVerifyStep - invalid verifyPositions:', verifyPositions);
    return;
  }
  
  const words = currentMnemonic.split(' ');
  const targetPosition = verifyPositions[verifyCurrentStep];
  const correctWord = words[targetPosition];
  
  console.log('showVerifyStep - asking for word at position:', targetPosition + 1);
  
  // Update prompt
  document.getElementById('verify-word-num').textContent = targetPosition + 1;
  document.querySelector('.verify-step').textContent = `${verifyCurrentStep + 1} of 4`;
  
  // Update progress dots
  document.querySelectorAll('.verify-progress-dots .dot').forEach((dot, idx) => {
    dot.classList.remove('active', 'completed');
    if (idx < verifyCurrentStep) dot.classList.add('completed');
    if (idx === verifyCurrentStep) dot.classList.add('active');
  });
  
  // Generate 4 options (1 correct + 3 wrong)
  const wrongWords = words.filter((w, i) => i !== targetPosition);
  const shuffledWrong = wrongWords.sort(() => Math.random() - 0.5).slice(0, 3);
  const options = [...shuffledWrong, correctWord].sort(() => Math.random() - 0.5);
  
  // Display options
  const optionsContainer = document.getElementById('verify-word-options');
  optionsContainer.innerHTML = '';
  
  options.forEach(word => {
    const btn = document.createElement('button');
    btn.className = 'word-option';
    btn.textContent = word;
    btn.addEventListener('click', () => handleWordSelect(word, correctWord, btn));
    optionsContainer.appendChild(btn);
  });
}

/**
 * Handle word selection
 */
function handleWordSelect(selectedWord, correctWord, btn) {
  const allBtns = document.querySelectorAll('.verify-options-grid .word-option');
  
  if (selectedWord === correctWord) {
    console.log('handleWordSelect - correct! Step', verifyCurrentStep, '-> Step', verifyCurrentStep + 1);
    btn.classList.add('correct');
    allBtns.forEach(b => b.style.pointerEvents = 'none');
    
    setTimeout(() => {
      verifyCurrentStep++;
      console.log('handleWordSelect - advancing to step:', verifyCurrentStep);
      showVerifyStep();
    }, 500);
  } else {
    console.log('handleWordSelect - wrong word selected');
    btn.classList.add('wrong');
    
    setTimeout(() => {
      btn.classList.remove('wrong');
    }, 400);
  }
}

/**
 * Handle verification complete
 */
async function handleVerifyComplete() {
  showToast('Recovery phrase verified! 🎉', 'success');
  currentMnemonic = null; // Clear for security
  sessionStorage.removeItem('temp_mnemonic');
  verifyPositions = [];
  verifyCurrentStep = 0;
  
  // Reload main screen properly
  await loadMainScreen();
  showScreen('main');
  
  // Refresh accounts list in case user navigates there
  await loadAccountsList();
}

/**
 * Update password strength indicator
 */
function updatePasswordStrength(password) {
  const strengthEl = document.getElementById('password-strength');
  if (!strengthEl) return;
  
  let strength = 0;
  if (password.length >= 8) strength++;
  if (password.length >= 12) strength++;
  if (/[A-Z]/.test(password)) strength++;
  if (/[0-9]/.test(password)) strength++;
  if (/[^A-Za-z0-9]/.test(password)) strength++;
  
  strengthEl.className = 'password-strength';
  if (strength <= 1) strengthEl.classList.add('weak');
  else if (strength === 2) strengthEl.classList.add('fair');
  else if (strength === 3) strengthEl.classList.add('good');
  else strengthEl.classList.add('strong');
}

/**
 * Handle wallet import
 */
async function handleImportWallet() {
  const password = document.getElementById('import-password').value;
  const confirmPassword = document.getElementById('import-confirm-password').value;

  if (password.length < 8) {
    showToast('Password must be at least 8 characters', 'error');
    return;
  }

  if (password !== confirmPassword) {
    showToast('Passwords do not match', 'error');
    return;
  }

  let data = { password, type: importType };

  if (importType === 'mnemonic') {
    const seed = document.getElementById('import-seed').value.trim();
    if (!seed || seed.split(' ').length < 12) {
      showToast('Please enter a valid 12 or 24 word recovery phrase', 'error');
      return;
    }
    data.mnemonic = seed;
  } else {
    const privateKey = document.getElementById('import-key').value.trim();
    if (!privateKey) {
      showToast('Please enter a private key', 'error');
      return;
    }
    data.privateKey = privateKey;
  }

  // Show progress
  const btn = document.getElementById('btn-import');
  const btnText = btn.querySelector('.btn-text');
  const btnLoader = btn.querySelector('.btn-loader');
  const progress = document.getElementById('import-progress');
  const progressFill = document.getElementById('import-progress-fill');
  const progressText = document.getElementById('import-progress-text');

  btnText?.classList.add('hidden');
  btnLoader?.classList.remove('hidden');
  progress?.classList.remove('hidden');
  btn.disabled = true;

  const steps = [
    { percent: 25, text: 'Validating input...' },
    { percent: 50, text: 'Recovering wallet...' },
    { percent: 75, text: 'Encrypting...' },
    { percent: 100, text: 'Complete!' }
  ];

  try {
    for (const step of steps.slice(0, 2)) {
      progressFill.style.width = step.percent + '%';
      progressText.textContent = step.text;
      await new Promise(r => setTimeout(r, 250));
    }

    const result = await sendMessage('importWallet', data);
    
    progressFill.style.width = '100%';
    progressText.textContent = 'Complete!';
    await new Promise(r => setTimeout(r, 300));

    if (result.success) {
      currentWalletAddress = result.address;
      await loadMainScreen();
      showScreen('main');
      showToast('Wallet imported successfully!', 'success');
    } else {
      showToast(result.error || 'Failed to import wallet', 'error');
    }
  } catch (error) {
    showToast('Error importing wallet: ' + error.message, 'error');
  } finally {
    btnText?.classList.remove('hidden');
    btnLoader?.classList.add('hidden');
    progress?.classList.add('hidden');
    progressFill.style.width = '0%';
    btn.disabled = false;
  }
}

/**
 * Handle wallet unlock
 */
async function handleUnlock() {
  const password = document.getElementById('unlock-password').value;
  const errorEl = document.getElementById('unlock-error');

  if (!password) {
    errorEl.textContent = 'Please enter your password';
    return;
  }

  // Show progress
  const btn = document.getElementById('btn-unlock');
  const btnText = btn.querySelector('.btn-text');
  const btnLoader = btn.querySelector('.btn-loader');
  const progress = document.getElementById('unlock-progress');
  const progressFill = document.getElementById('unlock-progress-fill');
  const progressText = document.getElementById('unlock-progress-text');

  btnText?.classList.add('hidden');
  btnLoader?.classList.remove('hidden');
  progress?.classList.remove('hidden');
  btn.disabled = true;

  try {
    progressFill.style.width = '30%';
    progressText.textContent = 'Decrypting wallet...';
    await new Promise(r => setTimeout(r, 200));

    const result = await sendMessage('unlockWallet', { password });
    
    progressFill.style.width = '70%';
    progressText.textContent = 'Loading accounts...';
    await new Promise(r => setTimeout(r, 200));

    progressFill.style.width = '100%';
    progressText.textContent = 'Complete!';
    await new Promise(r => setTimeout(r, 200));

    if (result.success) {
      currentWalletAddress = result.address;
      errorEl.textContent = '';
      document.getElementById('unlock-password').value = '';
      await loadMainScreen();
      
      // Check for pending dApp connection requests after unlock
      if (pendingDappRequest) {
        showDappConnectionRequest(pendingDappRequest);
      } else {
        // Check if there's a pending request from background
        const pendingResult = await sendMessage('getPendingDappRequest');
        if (pendingResult.success && pendingResult.request) {
          pendingDappRequest = pendingResult.request;
          showDappConnectionRequest(pendingDappRequest);
        }
      }
      
      showScreen('main');
    } else {
      errorEl.textContent = result.error || 'Incorrect password';
    }
  } catch (error) {
    errorEl.textContent = 'Error unlocking wallet';
  } finally {
    btnText?.classList.remove('hidden');
    btnLoader?.classList.add('hidden');
    progress?.classList.add('hidden');
    progressFill.style.width = '0%';
    btn.disabled = false;
  }
}

/**
 * Load main screen data
 */
async function loadMainScreen() {
  try {
    // Fetch wallet address and account info
    const status = await sendMessage('getWalletStatus');
    
    if (!currentWalletAddress) {
      if (status.success && status.address) {
        currentWalletAddress = status.address;
      }
    }

    // Get active account name
    const accountsResult = await sendMessage('getAccounts');
    if (accountsResult.success) {
      const activeAccount = accountsResult.accounts.find(a => a.isActive);
      if (activeAccount) {
        document.getElementById('account-name').textContent = activeAccount.name;
      }
    }

    // Get current network
    const networkResult = await sendMessage('getCurrentNetwork');
    if (networkResult.success) {
      currentNetwork = networkResult.network;
      elements.balanceSymbol.textContent = currentNetwork.symbol;
      
      // Update network icon
      updateNetworkIcon(currentNetwork);
      
      // Update network dropdown button text and icon
      const networkNameEl = document.getElementById('current-network-name');
      const networkBtnIcon = document.getElementById('network-btn-icon');
      
      // Update button icon
      if (networkBtnIcon) {
        let iconUrl = currentNetwork.icon || getNetworkIconUrl(currentNetwork.category || 'ramestta');
        if (iconUrl && iconUrl.startsWith('icons/')) iconUrl = chrome.runtime.getURL(iconUrl);
        networkBtnIcon.src = iconUrl;
        networkBtnIcon.onerror = function() { this.src = chrome.runtime.getURL('icons/rama.png'); };
      }
      
      if (networkNameEl) {
        // Check if using RPC2
        if (currentNetwork.rpcUrl === 'https://blockchain2.ramestta.com') {
          networkNameEl.textContent = 'Ramestta Mainnet (RPC 2)';
          currentRpcIndex = 2;
          updateNetworkDropdownSelection('ramestta_mainnet_rpc2');
        } else {
          networkNameEl.textContent = currentNetwork.name;
          currentRpcIndex = 1;
          updateNetworkDropdownSelection('ramestta_mainnet');
        }
      }
      
      // Check RPC status
      checkCurrentRpcStatus();
      
      // Update native token display
      const nativeTokenName = document.getElementById('native-token-name');
      const nativeTokenNetwork = document.getElementById('native-token-network');
      const nativeTokenIcon = document.getElementById('native-token-icon');
      if (nativeTokenName) nativeTokenName.textContent = currentNetwork.symbol;
      if (nativeTokenNetwork) nativeTokenNetwork.textContent = currentNetwork.name;
      if (nativeTokenIcon) {
        let iconUrl = currentNetwork.icon || getNetworkIconUrl(currentNetwork.category || 'ramestta');
        if (iconUrl && iconUrl.startsWith('icons/')) iconUrl = chrome.runtime.getURL(iconUrl);
        nativeTokenIcon.src = iconUrl;
        nativeTokenIcon.onerror = function() { this.src = chrome.runtime.getURL('icons/rama.png'); };
      }
      
      // Load custom networks into dropdown
      await loadNetworkDropdown();
      
      // Set current network in selector
      if (elements.networkSelect) {
        // Try to select by chainId
        const option = Array.from(elements.networkSelect.options).find(
          opt => opt.value === currentNetwork.chainId
        );
        if (option) {
          elements.networkSelect.value = currentNetwork.chainId;
        }
      }
    }

    // Display address
    if (currentWalletAddress) {
      elements.walletAddress.textContent = formatAddress(currentWalletAddress);
      document.getElementById('receive-address').textContent = currentWalletAddress;
    }

    // Get balance
    await refreshBalance();

    // Fetch prices
    await fetchPrices();

    // Load custom tokens
    await loadCustomTokens();

    // Load transaction history
    await loadTransactionHistory();
  } catch (error) {
    console.error('Error loading main screen:', error);
  }
}

/**
 * Load networks into the dropdown selector (only enabled networks)
 */
async function loadNetworkDropdown() {
  const networkSelect = elements.networkSelect;
  if (!networkSelect) return;

  // Load enabled networks
  await loadEnabledNetworks();

  // Clear existing options
  networkSelect.innerHTML = '';

  // Add Ramestta networks (always first, always available)
  const ramestttaOptgroup = document.createElement('optgroup');
  ramestttaOptgroup.label = '🏠 Ramestta';
  
  if (ALL_BUILTIN_NETWORKS.ramestta_mainnet) {
    ramestttaOptgroup.appendChild(createOption('0x55a', 'Ramestta Mainnet'));
  }
  if (ALL_BUILTIN_NETWORKS.ramestta_testnet && enabledNetworks.includes('ramestta_testnet')) {
    ramestttaOptgroup.appendChild(createOption('0x559', 'Ramestta Testnet'));
  }
  
  networkSelect.appendChild(ramestttaOptgroup);

  // Add other enabled built-in networks to both select and custom dropdown
  const otherEnabled = enabledNetworks.filter(key => !key.startsWith('ramestta_') && ALL_BUILTIN_NETWORKS[key]);
  const otherNetworksSection = document.getElementById('other-networks-section');
  const otherNetworksList = document.getElementById('other-networks-list');
  
  if (otherEnabled.length > 0) {
    const builtinOptgroup = document.createElement('optgroup');
    builtinOptgroup.label = '🌐 Other Networks';
    
    // Clear and populate the custom dropdown list
    if (otherNetworksList) {
      otherNetworksList.innerHTML = '';
    }
    if (otherNetworksSection) {
      otherNetworksSection.style.display = 'block';
    }
    
    otherEnabled.forEach(key => {
      const network = ALL_BUILTIN_NETWORKS[key];
      if (network) {
        // Use chainIdHex for network selection
        const chainIdHex = network.chainIdHex || ('0x' + network.chainId.toString(16));
        builtinOptgroup.appendChild(createOption(chainIdHex, network.name));
        
        // Add to custom dropdown
        if (otherNetworksList) {
          const iconUrl = getNetworkIconUrl(network.category || key);
          const fallbackIcon = chrome.runtime.getURL('icons/rama.png');
          const optionHtml = `
            <div class="network-option" data-chain-id="${chainIdHex}" data-network-key="${key}">
              <span class="network-checkmark"></span>
              <img src="${iconUrl}" alt="" class="network-option-icon" data-fallback="${fallbackIcon}">
              <span class="network-name">${network.name}</span>
            </div>
          `;
          otherNetworksList.insertAdjacentHTML('beforeend', optionHtml);
        }
      }
    });
    
    networkSelect.appendChild(builtinOptgroup);
    
    // Add error handlers for icons (safe to re-add)
    if (otherNetworksList) {
      otherNetworksList.querySelectorAll('.network-option-icon').forEach(img => {
        img.onerror = function() {
          this.src = this.dataset.fallback || chrome.runtime.getURL('icons/rama.png');
        };
      });
    }
  } else {
    if (otherNetworksSection) {
      otherNetworksSection.style.display = 'none';
    }
  }

  // Add custom networks
  const customNetworksSection = document.getElementById('custom-networks-section');
  const customNetworksList = document.getElementById('custom-networks-list');
  
  try {
    const result = await sendMessage('getCustomNetworks');
    if (result.success && result.networks.length > 0) {
      const customOptgroup = document.createElement('optgroup');
      customOptgroup.label = 'Custom Networks';
      
      // Clear and populate the custom dropdown list
      if (customNetworksList) {
        customNetworksList.innerHTML = '';
      }
      if (customNetworksSection) {
        customNetworksSection.style.display = 'block';
      }
      
      const ramaIcon = chrome.runtime.getURL('icons/rama.png');
      result.networks.forEach(network => {
        customOptgroup.appendChild(createOption(network.chainId, network.name));
        
        // Add to custom dropdown
        if (customNetworksList) {
          const optionHtml = `
            <div class="network-option" data-chain-id="${network.chainId}" data-network-key="custom_${network.chainId}">
              <span class="network-checkmark"></span>
              <img src="${ramaIcon}" alt="" class="network-option-icon">
              <span class="network-name">${network.name}</span>
            </div>
          `;
          customNetworksList.insertAdjacentHTML('beforeend', optionHtml);
        }
      });
      
      networkSelect.appendChild(customOptgroup);
      
      // Add click listeners to custom network options
      if (customNetworksList) {
        customNetworksList.querySelectorAll('.network-option').forEach(option => {
          option.addEventListener('click', async () => {
            const chainId = option.dataset.chainId;
            await handleNetworkOptionClick(chainId, chainId);
            document.getElementById('network-dropdown-btn')?.classList.remove('open');
            document.getElementById('network-dropdown-menu')?.classList.remove('show');
          });
        });
      }
    } else {
      if (customNetworksSection) {
        customNetworksSection.style.display = 'none';
      }
    }
  } catch (error) {
    console.error('Error loading custom networks for dropdown:', error);
    if (customNetworksSection) {
      customNetworksSection.style.display = 'none';
    }
  }
}

/**
 * Create an option element
 */
function createOption(value, text) {
  const option = document.createElement('option');
  option.value = value;
  option.textContent = text;
  return option;
}

/**
 * Refresh wallet balance
 */
async function refreshBalance() {
  try {
    console.log('refreshBalance called for network:', currentNetwork?.name, 'address:', currentWalletAddress);
    
    const result = await sendMessage('getBalance', { address: currentWalletAddress });
    console.log('getBalance result:', result);
    
    if (result.success) {
      // Format balance nicely
      const rawBalance = parseFloat(result.balance.ether);
      let balance;
      if (rawBalance >= 1000000) {
        balance = rawBalance.toFixed(2);
      } else if (rawBalance >= 1000) {
        balance = rawBalance.toFixed(4);
      } else if (rawBalance >= 1) {
        balance = rawBalance.toFixed(4);
      } else {
        balance = rawBalance.toFixed(6);
      }
      
      console.log('Balance:', balance, result.balance.symbol);
      
      elements.balanceValue.textContent = balance;
      document.getElementById('send-balance').textContent = `Balance: ${balance} ${currentNetwork?.symbol || 'RAMA'}`;
      
      // Update native token display
      const nativeTokenAmount = document.getElementById('native-token-amount');
      if (nativeTokenAmount) {
        nativeTokenAmount.textContent = balance;
      }
      
      // Update USD values after balance is set
      updatePriceDisplay();
    }
  } catch (error) {
    console.error('Error fetching balance:', error);
    elements.balanceValue.textContent = '0.0000';
  }
}

/**
 * Update token list display (legacy - kept for compatibility)
 */
function updateTokenList(balance) {
  // Now handled by refreshBalance and updatePriceDisplay
}

/**
 * Format Wei to Ether
 */
function formatWeiToEther(weiValue) {
  if (!weiValue) return '0.0000';
  try {
    const wei = BigInt(weiValue);
    const ether = Number(wei) / 1e18;
    if (ether < 0.0001 && ether > 0) return '< 0.0001';
    return ether.toFixed(4);
  } catch {
    return '0.0000';
  }
}

/**
 * Format timestamp to relative time
 */
function formatTimeAgo(timestamp) {
  if (!timestamp) return '';
  const seconds = Math.floor(Date.now() / 1000 - parseInt(timestamp));
  
  if (seconds < 60) return 'Just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`;
  
  const date = new Date(parseInt(timestamp) * 1000);
  return date.toLocaleDateString();
}

// Activity auto-refresh interval
let activityRefreshInterval = null;

/**
 * Start auto-refresh for activity tab
 */
function startActivityAutoRefresh() {
  // Clear existing interval
  if (activityRefreshInterval) {
    clearInterval(activityRefreshInterval);
  }
  
  // Refresh every 15 seconds when activity tab is active
  activityRefreshInterval = setInterval(() => {
    const activityTab = document.querySelector('#main-screen .tabs .tab[data-tab="activity"]');
    if (activityTab && activityTab.classList.contains('active')) {
      loadTransactionHistory(true); // Silent refresh
    }
  }, 15000);
}

/**
 * Stop auto-refresh for activity tab
 */
function stopActivityAutoRefresh() {
  if (activityRefreshInterval) {
    clearInterval(activityRefreshInterval);
    activityRefreshInterval = null;
  }
}

/**
 * Load transaction history
 * @param {boolean} silent - If true, don't show loading spinner
 */
async function loadTransactionHistory(silent = false) {
  const activityList = document.getElementById('activity-list');
  if (!activityList) return;
  
  // Show loading state (only if not silent refresh)
  if (!silent) {
    activityList.innerHTML = '<div class="loading-activity"><div class="spinner-small"></div> Loading transactions...</div>';
  }
  
  try {
    console.log('Fetching transaction history for:', currentWalletAddress);
    // Use longer timeout (30s) for transaction history as it involves multiple API calls
    const result = await sendMessage('getTransactionHistory', { address: currentWalletAddress }, 30000);
    console.log('Transaction history result:', result);
    
    if (result.success && result.history && result.history.length > 0) {
      activityList.innerHTML = '';
      const nativeSymbol = currentNetwork?.symbol || 'RAMA';

      result.history.slice(0, 30).forEach(tx => {
        const isReceive = tx.to?.toLowerCase() === currentWalletAddress?.toLowerCase();
        const isContract = tx.input && tx.input !== '0x' && tx.input.length > 10;
        const isFailed = tx.isError || tx.txreceipt_status === '0';
        const isTokenTransfer = tx.txType === 'erc20';
        const isNft = tx.txType === 'nft';
        
        let txType = isReceive ? 'Received' : 'Sent';
        let txIcon = isReceive ? '↙️' : '↗️';
        let tokenBadge = '';
        let displaySymbol = nativeSymbol;
        let displayValue = tx.value;
        
        // Handle token transfers
        if (isTokenTransfer) {
          txType = isReceive ? 'Received Token' : 'Sent Token';
          txIcon = isReceive ? '🪙' : '🔄';
          displaySymbol = tx.tokenSymbol || 'TOKEN';
          tokenBadge = `<span class="token-badge">${displaySymbol}</span>`;
          
          // Format token value with decimals
          const decimals = parseInt(tx.tokenDecimal) || 18;
          displayValue = formatTokenValue(tx.value, decimals);
        }
        
        // Handle NFT transfers
        if (isNft) {
          txType = isReceive ? 'Received NFT' : 'Sent NFT';
          txIcon = isReceive ? '🎨' : '🖼️';
          displaySymbol = tx.tokenSymbol || 'NFT';
          displayValue = `#${tx.tokenID || '?'}`;
          tokenBadge = `<span class="token-badge nft">${displaySymbol}</span>`;
        }
        
        // Handle contract calls (native transactions with input data)
        if (tx.txType === 'native' && isContract && !isReceive) {
          txType = tx.functionName ? tx.functionName.split('(')[0] : 'Contract Call';
          txIcon = '📄';
        }
        
        // Handle failed transactions
        if (isFailed) {
          txType = 'Failed';
          txIcon = '❌';
        }
        
        const timeAgo = formatTimeAgo(tx.timeStamp);
        const amount = tx.txType === 'native' ? formatWeiToEther(tx.value) : displayValue;
        const explorerUrl = currentNetwork?.explorerUrl;
        
        // Determine activity item class
        let activityClass = isFailed ? 'failed' : '';
        if (isTokenTransfer) activityClass += ' token-transfer';
        if (isNft) activityClass += ' nft-transfer';
        
        activityList.innerHTML += `
          <div class="activity-item ${activityClass.trim()}" data-tx-hash="${tx.hash}">
            <div class="activity-left">
              <div class="activity-icon ${isReceive ? 'receive' : 'send'} ${isFailed ? 'failed' : ''} ${isTokenTransfer ? 'token' : ''} ${isNft ? 'nft' : ''}">
                ${txIcon}
              </div>
              <div class="activity-details">
                <div class="activity-type">${txType} ${tokenBadge}</div>
                <div class="activity-address">${formatAddress(isReceive ? tx.from : tx.to)}</div>
                <div class="activity-time">${timeAgo}</div>
              </div>
            </div>
            <div class="activity-right">
              <div class="activity-amount ${isReceive ? 'receive' : 'send'}">
                ${isReceive ? '+' : '-'}${amount} ${displaySymbol}
              </div>
              ${explorerUrl ? `<a href="${explorerUrl}/tx/${tx.hash}" target="_blank" class="activity-link">View ↗</a>` : ''}
            </div>
          </div>
        `;
      });
      
      // Start auto-refresh when transactions are loaded
      startActivityAutoRefresh();
    } else {
      console.log('No transactions found or error:', result.error || 'empty history');
      activityList.innerHTML = '<div class="empty-activity">No recent activity</div>';
      // Still start auto-refresh to catch new transactions
      startActivityAutoRefresh();
    }
  } catch (error) {
    console.error('Error loading history:', error);
    activityList.innerHTML = `<div class="empty-activity">Failed to load activity<br><small style="color: #888;">${error.message || 'Unknown error'}</small></div>`;
  }
}

/**
 * Format token value with decimals
 * @param {string} value - Raw token value
 * @param {number} decimals - Token decimals
 * @returns {string} Formatted value
 */
function formatTokenValue(value, decimals) {
  if (!value || value === '0') return '0';
  
  try {
    const bigValue = BigInt(value);
    const divisor = BigInt(10 ** decimals);
    const intPart = bigValue / divisor;
    const fracPart = bigValue % divisor;
    
    if (fracPart === BigInt(0)) {
      return intPart.toString();
    }
    
    // Format fractional part with proper padding
    let fracStr = fracPart.toString().padStart(decimals, '0');
    // Remove trailing zeros and limit to 6 decimal places
    fracStr = fracStr.slice(0, 6).replace(/0+$/, '');
    
    if (fracStr === '') {
      return intPart.toString();
    }
    
    return `${intPart}.${fracStr}`;
  } catch (e) {
    // Fallback for very large numbers
    return parseFloat(value / (10 ** decimals)).toFixed(4);
  }
}

// Store current RPC selection (1 = blockchain.ramestta.com, 2 = blockchain2.ramestta.com)
let currentRpcIndex = 1;

/**
 * Check RPC status for Ramestta Mainnet endpoints and update the main button status
 */
async function checkRpcStatus() {
  const rpcEndpoints = [
    { id: 'rpc-status-mainnet-1', url: 'https://blockchain.ramestta.com', index: 1 },
    { id: 'rpc-status-mainnet-2', url: 'https://blockchain2.ramestta.com', index: 2 }
  ];
  
  const mainStatusDot = document.getElementById('rpc-status-dot');
  let activeRpcOnline = false;
  
  for (const endpoint of rpcEndpoints) {
    const statusEl = document.getElementById(endpoint.id);
    if (!statusEl) continue;
    
    // Set to checking state
    statusEl.classList.remove('online', 'offline');
    statusEl.classList.add('checking');
    statusEl.title = `${endpoint.url} - Checking...`;
    
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);
      
      const response = await fetch(endpoint.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          method: 'eth_blockNumber',
          params: [],
          id: 1
        }),
        signal: controller.signal
      });
      
      clearTimeout(timeoutId);
      
      if (response.ok) {
        const data = await response.json();
        if (data.result) {
          statusEl.classList.remove('checking', 'offline');
          statusEl.classList.add('online');
          statusEl.title = `${endpoint.url} - Online`;
          
          // Update main button status if this is the active RPC
          if (endpoint.index === currentRpcIndex && mainStatusDot) {
            mainStatusDot.classList.remove('checking', 'offline');
            mainStatusDot.classList.add('online');
            mainStatusDot.title = 'RPC Online';
            activeRpcOnline = true;
          }
        } else {
          throw new Error('Invalid response');
        }
      } else {
        throw new Error('HTTP error');
      }
    } catch (error) {
      statusEl.classList.remove('checking', 'online');
      statusEl.classList.add('offline');
      statusEl.title = `${endpoint.url} - Offline`;
      
      // Update main button status if this is the active RPC
      if (endpoint.index === currentRpcIndex && mainStatusDot) {
        mainStatusDot.classList.remove('checking', 'online');
        mainStatusDot.classList.add('offline');
        mainStatusDot.title = 'RPC Offline';
      }
    }
  }
}

/**
 * Check current RPC status on page load (without opening dropdown)
 */
async function checkCurrentRpcStatus() {
  const mainStatusDot = document.getElementById('rpc-status-dot');
  if (!mainStatusDot) return;
  
  const rpcUrl = currentRpcIndex === 2 
    ? 'https://blockchain2.ramestta.com' 
    : 'https://blockchain.ramestta.com';
  
  mainStatusDot.classList.remove('online', 'offline');
  mainStatusDot.classList.add('checking');
  
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);
    
    const response = await fetch(rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        method: 'eth_blockNumber',
        params: [],
        id: 1
      }),
      signal: controller.signal
    });
    
    clearTimeout(timeoutId);
    
    if (response.ok) {
      const data = await response.json();
      if (data.result) {
        mainStatusDot.classList.remove('checking', 'offline');
        mainStatusDot.classList.add('online');
        mainStatusDot.title = 'RPC Online';
      } else {
        throw new Error('Invalid response');
      }
    } else {
      throw new Error('HTTP error');
    }
  } catch (error) {
    mainStatusDot.classList.remove('checking', 'online');
    mainStatusDot.classList.add('offline');
    mainStatusDot.title = 'RPC Offline';
  }
}

/**
 * Handle network option click from custom dropdown
 */
async function handleNetworkOptionClick(chainIdHex, networkKey) {
  try {
    console.log('handleNetworkOptionClick:', { chainIdHex, networkKey });
    
    // Reset RPC to default when switching networks
    currentRpcIndex = 1;
    
    // Show loading state
    elements.balanceValue.textContent = '...';
    
    // Use networkKey if available, otherwise use chainIdHex
    const switchKey = networkKey || chainIdHex;
    console.log('Switching to network:', switchKey);
    
    const result = await sendMessage('switchNetwork', { networkKey: switchKey });
    console.log('switchNetwork result:', result);
    
    if (result.success) {
      currentNetwork = result.network;
      console.log('New currentNetwork:', currentNetwork?.name, currentNetwork?.symbol);
      
      updateNetworkDropdownSelection(networkKey);
      updateNetworkDisplay();
      
      // Force balance refresh with new network
      await refreshBalance();
      
      // Also reload transaction history for new network
      await loadTransactionHistory();
      
      checkCurrentRpcStatus();
      showToast(`Switched to ${currentNetwork.name}`, 'success');
    } else {
      console.error('Failed to switch network:', result.error);
      showToast(result.error || 'Failed to switch network', 'error');
    }
  } catch (error) {
    console.error('handleNetworkOptionClick error:', error);
    showToast('Failed to switch network', 'error');
  }
}

/**
 * Switch to RPC 2 for Ramestta Mainnet
 */
async function switchToRpc2() {
  try {
    currentRpcIndex = 2;
    
    // Update the RPC URL for Ramestta Mainnet
    const result = await sendMessage('switchNetwork', { 
      networkKey: '0x55a',
      customRpcUrl: 'https://blockchain2.ramestta.com'
    });
    
    if (result.success) {
      currentNetwork = result.network;
      updateNetworkDropdownSelection('ramestta_mainnet_rpc2');
      document.getElementById('current-network-name').textContent = 'Ramestta Mainnet (RPC 2)';
      await refreshBalance();
      checkCurrentRpcStatus();
      showToast('Switched to Ramestta Mainnet (RPC 2)', 'success');
    }
  } catch (error) {
    showToast('Failed to switch RPC', 'error');
  }
}

/**
 * Update network dropdown selection checkmarks
 */
function updateNetworkDropdownSelection(activeKey) {
  // Clear all checkmarks
  document.querySelectorAll('.network-option .network-checkmark').forEach(el => {
    el.textContent = '';
  });
  
  // Set checkmark for active network
  if (activeKey === 'ramestta_mainnet' || activeKey === '0x55a') {
    if (currentRpcIndex === 1) {
      const el = document.getElementById('check-mainnet');
      if (el) el.textContent = '✓';
    }
  } else if (activeKey === 'ramestta_mainnet_rpc2') {
    const el = document.getElementById('check-mainnet-rpc2');
    if (el) el.textContent = '✓';
  } else if (activeKey === 'ramestta_testnet') {
    const el = document.getElementById('check-testnet');
    if (el) el.textContent = '✓';
  } else {
    // For other networks, find by data-network-key
    const option = document.querySelector(`.network-option[data-network-key="${activeKey}"]`);
    if (option) {
      const checkmark = option.querySelector('.network-checkmark');
      if (checkmark) checkmark.textContent = '✓';
    }
  }
  
  // Update dropdown button text and icon
  const nameEl = document.getElementById('current-network-name');
  const iconEl = document.getElementById('network-btn-icon');
  
  if (nameEl) {
    if (activeKey === 'ramestta_mainnet_rpc2') {
      nameEl.textContent = 'Ramestta Mainnet (RPC 2)';
    } else if (currentNetwork) {
      nameEl.textContent = currentNetwork.name;
    }
  }
  
  // Update button icon
  if (iconEl && currentNetwork) {
    let iconUrl = currentNetwork.icon || getNetworkIconUrl(currentNetwork.category || 'ramestta');
    if (iconUrl && iconUrl.startsWith('icons/')) iconUrl = chrome.runtime.getURL(iconUrl);
    iconEl.src = iconUrl;
    iconEl.onerror = function() { this.src = chrome.runtime.getURL('icons/rama.png'); };
  }
}

/**
 * Update the network display after switching
 */
function updateNetworkDisplay() {
  if (!currentNetwork) return;
  
  elements.balanceSymbol.textContent = currentNetwork.symbol;
  
  // Update network icon
  updateNetworkIcon(currentNetwork);
  
  // Update native token display
  const nativeTokenName = document.getElementById('native-token-name');
  const nativeTokenNetwork = document.getElementById('native-token-network');
  const nativeTokenIcon = document.getElementById('native-token-icon');
  if (nativeTokenName) nativeTokenName.textContent = currentNetwork.symbol;
  if (nativeTokenNetwork) nativeTokenNetwork.textContent = currentNetwork.name;
  if (nativeTokenIcon) {
    let iconUrl = currentNetwork.icon || getNetworkIconUrl(currentNetwork.category || 'ramestta');
    if (iconUrl && iconUrl.startsWith('icons/')) iconUrl = chrome.runtime.getURL(iconUrl);
    nativeTokenIcon.src = iconUrl;
    nativeTokenIcon.onerror = function() { this.src = chrome.runtime.getURL('icons/rama.png'); };
  }
  
  // Update dropdown button
  const networkNameEl = document.getElementById('current-network-name');
  const networkBtnIcon = document.getElementById('network-btn-icon');
  
  if (networkNameEl) {
    networkNameEl.textContent = currentRpcIndex === 2 ? 'Ramestta Mainnet (RPC 2)' : currentNetwork.name;
  }
  
  if (networkBtnIcon) {
    let iconUrl = currentNetwork.icon || getNetworkIconUrl(currentNetwork.category || 'ramestta');
    if (iconUrl && iconUrl.startsWith('icons/')) iconUrl = chrome.runtime.getURL(iconUrl);
    networkBtnIcon.src = iconUrl;
    networkBtnIcon.onerror = function() { this.src = chrome.runtime.getURL('icons/rama.png'); };
  }
}

/**
 * Handle network change
 */
async function handleNetworkChange(e) {
  const networkKey = e.target.value;
  
  try {
    const result = await sendMessage('switchNetwork', { networkKey });
    
    if (result.success) {
      currentNetwork = result.network;
      currentRpcIndex = 1; // Reset to default RPC
      
      // Show loading state for balance
      elements.balanceValue.textContent = '...';
      elements.balanceSymbol.textContent = currentNetwork.symbol;
      
      // Update network icon
      updateNetworkIcon(currentNetwork);
      
      // Update dropdown button text
      const networkNameEl = document.getElementById('current-network-name');
      if (networkNameEl) networkNameEl.textContent = currentNetwork.name;
      updateNetworkDropdownSelection(networkKey);
      
      // Update native token display
      const nativeTokenName = document.getElementById('native-token-name');
      const nativeTokenNetwork = document.getElementById('native-token-network');
      const nativeTokenIcon = document.getElementById('native-token-icon');
      if (nativeTokenName) nativeTokenName.textContent = currentNetwork.symbol;
      if (nativeTokenNetwork) nativeTokenNetwork.textContent = currentNetwork.name;
      if (nativeTokenIcon) {
        let iconUrl = currentNetwork.icon || getNetworkIconUrl(currentNetwork.category || 'ramestta');
        if (iconUrl && iconUrl.startsWith('icons/')) iconUrl = chrome.runtime.getURL(iconUrl);
        nativeTokenIcon.src = iconUrl;
        nativeTokenIcon.onerror = function() { this.src = chrome.runtime.getURL('icons/rama.png'); };
      }
      
      // Refresh balance and transaction history
      await refreshBalance();
      await loadTransactionHistory();
      
      showToast(`Switched to ${currentNetwork.name}`, 'success');
    }
  } catch (error) {
    showToast('Failed to switch network', 'error');
  }
}

/**
 * Update the network icon in the balance section
 */
function updateNetworkIcon(network) {
  const networkIcon = document.getElementById('current-network-icon');
  if (networkIcon && network) {
    // Get icon URL from network config or fallback to category
    let iconUrl = network.icon || getNetworkIconUrl(network.category || 'ramestta');
    // For local icons, use chrome.runtime.getURL
    if (iconUrl && iconUrl.startsWith('icons/')) {
      iconUrl = chrome.runtime.getURL(iconUrl);
    }
    networkIcon.src = iconUrl;
    networkIcon.alt = network.name || 'Network';
    
    // Handle load error - fall back to Ramestta icon
    networkIcon.onerror = function() {
      this.src = chrome.runtime.getURL('icons/rama.png');
    };
  }
}

/**
 * Handle copy address
 */
function handleCopyAddress() {
  if (currentWalletAddress) {
    navigator.clipboard.writeText(currentWalletAddress);
    showToast('Address copied!', 'success');
  }
}

/**
 * Handle max amount button
 */
async function handleMaxAmount() {
  try {
    const result = await sendMessage('getBalance', { address: currentWalletAddress });
    if (result.success) {
      // Leave some for gas
      const maxAmount = Math.max(0, parseFloat(result.balance.ether) - 0.01);
      document.getElementById('send-amount').value = maxAmount.toFixed(6);
      updateGasEstimate();
    }
  } catch (error) {
    console.error('Error getting max amount:', error);
  }
}

/**
 * Update gas estimate
 */
async function updateGasEstimate() {
  const amount = document.getElementById('send-amount').value;
  const to = document.getElementById('send-to').value;

  if (amount && to && to.startsWith('0x')) {
    try {
      const result = await sendMessage('estimateGas', { to, amount });
      if (result.success) {
        // gasLimit * gasPrice / 10^18 = gas cost in native token
        const gasLimit = BigInt(result.gasInfo.gasLimit || '21000');
        const gasPrice = BigInt(result.gasInfo.gasPrice || result.gasInfo.maxFeePerGas || '1000000000');
        const gasCostWei = gasLimit * gasPrice;
        const gasCostEther = Number(gasCostWei) / 1e18;
        document.getElementById('estimated-gas').textContent = `~${gasCostEther.toFixed(6)} ${currentNetwork?.symbol || 'RAMA'}`;
      }
    } catch (error) {
      // Ignore gas estimation errors, show default
      document.getElementById('estimated-gas').textContent = `~0.001000 ${currentNetwork?.symbol || 'RAMA'}`;
    }
  }
}

/**
 * Handle send review
 */
function handleSendReview() {
  const to = document.getElementById('send-to').value.trim();
  const amount = document.getElementById('send-amount').value;

  if (!to || !to.startsWith('0x') || to.length !== 42) {
    showToast('Please enter a valid address', 'error');
    return;
  }

  if (!amount || parseFloat(amount) <= 0) {
    showToast('Please enter a valid amount', 'error');
    return;
  }

  // Get gas estimate value
  const gasText = document.getElementById('estimated-gas').textContent || '~0.001000';
  const gasMatch = gasText.match(/~?([\d.]+)/);
  const gasAmount = gasMatch ? parseFloat(gasMatch[1]) : 0.001;

  // Fill confirm screen
  document.getElementById('confirm-to').textContent = to;
  document.getElementById('confirm-amount').textContent = `${amount} ${currentNetwork?.symbol || 'RAMA'}`;
  document.getElementById('confirm-network').textContent = currentNetwork?.name || 'Ramestta Mainnet';
  document.getElementById('confirm-gas').textContent = gasText;
  document.getElementById('confirm-total').textContent = `${(parseFloat(amount) + gasAmount).toFixed(6)} ${currentNetwork?.symbol || 'RAMA'}`;

  showScreen('confirm');
}

/**
 * Handle send transaction
 */
async function handleSendTransaction() {
  const to = document.getElementById('send-to').value.trim();
  const amount = document.getElementById('send-amount').value;

  const btn = document.getElementById('btn-send-final');
  btn.disabled = true;
  btn.textContent = 'Sending...';

  try {
    const result = await sendMessage('sendTransaction', { to, amount });
    
    if (result.success) {
      // Show success with tx hash
      const txHash = result.txHash;
      const explorerUrl = currentNetwork?.explorerUrl;
      
      if (txHash && explorerUrl) {
        showToast(`Transaction sent! Hash: ${txHash.slice(0, 10)}...`, 'success');
        // Ask to view on explorer
        setTimeout(() => {
          showConfirmModal({
            title: 'Transaction Sent',
            message: 'Would you like to view this transaction on the block explorer?',
            confirmText: 'View',
            cancelText: 'Close',
            onConfirm: () => {
              window.open(`${explorerUrl}/tx/${txHash}`, '_blank');
            }
          });
        }, 500);
      } else {
        showToast('Transaction sent successfully!', 'success');
      }
      
      document.getElementById('send-to').value = '';
      document.getElementById('send-amount').value = '';
      await refreshBalance();
      showScreen('main');
    } else {
      showToast(result.error || 'Transaction failed', 'error');
    }
  } catch (error) {
    showToast('Error: ' + (error.message || 'Transaction failed'), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Confirm & Send';
  }
}

/**
 * Generate QR code for receive address
 */
async function generateQRCode() {
  const container = document.getElementById('qr-code');
  if (!container) {
    console.error('QR container not found');
    return;
  }
  
  container.innerHTML = '<div class="qr-loading">Generating...</div>';

  if (currentWalletAddress) {
    try {
      // Use qrcode-generator library (loaded from qr.bundle.js)
      if (typeof qrcode === 'undefined') {
        console.error('QR library not loaded');
        container.innerHTML = `<div class="qr-fallback">${currentWalletAddress}</div>`;
        return;
      }
      
      // Create QR code - type 0 means auto-detect version, 'M' is medium error correction
      const qr = qrcode(0, 'M');
      qr.addData(currentWalletAddress);
      qr.make();
      
      const moduleCount = qr.getModuleCount();
      const cellSize = 5;
      const margin = 16;
      const size = moduleCount * cellSize + margin * 2;
      
      const canvas = document.createElement('canvas');
      canvas.width = size;
      canvas.height = size;
      const ctx = canvas.getContext('2d');
      
      // White background
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, size, size);
      
      // Draw QR modules with black-blue gradient
      for (let row = 0; row < moduleCount; row++) {
        for (let col = 0; col < moduleCount; col++) {
          if (qr.isDark(row, col)) {
            // Gradient from black (#000) to blue (#1a56db)
            const progress = (row + col) / (moduleCount * 2);
            const r = Math.round(0 + progress * 26);
            const g = Math.round(0 + progress * 86);
            const b = Math.round(0 + progress * 219);
            ctx.fillStyle = `rgb(${r}, ${g}, ${b})`;
            ctx.fillRect(
              margin + col * cellSize,
              margin + row * cellSize,
              cellSize,
              cellSize
            );
          }
        }
      }
      
      container.innerHTML = '';
      canvas.style.borderRadius = '8px';
      container.appendChild(canvas);
      
    } catch (error) {
      console.error('QR generation error:', error);
      container.innerHTML = `<div class="qr-fallback">${currentWalletAddress}</div>`;
    }
  }
}

/**
 * Handle lock wallet
 */
async function handleLock() {
  try {
    await sendMessage('lockWallet');
    currentWalletAddress = null;
    showScreen('lock');
    showToast('Wallet locked', 'success');
  } catch (error) {
    showToast('Error locking wallet', 'error');
  }
}

// ============================================
// GENERIC INPUT & CONFIRM MODALS
// ============================================

/**
 * Show a generic input modal (replaces prompt())
 * @param {Object} options - Modal options
 * @param {string} options.title - Modal title
 * @param {string} options.label - Input label
 * @param {string} options.placeholder - Input placeholder
 * @param {string} options.defaultValue - Default input value
 * @param {string} options.confirmText - Confirm button text
 * @param {Function} options.onConfirm - Callback with input value
 */
function showInputModal({ title, label, placeholder = '', defaultValue = '', confirmText = 'OK', onConfirm }) {
  // Remove existing input modal
  const existing = document.getElementById('generic-input-modal');
  if (existing) existing.remove();
  
  const modal = document.createElement('div');
  modal.id = 'generic-input-modal';
  modal.className = 'modal active';
  modal.innerHTML = `
    <div class="modal-content" style="max-width: 320px;">
      <div class="modal-header">
        <h3>${title}</h3>
        <button class="modal-close" id="input-modal-close">✕</button>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label>${label}</label>
          <input type="text" id="generic-input-value" class="form-control" placeholder="${placeholder}" value="${defaultValue}">
        </div>
      </div>
      <div class="modal-footer" style="display: flex; gap: 12px; justify-content: flex-end;">
        <button class="btn btn-secondary" id="input-modal-cancel" style="flex: 1;">Cancel</button>
        <button class="btn btn-primary" id="input-modal-confirm" style="flex: 1;">${confirmText}</button>
      </div>
    </div>
  `;
  
  document.body.appendChild(modal);
  
  const input = document.getElementById('generic-input-value');
  input.focus();
  input.select();
  
  const closeModal = () => {
    modal.remove();
  };
  
  const handleConfirm = () => {
    const value = input.value.trim();
    closeModal();
    if (onConfirm) onConfirm(value);
  };
  
  document.getElementById('input-modal-close').addEventListener('click', closeModal);
  document.getElementById('input-modal-cancel').addEventListener('click', closeModal);
  document.getElementById('input-modal-confirm').addEventListener('click', handleConfirm);
  
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') handleConfirm();
    if (e.key === 'Escape') closeModal();
  });
  
  // Close on backdrop click
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeModal();
  });
}

/**
 * Show a generic confirm modal (replaces confirm())
 * @param {Object} options - Modal options
 * @param {string} options.title - Modal title
 * @param {string} options.message - Confirmation message
 * @param {string} options.confirmText - Confirm button text
 * @param {string} options.cancelText - Cancel button text
 * @param {boolean} options.isDanger - Use danger styling
 * @param {Function} options.onConfirm - Callback when confirmed
 * @param {Function} options.onCancel - Callback when cancelled
 */
function showConfirmModal({ title = 'Confirm', message, confirmText = 'OK', cancelText = 'Cancel', isDanger = false, onConfirm, onCancel }) {
  // Remove existing confirm modal
  const existing = document.getElementById('generic-confirm-modal');
  if (existing) existing.remove();
  
  const modal = document.createElement('div');
  modal.id = 'generic-confirm-modal';
  modal.className = 'modal active';
  modal.innerHTML = `
    <div class="modal-content" style="max-width: 320px;">
      <div class="modal-header">
        <h3>${title}</h3>
        <button class="modal-close" id="confirm-modal-close">✕</button>
      </div>
      <div class="modal-body">
        <p style="color: var(--text-secondary); margin: 0; line-height: 1.5;">${message}</p>
      </div>
      <div class="modal-footer" style="display: flex; gap: 12px; justify-content: flex-end;">
        <button class="btn btn-secondary" id="confirm-modal-cancel" style="flex: 1;">${cancelText}</button>
        <button class="btn ${isDanger ? 'btn-danger' : 'btn-primary'}" id="confirm-modal-confirm" style="flex: 1;">${confirmText}</button>
      </div>
    </div>
  `;
  
  document.body.appendChild(modal);
  
  const closeModal = () => {
    modal.remove();
  };
  
  document.getElementById('confirm-modal-close').addEventListener('click', () => {
    closeModal();
    if (onCancel) onCancel();
  });
  
  document.getElementById('confirm-modal-cancel').addEventListener('click', () => {
    closeModal();
    if (onCancel) onCancel();
  });
  
  document.getElementById('confirm-modal-confirm').addEventListener('click', () => {
    closeModal();
    if (onConfirm) onConfirm();
  });
  
  // Close on backdrop click
  modal.addEventListener('click', (e) => {
    if (e.target === modal) {
      closeModal();
      if (onCancel) onCancel();
    }
  });
  
  // Handle escape key
  const handleKeydown = (e) => {
    if (e.key === 'Escape') {
      closeModal();
      if (onCancel) onCancel();
      document.removeEventListener('keydown', handleKeydown);
    }
  };
  document.addEventListener('keydown', handleKeydown);
}

/**
 * Show auto-lock settings modal
 */
async function showAutoLockModal() {
  try {
    const result = await sendMessage('getAutoLockSettings');
    
    if (result.success) {
      // Select the current setting
      const radios = document.querySelectorAll('input[name="auto-lock"]');
      radios.forEach(radio => {
        radio.checked = parseInt(radio.value) === result.autoLockMinutes;
      });
    }
    
    document.getElementById('auto-lock-modal').classList.add('show');
  } catch (error) {
    showToast('Error loading settings', 'error');
  }
}

/**
 * Save auto-lock setting
 */
async function saveAutoLockSetting() {
  try {
    const selected = document.querySelector('input[name="auto-lock"]:checked');
    if (!selected) {
      showToast('Please select a timeout', 'error');
      return;
    }
    
    const minutes = parseInt(selected.value);
    const result = await sendMessage('setAutoLockTimeout', { minutes });
    
    if (result.success) {
      // Update the display in settings
      const displayText = minutes === 0 ? 'Never' : 
                         minutes >= 60 ? `${minutes / 60} hr` : `${minutes} min`;
      const autoLockValue = document.getElementById('auto-lock-value');
      if (autoLockValue) {
        autoLockValue.textContent = displayText;
      }
      
      document.getElementById('auto-lock-modal').classList.remove('show');
      showToast('Auto-lock timer updated', 'success');
    } else {
      showToast(result.error || 'Failed to save setting', 'error');
    }
  } catch (error) {
    showToast('Error saving setting', 'error');
  }
}

/**
 * Load and display current auto-lock setting
 */
async function loadAutoLockDisplay() {
  try {
    const result = await sendMessage('getAutoLockSettings');
    if (result.success) {
      const minutes = result.autoLockMinutes;
      const displayText = minutes === 0 ? 'Never' : 
                         minutes >= 60 ? `${minutes / 60} hr` : `${minutes} min`;
      const autoLockValue = document.getElementById('auto-lock-value');
      if (autoLockValue) {
        autoLockValue.textContent = displayText;
      }
    }
  } catch (error) {
    console.error('Error loading auto-lock display:', error);
  }
}

/**
 * Handle reset wallet - show confirmation modal
 */
async function handleResetWallet() {
  // Show the reset confirmation modal
  document.getElementById('reset-confirm-text').value = '';
  document.getElementById('btn-confirm-reset').disabled = true;
  document.getElementById('reset-wallet-modal').classList.add('show');
}

/**
 * Confirm reset wallet after modal verification
 */
async function confirmResetWallet() {
  const confirmText = document.getElementById('reset-confirm-text')?.value.trim();
  if (confirmText !== 'RESET') {
    showToast('Please type RESET to confirm', 'error');
    return;
  }
  
  try {
    await chrome.storage.local.clear();
    currentWalletAddress = null;
    
    // Clear all form fields to prevent old data showing
    clearAllFormFields();
    
    document.getElementById('reset-wallet-modal').classList.remove('show');
    showScreen('welcome');
    showToast('Wallet reset successfully', 'success');
  } catch (error) {
    showToast('Error resetting wallet', 'error');
  }
}

/**
 * Clear all form fields in the app
 */
function clearAllFormFields() {
  // Welcome/Create/Import screens
  const fieldsToReset = [
    'create-password',
    'confirm-password',
    'import-seed',
    'import-key',
    'import-password',
    'import-confirm-password',
    'reset-confirm-text',
    // Account management
    'import-key-value',
    'import-key-name',
    'import-seed-value',
    'import-seed-name',
    'import-seed-count',
    'bulk-add-count',
    'create-account-name',
    // Export key
    'export-key-password',
    // Send screen
    'send-address',
    'send-amount',
    // Other fields
    'unlock-password'
  ];
  
  fieldsToReset.forEach(id => {
    const el = document.getElementById(id);
    if (el) {
      if (el.tagName === 'TEXTAREA') {
        el.value = '';
      } else if (el.type === 'checkbox') {
        el.checked = false;
      } else {
        el.value = '';
      }
    }
  });
  
  // Reset checkboxes
  const checkboxes = ['agree-terms', 'seed-saved'];
  checkboxes.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.checked = false;
  });
  
  // Clear seed phrase display
  const seedDisplay = document.getElementById('seed-phrase-display');
  if (seedDisplay) seedDisplay.innerHTML = '';
  
  // Clear private key display
  const pkDisplay = document.getElementById('private-key-value');
  if (pkDisplay) pkDisplay.textContent = '';
}

/**
 * Handle export private key - now shows the export screen
 */
async function handleRevealPrivateKey() {
  const password = document.getElementById('export-key-password')?.value;
  if (!password) {
    showToast('Please enter your password', 'error');
    return;
  }

  try {
    const result = await sendMessage('exportPrivateKey', { password });
    if (result.success) {
      document.getElementById('private-key-value').textContent = result.privateKey;
      document.getElementById('export-key-auth').classList.add('hidden');
      document.getElementById('export-key-display').classList.remove('hidden');
    } else {
      showToast(result.error || 'Incorrect password', 'error');
    }
  } catch (error) {
    showToast('Error exporting key', 'error');
  }
}

/**
 * Copy private key to clipboard
 */
async function handleCopyPrivateKey() {
  const key = document.getElementById('private-key-value').textContent;
  try {
    await navigator.clipboard.writeText(key);
    showToast('Private key copied to clipboard', 'success');
  } catch (error) {
    showToast('Failed to copy', 'error');
  }
}

/**
 * Handle reveal recovery phrase
 */
async function handleRevealRecoveryPhrase() {
  const password = document.getElementById('export-seed-password')?.value;
  if (!password) {
    showToast('Please enter your password', 'error');
    return;
  }

  try {
    const result = await sendMessage('exportRecoveryPhrase', { password });
    if (result.success) {
      // Display the words in a grid
      const wordsContainer = document.getElementById('export-seed-words');
      const words = result.mnemonic.split(' ');
      wordsContainer.innerHTML = words.map((word, i) => `
        <div class="seed-word">
          <span class="word-number">${i + 1}</span>
          <span class="word-text">${word}</span>
        </div>
      `).join('');
      
      document.getElementById('export-seed-auth').classList.add('hidden');
      document.getElementById('export-seed-display').classList.remove('hidden');
    } else {
      showToast(result.error || 'Incorrect password', 'error');
    }
  } catch (error) {
    showToast('Error exporting phrase', 'error');
  }
}

/**
 * Copy recovery phrase to clipboard
 */
async function handleCopyRecoveryPhrase() {
  const words = Array.from(document.querySelectorAll('#export-seed-words .word-text'))
    .map(el => el.textContent).join(' ');
  try {
    await navigator.clipboard.writeText(words);
    showToast('Recovery phrase copied to clipboard', 'success');
  } catch (error) {
    showToast('Failed to copy', 'error');
  }
}

/**
 * Handle export seed phrase (legacy - now redirects to screen)
 */
async function handleExportSeed() {
  showScreen('export-seed');
}

/**
 * Handle export key (legacy - now redirects to screen)  
 */
async function handleExportKey() {
  showScreen('export-key');
}

/**
 * Handle add account
 */
async function handleAddAccount() {
  showInputModal({
    title: 'Add New Account',
    label: 'Account Name',
    placeholder: 'Enter account name',
    confirmText: 'Create',
    onConfirm: async (name) => {
      if (!name) return;
      try {
        const result = await sendMessage('addAccount', { name });
        if (result.success) {
          showToast(`Account created: ${formatAddress(result.address)}`, 'success');
        } else {
          showToast(result.error || 'Failed to add account', 'error');
        }
      } catch (error) {
        showToast('Error adding account', 'error');
      }
    }
  });
}

/**
 * Format address for display
 */
function formatAddress(address) {
  if (!address) return '';
  return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
}

/**
 * Format amount from wei
 */
function formatAmount(weiValue) {
  try {
    const ether = Number(BigInt(weiValue)) / 1e18;
    return ether.toFixed(4);
  } catch {
    return '0.0000';
  }
}

// ============================================
// TOKEN MANAGEMENT
// ============================================

let pendingTokenInfo = null;

/**
 * Open add token modal
 */
function openAddTokenModal() {
  document.getElementById('add-token-modal')?.classList.add('active');
  document.getElementById('token-address').value = '';
  document.getElementById('token-preview')?.classList.add('hidden');
  document.getElementById('btn-confirm-add-token').disabled = true;
  pendingTokenInfo = null;
}

/**
 * Close add token modal
 */
function closeAddTokenModal() {
  document.getElementById('add-token-modal')?.classList.remove('active');
  pendingTokenInfo = null;
}

/**
 * Handle token address input - fetch token info
 */
let tokenFetchTimeout = null;
let foundTokensOnNetworks = []; // Store tokens found across networks

async function handleTokenAddressInput(e) {
  const address = e.target.value.trim();
  
  // Clear previous timeout
  if (tokenFetchTimeout) clearTimeout(tokenFetchTimeout);
  
  // Hide preview initially
  document.getElementById('token-preview')?.classList.add('hidden');
  document.getElementById('token-network-list')?.classList.add('hidden');
  document.getElementById('btn-confirm-add-token').disabled = true;
  pendingTokenInfo = null;
  foundTokensOnNetworks = [];

  // Validate address format
  if (!address || !address.startsWith('0x') || address.length !== 42) {
    return;
  }

  // Show scanning indicator
  const previewDiv = document.getElementById('token-preview');
  if (previewDiv) {
    previewDiv.classList.remove('hidden');
    previewDiv.innerHTML = `
      <div class="scanning-indicator">
        <div class="spinner"></div>
        <p>Checking token on ${currentNetwork?.name || 'current network'}...</p>
      </div>
    `;
  }

  // Debounce the API call
  tokenFetchTimeout = setTimeout(async () => {
    try {
      // Only scan current active network (not all networks to avoid errors)
      const result = await sendMessage('getTokenInfo', { tokenAddress: address });
      
      if (result.success && result.token) {
        pendingTokenInfo = result.token;
        showTokenPreview(pendingTokenInfo);
      } else {
        previewDiv.innerHTML = `
          <div class="token-not-found">
            <p>❌ Token not found on ${currentNetwork?.name || 'current network'}</p>
            <p class="hint">Make sure you're on the correct network for this token</p>
          </div>
        `;
      }
    } catch (error) {
      console.error('Token fetch error:', error);
      previewDiv.innerHTML = `<p class="error">Error fetching token info</p>`;
    }
  }, 500);
}

/**
 * Show token preview for selected token
 */
function showTokenPreview(token) {
  const previewDiv = document.getElementById('token-preview');
  if (!previewDiv) return;
  
  previewDiv.classList.remove('hidden');
  previewDiv.innerHTML = `
    <div class="preview-row">
      <span>Name</span>
      <span id="preview-name">${token.name || 'Unknown'}</span>
    </div>
    <div class="preview-row">
      <span>Symbol</span>
      <span id="preview-symbol">${token.symbol || 'TOKEN'}</span>
    </div>
    <div class="preview-row">
      <span>Decimals</span>
      <span id="preview-decimals">${token.decimals || 18}</span>
    </div>
    <div class="preview-row network-row">
      <span>Network</span>
      <span id="preview-network">${token.network || 'Current Network'}</span>
    </div>
  `;
  document.getElementById('btn-confirm-add-token').disabled = false;
}

/**
 * Show list of networks where token was found
 */
function showTokenNetworkList(tokens) {
  const previewDiv = document.getElementById('token-preview');
  if (!previewDiv) return;
  
  previewDiv.classList.remove('hidden');
  previewDiv.innerHTML = `
    <div class="token-network-found">
      <p class="found-header">✅ Token found on ${tokens.length} networks</p>
      <p class="found-subtitle">Select which network to add from:</p>
      <div class="network-token-list">
        ${tokens.map((token, index) => `
          <div class="network-token-option ${index === 0 ? 'selected' : ''}" 
               data-select-network="${index}" 
               data-index="${index}">
            <div class="network-token-info">
              <span class="token-symbol">${token.symbol}</span>
              <span class="token-name">${token.name}</span>
            </div>
            <div class="network-info">
              <span class="network-name">${token.network}</span>
              <span class="network-symbol">${token.networkSymbol}</span>
            </div>
            ${index === 0 ? '<span class="selected-check">✓</span>' : ''}
          </div>
        `).join('')}
      </div>
    </div>
  `;
  
  // Add event listeners for network selection
  previewDiv.querySelectorAll('[data-select-network]').forEach(el => {
    el.addEventListener('click', () => {
      window.selectTokenNetwork(parseInt(el.dataset.selectNetwork));
    });
  });
  
  // Auto-select first token
  pendingTokenInfo = tokens[0];
  document.getElementById('btn-confirm-add-token').disabled = false;
}

/**
 * Select a token from the network list
 */
window.selectTokenNetwork = function(index) {
  if (index >= 0 && index < foundTokensOnNetworks.length) {
    pendingTokenInfo = foundTokensOnNetworks[index];
    
    // Update UI
    document.querySelectorAll('.network-token-option').forEach((el, i) => {
      el.classList.toggle('selected', i === index);
      const check = el.querySelector('.selected-check');
      if (i === index && !check) {
        el.insertAdjacentHTML('beforeend', '<span class="selected-check">✓</span>');
      } else if (i !== index && check) {
        check.remove();
      }
    });
  }
};

/**
 * Handle add token confirmation
 */
async function handleAddToken() {
  if (!pendingTokenInfo) return;

  try {
    const result = await sendMessage('addToken', {
      tokenAddress: pendingTokenInfo.address,
      chainId: pendingTokenInfo.chainId || null
    });

    if (result.success) {
      showToast(`${result.token.symbol} added successfully!`, 'success');
      closeAddTokenModal();
      await loadCustomTokens();
    } else {
      showToast(result.error || 'Failed to add token', 'error');
    }
  } catch (error) {
    showToast('Error adding token', 'error');
  }
}

/**
 * Auto-fetch tokens with balance on current network
 */
async function handleAutoFetchTokens() {
  showToast('Scanning for tokens...', 'info');
  
  try {
    const result = await sendMessage('autoFetchTokens', { 
      address: currentWalletAddress,
      networkKey: currentNetwork?.key || currentNetwork?.name
    });
    
    if (result.success) {
      const count = result.tokensFound || 0;
      if (count > 0) {
        showToast(`Found ${count} token${count > 1 ? 's' : ''} with balance!`, 'success');
        await loadCustomTokens();
      } else {
        showToast('No tokens with balance found', 'info');
      }
    } else {
      showToast(result.error || 'Failed to scan tokens', 'error');
    }
  } catch (error) {
    console.error('Auto-fetch error:', error);
    showToast('Error scanning for tokens', 'error');
  }
}

/**
 * Load and display custom tokens
 */
async function loadCustomTokens() {
  try {
    const result = await sendMessage('getTokens', {});
    
    if (result.success) {
      const container = document.getElementById('custom-tokens-container');
      if (!container) return;
      
      container.innerHTML = '';
      
      for (const token of result.tokens) {
        const tokenHtml = `
          <div class="token-item custom-token" data-address="${token.address}">
            <div class="token-info">
              <div class="token-icon">🪙</div>
              <div class="token-details">
                <span class="token-name">${token.symbol}</span>
                <span class="token-network">${token.name}</span>
              </div>
            </div>
            <div class="token-balance">
              <span class="token-amount">${parseFloat(token.balance || 0).toFixed(4)}</span>
              <span class="token-value">$${(parseFloat(token.balance || 0) * (tokenPrices[token.symbol] || 0)).toFixed(2)}</span>
            </div>
            <button class="token-delete" data-remove-token="${token.address}">Remove</button>
          </div>
        `;
        container.innerHTML += tokenHtml;
      }
      
      // Add event listeners for remove buttons
      container.querySelectorAll('[data-remove-token]').forEach(btn => {
        btn.addEventListener('click', () => {
          window.removeToken(btn.dataset.removeToken);
        });
      });
    }
  } catch (error) {
    console.error('Error loading tokens:', error);
  }
}

/**
 * Remove a custom token
 */
window.removeToken = async function(tokenAddress) {
  showConfirmModal({
    title: 'Remove Token',
    message: 'Are you sure you want to remove this token from your wallet?',
    confirmText: 'Remove',
    isDanger: true,
    onConfirm: async () => {
      try {
        const result = await sendMessage('removeToken', { tokenAddress });
        
        if (result.success) {
          showToast('Token removed', 'success');
          await loadCustomTokens();
        } else {
          showToast(result.error || 'Failed to remove token', 'error');
        }
      } catch (error) {
        showToast('Error removing token', 'error');
      }
    }
  });
};

// ============================================
// PRICE DATA
// ============================================

let tokenPrices = {};

/**
 * Fetch and display token prices
 */
async function fetchPrices() {
  try {
    const result = await sendMessage('getPrices', {
      symbols: ['RAMA', 'ETH'],
      currency: 'usd'
    });

    if (result.success) {
      tokenPrices = result.prices;
      updatePriceDisplay();
    }
  } catch (error) {
    console.error('Error fetching prices:', error);
  }
}

/**
 * Update price display in UI
 */
function updatePriceDisplay() {
  const symbol = currentNetwork?.symbol || 'RAMA';
  const price = tokenPrices[symbol] || 0;
  const balanceStr = elements.balanceValue?.textContent || '0';
  
  // Skip if balance is still loading
  if (balanceStr === '...' || balanceStr === '') {
    return;
  }
  
  const balance = parseFloat(balanceStr) || 0;
  const usdValue = balance * price;
  
  // Format USD value nicely
  let formattedUsd;
  if (usdValue >= 1000000) {
    formattedUsd = (usdValue / 1000000).toFixed(2) + 'M';
  } else if (usdValue >= 1000) {
    formattedUsd = usdValue.toFixed(2);
  } else if (usdValue >= 1) {
    formattedUsd = usdValue.toFixed(2);
  } else {
    formattedUsd = usdValue.toFixed(5);
  }

  // Update main balance USD value
  const balanceUsd = document.getElementById('balance-usd');
  if (balanceUsd) {
    balanceUsd.textContent = `≈ $${formattedUsd} USD`;
  }

  // Update native token value
  const nativeTokenValue = document.getElementById('native-token-value');
  if (nativeTokenValue) {
    nativeTokenValue.textContent = `$${formattedUsd}`;
  }
}

/**
 * Get price for a specific token
 */
async function getTokenPrice(symbol) {
  try {
    const result = await sendMessage('getPrice', { symbol, currency: 'usd' });
    if (result.success) {
      tokenPrices[symbol] = result.price;
      return result.price;
    }
  } catch (error) {
    console.error('Error getting price:', error);
  }
  return 0;
}

// Refresh prices periodically
setInterval(fetchPrices, 60000); // Every minute
// ============================================
// NETWORK MANAGEMENT
// ============================================

// ============================================
// ALL PRE-BUILT NETWORKS
// Complete list matching Android app networks
// Users can enable/disable these from settings
// ============================================
const ALL_BUILTIN_NETWORKS = {
  // Ramestta (Primary - Always enabled)
  ramestta_mainnet: { name: 'Ramestta Mainnet', chainId: '0x55a', rpcUrl: 'https://blockchain.ramestta.com/', symbol: 'RAMA', explorer: 'https://ramascan.com', category: 'ramestta', isDefault: true },
  ramestta_testnet: { name: 'Ramestta Testnet', chainId: '0x559', rpcUrl: 'https://testnet.ramestta.com/', symbol: 'RAMA', explorer: 'https://testnet.ramascan.com', category: 'ramestta', isDefault: true },
  
  // Ethereum
  ethereum_mainnet: { name: 'Ethereum Mainnet', chainId: '0x1', rpcUrl: 'https://eth.llamarpc.com', symbol: 'ETH', explorer: 'https://etherscan.io', category: 'ethereum' },
  ethereum_classic: { name: 'Ethereum Classic', chainId: '0x3d', rpcUrl: 'https://www.ethercluster.com/etc', symbol: 'ETC', explorer: 'https://blockscout.com/etc/mainnet', category: 'ethereum' },
  sepolia_testnet: { name: 'Sepolia Testnet', chainId: '0xaa36a7', rpcUrl: 'https://rpc.sepolia.org', symbol: 'ETH', explorer: 'https://sepolia.etherscan.io', category: 'ethereum', isTestnet: true },
  holesky_testnet: { name: 'Holesky Testnet', chainId: '0x4268', rpcUrl: 'https://rpc.holesky.ethpandaops.io', symbol: 'ETH', explorer: 'https://holesky.etherscan.io', category: 'ethereum', isTestnet: true },
  
  // Polygon
  polygon_mainnet: { name: 'Polygon Mainnet', chainId: '0x89', rpcUrl: 'https://polygon.llamarpc.com', symbol: 'MATIC', explorer: 'https://polygonscan.com', category: 'polygon' },
  polygon_amoy: { name: 'Polygon Amoy Testnet', chainId: '0x13882', rpcUrl: 'https://rpc-amoy.polygon.technology', symbol: 'MATIC', explorer: 'https://amoy.polygonscan.com', category: 'polygon', isTestnet: true },
  
  // Binance Smart Chain
  binance_mainnet: { name: 'BNB Smart Chain', chainId: '0x38', rpcUrl: 'https://bsc-dataseed.binance.org', symbol: 'BNB', explorer: 'https://bscscan.com', category: 'binance' },
  binance_testnet: { name: 'BNB Chain Testnet', chainId: '0x61', rpcUrl: 'https://data-seed-prebsc-1-s1.binance.org:8545', symbol: 'tBNB', explorer: 'https://testnet.bscscan.com', category: 'binance', isTestnet: true },
  
  // Avalanche
  avalanche_mainnet: { name: 'Avalanche C-Chain', chainId: '0xa86a', rpcUrl: 'https://api.avax.network/ext/bc/C/rpc', symbol: 'AVAX', explorer: 'https://snowtrace.io', category: 'avalanche' },
  avalanche_fuji: { name: 'Avalanche Fuji Testnet', chainId: '0xa869', rpcUrl: 'https://api.avax-test.network/ext/bc/C/rpc', symbol: 'AVAX', explorer: 'https://testnet.snowtrace.io', category: 'avalanche', isTestnet: true },
  
  // Arbitrum
  arbitrum_mainnet: { name: 'Arbitrum One', chainId: '0xa4b1', rpcUrl: 'https://arb1.arbitrum.io/rpc', symbol: 'ETH', explorer: 'https://arbiscan.io', category: 'arbitrum' },
  arbitrum_sepolia: { name: 'Arbitrum Sepolia', chainId: '0x66eee', rpcUrl: 'https://arbitrum-sepolia.drpc.org', symbol: 'ETH', explorer: 'https://sepolia.arbiscan.io', category: 'arbitrum', isTestnet: true },
  
  // Optimism
  optimism_mainnet: { name: 'Optimism', chainId: '0xa', rpcUrl: 'https://mainnet.optimism.io', symbol: 'ETH', explorer: 'https://optimistic.etherscan.io', category: 'optimism' },
  
  // Base
  base_mainnet: { name: 'Base', chainId: '0x2105', rpcUrl: 'https://base-rpc.publicnode.com', symbol: 'ETH', explorer: 'https://basescan.org', category: 'base' },
  base_sepolia: { name: 'Base Sepolia', chainId: '0x14a34', rpcUrl: 'https://sepolia.base.org', symbol: 'ETH', explorer: 'https://sepolia.basescan.org', category: 'base', isTestnet: true },
  
  // Fantom
  fantom_mainnet: { name: 'Fantom Opera', chainId: '0xfa', rpcUrl: 'https://rpcapi.fantom.network', symbol: 'FTM', explorer: 'https://ftmscan.com', category: 'fantom' },
  fantom_testnet: { name: 'Fantom Testnet', chainId: '0xfa2', rpcUrl: 'https://rpc.testnet.fantom.network', symbol: 'FTM', explorer: 'https://testnet.ftmscan.com', category: 'fantom', isTestnet: true },
  
  // Gnosis
  gnosis_mainnet: { name: 'Gnosis Chain', chainId: '0x64', rpcUrl: 'https://rpc.gnosischain.com', symbol: 'xDAI', explorer: 'https://gnosisscan.io', category: 'gnosis' },
  
  // Cronos
  cronos_mainnet: { name: 'Cronos Mainnet', chainId: '0x19', rpcUrl: 'https://evm.cronos.org', symbol: 'CRO', explorer: 'https://cronoscan.com', category: 'cronos' },
  cronos_testnet: { name: 'Cronos Testnet', chainId: '0x152', rpcUrl: 'https://evm-t3.cronos.org', symbol: 'tCRO', explorer: 'https://testnet.cronoscan.com', category: 'cronos', isTestnet: true },
  
  // Linea
  linea_mainnet: { name: 'Linea Mainnet', chainId: '0xe708', rpcUrl: 'https://rpc.linea.build', symbol: 'ETH', explorer: 'https://lineascan.build', category: 'linea' },
  linea_testnet: { name: 'Linea Sepolia', chainId: '0xe705', rpcUrl: 'https://rpc.sepolia.linea.build', symbol: 'ETH', explorer: 'https://sepolia.lineascan.build', category: 'linea', isTestnet: true },
  
  // Mantle
  mantle_mainnet: { name: 'Mantle', chainId: '0x1388', rpcUrl: 'https://rpc.mantle.xyz', symbol: 'MNT', explorer: 'https://explorer.mantle.xyz', category: 'mantle' },
  mantle_testnet: { name: 'Mantle Sepolia', chainId: '0x138b', rpcUrl: 'https://rpc.sepolia.mantle.xyz', symbol: 'MNT', explorer: 'https://sepolia.mantlescan.xyz', category: 'mantle', isTestnet: true },
  
  // Klaytn/Kaia
  klaytn_mainnet: { name: 'Kaia Mainnet', chainId: '0x2019', rpcUrl: 'https://klaytn.blockpi.network/v1/rpc/public', symbol: 'KAIA', explorer: 'https://scope.klaytn.com', category: 'klaytn' },
  klaytn_baobab: { name: 'Kaia Kairos Testnet', chainId: '0x3e9', rpcUrl: 'https://klaytn-baobab.blockpi.network/v1/rpc/public', symbol: 'KAIA', explorer: 'https://baobab.scope.klaytn.com', category: 'klaytn', isTestnet: true },
  
  // Aurora
  aurora_mainnet: { name: 'Aurora Mainnet', chainId: '0x4e454152', rpcUrl: 'https://mainnet.aurora.dev', symbol: 'ETH', explorer: 'https://aurorascan.dev', category: 'aurora' },
  aurora_testnet: { name: 'Aurora Testnet', chainId: '0x4e454153', rpcUrl: 'https://testnet.aurora.dev', symbol: 'ETH', explorer: 'https://testnet.aurorascan.dev', category: 'aurora', isTestnet: true },
  
  // IoTeX
  iotex_mainnet: { name: 'IoTeX Mainnet', chainId: '0x1251', rpcUrl: 'https://babel-api.mainnet.iotex.io', symbol: 'IOTX', explorer: 'https://iotexscan.io', category: 'iotex' },
  iotex_testnet: { name: 'IoTeX Testnet', chainId: '0x1252', rpcUrl: 'https://babel-api.testnet.iotex.io', symbol: 'IOTX', explorer: 'https://testnet.iotexscan.io', category: 'iotex', isTestnet: true },
  
  // Rootstock
  rootstock_mainnet: { name: 'Rootstock Mainnet', chainId: '0x1e', rpcUrl: 'https://public-node.rsk.co', symbol: 'RBTC', explorer: 'https://explorer.rsk.co', category: 'rootstock' },
  rootstock_testnet: { name: 'Rootstock Testnet', chainId: '0x1f', rpcUrl: 'https://public-node.testnet.rsk.co', symbol: 'tRBTC', explorer: 'https://explorer.testnet.rsk.co', category: 'rootstock', isTestnet: true },
  
  // OKX Chain
  okx_mainnet: { name: 'OKXChain Mainnet', chainId: '0x42', rpcUrl: 'https://exchainrpc.okex.org', symbol: 'OKT', explorer: 'https://www.oklink.com/oktc', category: 'okx' },
  
  // Palm
  palm_mainnet: { name: 'Palm Mainnet', chainId: '0x2a15c308d', rpcUrl: 'https://palm-mainnet.public.blastapi.io', symbol: 'PALM', explorer: 'https://explorer.palm.io', category: 'palm' },
  palm_testnet: { name: 'Palm Testnet', chainId: '0x2a15c3083', rpcUrl: 'https://palm-testnet.public.blastapi.io', symbol: 'PALM', explorer: 'https://explorer.palm-uat.xyz', category: 'palm', isTestnet: true },
  
  // Milkomeda
  milkomeda_c1: { name: 'Milkomeda Cardano', chainId: '0x7d1', rpcUrl: 'https://rpc-mainnet-cardano-evm.c1.milkomeda.com', symbol: 'milkADA', explorer: 'https://explorer-mainnet-cardano-evm.c1.milkomeda.com', category: 'milkomeda' },
  
  // Mint
  mint_mainnet: { name: 'Mint Mainnet', chainId: '0xb9', rpcUrl: 'https://global.rpc.mintchain.io', symbol: 'ETH', explorer: 'https://explorer.mintchain.io', category: 'mint' },
  mint_sepolia: { name: 'Mint Sepolia', chainId: '0x697', rpcUrl: 'https://sepolia-testnet-rpc.mintchain.io', symbol: 'ETH', explorer: 'https://sepolia-testnet-explorer.mintchain.io', category: 'mint', isTestnet: true }
};

// Default enabled networks (only Ramestta when wallet is first created)
const DEFAULT_ENABLED_NETWORKS = ['ramestta_mainnet', 'ramestta_testnet'];

// Current enabled networks (loaded from storage)
let enabledNetworks = [...DEFAULT_ENABLED_NETWORKS];

// Legacy BUILTIN_NETWORKS for backwards compatibility
const BUILTIN_NETWORKS = Object.entries(ALL_BUILTIN_NETWORKS)
  .filter(([key]) => DEFAULT_ENABLED_NETWORKS.includes(key))
  .map(([key, network]) => ({ ...network, key }));

/**
 * Load enabled networks from storage
 */
async function loadEnabledNetworks() {
  try {
    const result = await sendMessage('getEnabledNetworks');
    if (result.success && result.enabledNetworks) {
      enabledNetworks = result.enabledNetworks;
    }
  } catch (error) {
    console.log('Using default enabled networks');
    enabledNetworks = [...DEFAULT_ENABLED_NETWORKS];
  }
}

/**
 * Load networks list with enable/disable functionality
 */
async function loadNetworksList() {
  try {
    // Load enabled networks first
    await loadEnabledNetworks();
    
    const builtinContainer = document.getElementById('builtin-networks-list');
    if (builtinContainer) {
      // Group networks by category
      const categories = {};
      Object.entries(ALL_BUILTIN_NETWORKS).forEach(([key, network]) => {
        const cat = network.category || 'other';
        if (!categories[cat]) categories[cat] = [];
        categories[cat].push({ key, ...network });
      });
      
      // Define category order and display names with icon URLs
      const categoryOrder = [
        { key: 'ramestta', name: 'Ramestta (Primary)', isPrimary: true, icon: 'icons/rama.png' },
        { key: 'ethereum', name: 'Ethereum', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png' },
        { key: 'polygon', name: 'Polygon', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/polygon/info/logo.png' },
        { key: 'binance', name: 'BNB Smart Chain', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/info/logo.png' },
        { key: 'arbitrum', name: 'Arbitrum', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/arbitrum/info/logo.png' },
        { key: 'optimism', name: 'Optimism', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/optimism/info/logo.png' },
        { key: 'base', name: 'Base', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/base/info/logo.png' },
        { key: 'avalanche', name: 'Avalanche', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/avalanchec/info/logo.png' },
        { key: 'fantom', name: 'Fantom', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/fantom/info/logo.png' },
        { key: 'linea', name: 'Linea', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/linea/info/logo.png' },
        { key: 'mantle', name: 'Mantle', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/mantle/info/logo.png' },
        { key: 'gnosis', name: 'Gnosis', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/xdai/info/logo.png' },
        { key: 'cronos', name: 'Cronos', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/cronos/info/logo.png' },
        { key: 'klaytn', name: 'Klaytn/Kaia', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/klaytn/info/logo.png' },
        { key: 'aurora', name: 'Aurora', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/aurora/info/logo.png' },
        { key: 'iotex', name: 'IoTeX', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/iotex/info/logo.png' },
        { key: 'rootstock', name: 'Rootstock', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/rootstock/info/logo.png' },
        { key: 'okx', name: 'OKX Chain', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/okc/info/logo.png' },
        { key: 'palm', name: 'Palm', icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/palm/info/logo.png' },
        { key: 'milkomeda', name: 'Milkomeda', icon: 'https://raw.githubusercontent.com/milkomeda-com/assets/main/milkomeda-logo.png' },
        { key: 'mint', name: 'Mint', icon: 'https://mintchain.io/favicon.png' }
      ];
      
      let html = '';
      
      // Show all networks grouped by category
      categoryOrder.forEach(catInfo => {
        const networks = categories[catInfo.key];
        if (!networks || networks.length === 0) return;
        
        const categoryClass = catInfo.isPrimary ? 'ramestta-category' : '';
        const emoji = getCategoryEmoji(catInfo.key);
        // Convert local icons to extension URL
        let catIcon = catInfo.icon;
        if (catIcon && catIcon.startsWith('icons/')) {
          catIcon = chrome.runtime.getURL(catIcon);
        }
        
        html += `<div class="network-category ${categoryClass}">
          <h4>
            <img src="${catIcon}" alt="${catInfo.name}" class="category-icon" data-fallback-emoji="${emoji}">
            <span class="category-emoji" style="display:none;">${emoji}</span>
            ${catInfo.name}
          </h4>
          <div class="network-list">
            ${networks.map(network => createNetworkItemHtml(network, enabledNetworks.includes(network.key), catInfo.isPrimary)).join('')}
          </div>
        </div>`;
      });
      
      builtinContainer.innerHTML = html;
      
      // Add error handlers for category icons
      builtinContainer.querySelectorAll('.category-icon').forEach(img => {
        img.addEventListener('error', function() {
          this.style.display = 'none';
          if (this.nextElementSibling) this.nextElementSibling.style.display = 'inline';
        });
      });
      
      // Add error handlers for network icons
      builtinContainer.querySelectorAll('.network-icon-img').forEach(img => {
        img.addEventListener('error', function() {
          this.style.display = 'none';
          if (this.nextElementSibling) this.nextElementSibling.style.display = 'flex';
        });
      });

      // Add click handlers for enabling/disabling networks
      builtinContainer.querySelectorAll('.network-toggle').forEach(toggle => {
        toggle.addEventListener('click', async (e) => {
          e.stopPropagation();
          const networkKey = toggle.dataset.networkKey;
          const isEnabled = toggle.dataset.enabled === 'true';
          
          if (isEnabled) {
            await disableNetworkFromList(networkKey);
          } else {
            await enableNetworkFromList(networkKey);
          }
        });
      });
      
      // Add click handlers for selecting network
      builtinContainer.querySelectorAll('.network-item').forEach(item => {
        item.addEventListener('click', (e) => {
          if (!e.target.classList.contains('network-toggle')) {
            const networkKey = item.dataset.networkKey;
            if (enabledNetworks.includes(networkKey)) {
              selectNetworkByKey(networkKey);
            } else {
              showToast('Enable this network first', 'info');
            }
          }
        });
      });
    }

    // Load custom networks
    const result = await sendMessage('getCustomNetworks');
    const customContainer = document.getElementById('custom-networks-list');
    if (customContainer) {
      if (result.success && result.networks.length > 0) {
        customContainer.innerHTML = result.networks.map(network => {
          const chainIdHex = network.chainIdHex || ('0x' + network.chainId.toString(16));
          const chainIdNum = typeof network.chainId === 'number' ? network.chainId : parseInt(chainIdHex, 16);
          const isActive = currentNetwork?.chainId === chainIdNum || currentNetwork?.chainIdHex === chainIdHex;
          return `
          <div class="network-item custom ${isActive ? 'active' : ''}" data-chain-id="${chainIdHex}">
            <div class="network-icon">🔗</div>
            <div class="network-info">
              <div class="network-name">${network.name}</div>
              <div class="network-details">${network.symbol} • Chain ID: ${chainIdNum}</div>
            </div>
            <div class="network-status">
              ${isActive ? '<span class="network-checkmark">✓</span>' : ''}
              <button class="network-delete" data-remove-network="${network.key || chainIdHex}">Remove</button>
            </div>
          </div>
        `}).join('');

        // Add event listeners for custom network delete buttons
        customContainer.querySelectorAll('[data-remove-network]').forEach(btn => {
          btn.addEventListener('click', (e) => {
            e.stopPropagation();
            window.removeNetwork(btn.dataset.removeNetwork);
          });
        });

        customContainer.querySelectorAll('.network-item').forEach(item => {
          item.addEventListener('click', (e) => {
            if (!e.target.classList.contains('network-delete')) {
              selectNetwork(item.dataset.chainId);
            }
          });
        });
      } else {
        customContainer.innerHTML = '<p class="empty-state">No custom networks added</p>';
      }
    }
  } catch (error) {
    console.error('Error loading networks:', error);
    showToast('Error loading networks', 'error');
  }
}

/**
 * Create HTML for a network item
 */
function createNetworkItemHtml(network, isEnabled, isRamestta) {
  const isActive = currentNetwork?.chainIdHex === network.chainId || currentNetwork?.chainId === network.chainId;
  const testnetBadge = network.isTestnet ? '<span class="testnet-badge">Testnet</span>' : '';
  let iconUrl = network.icon || getNetworkIconUrl(network.category);
  // For local icons, use chrome.runtime.getURL
  if (iconUrl && iconUrl.startsWith('icons/')) {
    iconUrl = chrome.runtime.getURL(iconUrl);
  }
  
  return `
    <div class="network-item ${isActive ? 'active' : ''} ${isEnabled ? 'enabled' : 'disabled'}" 
         data-network-key="${network.key}" data-chain-id="${network.chainId}">
      <div class="network-icon">
        <img src="${iconUrl}" alt="${network.name}" class="network-icon-img" data-fallback="${getCategoryEmoji(network.category)}" />
        <span class="network-icon-fallback" style="display:none;">${getCategoryEmoji(network.category)}</span>
      </div>
      <div class="network-info">
        <div class="network-name">${network.name} ${testnetBadge}</div>
        <div class="network-details">${network.symbol} • Chain ID: ${parseInt(network.chainId, 16)}</div>
      </div>
      <div class="network-status">
        ${isActive ? '<span class="network-checkmark">✓</span>' : ''}
        ${!isRamestta ? `<button class="network-toggle ${isEnabled ? 'enabled' : ''}" 
                                  data-network-key="${network.key}" 
                                  data-enabled="${isEnabled}">
          ${isEnabled ? '✓ Enabled' : '+ Add'}
        </button>` : '<span class="primary-badge">Primary</span>'}
      </div>
    </div>
  `;
}

/**
 * Get emoji fallback for network category
 */
function getCategoryEmoji(category) {
  const emojis = {
    ramestta: '🏠',
    ethereum: '💎',
    polygon: '💜',
    binance: '💛',
    avalanche: '🔺',
    arbitrum: '🔵',
    optimism: '🔴',
    base: '🔷',
    fantom: '👻',
    gnosis: '🦉',
    cronos: '🔷',
    linea: '📐',
    mantle: '🟢',
    klaytn: '🟡',
    aurora: '🌅',
    iotex: '🔌',
    rootstock: '🟠',
    okx: '⭕',
    palm: '🌴',
    milkomeda: '🥛',
    mint: '🌿'
  };
  return emojis[category] || '🌐';
}

/**
 * Get icon URL for network category
 */
function getNetworkIconUrl(category) {
  const icons = {
    ramestta: 'icons/rama.png',
    ethereum: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png',
    polygon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/polygon/info/logo.png',
    binance: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/info/logo.png',
    avalanche: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/avalanchec/info/logo.png',
    arbitrum: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/arbitrum/info/logo.png',
    optimism: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/optimism/info/logo.png',
    base: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/base/info/logo.png',
    fantom: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/fantom/info/logo.png',
    gnosis: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/xdai/info/logo.png',
    cronos: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/cronos/info/logo.png',
    linea: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/linea/info/logo.png',
    mantle: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/mantle/info/logo.png',
    klaytn: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/klaytn/info/logo.png',
    aurora: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/aurora/info/logo.png',
    iotex: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/iotex/info/logo.png',
    rootstock: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/rootstock/info/logo.png',
    okx: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/okc/info/logo.png',
    palm: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/palm/info/logo.png',
    milkomeda: 'https://raw.githubusercontent.com/milkomeda-com/assets/main/milkomeda-logo.png',
    mint: 'https://mintchain.io/favicon.png'
  };
  const iconPath = icons[category] || 'icons/rama.png';
  // For local icons, use chrome.runtime.getURL to get the proper extension URL
  if (iconPath.startsWith('icons/')) {
    return chrome.runtime.getURL(iconPath);
  }
  return iconPath;
}

/**
 * Toggle network category visibility
 */
window.toggleNetworkCategory = function(header) {
  const content = header.nextElementSibling;
  const arrow = header.querySelector('.collapse-arrow');
  if (content.style.display === 'none') {
    content.style.display = 'block';
    arrow.textContent = '▲';
  } else {
    content.style.display = 'none';
    arrow.textContent = '▼';
  }
};

/**
 * Enable a network from the pre-built list
 */
async function enableNetworkFromList(networkKey) {
  try {
    const result = await sendMessage('enableBuiltinNetwork', { networkKey });
    if (result.success) {
      enabledNetworks = result.enabledNetworks;
      showToast(`${ALL_BUILTIN_NETWORKS[networkKey]?.name || networkKey} enabled`, 'success');
      await loadNetworksList();
      await loadNetworkDropdown();
    } else {
      showToast(result.error || 'Failed to enable network', 'error');
    }
  } catch (error) {
    showToast('Error enabling network', 'error');
  }
}

/**
 * Disable a network from the list
 */
async function disableNetworkFromList(networkKey) {
  // Don't allow disabling Ramestta
  if (networkKey === 'ramestta_mainnet') {
    showToast('Cannot disable primary network', 'error');
    return;
  }
  
  try {
    const result = await sendMessage('disableBuiltinNetwork', { networkKey });
    if (result.success) {
      enabledNetworks = result.enabledNetworks;
      showToast(`${ALL_BUILTIN_NETWORKS[networkKey]?.name || networkKey} disabled`, 'success');
      await loadNetworksList();
      await loadNetworkDropdown();
    } else {
      showToast(result.error || 'Failed to disable network', 'error');
    }
  } catch (error) {
    showToast('Error disabling network', 'error');
  }
}

/**
 * Select a network by its key
 */
async function selectNetworkByKey(networkKey) {
  try {
    const network = ALL_BUILTIN_NETWORKS[networkKey];
    if (network) {
      await selectNetwork(network.chainId);
    }
  } catch (error) {
    showToast('Error switching network', 'error');
  }
}

/**
 * Select a network
 */
async function selectNetwork(chainId) {
  try {
    // Update the network select dropdown
    const networkSelect = document.getElementById('network-select');
    if (networkSelect) {
      networkSelect.value = chainId;
      await handleNetworkChange({ target: networkSelect });
    }
    showToast('Network changed', 'success');
    showScreen('main');
    await loadMainScreen();
  } catch (error) {
    showToast('Error switching network', 'error');
  }
}

/**
 * Remove a custom network
 */
window.removeNetwork = async function(networkKeyOrChainId) {
  showConfirmModal({
    title: 'Remove Network',
    message: 'Are you sure you want to remove this custom network?',
    confirmText: 'Remove',
    isDanger: true,
    onConfirm: async () => {
      try {
        // networkKeyOrChainId can be either the network key (custom_1370) or chainId (0x55a)
        const isKey = networkKeyOrChainId.startsWith('custom_');
        const result = await sendMessage('removeCustomNetwork', { 
          networkKey: isKey ? networkKeyOrChainId : null,
          chainId: isKey ? null : networkKeyOrChainId 
        });
        if (result.success) {
          showToast('Network removed', 'success');
          await loadNetworksList();
        } else {
          showToast(result.error || 'Failed to remove network', 'error');
        }
      } catch (error) {
        showToast('Error removing network', 'error');
      }
    }
  });
};

/**
 * Save a custom network
 */
async function handleSaveNetwork() {
  const name = document.getElementById('network-name')?.value?.trim();
  const rpcUrl = document.getElementById('network-rpc')?.value?.trim();
  const chainIdInput = document.getElementById('network-chain-id')?.value?.trim();
  const symbol = document.getElementById('network-symbol')?.value?.trim();
  const explorer = document.getElementById('network-explorer')?.value?.trim() || '';

  // Validation
  if (!name || !rpcUrl || !chainIdInput || !symbol) {
    showToast('Please fill in all required fields', 'error');
    return;
  }

  // Convert chain ID to hex
  const chainId = '0x' + parseInt(chainIdInput).toString(16);

  try {
    const result = await sendMessage('addCustomNetwork', {
      name,
      rpcUrl,
      chainId,
      symbol,
      explorer
    });

    if (result.success) {
      showToast('Network added successfully', 'success');
      // Clear the form
      document.getElementById('network-name').value = '';
      document.getElementById('network-rpc').value = '';
      document.getElementById('network-chain-id').value = '';
      document.getElementById('network-symbol').value = '';
      document.getElementById('network-explorer').value = '';
      
      // Go back to networks list
      await loadNetworksList();
      showScreen('networks');
    } else {
      showToast(result.error || 'Failed to add network', 'error');
    }
  } catch (error) {
    showToast('Error adding network', 'error');
  }
}

// ============================================
// PASSWORD MANAGEMENT
// ============================================

/**
 * Handle password change
 */
async function handleChangePassword() {
  const currentPassword = document.getElementById('current-password')?.value;
  const newPassword = document.getElementById('new-password')?.value;
  const confirmPassword = document.getElementById('confirm-new-password')?.value;

  // Validation
  if (!currentPassword || !newPassword || !confirmPassword) {
    showToast('Please fill in all fields', 'error');
    return;
  }

  if (newPassword !== confirmPassword) {
    showToast('New passwords do not match', 'error');
    return;
  }

  if (newPassword.length < 8) {
    showToast('Password must be at least 8 characters', 'error');
    return;
  }

  try {
    const result = await sendMessage('changePassword', {
      currentPassword,
      newPassword
    });

    if (result.success) {
      showToast('Password changed successfully', 'success');
      // Clear the form
      document.getElementById('current-password').value = '';
      document.getElementById('new-password').value = '';
      document.getElementById('confirm-new-password').value = '';
      showScreen('settings');
    } else {
      showToast(result.error || 'Failed to change password', 'error');
    }
  } catch (error) {
    showToast('Error changing password', 'error');
  }
}

// ============================================
// CONNECTED SITES MANAGEMENT
// ============================================

/**
 * Load connected sites
 */
async function loadConnectedSites() {
  try {
    const result = await sendMessage('getConnectedSites');
    const container = document.getElementById('connected-sites-list');
    
    if (!container) return;

    if (result.success && result.sites.length > 0) {
      container.innerHTML = result.sites.map(site => `
        <div class="connected-site" data-origin="${site}">
          <div class="site-info">
            <div class="site-icon">🌐</div>
            <span class="site-url">${site}</span>
          </div>
          <button class="btn-disconnect" data-disconnect-site="${site}">Disconnect</button>
        </div>
      `).join('');
      
      // Add event listeners for disconnect buttons
      container.querySelectorAll('[data-disconnect-site]').forEach(btn => {
        btn.addEventListener('click', () => {
          window.disconnectSite(btn.dataset.disconnectSite);
        });
      });
    } else {
      container.innerHTML = '<p class="empty-state">No connected sites</p>';
    }
  } catch (error) {
    console.error('Error loading connected sites:', error);
  }
}

/**
 * Disconnect a site
 */
window.disconnectSite = async function(origin) {
  try {
    const result = await sendMessage('disconnectSite', { origin });
    if (result.success) {
      showToast('Site disconnected', 'success');
      await loadConnectedSites();
    } else {
      showToast(result.error || 'Failed to disconnect', 'error');
    }
  } catch (error) {
    showToast('Error disconnecting site', 'error');
  }
};

// Load connected sites when settings are opened
const originalShowScreen = showScreen;
window.showScreen = function(screenName) {
  originalShowScreen(screenName);
  
  // Reset export screens when navigating away
  if (screenName === 'settings') {
    loadConnectedSites();
  } else if (screenName === 'export-key') {
    document.getElementById('export-key-auth')?.classList.remove('hidden');
    document.getElementById('export-key-display')?.classList.add('hidden');
    document.getElementById('export-key-password').value = '';
    document.getElementById('private-key-value').textContent = '';
  } else if (screenName === 'export-seed') {
    document.getElementById('export-seed-auth')?.classList.remove('hidden');
    document.getElementById('export-seed-display')?.classList.add('hidden');
    document.getElementById('export-seed-password').value = '';
    document.getElementById('export-seed-words').innerHTML = '';
  }
};

// ============================================
// ACCOUNT MANAGEMENT
// ============================================

/**
 * Load and display all accounts in Android-style wallet hierarchy
 */
async function loadAccountsList() {
  try {
    const result = await sendMessage('getAccounts');
    const container = document.getElementById('accounts-list');
    
    if (!container) return;
    
    if (!result.success) {
      console.error('Failed to load accounts:', result.error);
      // Show empty state if no accounts
      container.innerHTML = '<p class="empty-state">No accounts yet. Create a master wallet to get started.</p>';
      return;
    }
    
    const { accounts, masterWallets = [] } = result;
    
    console.log('loadAccountsList - received accounts:', accounts.length);
    console.log('loadAccountsList - received masterWallets:', masterWallets.length);
    console.log('loadAccountsList - account types:', accounts.map(a => a.type));
    
    let html = '';
    
    // Group accounts by master wallet
    const masterWalletAccounts = {};
    const importedAccounts = [];
    const watchAccounts = [];
    
    accounts.forEach((acc, idx) => {
      acc.index = idx; // Store original index for switching
      if (acc.type === 'watch') {
        watchAccounts.push(acc);
      } else if (acc.masterWalletId) {
        if (!masterWalletAccounts[acc.masterWalletId]) {
          masterWalletAccounts[acc.masterWalletId] = [];
        }
        masterWalletAccounts[acc.masterWalletId].push(acc);
      } else if (acc.type === 'imported' || acc.type === 'imported-seed') {
        importedAccounts.push(acc);
      } else {
        // Legacy derived accounts without masterWalletId - treat as imported
        importedAccounts.push(acc);
      }
    });
    
    console.log('loadAccountsList - watch accounts:', watchAccounts.length);
    console.log('loadAccountsList - imported accounts:', importedAccounts.length);
    console.log('loadAccountsList - master wallet account groups:', Object.keys(masterWalletAccounts).length);
    
    // Render Master Wallets with their accounts nested inside
    masterWallets.forEach((mw, mwIdx) => {
      const mwAccounts = masterWalletAccounts[mw.id] || [];
      
      // Sort accounts by accountIndex for proper order
      mwAccounts.sort((a, b) => (a.accountIndex || 0) - (b.accountIndex || 0));
      
      // Check if this master wallet has an active account (should be expanded)
      const hasActiveAccount = mwAccounts.some(acc => acc.isActive);
      // Default collapsed, but expand if it has the active account
      const isCollapsed = !hasActiveAccount;
      
      html += `
        <div class="master-wallet-group ${isCollapsed ? 'collapsed' : ''}" data-master-id="${mw.id}">
          <div class="master-wallet-header">
            <button class="master-wallet-toggle" data-master-id="${mw.id}" title="Expand/Collapse">
              <span class="toggle-icon">${isCollapsed ? '▶' : '▼'}</span>
            </button>
            <div class="master-wallet-icon">🏦</div>
            <div class="master-wallet-info">
              <div class="master-wallet-name">${mw.name}</div>
              <div class="master-wallet-meta">${mwAccounts.length} account${mwAccounts.length !== 1 ? 's' : ''}</div>
            </div>
            <button class="master-wallet-menu-btn" data-master-id="${mw.id}" title="Master Wallet Options">⋮</button>
            <span class="master-wallet-badge">HD</span>
          </div>
          <div class="master-wallet-accounts" style="${isCollapsed ? 'display: none;' : ''}">
            ${mwAccounts.map((acc, accIdx) => `
              <div class="account-item ${acc.isActive ? 'active' : ''}" data-index="${acc.index}" data-address="${acc.address}" data-name="${acc.name}" data-type="${acc.type || 'derived'}" data-master-id="${mw.id}" data-account-index="${acc.accountIndex}">
                <div class="account-avatar" style="background: linear-gradient(135deg, #4ade80, #22c55e);">${acc.name.charAt(0).toUpperCase()}</div>
                <div class="account-info-main">
                  <div class="account-name-text">${acc.name}</div>
                  <div class="account-address-text">${formatAddress(acc.address)}</div>
                </div>
                <button class="account-menu-btn" data-index="${acc.index}" data-type="${acc.type || 'derived'}" data-master-id="${mw.id}" title="Account Options">⋮</button>
                ${acc.isActive ? '<span class="account-checkmark">✓</span>' : ''}
              </div>
            `).join('')}
          </div>
        </div>
      `;
    });
    
    // Render Imported Accounts section (only if there are any)
    if (importedAccounts.length > 0) {
      html += `
        <div class="imported-accounts-section">
          <div class="imported-accounts-header">
            <span class="section-icon">🔑</span>
            <span class="section-title">Imported Accounts</span>
          </div>
          <div class="imported-accounts-list">
            ${importedAccounts.map(acc => `
              <div class="account-item ${acc.isActive ? 'active' : ''}" data-index="${acc.index}" data-address="${acc.address}" data-name="${acc.name}" data-type="${acc.type || 'imported'}">
                <div class="account-avatar" style="background: linear-gradient(135deg, #f59e0b, #d97706);">${acc.name.charAt(0).toUpperCase()}</div>
                <div class="account-info-main">
                  <div class="account-name-text">${acc.name}</div>
                  <div class="account-address-text">${formatAddress(acc.address)}</div>
                </div>
                <button class="account-menu-btn" data-index="${acc.index}" data-type="${acc.type || 'imported'}" title="Account Options">⋮</button>
                ${acc.isActive ? '<span class="account-checkmark">✓</span>' : ''}
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }
    
    // Render Watch Wallets section (only if there are any)
    if (watchAccounts.length > 0) {
      html += `
        <div class="watch-accounts-section">
          <div class="watch-accounts-header">
            <span class="section-icon">👁</span>
            <span class="section-title">Watch Wallets</span>
          </div>
          <div class="watch-accounts-list">
            ${watchAccounts.map(acc => `
              <div class="account-item ${acc.isActive ? 'active' : ''}" data-index="${acc.index}" data-address="${acc.address}" data-name="${acc.name}" data-type="watch">
                <div class="account-avatar" style="background: linear-gradient(135deg, #a78bfa, #8b5cf6);">${acc.name.charAt(0).toUpperCase()}</div>
                <div class="account-info-main">
                  <div class="account-name-text">${acc.name} <span class="watch-badge">👁</span></div>
                  <div class="account-address-text">${formatAddress(acc.address)}</div>
                </div>
                <button class="account-menu-btn" data-index="${acc.index}" data-type="watch" title="Account Options">⋮</button>
                ${acc.isActive ? '<span class="account-checkmark">✓</span>' : ''}
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }
    
    if (!html) {
      html = `
        <div class="empty-wallet-state" style="text-align: center; padding: 40px 20px;">
          <div style="font-size: 48px; margin-bottom: 16px;">🏦</div>
          <h3 style="margin-bottom: 8px; color: var(--text-primary);">No Wallets Yet</h3>
          <p style="color: var(--text-muted); margin-bottom: 20px;">Create or import a wallet to get started</p>
        </div>
      `;
    }
    
    container.innerHTML = html;
    
    // Add event listeners for master wallet toggle (expand/collapse)
    container.querySelectorAll('.master-wallet-toggle').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const masterId = btn.dataset.masterId;
        const group = container.querySelector(`.master-wallet-group[data-master-id="${masterId}"]`);
        const accountsDiv = group?.querySelector('.master-wallet-accounts');
        const toggleIcon = btn.querySelector('.toggle-icon');
        
        if (group && accountsDiv) {
          const isCollapsed = group.classList.toggle('collapsed');
          accountsDiv.style.display = isCollapsed ? 'none' : 'block';
          toggleIcon.textContent = isCollapsed ? '▶' : '▼';
        }
      });
    });
    
    // Also allow clicking on the header (but not buttons) to toggle
    container.querySelectorAll('.master-wallet-header').forEach(header => {
      header.addEventListener('click', (e) => {
        // Don't toggle if clicking on menu button
        if (e.target.closest('.master-wallet-menu-btn') || e.target.closest('.master-wallet-toggle')) return;
        const toggleBtn = header.querySelector('.master-wallet-toggle');
        if (toggleBtn) toggleBtn.click();
      });
    });
    
    // Add event listeners for account items (click to switch)
    container.querySelectorAll('.account-item').forEach(item => {
      item.addEventListener('click', async (e) => {
        // Don't switch if clicking on menu button
        if (e.target.closest('.account-menu-btn')) return;
        const index = parseInt(item.dataset.index);
        await switchToAccount(index);
      });
    });
    
    // Add event listeners for account menu buttons
    container.querySelectorAll('.account-menu-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const index = parseInt(btn.dataset.index);
        const type = btn.dataset.type;
        const masterId = btn.dataset.masterId;
        const accountItem = btn.closest('.account-item');
        showAccountMenu(accountItem, index, type, masterId);
      });
    });
    
    // Add event listeners for master wallet menu buttons
    container.querySelectorAll('.master-wallet-menu-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const masterId = btn.dataset.masterId;
        showMasterWalletMenu(btn, masterId);
      });
    });
    
    // Reset search when accounts list is reloaded
    const searchInput = document.getElementById('search-wallet-input');
    const clearBtn = document.getElementById('clear-search-btn');
    if (searchInput) {
      searchInput.value = '';
    }
    if (clearBtn) {
      clearBtn.style.display = 'none';
    }
    
  } catch (error) {
    console.error('Error loading accounts:', error);
    showToast('Error loading accounts', 'error');
  }
}

/**
 * Filter wallets by address in the accounts list
 * @param {string} searchTerm - The address or part of address to search for
 */
function filterWalletsByAddress(searchTerm) {
  const container = document.getElementById('accounts-list');
  const clearBtn = document.getElementById('clear-search-btn');
  
  if (!container) return;
  
  // Show/hide clear button
  if (clearBtn) {
    clearBtn.style.display = searchTerm.trim() ? 'flex' : 'none';
  }
  
  // Normalize search term (lowercase)
  const normalizedSearch = searchTerm.toLowerCase().trim();
  
  // Remove existing search results section
  const existingResults = container.querySelector('.search-results-section');
  if (existingResults) existingResults.remove();
  
  // If empty search, show all and restore original state
  if (!normalizedSearch) {
    // Show all accounts
    container.querySelectorAll('.account-item').forEach(item => {
      item.classList.remove('search-hidden', 'search-match');
    });
    // Show all master wallet groups and sections
    container.querySelectorAll('.master-wallet-group, .imported-accounts-section, .watch-accounts-section').forEach(section => {
      section.classList.remove('search-hidden');
    });
    // Remove no results message if exists
    const noResultsEl = container.querySelector('.no-search-results');
    if (noResultsEl) noResultsEl.remove();
    return;
  }
  
  // Collect all matching accounts
  const matchingAccounts = [];
  
  container.querySelectorAll('.account-item').forEach(item => {
    const address = (item.dataset.address || '').toLowerCase();
    const name = (item.dataset.name || '').toLowerCase();
    
    // Match if address or name contains search term
    const isMatch = address.includes(normalizedSearch) || name.includes(normalizedSearch);
    
    if (isMatch) {
      matchingAccounts.push({
        element: item,
        address: item.dataset.address,
        name: item.dataset.name,
        index: item.dataset.index,
        type: item.dataset.type,
        masterId: item.dataset.masterId,
        isActive: item.classList.contains('active')
      });
    }
  });
  
  // Hide all original sections when searching
  container.querySelectorAll('.master-wallet-group, .imported-accounts-section, .watch-accounts-section').forEach(section => {
    section.classList.add('search-hidden');
  });
  
  // Remove no results message if exists
  let noResultsEl = container.querySelector('.no-search-results');
  
  if (matchingAccounts.length === 0) {
    // Show no results message
    if (!noResultsEl) {
      noResultsEl = document.createElement('div');
      noResultsEl.className = 'no-search-results';
      noResultsEl.innerHTML = `
        <div class="icon">🔍</div>
        <div class="message">No wallets found matching "${searchTerm}"</div>
      `;
      container.appendChild(noResultsEl);
    } else {
      noResultsEl.querySelector('.message').textContent = `No wallets found matching "${searchTerm}"`;
    }
  } else {
    if (noResultsEl) noResultsEl.remove();
    
    // Create search results section at the top
    const searchResultsSection = document.createElement('div');
    searchResultsSection.className = 'search-results-section';
    searchResultsSection.innerHTML = `
      <div class="search-results-header">
        <span class="section-icon">🔍</span>
        <span class="section-title">Search Results (${matchingAccounts.length})</span>
      </div>
      <div class="search-results-list"></div>
    `;
    
    const resultsList = searchResultsSection.querySelector('.search-results-list');
    
    // Add matching accounts to search results
    matchingAccounts.forEach(acc => {
      const avatarColor = acc.type === 'watch' 
        ? 'linear-gradient(135deg, #a78bfa, #8b5cf6)' 
        : acc.type === 'imported' || acc.type === 'imported-seed'
        ? 'linear-gradient(135deg, #f59e0b, #d97706)'
        : 'linear-gradient(135deg, #4ade80, #22c55e)';
      
      const watchBadge = acc.type === 'watch' ? '<span class="watch-badge">👁</span>' : '';
      const typeLabel = acc.type === 'watch' ? 'Watch' : acc.type === 'imported' || acc.type === 'imported-seed' ? 'Imported' : 'HD';
      
      const accountHtml = `
        <div class="account-item search-match ${acc.isActive ? 'active' : ''}" 
             data-index="${acc.index}" 
             data-address="${acc.address}" 
             data-name="${acc.name}" 
             data-type="${acc.type}"
             ${acc.masterId ? `data-master-id="${acc.masterId}"` : ''}>
          <div class="account-avatar" style="background: ${avatarColor};">${acc.name.charAt(0).toUpperCase()}</div>
          <div class="account-info-main">
            <div class="account-name-text">${acc.name} ${watchBadge}</div>
            <div class="account-address-text">${acc.address}</div>
          </div>
          <span class="search-type-badge">${typeLabel}</span>
          ${acc.isActive ? '<span class="account-checkmark">✓</span>' : ''}
        </div>
      `;
      resultsList.insertAdjacentHTML('beforeend', accountHtml);
    });
    
    // Insert at the beginning of the container
    container.insertBefore(searchResultsSection, container.firstChild);
    
    // Add click event listeners to search result items
    resultsList.querySelectorAll('.account-item').forEach(item => {
      item.addEventListener('click', async () => {
        const index = parseInt(item.dataset.index);
        await switchToAccount(index);
      });
    });
  }
}

/**
 * Render a wallet card (Android-style)
 */
function renderWalletCard(account, displayDetails, groupName, isMasterAccount = false, type = 'derived') {
  const avatarClass = type === 'imported' ? 'imported' : type === 'watch' ? 'watch' : '';
  const watchBadge = type === 'watch' ? '<span class="watch-badge">👁 Watch</span>' : '';
  
  return `
    <div class="wallet-card ${account.isActive ? 'active' : ''}" data-index="${account.index}">
      <button class="wallet-card-menu" data-menu="${account.index}">⋮</button>
      <div class="wallet-card-avatar ${avatarClass}">
        <div style="font-size: 20px;">${isMasterAccount ? '🏦' : type === 'watch' ? '👁' : '💎'}</div>
      </div>
      <div class="wallet-card-info">
        <div class="wallet-card-balance">$0.0000 ${watchBadge}</div>
        <div class="wallet-card-details">${displayDetails}</div>
      </div>
      <div class="wallet-card-change">+0.000%</div>
      <span class="wallet-card-arrow">›</span>
    </div>
  `;
}

/**
 * Attach event listeners to wallet cards
 */
function attachWalletCardEventListeners(container) {
  // Menu buttons
  container.querySelectorAll('[data-menu]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const index = parseInt(btn.dataset.menu);
      showWalletCardMenu(index, btn);
    });
  });

  // Click to switch account
  container.querySelectorAll('.wallet-card').forEach(item => {
    item.addEventListener('click', async (e) => {
      if (e.target.closest('.wallet-card-menu')) return;
      const index = parseInt(item.dataset.index);
      await switchToAccount(index);
    });
  });
}

/**
 * Show wallet card menu (rename, remove, etc.)
 */
function showWalletCardMenu(accountIndex, targetBtn) {
  // Remove any existing menu
  const existingMenu = document.querySelector('.wallet-card-popup-menu');
  if (existingMenu) existingMenu.remove();
  
  const menu = document.createElement('div');
  menu.className = 'wallet-card-popup-menu';
  menu.style.cssText = `
    position: absolute;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: 8px;
    padding: 8px 0;
    box-shadow: 0 4px 20px rgba(0,0,0,0.3);
    z-index: 1000;
    min-width: 140px;
  `;
  
  menu.innerHTML = `
    <div class="menu-item" data-action="rename" style="padding: 10px 16px; cursor: pointer; display: flex; align-items: center; gap: 10px;">
      <span>✏️</span> Rename
    </div>
    <div class="menu-item" data-action="copy" style="padding: 10px 16px; cursor: pointer; display: flex; align-items: center; gap: 10px;">
      <span>📋</span> Copy Address
    </div>
    <div class="menu-item delete" data-action="remove" style="padding: 10px 16px; cursor: pointer; display: flex; align-items: center; gap: 10px; color: var(--danger-color);">
      <span>🗑️</span> Remove
    </div>
  `;
  
  // Position menu
  const rect = targetBtn.getBoundingClientRect();
  menu.style.top = `${rect.bottom + 5}px`;
  menu.style.left = `${rect.left - 100}px`;
  
  document.body.appendChild(menu);
  
  // Add event listeners
  menu.querySelectorAll('.menu-item').forEach(item => {
    item.addEventListener('click', async () => {
      const action = item.dataset.action;
      menu.remove();
      
      if (action === 'rename') {
        const result = await sendMessage('getAccounts');
        if (result.success) {
          const acc = result.accounts[accountIndex];
          window.editAccountName(accountIndex, acc.name);
        }
      } else if (action === 'copy') {
        const result = await sendMessage('getAccounts');
        if (result.success) {
          const acc = result.accounts[accountIndex];
          navigator.clipboard.writeText(acc.address);
          showToast('Address copied!', 'success');
        }
      } else if (action === 'remove') {
        window.deleteAccount(accountIndex);
      }
    });
    
    item.addEventListener('mouseenter', () => {
      item.style.background = 'var(--bg-tertiary)';
    });
    item.addEventListener('mouseleave', () => {
      item.style.background = 'transparent';
    });
  });
  
  // Close menu on outside click
  const closeMenu = (e) => {
    if (!menu.contains(e.target)) {
      menu.remove();
      document.removeEventListener('click', closeMenu);
    }
  };
  setTimeout(() => document.addEventListener('click', closeMenu), 10);
}

/**
 * Render a single account item (legacy - kept for compatibility)
 */
function renderAccountItem(account) {
  const typeLabel = account.type === 'imported' ? 'Key' : 
                    account.type === 'imported-seed' ? 'Seed' :
                    account.type === 'watch' ? 'Watch' : '';
  
  return `
    <div class="account-item ${account.isActive ? 'active' : ''}" data-index="${account.index}">
      <div class="account-avatar">${account.name.charAt(0).toUpperCase()}</div>
      <div class="account-info-main">
        <div class="account-name-text">
          ${account.name}
          ${typeLabel ? `<span class="account-type-badge imported">${typeLabel}</span>` : ''}
        </div>
        <div class="account-address-text">${formatAddress(account.address)}</div>
      </div>
      ${account.isActive ? '<span class="account-checkmark">✓</span>' : ''}
      <div class="account-actions">
        <button class="account-action-btn" data-edit-account="${account.index}" data-account-name="${account.name}" title="Rename">✏️</button>
        ${account.index !== 0 || account.type === 'imported' ? `<button class="account-action-btn delete" data-delete-account="${account.index}" title="Remove">🗑️</button>` : ''}
      </div>
    </div>
  `;
}

/**
 * Attach event listeners to account items
 */
function attachAccountEventListeners(container) {
  // Edit buttons
  container.querySelectorAll('[data-edit-account]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      window.editAccountName(parseInt(btn.dataset.editAccount), btn.dataset.accountName);
    });
  });
  
  // Delete buttons
  container.querySelectorAll('[data-delete-account]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      window.deleteAccount(parseInt(btn.dataset.deleteAccount));
    });
  });

  // Click to switch account
  container.querySelectorAll('.account-item').forEach(item => {
    item.addEventListener('click', async (e) => {
      if (e.target.closest('.account-actions')) return;
      const index = parseInt(item.dataset.index);
      await switchToAccount(index);
    });
  });
}

/**
 * Switch to a different account
 */
async function switchToAccount(accountIndex) {
  try {
    const result = await sendMessage('switchAccount', { accountIndex });
    
    if (result.success) {
      currentWalletAddress = result.address;
      document.getElementById('account-name').textContent = result.name;
      showToast(`Switched to ${result.name}`, 'success');
      showScreen('main');
      await loadMainScreen();
    } else {
      showToast(result.error || 'Failed to switch account', 'error');
    }
  } catch (error) {
    showToast('Error switching account', 'error');
  }
}

/**
 * Derive a new account from HD wallet
 */
async function handleDeriveAccount() {
  // Show custom modal instead of browser prompt
  const modal = document.getElementById('create-account-modal');
  if (modal) {
    // Get next account number
    const result = await sendMessage('getAccounts');
    const hdAccountCount = result.accounts?.filter(a => a.type !== 'imported').length || 0;
    document.getElementById('new-account-name').value = `Account ${hdAccountCount + 1}`;
    modal.classList.add('show');
  }
}

/**
 * Confirm create account from modal
 */
async function confirmCreateAccount() {
  const name = document.getElementById('new-account-name')?.value?.trim();
  if (!name) {
    showToast('Please enter an account name', 'error');
    return;
  }

  try {
    const result = await sendMessage('addAccount', { name });
    
    if (result.success) {
      showToast(`Created ${name}`, 'success');
      document.getElementById('create-account-modal').classList.remove('show');
      document.getElementById('new-account-name').value = '';
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to create account', 'error');
    }
  } catch (error) {
    showToast('Error creating account', 'error');
  }
}

// Track selected master wallet for add account
let selectedMasterWalletId = null;

/**
 * Show Add Account modal with master wallet selection (Android-style bottom sheet)
 */
async function showAddAccountModal() {
  try {
    const result = await sendMessage('getMasterWallets');
    
    if (!result.success || !result.masterWallets || result.masterWallets.length === 0) {
      showToast('No Master Wallets found. Create one first!', 'error');
      document.getElementById('create-master-wallet-modal').classList.add('show');
      return;
    }
    
    const listContainer = document.getElementById('master-wallet-list');
    if (listContainer) {
      listContainer.innerHTML = result.masterWallets.map(mw => `
        <div class="master-wallet-option" data-master-id="${mw.id}">
          <div class="wallet-icon">🏦</div>
          <div class="wallet-details">
            <div class="wallet-name">${mw.name}</div>
            <div class="wallet-meta">${mw.accountCount} account${mw.accountCount !== 1 ? 's' : ''} · ${formatAddress(mw.firstAddress || '0x...')}</div>
          </div>
          <span class="option-arrow">›</span>
        </div>
      `).join('');
      
      // Add click handlers - clicking a wallet derives a new account from it
      listContainer.querySelectorAll('.master-wallet-option').forEach(option => {
        option.addEventListener('click', () => {
          selectedMasterWalletId = option.dataset.masterId;
          handleSelectMasterWallet(option.dataset.masterId);
        });
      });
    }
    
    selectedMasterWalletId = null;
    document.getElementById('select-master-modal').classList.add('show');
  } catch (error) {
    console.error('Error loading master wallets:', error);
    showToast('Error loading wallets', 'error');
  }
}

/**
 * Handle selecting a master wallet and adding account to it
 */
async function handleSelectMasterWallet(masterWalletId) {
  try {
    const result = await sendMessage('addAccountToMaster', { 
      masterWalletId, 
      name: null // Auto-generate name
    });
    
    if (result.success) {
      showToast(`Created ${result.name}`, 'success');
      document.getElementById('select-master-modal').classList.remove('show');
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to create account', 'error');
    }
  } catch (error) {
    console.error('Error adding account:', error);
    showToast('Error creating account', 'error');
  }
}

/**
 * Create new Master Wallet
 */
async function handleCreateMasterWallet() {
  const name = document.getElementById('master-wallet-name')?.value?.trim() || 'Master Wallet';
  
  try {
    const result = await sendMessage('createHDWallet', { name });
    
    if (result.success) {
      document.getElementById('create-master-wallet-modal').classList.remove('show');
      document.getElementById('master-wallet-name').value = '';
      
      // Update current wallet address to the new account
      if (result.address) {
        currentWalletAddress = result.address;
      }
      
      // Show the seed phrase to the user so they can back it up
      if (result.mnemonic) {
        // Set currentMnemonic for verification flow
        currentMnemonic = result.mnemonic;
        sessionStorage.setItem('temp_mnemonic', result.mnemonic);
        showToast(`${result.masterWalletName} created! Please backup seed phrase.`, 'success');
        
        // Use displaySeedPhrase to properly set up the seed display with copy support
        displaySeedPhrase(result.mnemonic);
        showScreen('seed');
      } else {
        await loadAccountsList();
        await loadMainScreen();
      }
    } else {
      showToast(result.error || 'Failed to create Master Wallet', 'error');
    }
  } catch (error) {
    console.error('Error creating Master Wallet:', error);
    showToast('Error creating Master Wallet', 'error');
  }
}

/**
 * Show bulk add to master modal
 */
async function showBulkAddMasterModal() {
  try {
    const result = await sendMessage('getMasterWallets');
    
    if (!result.success || !result.masterWallets || result.masterWallets.length === 0) {
      showToast('No Master Wallets found. Create one first!', 'error');
      return;
    }
    
    const listContainer = document.getElementById('bulk-master-wallet-list');
    if (listContainer) {
      listContainer.innerHTML = result.masterWallets.map(mw => `
        <div class="master-wallet-option" data-master-id="${mw.id}">
          <div class="wallet-icon">🏦</div>
          <div class="wallet-details">
            <div class="wallet-name">${mw.name}</div>
            <div class="wallet-accounts">${mw.accountCount} account${mw.accountCount !== 1 ? 's' : ''}</div>
          </div>
        </div>
      `).join('');
      
      // Add click handlers
      listContainer.querySelectorAll('.master-wallet-option').forEach(option => {
        option.addEventListener('click', () => {
          // Deselect all
          listContainer.querySelectorAll('.master-wallet-option').forEach(o => o.classList.remove('selected'));
          // Select this one
          option.classList.add('selected');
          selectedMasterWalletId = option.dataset.masterId;
          // Enable confirm button
          document.getElementById('btn-confirm-bulk-add-master').disabled = false;
        });
      });
    }
    
    selectedMasterWalletId = null;
    document.getElementById('btn-confirm-bulk-add-master').disabled = true;
    document.getElementById('bulk-add-master-modal').classList.add('show');
  } catch (error) {
    console.error('Error loading master wallets:', error);
    showToast('Error loading wallets', 'error');
  }
}

/**
 * Confirm bulk add accounts to selected master wallet
 */
async function confirmBulkAddToMaster() {
  if (!selectedMasterWalletId) {
    showToast('Please select a Master Wallet', 'error');
    return;
  }
  
  const count = parseInt(document.getElementById('bulk-add-master-count')?.value) || 5;
  
  if (count < 1 || count > 20) {
    showToast('Please enter a number between 1 and 20', 'error');
    return;
  }

  try {
    const result = await sendMessage('bulkAddToMaster', { 
      masterWalletId: selectedMasterWalletId, 
      count 
    });
    
    if (result.success) {
      showToast(`Added ${result.addedCount} accounts`, 'success');
      document.getElementById('bulk-add-master-modal').classList.remove('show');
      selectedMasterWalletId = null;
      
      // Update current wallet address to the first new account
      if (result.accounts && result.accounts.length > 0) {
        currentWalletAddress = result.accounts[0].address;
      }
      
      await loadAccountsList();
      await loadMainScreen();
    } else {
      showToast(result.error || 'Failed to add accounts', 'error');
    }
  } catch (error) {
    showToast('Error adding accounts', 'error');
  }
}

/**
 * Legacy: Handle create HD wallet (kept for backwards compatibility)
 */
async function handleCreateHDWallet() {
  const name = document.getElementById('hd-wallet-name')?.value?.trim() || 'Account 1';
  
  try {
    const result = await sendMessage('createHDWallet', { name });
    
    if (result.success) {
      document.getElementById('create-hd-wallet-modal')?.classList.remove('show');
      
      // Show the seed phrase to the user so they can back it up
      if (result.mnemonic) {
        // Store mnemonic temporarily for display
        sessionStorage.setItem('temp_mnemonic', result.mnemonic);
        showToast('HD Wallet created! Please backup your seed phrase.', 'success');
        
        // Show seed phrase screen for backup
        const seedDisplay = document.getElementById('seed-phrase-display');
        if (seedDisplay) {
          const words = result.mnemonic.split(' ');
          seedDisplay.innerHTML = words.map((word, i) => `
            <div class="seed-word">
              <span class="word-number">${i + 1}</span>
              <span class="word-text">${word}</span>
            </div>
          `).join('');
        }
        showScreen('seed');
      } else {
        await loadAccountsList();
      }
    } else {
      showToast(result.error || 'Failed to create HD wallet', 'error');
    }
  } catch (error) {
    console.error('Error creating HD wallet:', error);
    showToast('Error creating HD wallet', 'error');
  }
}

/**
 * Handle bulk add accounts
 */
async function handleBulkAddAccounts() {
  const modal = document.getElementById('bulk-add-modal');
  if (modal) {
    modal.classList.add('show');
  }
}

/**
 * Confirm bulk add accounts
 */
async function confirmBulkAdd() {
  const countInput = document.getElementById('bulk-add-count');
  const count = parseInt(countInput?.value) || 5;
  
  if (count < 1 || count > 20) {
    showToast('Please enter a number between 1 and 20', 'error');
    return;
  }

  try {
    const result = await sendMessage('bulkAddAccounts', { count });
    
    if (result.success) {
      showToast(`Added ${count} accounts`, 'success');
      document.getElementById('bulk-add-modal').classList.remove('show');
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to add accounts', 'error');
    }
  } catch (error) {
    showToast('Error adding accounts', 'error');
  }
}

/**
 * Import account via private key
 */
async function handleImportKeyAccount() {
  const name = document.getElementById('import-key-name')?.value?.trim();
  let privateKey = document.getElementById('import-key-value')?.value?.trim();

  if (!privateKey) {
    showToast('Please enter a private key', 'error');
    return;
  }

  // Add 0x prefix if missing
  if (!privateKey.startsWith('0x')) {
    privateKey = '0x' + privateKey;
  }

  // Validate private key length (64 hex chars + 0x prefix = 66)
  if (privateKey.length !== 66) {
    showToast('Invalid private key length', 'error');
    return;
  }

  try {
    const result = await sendMessage('importPrivateKeyAccount', { privateKey, name });
    
    if (result.success) {
      showToast('Account imported successfully', 'success');
      document.getElementById('import-key-modal').classList.remove('show');
      document.getElementById('import-key-name').value = '';
      document.getElementById('import-key-value').value = '';
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to import account', 'error');
    }
  } catch (error) {
    showToast('Error importing account: ' + error.message, 'error');
  }
}

/**
 * Show import seed modal
 */
function showImportSeedModal() {
  const modal = document.getElementById('import-seed-modal');
  if (modal) {
    modal.classList.add('show');
  }
}

/**
 * Import HD wallet from seed phrase
 */
async function handleImportSeedAccount() {
  const name = document.getElementById('import-seed-name')?.value?.trim();
  const seedPhrase = document.getElementById('import-seed-value')?.value?.trim();
  const count = parseInt(document.getElementById('import-seed-count')?.value) || 1;

  if (!seedPhrase) {
    showToast('Please enter a seed phrase', 'error');
    return;
  }

  // Validate seed phrase (12 or 24 words)
  const words = seedPhrase.split(/\s+/).filter(w => w.length > 0);
  if (words.length !== 12 && words.length !== 24) {
    showToast('Seed phrase must be 12 or 24 words', 'error');
    return;
  }

  try {
    const result = await sendMessage('importSeedPhraseAccounts', { 
      mnemonic: seedPhrase, 
      name, 
      count 
    });
    
    if (result.success) {
      showToast(`Imported ${result.count || result.addedCount || 1} account(s)`, 'success');
      document.getElementById('import-seed-modal').classList.remove('show');
      document.getElementById('import-seed-name').value = '';
      document.getElementById('import-seed-value').value = '';
      document.getElementById('import-seed-count').value = '1';
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to import wallet', 'error');
    }
  } catch (error) {
    showToast('Error importing wallet: ' + error.message, 'error');
  }
}

/**
 * Handle import wallet from the unified import modal
 */
async function handleImportWalletFromModal() {
  const activeSeedTab = document.querySelector('#import-wallet-modal #import-tab-seed.active');
  
  if (activeSeedTab) {
    // Import via seed phrase
    const seedPhrase = document.getElementById('import-wallet-seed')?.value?.trim();
    const name = document.getElementById('import-wallet-seed-name')?.value?.trim();
    const count = parseInt(document.getElementById('import-wallet-seed-count')?.value) || 1;
    
    if (!seedPhrase) {
      showToast('Please enter a seed phrase', 'error');
      return;
    }
    
    const words = seedPhrase.split(/\s+/).filter(w => w.length > 0);
    if (words.length !== 12 && words.length !== 24) {
      showToast('Seed phrase must be 12 or 24 words', 'error');
      return;
    }
    
    try {
      const result = await sendMessage('importSeedPhraseAccounts', { 
        mnemonic: seedPhrase, 
        name, 
        count 
      });
      
      if (result.success) {
        showToast(`Created "${result.masterWalletName}" with ${result.addedCount || 1} account(s)`, 'success');
        document.getElementById('import-wallet-modal').classList.remove('show');
        document.getElementById('import-wallet-seed').value = '';
        document.getElementById('import-wallet-seed-name').value = '';
        document.getElementById('import-wallet-seed-count').value = '1';
        
        // Update current wallet address if provided
        if (result.accounts && result.accounts.length > 0) {
          currentWalletAddress = result.accounts[0].address;
        }
        
        await loadAccountsList();
        await loadMainScreen();
      } else {
        showToast(result.error || 'Failed to import wallet', 'error');
      }
    } catch (error) {
      showToast('Error importing wallet: ' + error.message, 'error');
    }
  } else {
    // Import via private key
    let privateKey = document.getElementById('import-wallet-key')?.value?.trim();
    const name = document.getElementById('import-wallet-key-name')?.value?.trim();
    
    if (!privateKey) {
      showToast('Please enter a private key', 'error');
      return;
    }
    
    if (!privateKey.startsWith('0x')) {
      privateKey = '0x' + privateKey;
    }
    
    if (privateKey.length !== 66) {
      showToast('Invalid private key length', 'error');
      return;
    }
    
    try {
      const result = await sendMessage('importPrivateKeyAccount', { privateKey, name });
      
      if (result.success) {
        showToast('Account imported successfully', 'success');
        document.getElementById('import-wallet-modal').classList.remove('show');
        document.getElementById('import-wallet-key').value = '';
        document.getElementById('import-wallet-key-name').value = '';
        
        // Update current wallet address
        if (result.address) {
          currentWalletAddress = result.address;
        }
        
        await loadAccountsList();
        await loadMainScreen();
      } else {
        showToast(result.error || 'Failed to import account', 'error');
      }
    } catch (error) {
      showToast('Error importing account: ' + error.message, 'error');
    }
  }
}

/**
 * Handle adding a watch wallet
 */
async function handleAddWatchWallet() {
  const name = document.getElementById('watch-wallet-name')?.value?.trim();
  const address = document.getElementById('watch-wallet-address')?.value?.trim();
  
  if (!address) {
    showToast('Please enter a wallet address', 'error');
    return;
  }
  
  // Basic address validation
  if (!address.startsWith('0x') || address.length !== 42) {
    showToast('Invalid Ethereum address format', 'error');
    return;
  }
  
  try {
    const result = await sendMessage('addWatchWallet', { 
      address, 
      name: name || 'Watch Wallet' 
    });
    
    if (result.success) {
      showToast('Watch wallet added successfully', 'success');
      document.getElementById('watch-wallet-modal').classList.remove('show');
      document.getElementById('watch-wallet-name').value = '';
      document.getElementById('watch-wallet-address').value = '';
      
      // Update current wallet address to the new watch wallet
      if (result.address) {
        currentWalletAddress = result.address;
      }
      
      await loadAccountsList();
      await loadMainScreen();
    } else {
      showToast(result.error || 'Failed to add watch wallet', 'error');
    }
  } catch (error) {
    showToast('Error adding watch wallet: ' + error.message, 'error');
  }
}

/**
 * Show bulk add modal for a specific master wallet
 */
async function showBulkAddModal(masterWalletId) {
  try {
    const result = await sendMessage('getMasterWallets');
    if (result.success) {
      const mw = result.masterWallets.find(m => m.id === masterWalletId);
      if (mw) {
        document.getElementById('bulk-master-name').textContent = mw.name;
        selectedMasterWalletId = masterWalletId;
        document.getElementById('bulk-add-master-count').value = '5';
        document.getElementById('btn-confirm-bulk-add-master').textContent = 'Add 5 Wallets';
        document.getElementById('bulk-add-master-modal').classList.add('show');
      }
    }
  } catch (error) {
    showToast('Error loading wallet info', 'error');
  }
}

/**
 * Show recover account modal for a specific master wallet
 */
async function showRecoverAccountModal(masterWalletId) {
  try {
    const result = await sendMessage('getMasterWallets');
    if (result.success) {
      const mw = result.masterWallets.find(m => m.id === masterWalletId);
      if (mw) {
        document.getElementById('recover-master-name').textContent = mw.name;
        selectedMasterWalletId = masterWalletId;
        document.getElementById('recover-account-index').value = '0';
        document.getElementById('recover-account-modal').classList.add('show');
      }
    }
  } catch (error) {
    showToast('Error loading wallet info', 'error');
  }
}

/**
 * Handle recovering a specific account by index
 */
async function handleRecoverAccount() {
  if (!selectedMasterWalletId) {
    showToast('No master wallet selected', 'error');
    return;
  }
  
  const index = parseInt(document.getElementById('recover-account-index')?.value);
  
  if (isNaN(index) || index < 0) {
    showToast('Please enter a valid account index', 'error');
    return;
  }
  
  try {
    const result = await sendMessage('recoverAccountByIndex', { 
      masterWalletId: selectedMasterWalletId, 
      index 
    });
    
    if (result.success) {
      showToast(`Recovered Account ${index + 1}`, 'success');
      document.getElementById('recover-account-modal').classList.remove('show');
      selectedMasterWalletId = null;
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to recover account', 'error');
    }
  } catch (error) {
    showToast('Error recovering account: ' + error.message, 'error');
  }
}

/**
 * Edit account name - show custom modal
 */
window.editAccountName = async function(accountIndex, currentName) {
  // Create a temporary input modal
  const existingModal = document.getElementById('rename-account-modal');
  if (existingModal) existingModal.remove();
  
  const modal = document.createElement('div');
  modal.id = 'rename-account-modal';
  modal.className = 'modal show';
  modal.innerHTML = `
    <div class="modal-content">
      <div class="modal-header">
        <h3>Rename Account</h3>
        <button class="modal-close" id="close-rename-modal">×</button>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label for="rename-account-input">Account Name</label>
          <input type="text" id="rename-account-input" value="${currentName}" placeholder="Enter new name">
        </div>
        <button id="btn-confirm-rename" class="btn btn-primary btn-full">
          Save
        </button>
      </div>
    </div>
  `;
  document.body.appendChild(modal);
  
  // Focus the input
  const input = document.getElementById('rename-account-input');
  input.focus();
  input.select();
  
  // Handle close
  document.getElementById('close-rename-modal').addEventListener('click', () => {
    modal.remove();
  });
  
  // Handle confirm
  document.getElementById('btn-confirm-rename').addEventListener('click', async () => {
    const newName = input.value.trim();
    if (!newName || newName === currentName) {
      modal.remove();
      return;
    }

    try {
      const result = await sendMessage('renameAccount', { accountIndex, newName });
      
      if (result.success) {
        showToast('Account renamed', 'success');
        modal.remove();
        await loadAccountsList();
        
        // Update main screen if this is the active account
        const status = await sendMessage('getWalletStatus');
        if (status.success) {
          const accounts = await sendMessage('getAccounts');
          document.getElementById('account-name').textContent = 
            accounts.accounts?.find(a => a.isActive)?.name || 'Account 1';
        }
      } else {
        showToast(result.error || 'Failed to rename account', 'error');
      }
    } catch (error) {
      showToast('Error renaming account', 'error');
    }
  });
  
  // Handle Enter key
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      document.getElementById('btn-confirm-rename').click();
    } else if (e.key === 'Escape') {
      modal.remove();
    }
  });
};

/**
 * Delete an account
 */
window.deleteAccount = async function(accountIndex) {
  showConfirmModal({
    title: 'Remove Account',
    message: 'Are you sure you want to remove this account? This action cannot be undone.',
    confirmText: 'Remove',
    isDanger: true,
    onConfirm: async () => {
      try {
        const result = await sendMessage('removeAccount', { accountIndex });
        
        if (result.success) {
          showToast('Account removed', 'success');
          await loadAccountsList();
        } else {
          showToast(result.error || 'Failed to remove account', 'error');
        }
      } catch (error) {
        showToast('Error removing account', 'error');
      }
    }
  });
};

// ============================================
// DAPP CONNECTION REQUESTS
// ============================================

/**
 * Show dApp connection request modal
 */
function showDappConnectionRequest(request) {
  if (!request) return;
  
  // Create modal if it doesn't exist
  let modal = document.getElementById('dapp-connect-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'dapp-connect-modal';
    modal.className = 'modal';
    modal.innerHTML = `
      <div class="modal-content dapp-connect-content">
        <div class="dapp-connect-header">
          <div class="dapp-icon">🌐</div>
          <h3>Connection Request</h3>
        </div>
        <div class="dapp-connect-body">
          <p class="dapp-origin" id="dapp-origin"></p>
          <p class="dapp-message">This site wants to connect to your wallet</p>
          <div class="dapp-permissions">
            <p class="permission-title">This will allow the site to:</p>
            <ul>
              <li>✓ See your wallet address</li>
              <li>✓ Request transaction approvals</li>
              <li>✓ Request message signatures</li>
            </ul>
          </div>
          <div class="dapp-account-info">
            <span class="label">Account:</span>
            <span class="account-address" id="dapp-account-address"></span>
          </div>
          <div class="dapp-network-info">
            <span class="label">Network:</span>
            <span class="network-name" id="dapp-network-name"></span>
          </div>
        </div>
        <div class="dapp-connect-actions">
          <button class="btn btn-secondary" id="btn-reject-dapp">Reject</button>
          <button class="btn btn-primary" id="btn-approve-dapp">Connect</button>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
    
    // Add event listeners
    document.getElementById('btn-approve-dapp').addEventListener('click', approveDappConnection);
    document.getElementById('btn-reject-dapp').addEventListener('click', rejectDappConnection);
  }
  
  // Update modal content
  document.getElementById('dapp-origin').textContent = request.origin;
  document.getElementById('dapp-account-address').textContent = formatAddress(currentWalletAddress);
  document.getElementById('dapp-network-name').textContent = currentNetwork?.name || 'Ramestta Mainnet';
  
  // Show modal
  modal.classList.add('active');
}

/**
 * Approve dApp connection
 */
async function approveDappConnection() {
  if (!pendingDappRequest) return;
  
  try {
    const result = await sendMessage('approveDappConnection', { requestId: pendingDappRequest.id });
    
    if (result.success) {
      showToast('Connected to ' + pendingDappRequest.origin, 'success');
      document.getElementById('dapp-connect-modal')?.classList.remove('active');
      pendingDappRequest = null;
      
      // Close popup if opened from window
      if (window.location.search.includes('dappRequest')) {
        window.close();
      }
    } else {
      showToast(result.error || 'Failed to connect', 'error');
    }
  } catch (error) {
    showToast('Error connecting to site', 'error');
  }
}

/**
 * Reject dApp connection
 */
async function rejectDappConnection() {
  if (!pendingDappRequest) return;
  
  try {
    await sendMessage('rejectDappConnection', { requestId: pendingDappRequest.id });
    showToast('Connection rejected', 'info');
    document.getElementById('dapp-connect-modal')?.classList.remove('active');
    pendingDappRequest = null;
    
    // Close popup if opened from window
    if (window.location.search.includes('dappRequest')) {
      window.close();
    }
  } catch (error) {
    console.error('Error rejecting connection:', error);
  }
}

// ============================================
// ACCOUNT CONTEXT MENU
// ============================================

let activeContextMenu = null;

/**
 * Show account context menu
 */
function showAccountMenu(accountItem, accountIndex, accountType, masterId) {
  // Remove any existing menu
  closeContextMenu();
  
  const address = accountItem.dataset.address;
  const name = accountItem.dataset.name;
  const isFirstAccount = accountItem.dataset.accountIndex === '0';
  const isWatchOnly = accountType === 'watch';
  const isDerived = accountType === 'derived';
  
  // Create menu
  const menu = document.createElement('div');
  menu.className = 'account-context-menu';
  menu.innerHTML = `
    <div class="context-menu-header">
      <span class="context-menu-title">${name}</span>
      <button class="context-menu-close">✕</button>
    </div>
    <div class="context-menu-items">
      <button class="context-menu-item" data-action="copy-address">
        <span class="menu-icon">📋</span>
        <span>Copy Address</span>
      </button>
      <button class="context-menu-item" data-action="view-explorer">
        <span class="menu-icon">🔍</span>
        <span>View on Explorer</span>
      </button>
      <div class="context-menu-divider"></div>
      <button class="context-menu-item" data-action="rename">
        <span class="menu-icon">✏️</span>
        <span>Rename Account</span>
      </button>
      ${!isWatchOnly ? `
        <button class="context-menu-item" data-action="show-private-key">
          <span class="menu-icon">🔑</span>
          <span>Show Private Key</span>
        </button>
      ` : ''}
      ${isDerived && isFirstAccount && masterId ? `
        <button class="context-menu-item" data-action="show-recovery-phrase">
          <span class="menu-icon">📝</span>
          <span>Show Recovery Phrase</span>
        </button>
      ` : ''}
      <div class="context-menu-divider"></div>
      ${!isFirstAccount || !isDerived ? `
        <button class="context-menu-item danger" data-action="remove">
          <span class="menu-icon">🗑️</span>
          <span>Remove Account</span>
        </button>
      ` : ''}
    </div>
  `;
  
  // Position menu
  document.body.appendChild(menu);
  activeContextMenu = menu;
  
  // Add event listeners
  menu.querySelector('.context-menu-close').addEventListener('click', closeContextMenu);
  
  menu.querySelectorAll('.context-menu-item').forEach(item => {
    item.addEventListener('click', async () => {
      const action = item.dataset.action;
      await handleAccountMenuAction(action, accountIndex, address, name, masterId);
      closeContextMenu();
    });
  });
  
  // Close on click outside
  setTimeout(() => {
    document.addEventListener('click', handleOutsideClick);
  }, 10);
}

/**
 * Show master wallet context menu
 */
function showMasterWalletMenu(btn, masterId) {
  closeContextMenu();
  
  const masterGroup = btn.closest('.master-wallet-group');
  const masterName = masterGroup?.querySelector('.master-wallet-name')?.textContent || 'Master Wallet';
  
  const menu = document.createElement('div');
  menu.className = 'account-context-menu';
  menu.innerHTML = `
    <div class="context-menu-header">
      <span class="context-menu-title">${masterName}</span>
      <button class="context-menu-close">✕</button>
    </div>
    <div class="context-menu-items">
      <button class="context-menu-item" data-action="add-account">
        <span class="menu-icon">➕</span>
        <span>Add New Account</span>
      </button>
      <button class="context-menu-item" data-action="bulk-add">
        <span class="menu-icon">📦</span>
        <span>Bulk Add Accounts</span>
      </button>
      <button class="context-menu-item" data-action="show-recovery-phrase">
        <span class="menu-icon">📝</span>
        <span>Show Recovery Phrase</span>
      </button>
      <div class="context-menu-divider"></div>
      <button class="context-menu-item" data-action="rename-master">
        <span class="menu-icon">✏️</span>
        <span>Rename Wallet</span>
      </button>
    </div>
  `;
  
  document.body.appendChild(menu);
  activeContextMenu = menu;
  
  menu.querySelector('.context-menu-close').addEventListener('click', closeContextMenu);
  
  menu.querySelectorAll('.context-menu-item').forEach(item => {
    item.addEventListener('click', async () => {
      const action = item.dataset.action;
      await handleMasterWalletMenuAction(action, masterId, masterName);
      closeContextMenu();
    });
  });
  
  setTimeout(() => {
    document.addEventListener('click', handleOutsideClick);
  }, 10);
}

/**
 * Handle account menu actions
 */
async function handleAccountMenuAction(action, accountIndex, address, name, masterId) {
  switch (action) {
    case 'copy-address':
      await navigator.clipboard.writeText(address);
      showToast('Address copied!', 'success');
      break;
      
    case 'view-explorer':
      const network = await sendMessage('getCurrentNetwork');
      if (network.success && network.network.explorerUrl) {
        const explorerUrl = network.network.explorerUrl.replace(/\/$/, '');
        window.open(`${explorerUrl}/address/${address}`, '_blank');
      } else {
        showToast('No explorer available for this network', 'error');
      }
      break;
      
    case 'rename':
      showInputModal({
        title: 'Rename Account',
        label: 'Account Name',
        placeholder: 'Enter new account name',
        defaultValue: name,
        confirmText: 'Rename',
        onConfirm: async (newName) => {
          if (newName && newName.trim()) {
            const result = await sendMessage('renameAccount', { accountIndex, newName: newName.trim() });
            if (result.success) {
              showToast('Account renamed!', 'success');
              await loadAccountsList();
            } else {
              showToast(result.error || 'Failed to rename', 'error');
            }
          }
        }
      });
      break;
      
    case 'show-private-key':
      showPrivateKeyModal(accountIndex);
      break;
      
    case 'show-recovery-phrase':
      showRecoveryPhraseModal(masterId);
      break;
      
    case 'remove':
      showConfirmModal({
        title: 'Remove Account',
        message: `Are you sure you want to remove "${name}"? This action cannot be undone.`,
        confirmText: 'Remove',
        isDanger: true,
        onConfirm: async () => {
          const result = await sendMessage('removeAccount', { accountIndex });
          if (result.success) {
            showToast('Account removed', 'success');
            await loadAccountsList();
            await loadMainScreen();
          } else {
            showToast(result.error || 'Failed to remove account', 'error');
          }
        }
      });
      break;
  }
}

/**
 * Handle master wallet menu actions
 */
async function handleMasterWalletMenuAction(action, masterId, masterName) {
  switch (action) {
    case 'add-account':
      showInputModal({
        title: 'Add New Account',
        label: 'Account Name (optional)',
        placeholder: 'Enter account name',
        confirmText: 'Create',
        onConfirm: async (accountName) => {
          const result = await sendMessage('addAccountToMaster', { 
            masterWalletId: masterId, 
            name: accountName?.trim() || undefined 
          });
          if (result.success) {
            showToast(`Account created: ${result.name}`, 'success');
            
            // Update current wallet address to the new account
            if (result.address) {
              currentWalletAddress = result.address;
            }
            
            await loadAccountsList();
            await loadMainScreen();
          } else {
            showToast(result.error || 'Failed to create account', 'error');
          }
        }
      });
      break;
      
    case 'bulk-add':
      showBulkAddModal(masterId);
      break;
      
    case 'show-recovery-phrase':
      showRecoveryPhraseModal(masterId);
      break;
      
    case 'rename-master':
      showInputModal({
        title: 'Rename Master Wallet',
        label: 'Wallet Name',
        placeholder: 'Enter new wallet name',
        defaultValue: masterName,
        confirmText: 'Rename',
        onConfirm: async (newName) => {
          if (newName && newName.trim() && newName.trim() !== masterName) {
            const result = await sendMessage('renameMasterWallet', { 
              masterWalletId: masterId, 
              newName: newName.trim() 
            });
            if (result.success) {
              showToast('Wallet renamed successfully!', 'success');
              await loadAccountsList();
            } else {
              showToast(result.error || 'Failed to rename wallet', 'error');
            }
          }
        }
      });
      break;
  }
}

/**
 * Show private key modal
 */
function showPrivateKeyModal(accountIndex) {
  // Create or show the private key modal
  let modal = document.getElementById('private-key-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'private-key-modal';
    modal.className = 'modal';
    modal.innerHTML = `
      <div class="modal-content">
        <div class="modal-header">
          <h3>🔑 Private Key</h3>
          <button class="modal-close">✕</button>
        </div>
        <div class="modal-body">
          <div class="warning-box">
            <span class="warning-icon">⚠️</span>
            <p>Never share your private key! Anyone with this key can steal your funds.</p>
          </div>
          <div class="form-group">
            <label>Enter Password to Reveal</label>
            <input type="password" id="pk-password" class="form-control" placeholder="Your wallet password">
          </div>
          <div id="pk-reveal-area" class="hidden">
            <div class="private-key-display">
              <code id="pk-value"></code>
            </div>
            <button id="btn-copy-pk" class="btn btn-secondary">📋 Copy Private Key</button>
          </div>
          <button id="btn-reveal-pk" class="btn btn-primary">Reveal Private Key</button>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
    
    modal.querySelector('.modal-close').addEventListener('click', () => {
      modal.classList.remove('show');
      document.getElementById('pk-password').value = '';
      document.getElementById('pk-reveal-area').classList.add('hidden');
      document.getElementById('btn-reveal-pk').classList.remove('hidden');
    });
    
    document.getElementById('btn-copy-pk').addEventListener('click', () => {
      const pk = document.getElementById('pk-value').textContent;
      navigator.clipboard.writeText(pk);
      showToast('Private key copied!', 'success');
    });
  }
  
  // Reset and show
  document.getElementById('pk-password').value = '';
  document.getElementById('pk-reveal-area').classList.add('hidden');
  document.getElementById('btn-reveal-pk').classList.remove('hidden');
  modal.classList.add('show');
  
  // Set up reveal button
  const revealBtn = document.getElementById('btn-reveal-pk');
  revealBtn.onclick = async () => {
    const password = document.getElementById('pk-password').value;
    if (!password) {
      showToast('Please enter your password', 'error');
      return;
    }
    
    const result = await sendMessage('exportPrivateKey', { password, accountIndex });
    if (result.success) {
      document.getElementById('pk-value').textContent = result.privateKey;
      document.getElementById('pk-reveal-area').classList.remove('hidden');
      revealBtn.classList.add('hidden');
    } else {
      showToast(result.error || 'Incorrect password', 'error');
    }
  };
}

/**
 * Show recovery phrase modal
 */
function showRecoveryPhraseModal(masterId) {
  let modal = document.getElementById('recovery-phrase-modal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'recovery-phrase-modal';
    modal.className = 'modal';
    modal.innerHTML = `
      <div class="modal-content">
        <div class="modal-header">
          <h3>📝 Recovery Phrase</h3>
          <button class="modal-close">✕</button>
        </div>
        <div class="modal-body">
          <div class="warning-box">
            <span class="warning-icon">⚠️</span>
            <p>Never share your recovery phrase! Anyone with these words can steal your funds.</p>
          </div>
          <div class="form-group">
            <label>Enter Password to Reveal</label>
            <input type="password" id="rp-password" class="form-control" placeholder="Your wallet password">
          </div>
          <div id="rp-reveal-area" class="hidden">
            <div id="rp-words" class="seed-phrase-grid"></div>
            <button id="btn-copy-rp" class="btn btn-secondary">📋 Copy Recovery Phrase</button>
          </div>
          <button id="btn-reveal-rp" class="btn btn-primary">Reveal Recovery Phrase</button>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
    
    modal.querySelector('.modal-close').addEventListener('click', () => {
      modal.classList.remove('show');
      document.getElementById('rp-password').value = '';
      document.getElementById('rp-reveal-area').classList.add('hidden');
      document.getElementById('btn-reveal-rp').classList.remove('hidden');
      document.getElementById('rp-words').innerHTML = '';
    });
  }
  
  // Reset and show
  document.getElementById('rp-password').value = '';
  document.getElementById('rp-reveal-area').classList.add('hidden');
  document.getElementById('btn-reveal-rp').classList.remove('hidden');
  document.getElementById('rp-words').innerHTML = '';
  modal.classList.add('show');
  
  // Set up reveal button
  const revealBtn = document.getElementById('btn-reveal-rp');
  revealBtn.onclick = async () => {
    const password = document.getElementById('rp-password').value;
    if (!password) {
      showToast('Please enter your password', 'error');
      return;
    }
    
    const result = await sendMessage('exportRecoveryPhrase', { password });
    if (result.success) {
      const words = result.mnemonic.split(' ');
      document.getElementById('rp-words').innerHTML = words.map((word, i) => `
        <div class="seed-word">
          <span class="seed-word-num">${i + 1}</span>${word}
        </div>
      `).join('');
      document.getElementById('rp-reveal-area').classList.remove('hidden');
      revealBtn.classList.add('hidden');
      
      // Set up copy button
      document.getElementById('btn-copy-rp').onclick = () => {
        navigator.clipboard.writeText(result.mnemonic);
        showToast('Recovery phrase copied!', 'success');
      };
    } else {
      showToast(result.error || 'Incorrect password', 'error');
    }
  };
}

/**
 * Close context menu
 */
function closeContextMenu() {
  if (activeContextMenu) {
    activeContextMenu.remove();
    activeContextMenu = null;
  }
  document.removeEventListener('click', handleOutsideClick);
}

/**
 * Handle click outside context menu
 */
function handleOutsideClick(e) {
  if (activeContextMenu && !activeContextMenu.contains(e.target)) {
    closeContextMenu();
  }
}