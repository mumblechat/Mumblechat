/**
 * RamaPay Chrome Extension - Popup Script
 * Handles all UI interactions and communication with background service worker
 */

// State
let currentScreen = 'loading';
let currentWalletAddress = null;
let currentNetwork = null;
let importType = 'mnemonic';

// DOM Elements cache
const screens = {};
const elements = {};

/**
 * Initialize the popup
 */
document.addEventListener('DOMContentLoaded', async () => {
  cacheElements();
  setupEventListeners();
  await checkWalletStatus();
});

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

  // Seed verification - skip button
  document.getElementById('btn-skip-verify')?.addEventListener('click', skipVerification);

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
  document.getElementById('btn-copy-address')?.addEventListener('click', handleCopyAddress);
  document.getElementById('btn-send')?.addEventListener('click', () => showScreen('send'));
  document.getElementById('btn-receive')?.addEventListener('click', () => {
    showScreen('receive');
    generateQRCode();
  });
  document.getElementById('btn-swap')?.addEventListener('click', () => showToast('Swap coming soon!', 'info'));
  document.getElementById('btn-settings')?.addEventListener('click', () => showScreen('settings'));

  // Tabs on main screen
  document.querySelectorAll('#main-screen .tabs .tab').forEach(tab => {
    tab.addEventListener('click', (e) => {
      const tabName = e.target.dataset.tab;
      document.querySelectorAll('#main-screen .tabs .tab').forEach(t => t.classList.remove('active'));
      e.target.classList.add('active');
      document.querySelectorAll('#main-screen .tab-panel').forEach(p => p.classList.remove('active'));
      document.getElementById(`${tabName}-tab`)?.classList.add('active');
    });
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
  document.getElementById('btn-add-account')?.addEventListener('click', () => {
    loadAccountsList();
    showScreen('accounts');
  });
  document.getElementById('btn-manage-networks')?.addEventListener('click', () => {
    loadNetworksList();
    showScreen('networks');
  });
  document.getElementById('btn-add-network')?.addEventListener('click', () => showScreen('add-network'));
  document.getElementById('btn-add-network-from-list')?.addEventListener('click', () => showScreen('add-network'));
  document.getElementById('btn-change-password')?.addEventListener('click', () => showScreen('change-password'));

  // Account Management
  document.getElementById('btn-account-selector')?.addEventListener('click', () => {
    loadAccountsList();
    showScreen('accounts');
  });
  document.getElementById('btn-derive-account')?.addEventListener('click', handleDeriveAccount);
  document.getElementById('btn-import-key-account')?.addEventListener('click', () => {
    document.getElementById('import-key-modal').classList.add('show');
  });
  document.getElementById('close-import-key-modal')?.addEventListener('click', () => {
    document.getElementById('import-key-modal').classList.remove('show');
  });
  document.getElementById('btn-confirm-import-key')?.addEventListener('click', handleImportKeyAccount);

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
async function sendMessage(action, data = {}) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error('Message timeout'));
    }, 5000);
    
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
      
      if (!status.isUnlocked) {
        showScreen('lock');
      } else {
        currentWalletAddress = status.address;
        currentNetwork = status.network;
        await loadMainScreen();
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
  Object.values(screens).forEach(screen => screen.classList.remove('active'));
  if (screens[screenName]) {
    screens[screenName].classList.add('active');
    currentScreen = screenName;
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
  const mnemonic = document.getElementById('seed-phrase-display').dataset.mnemonic;
  navigator.clipboard.writeText(mnemonic);
  showToast('Recovery phrase copied!', 'success');
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
  verifyCurrentStep = 0;
  
  // Pick 4 random positions (1-indexed for display)
  const allPositions = Array.from({length: 12}, (_, i) => i);
  verifyPositions = allPositions.sort(() => Math.random() - 0.5).slice(0, 4).sort((a, b) => a - b);
  
  showVerifyStep();
}

/**
 * Show current verification step
 */
function showVerifyStep() {
  if (verifyCurrentStep >= 4) {
    // All verified!
    handleVerifyComplete();
    return;
  }
  
  const words = currentMnemonic.split(' ');
  const targetPosition = verifyPositions[verifyCurrentStep];
  const correctWord = words[targetPosition];
  
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
    btn.classList.add('correct');
    allBtns.forEach(b => b.style.pointerEvents = 'none');
    
    setTimeout(() => {
      verifyCurrentStep++;
      showVerifyStep();
    }, 500);
  } else {
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
  verifyPositions = [];
  verifyCurrentStep = 0;
  await loadMainScreen();
  showScreen('main');
}

/**
 * Skip verification (with confirmation)
 */
function skipVerification() {
  if (confirm('Are you sure? Skipping verification means you may not have properly backed up your recovery phrase.')) {
    currentMnemonic = null;
    loadMainScreen();
    showScreen('main');
  }
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
    // Fetch wallet address and account info if not set
    if (!currentWalletAddress) {
      const status = await sendMessage('getWalletStatus');
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
      
      // Update native token display
      const nativeTokenName = document.getElementById('native-token-name');
      const nativeTokenNetwork = document.getElementById('native-token-network');
      if (nativeTokenName) nativeTokenName.textContent = currentNetwork.symbol;
      if (nativeTokenNetwork) nativeTokenNetwork.textContent = currentNetwork.name;
      
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
 * Load networks into the dropdown selector
 */
async function loadNetworkDropdown() {
  const networkSelect = elements.networkSelect;
  if (!networkSelect) return;

  // Clear existing options
  networkSelect.innerHTML = '';

  // Add built-in networks
  const builtinOptgroup = document.createElement('optgroup');
  builtinOptgroup.label = 'Built-in Networks';
  
  builtinOptgroup.appendChild(createOption('0x55a', 'Ramestta Mainnet'));
  builtinOptgroup.appendChild(createOption('0x561', 'Ramestta Testnet'));
  
  networkSelect.appendChild(builtinOptgroup);

  // Add custom networks
  try {
    const result = await sendMessage('getCustomNetworks');
    if (result.success && result.networks.length > 0) {
      const customOptgroup = document.createElement('optgroup');
      customOptgroup.label = 'Custom Networks';
      
      result.networks.forEach(network => {
        customOptgroup.appendChild(createOption(network.chainId, network.name));
      });
      
      networkSelect.appendChild(customOptgroup);
    }
  } catch (error) {
    console.error('Error loading custom networks for dropdown:', error);
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
    const result = await sendMessage('getBalance', { address: currentWalletAddress });
    
    if (result.success) {
      const balance = parseFloat(result.balance.ether).toFixed(4);
      elements.balanceValue.textContent = balance;
      document.getElementById('send-balance').textContent = `Balance: ${balance} ${currentNetwork?.symbol || 'RAMA'}`;
      
      // Update native token display
      const nativeTokenAmount = document.getElementById('native-token-amount');
      if (nativeTokenAmount) {
        nativeTokenAmount.textContent = balance;
      }
      
      // Update USD values
      updatePriceDisplay();
    }
  } catch (error) {
    console.error('Error fetching balance:', error);
  }
}

/**
 * Update token list display (legacy - kept for compatibility)
 */
function updateTokenList(balance) {
  // Now handled by refreshBalance and updatePriceDisplay
}

/**
 * Load transaction history
 */
async function loadTransactionHistory() {
  try {
    const result = await sendMessage('getTransactionHistory', { address: currentWalletAddress });
    
    if (result.success && result.history.length > 0) {
      const activityList = document.getElementById('activity-list');
      activityList.innerHTML = '';

      result.history.slice(0, 10).forEach(tx => {
        const isReceive = tx.to?.toLowerCase() === currentWalletAddress?.toLowerCase();
        activityList.innerHTML += `
          <div class="activity-item">
            <div style="display: flex; align-items: center; gap: 12px;">
              <div class="activity-icon ${isReceive ? 'receive' : 'send'}">
                ${isReceive ? '↙️' : '↗️'}
              </div>
              <div>
                <div style="font-weight: 500;">${isReceive ? 'Received' : 'Sent'}</div>
                <div style="font-size: 12px; color: var(--text-muted);">${formatAddress(isReceive ? tx.from : tx.to)}</div>
              </div>
            </div>
            <div style="text-align: right;">
              <div style="font-weight: 500; color: ${isReceive ? 'var(--secondary-color)' : 'var(--text-primary)'}">
                ${isReceive ? '+' : '-'}${formatAmount(tx.value)} RAMA
              </div>
            </div>
          </div>
        `;
      });
    }
  } catch (error) {
    console.error('Error loading history:', error);
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
      elements.balanceSymbol.textContent = currentNetwork.symbol;
      await refreshBalance();
      showToast(`Switched to ${currentNetwork.name}`, 'success');
    }
  } catch (error) {
    showToast('Failed to switch network', 'error');
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
        const gasCost = (BigInt(result.gasInfo.gasLimit) * BigInt(result.gasInfo.gasPrice || '1000000000')) / BigInt(10**18);
        document.getElementById('estimated-gas').textContent = `~${(Number(gasCost) / 1000).toFixed(6)} ${currentNetwork?.symbol || 'RAMA'}`;
      }
    } catch (error) {
      // Ignore gas estimation errors
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

  // Fill confirm screen
  document.getElementById('confirm-to').textContent = to;
  document.getElementById('confirm-amount').textContent = `${amount} ${currentNetwork?.symbol || 'RAMA'}`;
  document.getElementById('confirm-network').textContent = currentNetwork?.name || 'Ramestta Mainnet';
  document.getElementById('confirm-gas').textContent = document.getElementById('estimated-gas').textContent;
  document.getElementById('confirm-total').textContent = `${(parseFloat(amount) + 0.001).toFixed(6)} ${currentNetwork?.symbol || 'RAMA'}`;

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
      showToast('Transaction sent successfully!', 'success');
      document.getElementById('send-to').value = '';
      document.getElementById('send-amount').value = '';
      await refreshBalance();
      showScreen('main');
    } else {
      showToast(result.error || 'Transaction failed', 'error');
    }
  } catch (error) {
    showToast('Error sending transaction: ' + error.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Confirm & Send';
  }
}

/**
 * Generate QR code for receive address
 */
function generateQRCode() {
  const container = document.getElementById('qr-code');
  container.innerHTML = '';

  if (currentWalletAddress) {
    // Simple QR code generation using canvas
    const canvas = document.createElement('canvas');
    canvas.width = 180;
    canvas.height = 180;
    const ctx = canvas.getContext('2d');

    // Draw a placeholder - in production, use a proper QR library
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, 180, 180);
    ctx.fillStyle = '#000000';
    ctx.font = '10px monospace';
    ctx.textAlign = 'center';
    
    // Draw address in a grid pattern as a simple visual
    const addr = currentWalletAddress;
    for (let i = 0; i < 6; i++) {
      ctx.fillText(addr.substring(i * 7, (i + 1) * 7), 90, 30 + i * 25);
    }

    container.appendChild(canvas);
    
    // Add note about QR
    const note = document.createElement('p');
    note.style.fontSize = '10px';
    note.style.color = '#666';
    note.style.marginTop = '8px';
    note.textContent = 'Scan or copy address below';
    container.appendChild(note);
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

/**
 * Handle reset wallet
 */
async function handleResetWallet() {
  if (confirm('Are you sure you want to reset your wallet? This cannot be undone. Make sure you have backed up your recovery phrase!')) {
    try {
      await chrome.storage.local.clear();
      currentWalletAddress = null;
      showScreen('welcome');
      showToast('Wallet reset successfully', 'success');
    } catch (error) {
      showToast('Error resetting wallet', 'error');
    }
  }
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
  const name = prompt('Enter account name:');
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
async function handleTokenAddressInput(e) {
  const address = e.target.value.trim();
  
  // Clear previous timeout
  if (tokenFetchTimeout) clearTimeout(tokenFetchTimeout);
  
  // Hide preview initially
  document.getElementById('token-preview')?.classList.add('hidden');
  document.getElementById('btn-confirm-add-token').disabled = true;
  pendingTokenInfo = null;

  // Validate address format
  if (!address || !address.startsWith('0x') || address.length !== 42) {
    return;
  }

  // Debounce the API call
  tokenFetchTimeout = setTimeout(async () => {
    try {
      const result = await sendMessage('getTokenInfo', { tokenAddress: address });
      
      if (result.success && result.token) {
        pendingTokenInfo = result.token;
        
        // Show preview
        document.getElementById('preview-name').textContent = result.token.name;
        document.getElementById('preview-symbol').textContent = result.token.symbol;
        document.getElementById('preview-decimals').textContent = result.token.decimals;
        document.getElementById('token-preview')?.classList.remove('hidden');
        document.getElementById('btn-confirm-add-token').disabled = false;
      } else {
        showToast('Invalid token address or token not found', 'error');
      }
    } catch (error) {
      console.error('Token fetch error:', error);
    }
  }, 500);
}

/**
 * Handle add token confirmation
 */
async function handleAddToken() {
  if (!pendingTokenInfo) return;

  try {
    const result = await sendMessage('addToken', {
      tokenAddress: pendingTokenInfo.address
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
            <button class="token-delete" onclick="removeToken('${token.address}')">Remove</button>
          </div>
        `;
        container.innerHTML += tokenHtml;
      }
    }
  } catch (error) {
    console.error('Error loading tokens:', error);
  }
}

/**
 * Remove a custom token
 */
window.removeToken = async function(tokenAddress) {
  if (!confirm('Remove this token from your wallet?')) return;

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
  const balance = parseFloat(balanceStr) || 0;
  const usdValue = (balance * price).toFixed(2);

  // Update main balance USD value
  const balanceUsd = document.getElementById('balance-usd');
  if (balanceUsd) {
    balanceUsd.textContent = `≈ $${usdValue} USD`;
  }

  // Update native token value
  const nativeTokenValue = document.getElementById('native-token-value');
  if (nativeTokenValue) {
    nativeTokenValue.textContent = `$${usdValue}`;
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

// Built-in networks
const BUILTIN_NETWORKS = [
  { name: 'Ramestta Mainnet', chainId: '0x55a', rpcUrl: 'https://blockchain.ramestta.com/', symbol: 'RAMA', explorer: 'https://ramascan.com' },
  { name: 'Ramestta Testnet', chainId: '0x561', rpcUrl: 'https://testnet.ramestta.com/', symbol: 'RAMA', explorer: 'https://testnet.ramascan.com' }
];

/**
 * Load networks list
 */
async function loadNetworksList() {
  try {
    // Load built-in networks
    const builtinContainer = document.getElementById('builtin-networks-list');
    if (builtinContainer) {
      builtinContainer.innerHTML = BUILTIN_NETWORKS.map(network => `
        <div class="network-item ${currentNetwork?.chainId === network.chainId ? 'active' : ''}" data-chain-id="${network.chainId}">
          <div class="network-icon">🌐</div>
          <div class="network-info">
            <div class="network-name">${network.name}</div>
            <div class="network-details">Chain ID: ${parseInt(network.chainId, 16)}</div>
          </div>
          <div class="network-status">
            ${currentNetwork?.chainId === network.chainId ? '<span class="network-checkmark">✓</span>' : ''}
          </div>
        </div>
      `).join('');

      // Add click handlers
      builtinContainer.querySelectorAll('.network-item').forEach(item => {
        item.addEventListener('click', () => selectNetwork(item.dataset.chainId));
      });
    }

    // Load custom networks
    const result = await sendMessage('getCustomNetworks');
    const customContainer = document.getElementById('custom-networks-list');
    if (customContainer) {
      if (result.success && result.networks.length > 0) {
        customContainer.innerHTML = result.networks.map(network => `
          <div class="network-item custom ${currentNetwork?.chainId === network.chainId ? 'active' : ''}" data-chain-id="${network.chainId}">
            <div class="network-icon">🔗</div>
            <div class="network-info">
              <div class="network-name">${network.name}</div>
              <div class="network-details">Chain ID: ${parseInt(network.chainId, 16)}</div>
            </div>
            <div class="network-status">
              ${currentNetwork?.chainId === network.chainId ? '<span class="network-checkmark">✓</span>' : ''}
              <button class="network-delete" onclick="removeNetwork('${network.chainId}')">Remove</button>
            </div>
          </div>
        `).join('');

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
window.removeNetwork = async function(chainId) {
  if (!confirm('Remove this custom network?')) return;

  try {
    const result = await sendMessage('removeCustomNetwork', { chainId });
    if (result.success) {
      showToast('Network removed', 'success');
      await loadNetworksList();
    } else {
      showToast(result.error || 'Failed to remove network', 'error');
    }
  } catch (error) {
    showToast('Error removing network', 'error');
  }
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
          <button class="btn-disconnect" onclick="disconnectSite('${site}')">Disconnect</button>
        </div>
      `).join('');
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
 * Load and display all accounts
 */
async function loadAccountsList() {
  try {
    const result = await sendMessage('getAccounts');
    const container = document.getElementById('accounts-list');
    
    if (!container) return;
    
    if (result.success && result.accounts.length > 0) {
      container.innerHTML = result.accounts.map((account, index) => `
        <div class="account-item ${account.isActive ? 'active' : ''}" data-index="${index}">
          <div class="account-avatar">${account.name.charAt(0).toUpperCase()}</div>
          <div class="account-info-main">
            <div class="account-name-text">
              ${account.name}
              ${account.type === 'imported' ? '<span class="account-type-badge imported">Imported</span>' : ''}
            </div>
            <div class="account-address-text">${formatAddress(account.address)}</div>
          </div>
          ${account.isActive ? '<span class="account-checkmark">✓</span>' : ''}
          <div class="account-actions">
            <button class="account-action-btn" onclick="editAccountName(${index}, '${account.name}')" title="Rename">✏️</button>
            ${index !== 0 || account.type === 'imported' ? `<button class="account-action-btn delete" onclick="deleteAccount(${index})" title="Remove">🗑️</button>` : ''}
          </div>
        </div>
      `).join('');

      // Add click handlers for switching accounts
      container.querySelectorAll('.account-item').forEach(item => {
        item.addEventListener('click', async (e) => {
          // Don't switch if clicking action buttons
          if (e.target.closest('.account-actions')) return;
          
          const index = parseInt(item.dataset.index);
          await switchToAccount(index);
        });
      });
    } else {
      container.innerHTML = '<p class="empty-state">No accounts found</p>';
    }
  } catch (error) {
    console.error('Error loading accounts:', error);
    showToast('Error loading accounts', 'error');
  }
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
  const name = prompt('Enter account name:', `Account ${Date.now() % 1000}`);
  if (!name) return;

  try {
    const result = await sendMessage('addAccount', { name });
    
    if (result.success) {
      showToast(`Created ${name}`, 'success');
      await loadAccountsList();
    } else {
      showToast(result.error || 'Failed to create account', 'error');
    }
  } catch (error) {
    showToast('Error creating account', 'error');
  }
}

/**
 * Import account via private key
 */
async function handleImportKeyAccount() {
  const name = document.getElementById('import-key-name')?.value?.trim();
  const privateKey = document.getElementById('import-key-value')?.value?.trim();

  if (!privateKey) {
    showToast('Please enter a private key', 'error');
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
    showToast('Error importing account', 'error');
  }
}

/**
 * Edit account name
 */
window.editAccountName = async function(accountIndex, currentName) {
  const newName = prompt('Enter new account name:', currentName);
  if (!newName || newName === currentName) return;

  try {
    const result = await sendMessage('renameAccount', { accountIndex, newName });
    
    if (result.success) {
      showToast('Account renamed', 'success');
      await loadAccountsList();
      
      // Update main screen if this is the active account
      const status = await sendMessage('getWalletStatus');
      if (status.success) {
        document.getElementById('account-name').textContent = 
          (await sendMessage('getAccounts')).accounts?.find(a => a.isActive)?.name || 'Account 1';
      }
    } else {
      showToast(result.error || 'Failed to rename account', 'error');
    }
  } catch (error) {
    showToast('Error renaming account', 'error');
  }
};

/**
 * Delete an account
 */
window.deleteAccount = async function(accountIndex) {
  if (!confirm('Are you sure you want to remove this account? This cannot be undone.')) return;

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
};