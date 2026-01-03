package com.ramapay.app.chat.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.ui.SendActivity;
import com.ramapay.app.viewmodel.DappBrowserViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * JavaScript bridge for MumbleChat WebView
 * Provides native Android features to web chat:
 * - File/photo attachment picking
 * - Crypto payments (RAMA, MCT, tokens)
 * - Transaction receipts
 */
public class ChatBridge {
    
    private final Fragment fragment;
    private final WebView webView;
    private final Wallet wallet;
    private final DappBrowserViewModel viewModel;
    private final Gson gson = new Gson();
    
    // Callback for file selection
    private String fileCallbackId = null;
    
    // File picker launcher
    private ActivityResultLauncher<Intent> filePicker;
    private ActivityResultLauncher<Intent> imagePicker;
    
    public ChatBridge(Fragment fragment, WebView webView, Wallet wallet, DappBrowserViewModel viewModel) {
        this.fragment = fragment;
        this.webView = webView;
        this.wallet = wallet;
        this.viewModel = viewModel;
        
        setupFilePickerLaunchers();
    }
    
    private void setupFilePickerLaunchers() {
        // File picker (any file type)
        filePicker = fragment.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleFileSelected(result.getData().getData());
                } else {
                    notifyFileCancelled();
                }
            }
        );
        
        // Image picker (photos/videos)
        imagePicker = fragment.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleFileSelected(result.getData().getData());
                } else {
                    notifyFileCancelled();
                }
            }
        );
    }
    
    // ========== JavaScript Interface Methods ==========
    
    /**
     * Get wallet address for chat identity
     */
    @JavascriptInterface
    public String getWalletAddress() {
        return wallet != null ? wallet.address : "";
    }
    
    /**
     * Get wallet balances (RAMA, MCT, and other tokens)
     */
    @JavascriptInterface
    public String getWalletBalances() {
        JSONObject balances = new JSONObject();
        try {
            // For now, return placeholder - will be implemented with real token data
            balances.put("RAMA", "0");
            balances.put("MCT", "0");
            balances.put("timestamp", System.currentTimeMillis());
        } catch (JSONException e) {
            Timber.e(e, "Error creating balances JSON");
        }
        return balances.toString();
    }
    
    /**
     * Pick a file attachment (documents, PDFs, etc.)
     */
    @JavascriptInterface
    public void pickFile(String callbackId) {
        Timber.d("pickFile called with callbackId: %s", callbackId);
        this.fileCallbackId = callbackId;
        
        fragment.requireActivity().runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            
            try {
                filePicker.launch(Intent.createChooser(intent, "Select File"));
            } catch (Exception e) {
                Timber.e(e, "Error launching file picker");
                notifyFileError("Failed to open file picker");
            }
        });
    }
    
    /**
     * Pick an image/photo attachment
     */
    @JavascriptInterface
    public void pickImage(String callbackId) {
        Timber.d("pickImage called with callbackId: %s", callbackId);
        this.fileCallbackId = callbackId;
        
        fragment.requireActivity().runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            
            try {
                imagePicker.launch(Intent.createChooser(intent, "Select Photo"));
            } catch (Exception e) {
                Timber.e(e, "Error launching image picker");
                notifyFileError("Failed to open image picker");
            }
        });
    }
    
    /**
     * Send crypto payment (RAMA, MCT, or any token)
     * @param recipient Wallet address to send to
     * @param tokenSymbol Symbol of token (RAMA, MCT, etc.)
     * @param amount Amount to send
     */
    @JavascriptInterface
    public void sendCryptoPayment(String recipient, String tokenSymbol, String amount) {
        Timber.d("sendCryptoPayment: %s %s to %s", amount, tokenSymbol, recipient);
        
        fragment.requireActivity().runOnUiThread(() -> {
            try {
                // Launch SendActivity with pre-filled data
                Intent intent = new Intent(fragment.getContext(), SendActivity.class);
                intent.putExtra("wallet", wallet);
                intent.putExtra("recipient", recipient);
                intent.putExtra("symbol", tokenSymbol);
                intent.putExtra("amount", amount);
                intent.putExtra("fromChat", true);
                fragment.startActivity(intent);
                
                Toast.makeText(fragment.getContext(), 
                    "Opening payment for " + amount + " " + tokenSymbol, 
                    Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Timber.e(e, "Error launching SendActivity");
                notifyPaymentError("Failed to open payment screen");
            }
        });
    }
    
    /**
     * Show transaction receipt/confirmation
     * @param txHash Transaction hash
     * @param details JSON string with transaction details
     */
    @JavascriptInterface
    public void showTransactionReceipt(String txHash, String details) {
        Timber.d("showTransactionReceipt: %s", txHash);
        
        fragment.requireActivity().runOnUiThread(() -> {
            try {
                JSONObject detailsJson = new JSONObject(details);
                String message = String.format(
                    "Transaction sent!\n\nHash: %s\n\nAmount: %s %s\nTo: %s",
                    txHash.substring(0, 10) + "..." + txHash.substring(txHash.length() - 8),
                    detailsJson.optString("amount", ""),
                    detailsJson.optString("symbol", ""),
                    detailsJson.optString("recipient", "")
                );
                
                Toast.makeText(fragment.getContext(), message, Toast.LENGTH_LONG).show();
                
                // TODO: Create a proper receipt dialog with share button
            } catch (JSONException e) {
                Timber.e(e, "Error parsing transaction details");
            }
        });
    }
    
    /**
     * Get device capabilities
     */
    @JavascriptInterface
    public String getCapabilities() {
        JSONObject capabilities = new JSONObject();
        try {
            capabilities.put("fileAttachments", true);
            capabilities.put("imageAttachments", true);
            capabilities.put("cryptoPayments", true);
            capabilities.put("nativeBridge", true);
            capabilities.put("platform", "android");
            capabilities.put("version", "1.0.0");
        } catch (JSONException e) {
            Timber.e(e, "Error creating capabilities JSON");
        }
        return capabilities.toString();
    }
    
    // ========== Internal Helper Methods ==========
    
    private void handleFileSelected(Uri uri) {
        if (uri == null || fileCallbackId == null) {
            notifyFileError("No file selected");
            return;
        }
        
        fragment.requireActivity().runOnUiThread(() -> {
            try {
                // Get file info
                String fileName = getFileName(uri);
                String mimeType = fragment.requireContext().getContentResolver().getType(uri);
                long fileSize = getFileSize(uri);
                
                // Read file content to base64
                String base64Content = readFileToBase64(uri);
                
                // Create result JSON
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("uri", uri.toString());
                result.put("fileName", fileName);
                result.put("mimeType", mimeType);
                result.put("size", fileSize);
                result.put("base64", base64Content);
                
                // Call JavaScript callback
                String jsCallback = String.format(
                    "if (window.onFileSelected) { window.onFileSelected('%s', %s); }",
                    fileCallbackId,
                    result.toString()
                );
                webView.evaluateJavascript(jsCallback, null);
                
                Timber.d("File selected: %s (%d bytes)", fileName, fileSize);
                
            } catch (Exception e) {
                Timber.e(e, "Error handling file selection");
                notifyFileError("Failed to read file: " + e.getMessage());
            } finally {
                fileCallbackId = null;
            }
        });
    }
    
    private void notifyFileCancelled() {
        if (fileCallbackId != null) {
            String jsCallback = String.format(
                "if (window.onFileSelected) { window.onFileSelected('%s', {success: false, cancelled: true}); }",
                fileCallbackId
            );
            webView.post(() -> webView.evaluateJavascript(jsCallback, null));
            fileCallbackId = null;
        }
    }
    
    private void notifyFileError(String error) {
        if (fileCallbackId != null) {
            try {
                JSONObject result = new JSONObject();
                result.put("success", false);
                result.put("error", error);
                
                String jsCallback = String.format(
                    "if (window.onFileSelected) { window.onFileSelected('%s', %s); }",
                    fileCallbackId,
                    result.toString()
                );
                webView.post(() -> webView.evaluateJavascript(jsCallback, null));
            } catch (JSONException e) {
                Timber.e(e, "Error creating error JSON");
            } finally {
                fileCallbackId = null;
            }
        }
    }
    
    private void notifyPaymentError(String error) {
        Toast.makeText(fragment.getContext(), error, Toast.LENGTH_SHORT).show();
        webView.post(() -> {
            String jsCallback = String.format(
                "if (window.onPaymentError) { window.onPaymentError('%s'); }",
                error.replace("'", "\\'")
            );
            webView.evaluateJavascript(jsCallback, null);
        });
    }
    
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = fragment.requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Timber.e(e, "Error getting file name");
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
    
    private long getFileSize(Uri uri) {
        long size = 0;
        try (android.database.Cursor cursor = fragment.requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            Timber.e(e, "Error getting file size");
        }
        return size;
    }
    
    private String readFileToBase64(Uri uri) throws Exception {
        try (InputStream inputStream = fragment.requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new Exception("Cannot open input stream");
            }
            
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
            byte[] buffer = new byte[8192];
            int bytesRead;
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            
            while ((bytesRead = bufferedInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            
            byte[] fileBytes = byteArrayOutputStream.toByteArray();
            return android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP);
        }
    }
}
