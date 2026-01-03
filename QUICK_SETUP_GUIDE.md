# Quick Setup & Testing Guide

## 📱 Install the App

### Step 1: Connect Your Android Device

```bash
# Enable USB Debugging on your Android phone:
# Settings > Developer Options > USB Debugging > ON

# Connect phone via USB cable

# Verify connection
adb devices
# Should show: List of devices attached
#              <device-id>    device
```

### Step 2: Install APK

```bash
cd /Users/dev/Downloads/Blockchain/Mumblechat\ Ramestta\ Protocol

# Install the app
adb install -r app/build/outputs/apk/noAnalytics/debug/RamaPay.apk

# Should show: Success
```

### Step 3: Launch & Test

1. **Open RamaPay app** on your phone
2. **Create/Import wallet** (if first time)
3. **Go to Chat tab** (bottom navigation)
4. **Web page loads**: https://mumblechat.com/conversations

---

## 🧪 Test the Features

### Test 1: Input Text Color ✅

1. Open any chat conversation
2. Tap on the message input field
3. **Expected**: Text is now visible (dark/white depending on theme)
4. **Before**: Text was white on white (invisible)

### Test 2: JavaScript Bridge

#### Option A: Chrome DevTools (Recommended)

```bash
# On your computer (while phone is connected):
1. Open Chrome browser
2. Go to: chrome://inspect
3. Find "RamaPay" under Remote Target
4. Click "Inspect"
5. Go to Console tab
```

Run these commands in the console:

```javascript
// 1. Check if bridge is loaded
window.RamaPay
// Should return: Object with pickFile, pickImage, sendPayment, etc.

// 2. Check capabilities
window.RamaPay.capabilities()
// Should return: {fileAttachments: true, imageAttachments: true, ...}

// 3. Get your wallet address
window.RamaPay.getWalletAddress()
// Should return: "0x..."

// 4. Test file picker
window.RamaPay.pickFile((result) => {
  console.log('File selected:', result);
});
// Should open file picker on phone
// Select any file
// Check console for result

// 5. Test image picker
window.RamaPay.pickImage((result) => {
  console.log('Image selected:', result);
});
// Should open photo picker on phone

// 6. Test payment (replace with real address)
window.RamaPay.sendPayment('0xYourFriendAddress', 'MCT', '1');
// Should open SendActivity with pre-filled data
```

#### Option B: In-App Console

If you have a web console in MumbleChat:

```javascript
console.log('Bridge available:', !!window.RamaPay);
console.log('My address:', window.RamaPay.getWalletAddress());
```

### Test 3: File Attachment (If Web UI Supports It)

If MumbleChat web app has an attach button:

1. Click attach button
2. Should call `window.RamaPay.pickFile()`
3. Native Android file picker opens
4. Select a file
5. File data returned to web app

### Test 4: Crypto Payment

1. In chat, type: `/pay 0xRecipientAddress 10 MCT`
2. Or click "Send Payment" button (if web UI has it)
3. Should open native SendActivity
4. Review transaction details
5. Confirm and sign

---

## 📊 View Logs

### Real-time Logs

```bash
# Watch all RamaPay logs
adb logcat | grep -i "ramapay\|mumblechat\|bridge"

# Watch only bridge activity
adb logcat | grep "ChatBridge"

# Watch WebView console
adb logcat | grep "chromium"
```

### Check for Errors

```bash
# Filter errors only
adb logcat | grep -E "ERROR|FATAL"
```

---

## 🔍 Troubleshooting

### Issue: APK won't install

```bash
# Uninstall old version first
adb uninstall com.ramapay.app

# Then install fresh
adb install app/build/outputs/apk/noAnalytics/debug/RamaPay.apk
```

### Issue: Device not found

```bash
# Kill and restart adb
adb kill-server
adb start-server
adb devices
```

### Issue: Input text still invisible

