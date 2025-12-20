package com.ramapay.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.ramapay.app.R;
import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.entity.tokens.TokenInfo;
import com.ramapay.app.util.Utils;
import com.ramapay.app.viewmodel.TokenListingViewModel;
import com.ramapay.app.widget.AWalletAlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class TokenListingRequestActivity extends BaseActivity
{
    private TokenListingViewModel viewModel;

    private final Pattern findAddress = Pattern.compile("(0x)([0-9a-fA-F]{40})($|\\s)");

    private TextInputLayout tokenAddressLayout;
    private TextInputEditText tokenAddressInput;
    private TextInputLayout chainLayout;
    private AutoCompleteTextView chainDropdown;
    private TextInputLayout tokenNameLayout;
    private TextInputEditText tokenNameInput;
    private TextInputLayout tokenSymbolLayout;
    private TextInputEditText tokenSymbolInput;
    private TextInputLayout decimalsLayout;
    private TextInputEditText decimalsInput;
    private TextInputLayout iconUrlLayout;
    private TextInputEditText iconUrlInput;
    private TextInputLayout websiteLayout;
    private TextInputEditText websiteInput;
    private TextInputLayout emailLayout;
    private TextInputEditText emailInput;
    private TextInputLayout notesLayout;
    private TextInputEditText notesInput;
    private MaterialButton submitButton;

    // Progress UI
    private LinearLayout progressLayout;
    private ProgressBar progressBar;
    private TextView progressText;

    private List<NetworkInfo> networks;
    private NetworkInfo selectedNetwork = null;
    private long selectedChainId = -1;
    private String lastCheckedAddress = "";
    private int detectedDecimals = 18;

    private AWalletAlertDialog progressDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_listing_request);

        toolbar();
        setTitle(getString(R.string.title_token_listing_request));

        viewModel = new ViewModelProvider(this).get(TokenListingViewModel.class);

        initViews();
        initChainDropdown();
        setupObservers();
        setupListeners();
    }

    private void initViews()
    {
        tokenAddressLayout = findViewById(R.id.token_address_layout);
        tokenAddressInput = findViewById(R.id.token_address_input);
        chainLayout = findViewById(R.id.chain_layout);
        chainDropdown = findViewById(R.id.chain_dropdown);
        tokenNameLayout = findViewById(R.id.token_name_layout);
        tokenNameInput = findViewById(R.id.token_name_input);
        tokenSymbolLayout = findViewById(R.id.token_symbol_layout);
        tokenSymbolInput = findViewById(R.id.token_symbol_input);
        decimalsLayout = findViewById(R.id.decimals_layout);
        decimalsInput = findViewById(R.id.decimals_input);
        iconUrlLayout = findViewById(R.id.icon_url_layout);
        iconUrlInput = findViewById(R.id.icon_url_input);
        websiteLayout = findViewById(R.id.website_layout);
        websiteInput = findViewById(R.id.website_input);
        emailLayout = findViewById(R.id.email_layout);
        emailInput = findViewById(R.id.email_input);
        notesLayout = findViewById(R.id.notes_layout);
        notesInput = findViewById(R.id.notes_input);
        submitButton = findViewById(R.id.btn_submit_request);

        // Progress UI
        progressLayout = findViewById(R.id.progress_layout);
        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.progress_text);

        if (progressLayout != null)
        {
            progressLayout.setVisibility(View.GONE);
        }

        // Setup paste button click listeners
        setupPasteButtons();
    }

    private void setupPasteButtons()
    {
        // Paste button for contract address
        tokenAddressLayout.setEndIconOnClickListener(v -> {
            String clipboardText = getClipboardText();
            if (!TextUtils.isEmpty(clipboardText))
            {
                tokenAddressInput.setText(clipboardText);
                tokenAddressInput.setSelection(clipboardText.length());
            }
        });

        // Paste button for icon URL
        iconUrlLayout.setEndIconOnClickListener(v -> {
            String clipboardText = getClipboardText();
            if (!TextUtils.isEmpty(clipboardText))
            {
                iconUrlInput.setText(clipboardText);
                iconUrlInput.setSelection(clipboardText.length());
            }
        });

        // Paste button for website
        websiteLayout.setEndIconOnClickListener(v -> {
            String clipboardText = getClipboardText();
            if (!TextUtils.isEmpty(clipboardText))
            {
                websiteInput.setText(clipboardText);
                websiteInput.setSelection(clipboardText.length());
            }
        });
    }

    private String getClipboardText()
    {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip())
        {
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0)
            {
                CharSequence text = clipData.getItemAt(0).getText();
                if (text != null)
                {
                    return text.toString().trim();
                }
            }
        }
        return "";
    }

    private void initChainDropdown()
    {
        networks = new ArrayList<>();
        List<String> chainNames = new ArrayList<>();

        // Get all available networks
        NetworkInfo[] allNetworks = viewModel.getAvailableNetworks();
        for (NetworkInfo network : allNetworks)
        {
            if (network != null && !TextUtils.isEmpty(network.name))
            {
                networks.add(network);
                chainNames.add(network.name);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                chainNames
        );

        chainDropdown.setAdapter(adapter);
        chainDropdown.setOnItemClickListener((parent, view, position, id) -> {
            if (position < networks.size())
            {
                selectedNetwork = networks.get(position);
                selectedChainId = selectedNetwork.chainId;
                chainLayout.setError(null);
            }
        });
    }

    private void setupObservers()
    {
        // Token detected
        viewModel.detectedToken().observe(this, tokenInfo -> {
            if (tokenInfo != null)
            {
                runOnUiThread(() -> {
                    fillTokenDetails(tokenInfo);
                    hideProgress();
                    Toast.makeText(this, R.string.token_detected_successfully, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // Network detected
        viewModel.detectedNetwork().observe(this, networkInfo -> {
            if (networkInfo != null)
            {
                runOnUiThread(() -> {
                    selectedNetwork = networkInfo;
                    selectedChainId = networkInfo.chainId;
                    chainDropdown.setText(networkInfo.name, false);
                    chainLayout.setError(null);
                });
            }
        });

        // Scan progress
        viewModel.scanProgress().observe(this, progress -> {
            if (progressBar != null)
            {
                progressBar.setProgress(progress);
            }
            if (progressText != null)
            {
                progressText.setText(getString(R.string.scanning_networks_progress, progress));
            }
        });

        // Scan complete
        viewModel.scanComplete().observe(this, complete -> {
            if (complete)
            {
                hideProgress();
            }
        });

        // No contract found
        viewModel.noContractFound().observe(this, notFound -> {
            if (notFound)
            {
                hideProgress();
                Toast.makeText(this, R.string.contract_not_found_on_networks, Toast.LENGTH_LONG).show();
            }
        });

        // Submit success
        viewModel.submitSuccess().observe(this, success -> {
            if (success)
            {
                hideSubmitProgress();
                showSuccessDialog();
            }
        });

        // Submit error
        viewModel.submitError().observe(this, error -> {
            if (error != null)
            {
                hideSubmitProgress();
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setupListeners()
    {
        // Token address input listener - auto-detect on valid address
        tokenAddressInput.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
                String input = s.toString().trim();
                if (input.length() > 38)
                {
                    checkContractAddress(input);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        submitButton.setOnClickListener(v -> {
            if (validateForm())
            {
                submitRequest();
            }
        });

        // Clear errors on focus
        tokenAddressInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) tokenAddressLayout.setError(null);
        });
        tokenNameInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) tokenNameLayout.setError(null);
        });
        tokenSymbolInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) tokenSymbolLayout.setError(null);
        });
        iconUrlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) iconUrlLayout.setError(null);
        });
        emailInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) emailLayout.setError(null);
        });
    }

    private void checkContractAddress(String address)
    {
        // Extract valid address if needed
        if (!Utils.isAddressValid(address))
        {
            Matcher matcher = findAddress.matcher(address);
            if (matcher.find())
            {
                address = matcher.group(1) + matcher.group(2);
            }
        }

        if (Utils.isAddressValid(address) && !address.equalsIgnoreCase(lastCheckedAddress))
        {
            lastCheckedAddress = address;
            showProgress();
            tokenAddressLayout.setError(null);

            // Clear previous data
            tokenNameInput.setText("");
            tokenSymbolInput.setText("");
            if (decimalsInput != null) decimalsInput.setText("");
            chainDropdown.setText("", false);
            selectedNetwork = null;
            selectedChainId = -1;

            // Scan all networks for the contract
            viewModel.scanAllNetworks(address);
        }
    }

    private void fillTokenDetails(TokenInfo tokenInfo)
    {
        if (!TextUtils.isEmpty(tokenInfo.name))
        {
            tokenNameInput.setText(tokenInfo.name);
        }
        if (!TextUtils.isEmpty(tokenInfo.symbol))
        {
            tokenSymbolInput.setText(tokenInfo.symbol);
        }
        if (decimalsInput != null && tokenInfo.decimals > 0)
        {
            decimalsInput.setText(String.valueOf(tokenInfo.decimals));
            detectedDecimals = tokenInfo.decimals;
        }
    }

    private void showProgress()
    {
        if (progressLayout != null)
        {
            progressLayout.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            progressText.setText(R.string.detecting_token);
        }
    }

    private void hideProgress()
    {
        if (progressLayout != null)
        {
            progressLayout.setVisibility(View.GONE);
        }
    }

    private void showSubmitProgress()
    {
        progressDialog = new AWalletAlertDialog(this);
        progressDialog.setTitle(R.string.submitting_request);
        progressDialog.setProgressMode();
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    private void hideSubmitProgress()
    {
        if (progressDialog != null && progressDialog.isShowing())
        {
            progressDialog.dismiss();
        }
    }

    private void showSuccessDialog()
    {
        AWalletAlertDialog dialog = new AWalletAlertDialog(this);
        dialog.setIcon(AWalletAlertDialog.SUCCESS);
        dialog.setTitle(R.string.token_listing_submitted);
        dialog.setMessage(getString(R.string.token_listing_submitted_message));
        dialog.setButtonText(R.string.ok);
        dialog.setButtonListener(v -> {
            dialog.dismiss();
            finish();
        });
        dialog.setCancelable(false);
        dialog.show();
    }

    private boolean validateForm()
    {
        boolean isValid = true;

        // Validate token address
        String tokenAddress = getTextFromInput(tokenAddressInput);
        if (TextUtils.isEmpty(tokenAddress))
        {
            tokenAddressLayout.setError(getString(R.string.error_token_address_required));
            isValid = false;
        }
        else if (!isValidContractAddress(tokenAddress))
        {
            tokenAddressLayout.setError(getString(R.string.error_invalid_address));
            isValid = false;
        }

        // Validate chain selection
        if (selectedChainId <= 0)
        {
            chainLayout.setError(getString(R.string.error_chain_required));
            isValid = false;
        }
        else
        {
            chainLayout.setError(null);
        }

        // Validate token name
        String tokenName = getTextFromInput(tokenNameInput);
        if (TextUtils.isEmpty(tokenName))
        {
            tokenNameLayout.setError(getString(R.string.error_listing_token_name_required));
            isValid = false;
        }

        // Validate token symbol
        String tokenSymbol = getTextFromInput(tokenSymbolInput);
        if (TextUtils.isEmpty(tokenSymbol))
        {
            tokenSymbolLayout.setError(getString(R.string.error_token_symbol_required));
            isValid = false;
        }

        // Validate icon URL
        String iconUrl = getTextFromInput(iconUrlInput);
        if (TextUtils.isEmpty(iconUrl))
        {
            iconUrlLayout.setError(getString(R.string.error_icon_url_required));
            isValid = false;
        }
        else if (!isValidUrl(iconUrl))
        {
            iconUrlLayout.setError(getString(R.string.error_invalid_url));
            isValid = false;
        }

        // Validate email
        String email = getTextFromInput(emailInput);
        if (TextUtils.isEmpty(email))
        {
            emailLayout.setError(getString(R.string.error_email_required));
            isValid = false;
        }
        else if (!isValidEmail(email))
        {
            emailLayout.setError(getString(R.string.error_listing_invalid_email));
            isValid = false;
        }

        return isValid;
    }

    private void submitRequest()
    {
        showSubmitProgress();

        String tokenAddress = getTextFromInput(tokenAddressInput);
        String tokenName = getTextFromInput(tokenNameInput);
        String tokenSymbol = getTextFromInput(tokenSymbolInput);
        String iconUrl = getTextFromInput(iconUrlInput);
        String website = getTextFromInput(websiteInput);
        String contactEmail = getTextFromInput(emailInput);
        String notes = getTextFromInput(notesInput);

        int decimals = detectedDecimals;
        if (decimalsInput != null)
        {
            String decimalsStr = getTextFromInput(decimalsInput);
            if (!TextUtils.isEmpty(decimalsStr))
            {
                try
                {
                    decimals = Integer.parseInt(decimalsStr);
                }
                catch (NumberFormatException e)
                {
                    decimals = 18;
                }
            }
        }

        viewModel.submitTokenListingRequest(
                tokenAddress,
                selectedChainId,
                tokenName,
                tokenSymbol,
                decimals,
                iconUrl,
                website,
                contactEmail,
                notes
        );
    }

    private String getTextFromInput(TextInputEditText input)
    {
        if (input != null && input.getText() != null)
        {
            return input.getText().toString().trim();
        }
        return "";
    }

    private boolean isValidContractAddress(String address)
    {
        return address != null && address.matches("^0x[a-fA-F0-9]{40}$");
    }

    private boolean isValidEmail(String email)
    {
        return email != null && Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    private boolean isValidUrl(String url)
    {
        return url != null && Patterns.WEB_URL.matcher(url).matches();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        viewModel.stopScan();
    }
}
