package com.ramapay.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.ramapay.app.R;
import com.ramapay.app.service.AppSecurityManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Activity for managing security settings after initial setup
 * Allows configuring auto-lock timeout, transaction authentication, and biometrics
 */
@AndroidEntryPoint
public class SecuritySettingsActivity extends BaseActivity {

    @Inject
    AppSecurityManager securityManager;

    private Spinner timeoutSpinner;
    private SwitchMaterial transactionAuthSwitch;
    private SwitchMaterial biometricSwitch;
    private MaterialButton btnChangePassword;
    private ImageView iconBiometric;

    // Timeout values in milliseconds matching the spinner positions
    private final long[] timeoutValues = {
            AppSecurityManager.TIMEOUT_1_MIN,
            AppSecurityManager.TIMEOUT_5_MIN,
            AppSecurityManager.TIMEOUT_15_MIN,
            AppSecurityManager.TIMEOUT_30_MIN
    };

    public static Intent createIntent(Context context) {
        return new Intent(context, SecuritySettingsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_settings);

        initToolbar();
        initViews();
        loadCurrentSettings();
        setupListeners();
    }

    private void initToolbar() {
        if (toolbar() != null) {
            setTitle(getString(R.string.security_settings));
            enableDisplayHomeAsUp();
        }
    }

    private void initViews() {
        timeoutSpinner = findViewById(R.id.timeout_spinner);
        transactionAuthSwitch = findViewById(R.id.transaction_auth_switch);
        biometricSwitch = findViewById(R.id.biometric_switch);
        btnChangePassword = findViewById(R.id.btn_change_password);
        iconBiometric = findViewById(R.id.icon_biometric);

        // Setup timeout spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.timeout_options,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        timeoutSpinner.setAdapter(adapter);

        // Check if biometric is available and update icon
        checkBiometricAvailability();
        updateBiometricIcon();
    }
    
    private void updateBiometricIcon() {
        if (iconBiometric == null) return;
        
        AppSecurityManager.BiometricType biometricType = securityManager.getBiometricType();
        switch (biometricType) {
            case FACE:
                iconBiometric.setImageResource(R.drawable.ic_face_id);
                break;
            case FINGERPRINT:
            case MULTIPLE:
            default:
                iconBiometric.setImageResource(R.drawable.ic_fingerprint);
                break;
        }
    }

    private void loadCurrentSettings() {
        // Load and set current timeout
        long currentTimeout = securityManager.getAuthTimeout();
        int position = getTimeoutPosition(currentTimeout);
        timeoutSpinner.setSelection(position);

        // Load transaction auth setting
        transactionAuthSwitch.setChecked(securityManager.isTransactionAuthEnabled());

        // Load biometric setting
        biometricSwitch.setChecked(securityManager.isBiometricEnabled());
    }

    private int getTimeoutPosition(long timeout) {
        for (int i = 0; i < timeoutValues.length; i++) {
            if (timeoutValues[i] == timeout) {
                return i;
            }
        }
        return 1; // Default to 5 min (position 1)
    }

    private void setupListeners() {
        // Timeout spinner listener
        timeoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                long timeout = timeoutValues[position];
                securityManager.setAuthTimeout(timeout);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        // Transaction auth toggle
        transactionAuthSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Require authentication to enable transaction auth
                verifyBiometricForTransactionAuth();
            } else {
                securityManager.setTransactionAuthEnabled(false);
            }
        });

        // Biometric toggle
        biometricSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                promptEnableBiometric();
            } else {
                securityManager.setBiometricEnabled(false);
                Toast.makeText(this, R.string.biometric_disabled, Toast.LENGTH_SHORT).show();
            }
        });

        // Change password button
        btnChangePassword.setOnClickListener(v -> {
            Intent intent = ChangePasswordActivity.createIntent(this);
            startActivity(intent);
        });
    }

    private void checkBiometricAvailability() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
        );

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            biometricSwitch.setEnabled(false);
            biometricSwitch.setAlpha(0.5f);
        }
    }

    private void verifyBiometricForTransactionAuth() {
        if (!securityManager.isBiometricEnabled()) {
            // If biometric is not enabled, just enable transaction auth
            securityManager.setTransactionAuthEnabled(true);
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        securityManager.setTransactionAuthEnabled(true);
                        Toast.makeText(SecuritySettingsActivity.this,
                                R.string.transaction_auth_enabled, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        transactionAuthSwitch.setChecked(false);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        transactionAuthSwitch.setChecked(false);
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.verify_identity))
                .setSubtitle(getString(R.string.confirm_to_enable_transaction_auth))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void promptEnableBiometric() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        securityManager.setBiometricEnabled(true);
                        Toast.makeText(SecuritySettingsActivity.this,
                                R.string.biometric_enabled, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        biometricSwitch.setChecked(false);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        biometricSwitch.setChecked(false);
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.enable_biometric_title))
                .setSubtitle(getString(R.string.enable_biometric_subtitle))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
