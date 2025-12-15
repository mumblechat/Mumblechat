package com.ramapay.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.ramapay.app.R;
import com.ramapay.app.entity.MediaLinks;
import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.repository.EthereumNetworkRepositoryType;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import timber.log.Timber;

@AndroidEntryPoint
public class TokenListingRequestActivity extends BaseActivity
{
    @Inject
    EthereumNetworkRepositoryType ethereumNetworkRepository;

    private TextInputLayout tokenAddressLayout;
    private TextInputEditText tokenAddressInput;
    private TextInputLayout chainLayout;
    private AutoCompleteTextView chainDropdown;
    private TextInputLayout tokenNameLayout;
    private TextInputEditText tokenNameInput;
    private TextInputLayout tokenSymbolLayout;
    private TextInputEditText tokenSymbolInput;
    private TextInputLayout iconUrlLayout;
    private TextInputEditText iconUrlInput;
    private TextInputLayout websiteLayout;
    private TextInputEditText websiteInput;
    private TextInputLayout emailLayout;
    private TextInputEditText emailInput;
    private TextInputLayout notesLayout;
    private TextInputEditText notesInput;
    private MaterialButton submitButton;

    private List<NetworkInfo> networks;
    private String selectedChain = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_listing_request);

        toolbar();
        setTitle(getString(R.string.title_token_listing_request));

        initViews();
        initChainDropdown();
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
        iconUrlLayout = findViewById(R.id.icon_url_layout);
        iconUrlInput = findViewById(R.id.icon_url_input);
        websiteLayout = findViewById(R.id.website_layout);
        websiteInput = findViewById(R.id.website_input);
        emailLayout = findViewById(R.id.email_layout);
        emailInput = findViewById(R.id.email_input);
        notesLayout = findViewById(R.id.notes_layout);
        notesInput = findViewById(R.id.notes_input);
        submitButton = findViewById(R.id.btn_submit_request);
    }

    private void initChainDropdown()
    {
        networks = new ArrayList<>();
        List<String> chainNames = new ArrayList<>();

        // Get all available networks
        NetworkInfo[] allNetworks = ethereumNetworkRepository.getAvailableNetworkList();
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
                selectedChain = networks.get(position).name;
            }
        });
    }

    private void setupListeners()
    {
        submitButton.setOnClickListener(v -> {
            if (validateForm())
            {
                submitRequest();
            }
        });

        // Clear errors on text change
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
        if (TextUtils.isEmpty(selectedChain))
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
        String tokenAddress = getTextFromInput(tokenAddressInput);
        String tokenName = getTextFromInput(tokenNameInput);
        String tokenSymbol = getTextFromInput(tokenSymbolInput);
        String iconUrl = getTextFromInput(iconUrlInput);
        String website = getTextFromInput(websiteInput);
        String contactEmail = getTextFromInput(emailInput);
        String notes = getTextFromInput(notesInput);

        // Build email body
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("Token Listing Request\n");
        bodyBuilder.append("=====================\n\n");
        bodyBuilder.append("Token Contract Address: ").append(tokenAddress).append("\n");
        bodyBuilder.append("Blockchain Network: ").append(selectedChain).append("\n");
        bodyBuilder.append("Token Name: ").append(tokenName).append("\n");
        bodyBuilder.append("Token Symbol: ").append(tokenSymbol).append("\n");
        bodyBuilder.append("Token Icon URL: ").append(iconUrl).append("\n");
        
        if (!TextUtils.isEmpty(website))
        {
            bodyBuilder.append("Project Website: ").append(website).append("\n");
        }
        
        bodyBuilder.append("Contact Email: ").append(contactEmail).append("\n");
        
        if (!TextUtils.isEmpty(notes))
        {
            bodyBuilder.append("\nAdditional Notes:\n").append(notes).append("\n");
        }

        // Send email intent
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        String emailAddress = MediaLinks.AWALLET_EMAIL1 + "@" + MediaLinks.AWALLET_EMAIL2;
        String mailtoUri = "mailto:" + emailAddress +
                "?subject=" + Uri.encode(getString(R.string.token_listing_email_subject)) +
                "&body=" + Uri.encode(bodyBuilder.toString());
        intent.setData(Uri.parse(mailtoUri));

        try
        {
            startActivity(intent);
            Toast.makeText(this, R.string.token_listing_request_sent, Toast.LENGTH_LONG).show();
            finish();
        }
        catch (Exception e)
        {
            Timber.e(e, "Failed to open email client");
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show();
        }
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
        // Basic validation for Ethereum-style addresses
        return address != null && 
               address.matches("^0x[a-fA-F0-9]{40}$");
    }

    private boolean isValidEmail(String email)
    {
        return email != null && Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    private boolean isValidUrl(String url)
    {
        return url != null && Patterns.WEB_URL.matcher(url).matches();
    }
}
