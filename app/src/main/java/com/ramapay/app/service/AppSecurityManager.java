package com.ramapay.app.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import timber.log.Timber;

/**
 * Manages app-wide security settings including:
 * - Password-based authentication
 * - Biometric (fingerprint/face) authentication
 * - App lock functionality
 * - Security state management
 */
@Singleton
public class AppSecurityManager {
    
    private static final String PREFS_NAME = "ramapay_security_prefs";
    private static final String KEY_SECURITY_ENABLED = "security_enabled";
    private static final String KEY_PASSWORD_HASH = "password_hash";
    private static final String KEY_PASSWORD_SALT = "password_salt";
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";
    private static final String KEY_SECURITY_SETUP_SKIPPED = "security_setup_skipped";
    private static final String KEY_FIRST_WALLET_CREATED = "first_wallet_created";
    private static final String KEY_LAST_AUTH_TIME = "last_auth_time";
    private static final String KEY_AUTH_TIMEOUT = "auth_timeout";
    private static final String KEY_TRANSACTION_AUTH_ENABLED = "transaction_auth_enabled";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_USING_PIN = "using_pin";  // true = PIN, false = password
    
    // Timeout options in milliseconds
    public static final long TIMEOUT_1_MIN = 1 * 60 * 1000;
    public static final long TIMEOUT_5_MIN = 5 * 60 * 1000;
    public static final long TIMEOUT_15_MIN = 15 * 60 * 1000;
    public static final long TIMEOUT_30_MIN = 30 * 60 * 1000;
    public static final long TIMEOUT_IMMEDIATE = 0; // Always require auth
    
    // PIN length
    public static final int PIN_LENGTH = 6;
    
    // Default timeout - 5 minutes
    private static final long DEFAULT_TIMEOUT_MS = TIMEOUT_5_MIN;
    
    private final Context context;
    private SharedPreferences securePrefs;
    private boolean isAuthenticated = false;
    private long lastAuthTime = 0;
    
    @Inject
    public AppSecurityManager(@ApplicationContext Context context) {
        this.context = context;
        initSecurePreferences();
    }
    
    private void initSecurePreferences() {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            
            securePrefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Timber.e(e, "Failed to create secure preferences, falling back to regular prefs");
            securePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }
    
    /**
     * Check if security is enabled for the app
     */
    public boolean isSecurityEnabled() {
        return securePrefs.getBoolean(KEY_SECURITY_ENABLED, false);
    }
    
    /**
     * Check if biometric authentication is enabled
     */
    public boolean isBiometricEnabled() {
        return securePrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }
    
    /**
     * Check if the user skipped security setup during first wallet creation
     */
    public boolean isSecuritySetupSkipped() {
        return securePrefs.getBoolean(KEY_SECURITY_SETUP_SKIPPED, false);
    }
    
    /**
     * Check if this is the first wallet being created (no wallet has been created before)
     */
    public boolean isFirstWalletCreated() {
        return securePrefs.getBoolean(KEY_FIRST_WALLET_CREATED, false);
    }
    
    /**
     * Mark that the first wallet has been created
     */
    public void setFirstWalletCreated() {
        securePrefs.edit().putBoolean(KEY_FIRST_WALLET_CREATED, true).apply();
    }
    
    /**
     * Set security setup as skipped
     */
    public void setSecuritySetupSkipped(boolean skipped) {
        securePrefs.edit().putBoolean(KEY_SECURITY_SETUP_SKIPPED, skipped).apply();
    }
    
