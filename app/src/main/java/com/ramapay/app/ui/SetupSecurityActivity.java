package com.ramapay.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.ramapay.app.R;
import com.ramapay.app.service.AppSecurityManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Activity for setting up app security (password + biometric)
 * Shown after first wallet creation/import
 */
@AndroidEntryPoint
public class SetupSecurityActivity extends BaseActivity {
    
    public static final String EXTRA_FROM_SETTINGS = "from_settings";
    public static final String EXTRA_IS_FIRST_SETUP = "is_first_setup";
    
    @Inject
    AppSecurityManager securityManager;
    
    private TextInputLayout passwordLayout;
    private TextInputEditText passwordInput;
    private TextInputLayout confirmPasswordLayout;
    private TextInputEditText confirmPasswordInput;
    private LinearLayout biometricSection;
    private SwitchMaterial biometricSwitch;
    private MaterialButton btnEnableSecurity;
    private MaterialButton btnSkip;
    private TextView passwordRequirements;
    
    private boolean fromSettings = false;
    private boolean isFirstSetup = true;
    
    public static Intent createIntent(Context context, boolean fromSettings, boolean isFirstSetup) {
        Intent intent = new Intent(context, SetupSecurityActivity.class);
        intent.putExtra(EXTRA_FROM_SETTINGS, fromSettings);
        intent.putExtra(EXTRA_IS_FIRST_SETUP, isFirstSetup);
        return intent;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_security);
        
        fromSettings = getIntent().getBooleanExtra(EXTRA_FROM_SETTINGS, false);
        isFirstSetup = getIntent().getBooleanExtra(EXTRA_IS_FIRST_SETUP, true);
        
