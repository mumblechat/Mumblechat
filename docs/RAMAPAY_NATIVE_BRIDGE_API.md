# RamaPay Native Features for MumbleChat

## Overview

The RamaPay Android app provides native features to enhance the MumbleChat web experience through a JavaScript bridge.

## Features Available

### 1. **Input Text Color Fix** ✅
Automatically fixes white input text to be visible in both light and dark modes.

### 2. **File Attachments** 📎
Pick and attach files from the device.

### 3. **Photo/Image Attachments** 📷
Pick and attach photos from the device gallery.

### 4. **Crypto Payments** 💰
Send RAMA, MCT, or any token to chat participants.

### 5. **Transaction Receipts** 🧾
Display transaction confirmations with hash and share options.

---

## JavaScript API

The `RamaPay` object is globally available in the WebView after page load.

### Check if Native Features are Available

```javascript
// Listen for RamaPay ready event
window.addEventListener('RamaPayReady', (event) => {
  console.log('RamaPay native features available:', event.detail);
  // event.detail contains capabilities object
});

// Or check capabilities directly
const capabilities = window.RamaPay?.capabilities();
console.log(capabilities);
/*
{
  "fileAttachments": true,
  "imageAttachments": true,
  "cryptoPayments": true,
  "nativeBridge": true,
  "platform": "android",
  "version": "1.0.0"
}
*/
```

### Get Wallet Address

```javascript
const walletAddress = window.RamaPay.getWalletAddress();
console.log('Current wallet:', walletAddress);
// Returns: "0x1234...5678"
```

### Get Wallet Balances

```javascript
const balances = window.RamaPay.getBalances();
console.log(balances);
/*
{
  "RAMA": "1.5",
  "MCT": "100.0",
  "timestamp": 1704240000000
}
*/
```

### Pick a File Attachment

```javascript
// Pick any file type
window.RamaPay.pickFile((result) => {
  if (result.success) {
    console.log('File selected:', result.fileName);
    console.log('Size:', result.size, 'bytes');
    console.log('MIME type:', result.mimeType);
    console.log('Base64 data:', result.base64);
    
    // Send file through MumbleChat
    sendFileMessage(result.fileName, result.mimeType, result.base64);
  } else if (result.cancelled) {
    console.log('User cancelled file selection');
  } else {
    console.error('Error:', result.error);
  }
});
```

### Pick an Image/Photo

```javascript
// Pick image from gallery
window.RamaPay.pickImage((result) => {
  if (result.success) {
    console.log('Image selected:', result.fileName);
    
    // Display preview
    const img = document.createElement('img');
    img.src = 'data:' + result.mimeType + ';base64,' + result.base64;
    document.body.appendChild(img);
    
    // Send image through MumbleChat
    sendImageMessage(result.fileName, result.base64);
  }
});
```

### Send Crypto Payment

```javascript
// Send RAMA to someone
window.RamaPay.sendPayment(
  '0xRecipientAddress...',  // recipient wallet
  'RAMA',                    // token symbol
  '1.5'                      // amount
);

// Send MCT tokens
window.RamaPay.sendPayment(
  '0xRecipientAddress...',
  'MCT',
  '100'
);

// Send any ERC-20 token
window.RamaPay.sendPayment(
  '0xRecipientAddress...',
  'USDT',  // or any token symbol
  '50'
);
```

This will open the native SendActivity with pre-filled information. User confirms and signs the transaction natively.

### Show Transaction Receipt

```javascript
// After transaction is sent, display receipt
window.RamaPay.showReceipt(
  '0xTransactionHash...',
  {
    amount: '1.5',
    symbol: 'RAMA',
    recipient: '0xRecipientAddress...',
    from: '0xSenderAddress...',
    timestamp: Date.now()
  }
);
```

---

## Implementation Examples

### Example 1: Add File Attachment Button to Chat

```javascript
// Create attachment button
const attachButton = document.createElement('button');
attachButton.innerHTML = '📎';
attachButton.onclick = () => {
  if (window.RamaPay) {
    window.RamaPay.pickFile((result) => {
      if (result.success) {
        // Encrypt and send file through MumbleChat protocol
        encryptAndSendFile(result);
      }
    });
  } else {
    alert('Native file picker not available');
  }
};

// Add to chat input area
document.querySelector('.chat-input-container').appendChild(attachButton);
```

### Example 2: Send Payment in Chat