    /**
     * Check if biometric hardware is available on this device
     */
    public boolean isBiometricAvailable() {
        BiometricManager biometricManager = BiometricManager.from(context);
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        );
        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }
    
    /**
     * Check if device has biometric enrolled
     */
    public boolean hasBiometricEnrolled() {
        BiometricManager biometricManager = BiometricManager.from(context);
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        );
        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }
    
    /**
     * Enum representing available biometric types
     */
    public enum BiometricType {
        NONE,
        FINGERPRINT,
        FACE,
        IRIS,
        MULTIPLE  // Device has multiple biometric types
    }
    
    /**
     * Detect what type of biometric hardware is available on the device
     * @return The primary biometric type available
     */
    public BiometricType getBiometricType() {
        if (!isBiometricAvailable()) {
            return BiometricType.NONE;
        }
        
        android.content.pm.PackageManager pm = context.getPackageManager();
        
        boolean hasFace = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE);
        boolean hasFingerprint = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT);
        boolean hasIris = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_IRIS);
        
        // Count how many types are available
        int count = 0;
        if (hasFace) count++;
        if (hasFingerprint) count++;
        if (hasIris) count++;
        
        if (count > 1) {
            return BiometricType.MULTIPLE;
        }
        
        if (hasFace) {
            return BiometricType.FACE;
        }
        
        if (hasFingerprint) {
            return BiometricType.FINGERPRINT;
        }
        
        if (hasIris) {
            return BiometricType.IRIS;
        }
        
        // Default to fingerprint if we can't determine
        return BiometricType.FINGERPRINT;
    }
    
    /**
     * Check if the device primarily uses Face authentication
     */
    public boolean isFaceAuthDevice() {
        BiometricType type = getBiometricType();
        return type == BiometricType.FACE;
    }
    
    /**
     * Setup password-based security
     * @param password The user's chosen password
     * @param enableBiometric Whether to enable biometric authentication
     * @return true if setup was successful
     */
    public boolean setupSecurity(@NonNull String password, boolean enableBiometric) {
        try {
            // Generate salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash password with salt
            String passwordHash = hashPassword(password, salt);
            
            // Store in secure preferences
            securePrefs.edit()
                    .putString(KEY_PASSWORD_HASH, passwordHash)
                    .putString(KEY_PASSWORD_SALT, saltBase64)
                    .putBoolean(KEY_BIOMETRIC_ENABLED, enableBiometric && isBiometricAvailable())
                    .putBoolean(KEY_SECURITY_ENABLED, true)
                    .putBoolean(KEY_SECURITY_SETUP_SKIPPED, false)
                    .apply();
            
            isAuthenticated = true;
            lastAuthTime = System.currentTimeMillis();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to setup security");
            return false;
        }
    }
    
    /**
     * Verify password
     * @param password The password to verify
     * @return true if password matches
     */
    public boolean verifyPassword(@NonNull String password) {
        try {
            String storedHash = securePrefs.getString(KEY_PASSWORD_HASH, null);
            String saltBase64 = securePrefs.getString(KEY_PASSWORD_SALT, null);
            
            if (storedHash == null || saltBase64 == null) {
                return false;
            }
            
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            String inputHash = hashPassword(password, salt);
            
            boolean matches = storedHash.equals(inputHash);
            if (matches) {
                isAuthenticated = true;
                lastAuthTime = System.currentTimeMillis();
            }
            return matches;
        } catch (Exception e) {
            Timber.e(e, "Failed to verify password");
            return false;
        }
    }
    
    /**
     * Change password
     * @param currentPassword Current password for verification
     * @param newPassword New password to set
     * @return true if password was changed successfully
     */
    public boolean changePassword(@NonNull String currentPassword, @NonNull String newPassword) {
        if (!verifyPassword(currentPassword)) {
            return false;
        }
        
        try {
            // Generate new salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash new password
            String passwordHash = hashPassword(newPassword, salt);
            
            securePrefs.edit()
                    .putString(KEY_PASSWORD_HASH, passwordHash)
                    .putString(KEY_PASSWORD_SALT, saltBase64)
                    .apply();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to change password");
            return false;
        }
    }
    
    /**
     * Set a new password (used when changing password after verification)
     * @param newPassword The new password to set
     * @return true if password was set successfully
     */
    public boolean setPassword(@NonNull String newPassword) {
        try {
            // Generate new salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash new password
            String passwordHash = hashPassword(newPassword, salt);
            
            securePrefs.edit()
                    .putString(KEY_PASSWORD_HASH, passwordHash)
                    .putString(KEY_PASSWORD_SALT, saltBase64)
                    .apply();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to set password");
            return false;
        }
    }
    
    /**
     * Enable or disable biometric authentication
     */
    public void setBiometricEnabled(boolean enabled) {
        securePrefs.edit()
                .putBoolean(KEY_BIOMETRIC_ENABLED, enabled && isBiometricAvailable())
                .apply();
    }
    
    /**
     * Mark authentication as successful (called after biometric auth)
     */
    public void onAuthenticationSuccess() {
        isAuthenticated = true;
        lastAuthTime = System.currentTimeMillis();
    }
    
    /**
     * Check if user is currently authenticated and session is still valid
     */
    public boolean isSessionValid() {
        if (!isSecurityEnabled()) {
            return true;
        }
        
        if (!isAuthenticated) {
            return false;
        }
        
        // Check if auth has timed out
        long currentTime = System.currentTimeMillis();
        long timeout = getAuthTimeout();
        if (timeout == TIMEOUT_IMMEDIATE || currentTime - lastAuthTime > timeout) {
            isAuthenticated = false;
            return false;
        }
        
        return true;
    }
    
    /**
     * Get the configured authentication timeout
     */
    public long getAuthTimeout() {
        return securePrefs.getLong(KEY_AUTH_TIMEOUT, DEFAULT_TIMEOUT_MS);
    }
    
    /**
     * Set the authentication timeout
     * @param timeoutMs Timeout in milliseconds
     */
    public void setAuthTimeout(long timeoutMs) {
        securePrefs.edit().putLong(KEY_AUTH_TIMEOUT, timeoutMs).apply();
    }
    
    /**
     * Check if transaction-level authentication is enabled
     */
    public boolean isTransactionAuthEnabled() {
        return securePrefs.getBoolean(KEY_TRANSACTION_AUTH_ENABLED, false);
    }
    
    /**
     * Enable or disable transaction-level authentication
     */
    public void setTransactionAuthEnabled(boolean enabled) {
        securePrefs.edit().putBoolean(KEY_TRANSACTION_AUTH_ENABLED, enabled).apply();
    }
    
    /**
     * Refresh the authentication session
     */
    public void refreshSession() {
        if (isAuthenticated) {
            lastAuthTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Lock the app (require re-authentication)
     */
    public void lockApp() {
        isAuthenticated = false;
        lastAuthTime = 0;
    }
    
    /**
     * Disable security entirely
     * @param password Current password for verification
     * @return true if security was disabled
     */
    public boolean disableSecurity(@NonNull String password) {
        if (!verifyPassword(password)) {
            return false;
        }
        
        securePrefs.edit()
                .remove(KEY_PASSWORD_HASH)
                .remove(KEY_PASSWORD_SALT)
                .putBoolean(KEY_BIOMETRIC_ENABLED, false)
                .putBoolean(KEY_SECURITY_ENABLED, false)
                .apply();
        
        isAuthenticated = false;
        return true;
    }
    
    /**
     * Check if authentication is required for app access
     */
    public boolean requiresAuthentication() {
        return isSecurityEnabled() && !isSessionValid();
    }
    
    /**
     * Hash password with salt using SHA-256
     */
    private String hashPassword(String password, byte[] salt) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        // Double hash for extra security
        hash = digest.digest(hash);
        return Base64.getEncoder().encodeToString(hash);
    }
    
    // ==================== PIN Methods ====================
    
    /**
     * Check if user is using PIN instead of password
     */
    public boolean isUsingPin() {
        return securePrefs.getBoolean(KEY_USING_PIN, false);
    }
    
    /**
     * Setup PIN-based security
     * @param pin The 6-digit PIN
     * @param enableBiometric Whether to enable biometric authentication
     * @return true if setup was successful
     */
    public boolean setupSecurityWithPin(@NonNull String pin, boolean enableBiometric) {
        if (pin.length() != PIN_LENGTH || !pin.matches("\\d+")) {
            return false;
        }
        
        try {
            // Generate salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash PIN with salt
            String pinHash = hashPassword(pin, salt);
            
            // Store in secure preferences
            securePrefs.edit()
                    .putString(KEY_PIN_HASH, pinHash)
                    .putString(KEY_PIN_SALT, saltBase64)
                    .putBoolean(KEY_USING_PIN, true)
                    .putBoolean(KEY_BIOMETRIC_ENABLED, enableBiometric && isBiometricAvailable())
                    .putBoolean(KEY_SECURITY_ENABLED, true)
                    .putBoolean(KEY_SECURITY_SETUP_SKIPPED, false)
                    // Clear password-based auth
                    .remove(KEY_PASSWORD_HASH)
                    .remove(KEY_PASSWORD_SALT)
                    .apply();
            
            isAuthenticated = true;
            lastAuthTime = System.currentTimeMillis();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to setup PIN security");
            return false;
        }
    }
    
    /**
     * Verify PIN
     * @param pin The PIN to verify
     * @return true if PIN matches
     */
    public boolean verifyPin(@NonNull String pin) {
        try {
            String storedHash = securePrefs.getString(KEY_PIN_HASH, null);
            String saltBase64 = securePrefs.getString(KEY_PIN_SALT, null);
            
            if (storedHash == null || saltBase64 == null) {
                return false;
            }
            
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            String inputHash = hashPassword(pin, salt);
            
            boolean matches = storedHash.equals(inputHash);
            if (matches) {
                isAuthenticated = true;
                lastAuthTime = System.currentTimeMillis();
            }
            return matches;
        } catch (Exception e) {
            Timber.e(e, "Failed to verify PIN");
            return false;
        }
    }
    
    /**
     * Change PIN
     * @param currentPin Current PIN for verification
     * @param newPin New PIN to set
     * @return true if PIN was changed successfully
     */
    public boolean changePin(@NonNull String currentPin, @NonNull String newPin) {
        if (!verifyPin(currentPin)) {
            return false;
        }
        
        if (newPin.length() != PIN_LENGTH || !newPin.matches("\\d+")) {
            return false;
        }
        
        try {
            // Generate new salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash new PIN
            String pinHash = hashPassword(newPin, salt);
            
            securePrefs.edit()
                    .putString(KEY_PIN_HASH, pinHash)
                    .putString(KEY_PIN_SALT, saltBase64)
                    .apply();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to change PIN");
            return false;
        }
    }
    
    /**
     * Set a new PIN (after verification)
     * @param newPin The new PIN to set
     * @return true if PIN was set successfully
     */
    public boolean setPin(@NonNull String newPin) {
        if (newPin.length() != PIN_LENGTH || !newPin.matches("\\d+")) {
            return false;
        }
        
        try {
            // Generate new salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash new PIN
            String pinHash = hashPassword(newPin, salt);
            
            securePrefs.edit()
                    .putString(KEY_PIN_HASH, pinHash)
                    .putString(KEY_PIN_SALT, saltBase64)
                    .apply();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to set PIN");
            return false;
        }
    }
    
    /**
     * Switch from PIN to password
     * @param currentPin Current PIN for verification
     * @param newPassword New password to set
     * @return true if switch was successful
     */
    public boolean switchToPassword(@NonNull String currentPin, @NonNull String newPassword) {
        if (!verifyPin(currentPin)) {
            return false;
        }
        
        try {
            // Generate salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash password with salt
            String passwordHash = hashPassword(newPassword, salt);
            
            // Store in secure preferences
            securePrefs.edit()
                    .putString(KEY_PASSWORD_HASH, passwordHash)
                    .putString(KEY_PASSWORD_SALT, saltBase64)
                    .putBoolean(KEY_USING_PIN, false)
                    // Clear PIN-based auth
                    .remove(KEY_PIN_HASH)
                    .remove(KEY_PIN_SALT)
                    .apply();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to switch to password");
            return false;
        }
    }
    
    /**
     * Switch from password to PIN
     * @param currentPassword Current password for verification
     * @param newPin New PIN to set
     * @return true if switch was successful
     */
    public boolean switchToPin(@NonNull String currentPassword, @NonNull String newPin) {
        if (!verifyPassword(currentPassword)) {
            return false;
        }
        
        if (newPin.length() != PIN_LENGTH || !newPin.matches("\\d+")) {
            return false;
        }
        
        try {
            // Generate salt
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            
            // Hash PIN with salt
            String pinHash = hashPassword(newPin, salt);
            
            // Store in secure preferences
            securePrefs.edit()
                    .putString(KEY_PIN_HASH, pinHash)
                    .putString(KEY_PIN_SALT, saltBase64)
                    .putBoolean(KEY_USING_PIN, true)
                    // Clear password-based auth
                    .remove(KEY_PASSWORD_HASH)
                    .remove(KEY_PASSWORD_SALT)
                    .apply();
            
            return true;
        } catch (Exception e) {
            Timber.e(e, "Failed to switch to PIN");
            return false;
        }
    }
    
    /**
     * Verify credential (PIN or password) based on current mode
     * @param credential The PIN or password
     * @return true if credential matches
     */
    public boolean verifyCredential(@NonNull String credential) {
        if (isUsingPin()) {
            return verifyPin(credential);
        } else {
            return verifyPassword(credential);
        }
    }
    
    /**
     * Disable security using PIN
     * @param pin Current PIN for verification
     * @return true if security was disabled
     */
    public boolean disableSecurityWithPin(@NonNull String pin) {
        if (!verifyPin(pin)) {
            return false;
        }
        
        securePrefs.edit()
                .remove(KEY_PIN_HASH)
                .remove(KEY_PIN_SALT)
                .putBoolean(KEY_USING_PIN, false)
                .putBoolean(KEY_BIOMETRIC_ENABLED, false)
                .putBoolean(KEY_SECURITY_ENABLED, false)
                .apply();
        
        isAuthenticated = false;
        return true;
    }
}