1. Open Chrome DevTools (chrome://inspect)
2. Check if CSS was injected:
   ```javascript
   document.querySelector('style').innerHTML.includes('input')
   ```
3. If not, reload the page
4. Check console for errors

### Issue: Bridge not available

```javascript
// Check if injected
typeof window.RamaPayBridge
// Should return: "object"

typeof window.RamaPay
// Should return: "object"

// If undefined, wait for page load
window.addEventListener('RamaPayReady', (e) => {
  console.log('Bridge ready:', e.detail);
});
```

---

## 🎯 What to Test

### ✅ Checklist

- [ ] App installs successfully
- [ ] Wallet creates/imports without errors
- [ ] Chat tab loads MumbleChat website
- [ ] Input text is visible (not white on white)
- [ ] `window.RamaPay` object exists (check DevTools)
- [ ] `getWalletAddress()` returns your address
- [ ] `pickFile()` opens file picker
- [ ] `pickImage()` opens photo picker
- [ ] `sendPayment()` opens SendActivity
- [ ] File selection returns base64 data
- [ ] Payment screen shows pre-filled data

---

## 📸 Expected Behavior

### Before (Issues):
- ❌ Input text invisible (white on white)
- ❌ No native file picker
- ❌ No photo attachment
- ❌ No crypto payment integration

### After (Fixed):
- ✅ Input text visible in all themes
- ✅ Native file picker via JavaScript
- ✅ Native photo picker via JavaScript
- ✅ Crypto payment opens SendActivity
- ✅ Full JavaScript bridge for web app
- ✅ Transaction receipts ready

---

## 🚀 Next: Web App Integration

Once you verify the bridge works, you can:

1. **Contact MumbleChat web developers**
2. **Share API documentation**: `docs/RAMAPAY_NATIVE_BRIDGE_API.md`
3. **Request attachment buttons** in web UI
4. **Request payment integration** in chat interface
5. **Test end-to-end file sharing**

---

## 💡 Quick Test Script

Save this as `test-bridge.html` and load in WebView:

```html
<!DOCTYPE html>
<html>
<head>
  <title>RamaPay Bridge Test</title>
  <style>
    body { font-family: sans-serif; padding: 20px; }
    button { margin: 10px; padding: 10px 20px; }
    .result { background: #f0f0f0; padding: 10px; margin: 10px 0; }
  </style>
</head>
<body>
  <h1>RamaPay Bridge Test</h1>
  
  <button onclick="testCapabilities()">Test Capabilities</button>
  <button onclick="testWallet()">Get Wallet Address</button>
  <button onclick="testFilePicker()">Pick File</button>
  <button onclick="testImagePicker()">Pick Image</button>
  <button onclick="testPayment()">Test Payment</button>
  
  <div id="results"></div>
  
  <script>
    const log = (msg) => {
      const div = document.createElement('div');
      div.className = 'result';
      div.textContent = JSON.stringify(msg, null, 2);
      document.getElementById('results').appendChild(div);
    };
    
    function testCapabilities() {
      if (window.RamaPay) {
        log(window.RamaPay.capabilities());
      } else {
        log('ERROR: Bridge not available');
      }
    }
    
    function testWallet() {
      if (window.RamaPay) {
        log({ address: window.RamaPay.getWalletAddress() });
      }
    }
    
    function testFilePicker() {
      if (window.RamaPay) {
        window.RamaPay.pickFile((result) => {
          log({ file: result });
        });
      }
    }
    
    function testImagePicker() {
      if (window.RamaPay) {
        window.RamaPay.pickImage((result) => {
          log({ image: result });
        });
      }
    }
    
    function testPayment() {
      if (window.RamaPay) {
        window.RamaPay.sendPayment(
          '0x742d35Cc6634C0532925a3b844Bc454e4438f44e',
          'MCT',
          '1'
        );
      }
    }
    
    window.addEventListener('RamaPayReady', (e) => {
      log({ event: 'RamaPayReady', detail: e.detail });
    });
  </script>
</body>
</html>
```

Load this in the WebView to test all features!

---

## 📞 Need Help?

Check the logs:
```bash
adb logcat | grep -i "ramapay\|bridge\|error"
```

If issues persist, share:
1. Device model and Android version
2. Error logs from adb logcat
3. What you were trying to do
4. Expected vs actual behavior

---

**Happy Testing! 🎉**