        initViews();
        setupListeners();
        checkBiometricAvailability();
    }
    
    private void initViews() {
        passwordLayout = findViewById(R.id.password_layout);
        passwordInput = findViewById(R.id.password_input);
        confirmPasswordLayout = findViewById(R.id.confirm_password_layout);
        confirmPasswordInput = findViewById(R.id.confirm_password_input);
        biometricSection = findViewById(R.id.biometric_section);
        biometricSwitch = findViewById(R.id.biometric_switch);
        btnEnableSecurity = findViewById(R.id.btn_enable_security);
        btnSkip = findViewById(R.id.btn_skip);
        passwordRequirements = findViewById(R.id.password_requirements);
        
        // Hide skip button if coming from settings
        if (fromSettings) {
            btnSkip.setVisibility(View.GONE);
            
            // Add toolbar with back button
            if (toolbar() != null) {
                setTitle(getString(R.string.security_settings));
                enableDisplayHomeAsUp();
            }
            
            // If security is already enabled, show different UI for managing it
            if (securityManager.isSecurityEnabled()) {
                // Update hints for changing password
                passwordLayout.setHint(getString(R.string.current_password));
                confirmPasswordLayout.setHint(getString(R.string.new_password_optional));
                passwordRequirements.setText(R.string.enter_current_password_to_change);
                btnEnableSecurity.setText(R.string.save_changes);
                
                // Pre-set biometric switch to current state
                biometricSwitch.setChecked(securityManager.isBiometricEnabled());
            }
        }
    }
    
    private void setupListeners() {
        // Password validation
        TextWatcher passwordWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                validatePasswords();
            }
        };
        
        passwordInput.addTextChangedListener(passwordWatcher);
        confirmPasswordInput.addTextChangedListener(passwordWatcher);
        
        // Enable security button
        btnEnableSecurity.setOnClickListener(v -> enableSecurity());
        
        // Skip button
        btnSkip.setOnClickListener(v -> skipSecurity());
    }
    
    private void checkBiometricAvailability() {
        if (securityManager.isBiometricAvailable()) {
            biometricSection.setVisibility(View.VISIBLE);
            biometricSwitch.setChecked(true);
        } else {
            biometricSection.setVisibility(View.GONE);
            biometricSwitch.setChecked(false);
        }
    }
    
    private void validatePasswords() {
        String password = passwordInput.getText() != null ? passwordInput.getText().toString() : "";
        String confirmPassword = confirmPasswordInput.getText() != null ? confirmPasswordInput.getText().toString() : "";
        
        // Clear errors
        passwordLayout.setError(null);
        confirmPasswordLayout.setError(null);
        
        boolean isValid = true;
        
        // If security is already enabled and we're in settings, password is for verification
        boolean isChangingSettings = fromSettings && securityManager.isSecurityEnabled();
        
        if (isChangingSettings) {
            // Only need current password to make changes (new password is optional)
            btnEnableSecurity.setEnabled(password.length() >= 6);
        } else {
            // First time setup - need both passwords to match
            if (password.length() > 0 && password.length() < 6) {
                passwordLayout.setError(getString(R.string.password_too_short));
                isValid = false;
            }
            
            if (confirmPassword.length() > 0 && !password.equals(confirmPassword)) {
                confirmPasswordLayout.setError(getString(R.string.passwords_do_not_match));
                isValid = false;
            }
            
            btnEnableSecurity.setEnabled(password.length() >= 6 && password.equals(confirmPassword));
        }
    }
    
    private void enableSecurity() {
        String password = passwordInput.getText() != null ? passwordInput.getText().toString() : "";
        String confirmPassword = confirmPasswordInput.getText() != null ? confirmPasswordInput.getText().toString() : "";
        
        boolean isChangingSettings = fromSettings && securityManager.isSecurityEnabled();
        
        if (isChangingSettings) {
            // Verify current password first
            if (!securityManager.verifyPassword(password)) {
                passwordLayout.setError(getString(R.string.incorrect_password));
                return;
            }
            
            // If new password is provided, change it
            if (confirmPassword.length() >= 6) {
                boolean success = securityManager.changePassword(password, confirmPassword);
                if (!success) {
                    Toast.makeText(this, R.string.security_setup_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            
            // Update biometric setting
            boolean enableBiometric = biometricSwitch.isChecked() && securityManager.isBiometricAvailable();
            if (enableBiometric && !securityManager.isBiometricEnabled()) {
                // Verify biometric before enabling
                verifyBiometricForSettings();
                return;
            } else {
                securityManager.setBiometricEnabled(enableBiometric);
            }
            
            Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show();
            finishWithResult(true);
        } else {
            // First time setup
            if (password.length() < 6) {
                passwordLayout.setError(getString(R.string.password_too_short));
                return;
            }
            
            if (!password.equals(confirmPassword)) {
                confirmPasswordLayout.setError(getString(R.string.passwords_do_not_match));
                return;
            }
            
            boolean enableBiometric = biometricSwitch.isChecked() && securityManager.isBiometricAvailable();
            
            // If biometric is enabled, verify it first
            if (enableBiometric) {
                verifyBiometricAndSetup(password);
            } else {
                completeSecuritySetup(password, false);
            }
        }
    }
    
    private void verifyBiometricForSettings() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            Toast.makeText(SetupSecurityActivity.this, errString, Toast.LENGTH_SHORT).show();
                        }
                    }
                    
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        securityManager.setBiometricEnabled(true);
                        Toast.makeText(SetupSecurityActivity.this, R.string.password_changed, Toast.LENGTH_SHORT).show();
                        finishWithResult(true);
                    }
                    
                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        Toast.makeText(SetupSecurityActivity.this, 
                                R.string.fingerprint_authentication_failed, Toast.LENGTH_SHORT).show();
                    }
                });
        
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.enable_biometric))
                .setSubtitle(getString(R.string.authenticate_to_continue))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .build();
        
        biometricPrompt.authenticate(promptInfo);
    }
    
    private void verifyBiometricAndSetup(String password) {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // Still setup security but without biometric
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                            completeSecuritySetup(password, false);
                        } else {
                            Toast.makeText(SetupSecurityActivity.this, errString, Toast.LENGTH_SHORT).show();
                        }
                    }
                    
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        completeSecuritySetup(password, true);
                    }
                    
                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        Toast.makeText(SetupSecurityActivity.this, 
                                R.string.fingerprint_authentication_failed, Toast.LENGTH_SHORT).show();
                    }
                });
        
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.enable_biometric))
                .setSubtitle(getString(R.string.authenticate_to_continue))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .build();
        
        biometricPrompt.authenticate(promptInfo);
    }
    
    private void completeSecuritySetup(String password, boolean enableBiometric) {
        boolean success = securityManager.setupSecurity(password, enableBiometric);
        
        if (success) {
            Toast.makeText(this, R.string.security_enabled, Toast.LENGTH_SHORT).show();
            finishWithResult(true);
        } else {
            Toast.makeText(this, R.string.security_setup_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    private void skipSecurity() {
        securityManager.setSecuritySetupSkipped(true);
        Toast.makeText(this, R.string.setup_security_later, Toast.LENGTH_SHORT).show();
        finishWithResult(false);
    }
    
    private void finishWithResult(boolean securityEnabled) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("security_enabled", securityEnabled);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
    
    @Override
    public void onBackPressed() {
        if (fromSettings) {
            super.onBackPressed();
        } else {
            // Don't allow back press during first setup, must choose skip or enable
            Toast.makeText(this, R.string.setup_security_later, Toast.LENGTH_SHORT).show();
        }
    }
}
