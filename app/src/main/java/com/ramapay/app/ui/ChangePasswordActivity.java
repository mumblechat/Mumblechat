package com.ramapay.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import com.ramapay.app.R;
import com.ramapay.app.service.AppSecurityManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Activity for changing the app security password
 */
@AndroidEntryPoint
public class ChangePasswordActivity extends BaseActivity {

    @Inject
    AppSecurityManager securityManager;

    private TextInputLayout currentPasswordLayout;
    private TextInputEditText currentPasswordInput;
    private TextInputLayout newPasswordLayout;
    private TextInputEditText newPasswordInput;
    private TextInputLayout confirmPasswordLayout;
    private TextInputEditText confirmPasswordInput;
    private MaterialButton btnChangePassword;

    public static Intent createIntent(Context context) {
        return new Intent(context, ChangePasswordActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        initToolbar();
        initViews();
        setupListeners();
    }

    private void initToolbar() {
        if (toolbar() != null) {
            setTitle(getString(R.string.change_password));
            enableDisplayHomeAsUp();
        }
    }

    private void initViews() {
        currentPasswordLayout = findViewById(R.id.current_password_layout);
        currentPasswordInput = findViewById(R.id.current_password_input);
        newPasswordLayout = findViewById(R.id.new_password_layout);
        newPasswordInput = findViewById(R.id.new_password_input);
        confirmPasswordLayout = findViewById(R.id.confirm_password_layout);
        confirmPasswordInput = findViewById(R.id.confirm_password_input);
        btnChangePassword = findViewById(R.id.btn_change_password);

        btnChangePassword.setEnabled(false);
    }

    private void setupListeners() {
        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                validateForm();
            }
        };

        currentPasswordInput.addTextChangedListener(textWatcher);
        newPasswordInput.addTextChangedListener(textWatcher);
        confirmPasswordInput.addTextChangedListener(textWatcher);

        btnChangePassword.setOnClickListener(v -> changePassword());
    }

    private void validateForm() {
        String currentPassword = getTextValue(currentPasswordInput);
        String newPassword = getTextValue(newPasswordInput);
        String confirmPassword = getTextValue(confirmPasswordInput);

        boolean isValid = !currentPassword.isEmpty() &&
                newPassword.length() >= 6 &&
                newPassword.equals(confirmPassword);

        btnChangePassword.setEnabled(isValid);
    }

    private void changePassword() {
        String currentPassword = getTextValue(currentPasswordInput);
        String newPassword = getTextValue(newPasswordInput);
        String confirmPassword = getTextValue(confirmPasswordInput);

        // Clear previous errors
        currentPasswordLayout.setError(null);
        newPasswordLayout.setError(null);
        confirmPasswordLayout.setError(null);

        // Verify current password
        if (!securityManager.verifyPassword(currentPassword)) {
            currentPasswordLayout.setError(getString(R.string.incorrect_password));
            return;
        }

        // Validate new password
        if (newPassword.length() < 6) {
            newPasswordLayout.setError(getString(R.string.password_too_short));
            return;
        }

        // Validate confirm password
        if (!newPassword.equals(confirmPassword)) {
            confirmPasswordLayout.setError(getString(R.string.passwords_do_not_match));
            return;
        }

        // Check if new password is same as current
        if (currentPassword.equals(newPassword)) {
            newPasswordLayout.setError(getString(R.string.new_password_same_as_current));
            return;
        }

        // Change the password
        boolean success = securityManager.setPassword(newPassword);

        if (success) {
            Toast.makeText(this, R.string.password_changed_successfully, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, R.string.error_changing_password, Toast.LENGTH_SHORT).show();
        }
    }

    private String getTextValue(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
