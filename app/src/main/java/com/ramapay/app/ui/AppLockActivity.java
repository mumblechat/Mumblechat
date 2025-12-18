package com.ramapay.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.ramapay.app.R;
import com.ramapay.app.service.AppSecurityManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Activity for unlocking the app with password or biometric
 */
@AndroidEntryPoint
public class AppLockActivity extends BaseActivity {
    
    public static final String EXTRA_FOR_TRANSACTION = "for_transaction";
    public static final int RESULT_AUTHENTICATED = 1001;
    public static final int RESULT_CANCELLED = 1002;
    
    @Inject
    AppSecurityManager securityManager;
    
    private TextInputLayout passwordLayout;
    private TextInputEditText passwordInput;
    private MaterialButton btnUnlock;
    private MaterialButton btnBiometric;
    
    // Animation views
    private ImageView glowOuter;
    private ImageView ringOuter;
    private ImageView ringInner;
    private ImageView particleRing;
    private ImageView appLogo;
    
    // Animators
    private ObjectAnimator outerRingAnimator;
    private ObjectAnimator innerRingAnimator;
    private ObjectAnimator particleRingAnimator;
    private ObjectAnimator glowPulseAnimator;
    private ObjectAnimator logoPulseAnimator;
    
    private boolean forTransaction = false;
    
    public static Intent createIntent(Context context) {
        return new Intent(context, AppLockActivity.class);
    }
    
    public static Intent createIntentForTransaction(Context context) {
        Intent intent = new Intent(context, AppLockActivity.class);
        intent.putExtra(EXTRA_FOR_TRANSACTION, true);
        return intent;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_lock);
        
        forTransaction = getIntent().getBooleanExtra(EXTRA_FOR_TRANSACTION, false);
        
        initViews();
        setupListeners();
        updateBiometricButton();
        