```javascript
// Parse message for payment intent
// Example: "/pay 0x1234... 10 MCT"
if (message.startsWith('/pay ')) {
  const [_, recipient, amount, token] = message.split(' ');
  
  if (window.RamaPay) {
    window.RamaPay.sendPayment(recipient, token, amount);
  } else {
    // Fallback: show web-based payment UI
    showWebPaymentDialog(recipient, amount, token);
  }
}
```

### Example 3: Image Attachment with Preview

```javascript
const imageButton = document.createElement('button');
imageButton.innerHTML = '📷';
imageButton.onclick = () => {
  window.RamaPay.pickImage((result) => {
    if (result.success && result.base64) {
      // Show preview
      const preview = document.createElement('div');
      preview.className = 'image-preview';
      preview.innerHTML = `
        <img src="data:${result.mimeType};base64,${result.base64}" />
        <p>${result.fileName} (${formatBytes(result.size)})</p>
        <button onclick="sendImage('${result.base64}')">Send</button>
        <button onclick="this.parentElement.remove()">Cancel</button>
      `;
      document.body.appendChild(preview);
    }
  });
};
```

### Example 4: Detect Features and Show Appropriate UI

```javascript
window.addEventListener('RamaPayReady', (event) => {
  const caps = event.detail;
  
  // Show native attachment buttons only if supported
  if (caps.fileAttachments) {
    document.getElementById('file-attach-btn').style.display = 'block';
  }
  
  if (caps.imageAttachments) {
    document.getElementById('image-attach-btn').style.display = 'block';
  }
  
  if (caps.cryptoPayments) {
    document.getElementById('send-crypto-btn').style.display = 'block';
  }
  
  console.log('Running on:', caps.platform, 'version:', caps.version);
});
```

---

## File Size Limits

Based on MumbleChat protocol documentation:

- **Standard messages**: Free (< 1024 characters)
- **Large messages** (> 1024 chars): Small MCT fee
- **Files < 50 MB**: Standard relay delivery
- **Files > 50 MB**: Higher MCT fee per MB

The app will handle fee calculation automatically when sending large files.

---

## Security Notes

1. **File Access**: Files are read with user permission only
2. **Payments**: Always require user confirmation in native SendActivity
3. **No Private Keys**: Private keys never exposed to WebView
4. **Signatures**: All signing happens in native Android code

---

## Error Handling

```javascript
window.RamaPay.pickFile((result) => {
  if (!result.success) {
    if (result.cancelled) {
      // User cancelled - no action needed
      console.log('User cancelled file selection');
    } else if (result.error) {
      // Show error to user
      alert('Error selecting file: ' + result.error);
    }
  }
});
```

---

## Browser/Web Fallback

Always check if `window.RamaPay` exists before using:

```javascript
function attachFile() {
  if (window.RamaPay) {
    // Use native file picker
    window.RamaPay.pickFile(handleFile);
  } else {
    // Use HTML file input as fallback
    const input = document.createElement('input');
    input.type = 'file';
    input.onchange = (e) => handleFile({
      success: true,
      fileName: e.target.files[0].name,
      // ... convert File to base64
    });
    input.click();
  }
}
```

---

## Testing

Open Chrome DevTools (Remote Debugging) to test:

1. Connect Android device via USB
2. Open `chrome://inspect` in Chrome
3. Find RamaPay WebView
4. Open DevTools
5. Run commands in console:

```javascript
// Test capabilities
console.log(window.RamaPay.capabilities());

// Test file picker
window.RamaPay.pickFile(console.log);

// Test wallet
console.log(window.RamaPay.getWalletAddress());
```

---

## Future Enhancements (Planned)

1. **Video attachment** with compression
2. **Audio recording** for voice messages
3. **Camera capture** (take photo directly)
4. **Contact picker** for recipient selection
5. **QR code scanner** for addresses
6. **Biometric confirmation** for payments
7. **Transaction history** viewer
8. **Token balance real-time updates**

---

## Questions?

See the MumbleChat Protocol documentation:
- `/docs/MUMBLECHAT_PROTOCOL/` - Full protocol spec
- `/docs/RAMAPAY_COMPLETE_GUIDE.md` - App integration guide

Smart Contracts (Ramestta Mainnet):
- **MCTToken**: `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE`
- **Registry**: `0x4f8D4955F370881B05b68D2344345E749d8632e3`