        // Auto-show biometric if enabled
        if (securityManager.isBiometricEnabled()) {
            btnBiometric.setVisibility(View.VISIBLE);
            // Auto-trigger biometric after a short delay
            btnBiometric.postDelayed(this::showBiometricPrompt, 300);
        } else {
            btnBiometric.setVisibility(View.GONE);
        }
    }
    
    private void updateBiometricButton() {
        if (btnBiometric == null) return;
        
        AppSecurityManager.BiometricType biometricType = securityManager.getBiometricType();
        switch (biometricType) {
            case FACE:
                btnBiometric.setIconResource(R.drawable.ic_face_id);
                btnBiometric.setText(R.string.use_face_id);
                break;
            case FINGERPRINT:
            case MULTIPLE:
            default:
                btnBiometric.setIconResource(R.drawable.ic_fingerprint);
                btnBiometric.setText(R.string.use_biometric);
                break;
        }
    }
    
    private void initViews() {
        passwordLayout = findViewById(R.id.password_layout);
        passwordInput = findViewById(R.id.password_input);
        btnUnlock = findViewById(R.id.btn_unlock);
        btnBiometric = findViewById(R.id.btn_biometric);
        
        // Animation views
        glowOuter = findViewById(R.id.glow_outer);
        ringOuter = findViewById(R.id.ring_outer);
        ringInner = findViewById(R.id.ring_inner);
        particleRing = findViewById(R.id.particle_ring);
        appLogo = findViewById(R.id.app_logo);
        
        // Start animations
        startLogoAnimations();
        
        // Update UI based on whether user is using PIN or password
        if (securityManager.isUsingPin()) {
            passwordLayout.setHint(getString(R.string.enter_pin));
            passwordInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | 
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        } else {
            passwordLayout.setHint(getString(R.string.enter_password));
            passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | 
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        
        // Check if currently locked out
        checkLockoutState();
    }
    
    private void checkLockoutState() {
        if (securityManager.isLockedOut()) {
            long remainingMs = securityManager.getRemainingLockoutTime();
            int remainingSec = (int) (remainingMs / 1000);
            passwordLayout.setError(getString(R.string.locked_out_seconds, remainingSec));
            btnUnlock.setEnabled(false);
            // Re-check when lockout expires
            btnUnlock.postDelayed(() -> {
                if (!securityManager.isLockedOut()) {
                    btnUnlock.setEnabled(true);
                    passwordLayout.setError(null);
                } else {
                    checkLockoutState(); // Recurse if still locked
                }
            }, Math.min(remainingMs + 100, 5000)); // Check at most every 5 seconds
        }
    }
    
    private void setupListeners() {
        btnUnlock.setOnClickListener(v -> verifyCredential());
        btnBiometric.setOnClickListener(v -> showBiometricPrompt());
        
        // Handle keyboard done action
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                verifyCredential();
                return true;
            }
            return false;
        });
    }
    
    private void verifyCredential() {
        // Check if locked out
        if (securityManager.isLockedOut()) {
            long remainingMs = securityManager.getRemainingLockoutTime();
            int remainingSec = (int) (remainingMs / 1000);
            passwordLayout.setError(getString(R.string.locked_out_seconds, remainingSec));
            btnUnlock.setEnabled(false);
            // Re-check after remaining time
            btnUnlock.postDelayed(() -> {
                if (!securityManager.isLockedOut()) {
                    btnUnlock.setEnabled(true);
                    passwordLayout.setError(null);
                }
            }, remainingMs + 100);
            return;
        }
        
        String credential = passwordInput.getText() != null ? passwordInput.getText().toString() : "";
        
        if (credential.isEmpty()) {
            passwordLayout.setError(securityManager.isUsingPin() ? 
                    getString(R.string.enter_pin) : getString(R.string.enter_password));
            return;
        }
        
        boolean verified;
        if (securityManager.isUsingPin()) {
            verified = securityManager.verifyPin(credential);
        } else {
            verified = securityManager.verifyPassword(credential);
        }
        
        if (verified) {
            securityManager.resetFailedAttempts();
            onAuthenticationSuccess();
        } else {
            int attempts = securityManager.incrementFailedAttempts();
            
            if (securityManager.isLockedOut()) {
                long remainingMs = securityManager.getRemainingLockoutTime();
                int remainingSec = (int) (remainingMs / 1000);
                passwordLayout.setError(getString(R.string.locked_out_seconds, remainingSec));
                btnUnlock.setEnabled(false);
                // Re-enable after lockout expires
                btnUnlock.postDelayed(() -> {
                    if (!securityManager.isLockedOut()) {
                        btnUnlock.setEnabled(true);
                        passwordLayout.setError(null);
                    }
                }, remainingMs + 100);
            } else {
                int remaining = 5 - (attempts % 5);
                if (remaining == 0) remaining = 5;
                passwordLayout.setError(getString(R.string.incorrect_attempts_remaining, remaining));
            }
            passwordInput.setText("");
        }
    }
    
    private void showBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // User can still use password
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            Toast.makeText(AppLockActivity.this, errString, Toast.LENGTH_SHORT).show();
                        }
                    }
                    
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        onAuthenticationSuccess();
                    }
                    
                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        // Biometric didn't match, user can retry or use password
                    }
                });
        
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_ramapay))
                .setSubtitle(getString(R.string.authenticate_to_continue))
                .setNegativeButtonText(getString(R.string.enter_password))
                .build();
        
        biometricPrompt.authenticate(promptInfo);
    }
    
    private void onAuthenticationSuccess() {
        securityManager.onAuthenticationSuccess();
        
        if (forTransaction) {
            setResult(RESULT_AUTHENTICATED);
        } else {
            setResult(RESULT_OK);
        }
        finish();
        // Use instant transition for faster experience
        overridePendingTransition(0, 0);
    }
    
    @Override
    public void onBackPressed() {
        if (forTransaction) {
            setResult(RESULT_CANCELLED);
            finish();
        } else {
            // Don't allow back press on app lock screen
            // User must authenticate
            moveTaskToBack(true);
        }
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Prevent home and recent apps buttons from bypassing lock
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    /**
     * Start all logo animations for the futuristic effect
     */
    private void startLogoAnimations() {
        // Outer ring rotation (clockwise, slow)
        if (ringOuter != null) {
            outerRingAnimator = ObjectAnimator.ofFloat(ringOuter, "rotation", 0f, 360f);
            outerRingAnimator.setDuration(8000);
            outerRingAnimator.setRepeatCount(ValueAnimator.INFINITE);
            outerRingAnimator.setInterpolator(new LinearInterpolator());
            outerRingAnimator.start();
        }
        
        // Inner ring rotation (counter-clockwise, faster)
        if (ringInner != null) {
            innerRingAnimator = ObjectAnimator.ofFloat(ringInner, "rotation", 0f, -360f);
            innerRingAnimator.setDuration(5000);
            innerRingAnimator.setRepeatCount(ValueAnimator.INFINITE);
            innerRingAnimator.setInterpolator(new LinearInterpolator());
            innerRingAnimator.start();
        }
        
        // Particle ring rotation (slow, with different timing)
        if (particleRing != null) {
            particleRingAnimator = ObjectAnimator.ofFloat(particleRing, "rotation", 0f, 360f);
            particleRingAnimator.setDuration(12000);
            particleRingAnimator.setRepeatCount(ValueAnimator.INFINITE);
            particleRingAnimator.setInterpolator(new LinearInterpolator());
            particleRingAnimator.start();
        }
        
        // Glow pulse animation
        if (glowOuter != null) {
            glowPulseAnimator = ObjectAnimator.ofFloat(glowOuter, "alpha", 0.3f, 0.7f);
            glowPulseAnimator.setDuration(2000);
            glowPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            glowPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            glowPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            glowPulseAnimator.start();
        }
        
        // Logo subtle pulse (scale)
        if (appLogo != null) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(appLogo, "scaleX", 1f, 1.05f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(appLogo, "scaleY", 1f, 1.05f);
            
            scaleX.setDuration(1500);
            scaleX.setRepeatCount(ValueAnimator.INFINITE);
            scaleX.setRepeatMode(ValueAnimator.REVERSE);
            scaleX.setInterpolator(new AccelerateDecelerateInterpolator());
            
            scaleY.setDuration(1500);
            scaleY.setRepeatCount(ValueAnimator.INFINITE);
            scaleY.setRepeatMode(ValueAnimator.REVERSE);
            scaleY.setInterpolator(new AccelerateDecelerateInterpolator());
            
            scaleX.start();
            scaleY.start();
        }
    }
    
    /**
     * Stop all animations when the activity is paused
     */
    private void stopLogoAnimations() {
        if (outerRingAnimator != null) outerRingAnimator.cancel();
        if (innerRingAnimator != null) innerRingAnimator.cancel();
        if (particleRingAnimator != null) particleRingAnimator.cancel();
        if (glowPulseAnimator != null) glowPulseAnimator.cancel();
        if (logoPulseAnimator != null) logoPulseAnimator.cancel();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        stopLogoAnimations();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        startLogoAnimations();
    }
}
