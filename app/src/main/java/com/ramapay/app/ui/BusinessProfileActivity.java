package com.ramapay.app.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.ramapay.app.R;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.repository.entity.RealmBusinessProfile;
import com.ramapay.app.service.RealmManager;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.realm.Realm;
import timber.log.Timber;

/**
 * Activity for setting up and editing business profile for PoS merchants
 */
@AndroidEntryPoint
public class BusinessProfileActivity extends BaseActivity
{
    @Inject
    RealmManager realmManager;

    private Wallet wallet;
    
    // UI Elements
    private ImageView imgBusinessLogo;
    private TextInputEditText inputBusinessName;
    private AutoCompleteTextView inputBusinessType;
    private TextInputEditText inputOwnerName;
    private TextInputEditText inputPhone;
    private TextInputEditText inputEmail;
    private TextInputEditText inputAddress;
    private TextInputEditText inputCity;
    private TextInputEditText inputPincode;
    private TextInputEditText inputState;
    private TextInputEditText inputCountry;
    private TextInputEditText inputGst;
    private AutoCompleteTextView inputDefaultCurrency;
    private MaterialButton btnSaveProfile;

    private RealmBusinessProfile existingProfile;

    // Business types
    private static final String[] BUSINESS_TYPES = {
            "Retail Store",
            "Restaurant/Cafe",
            "Grocery Store",
            "Pharmacy",
            "Service Provider",
            "Professional Services",
            "Education",
            "Healthcare",
            "E-commerce",
            "Manufacturing",
            "Other"
    };

    // Currencies
    private static final String[] CURRENCIES = {
            "INR - Indian Rupee",
            "USD - US Dollar",
            "EUR - Euro",
            "GBP - British Pound",
            "AED - UAE Dirham",
            "SGD - Singapore Dollar",
            "JPY - Japanese Yen",
            "AUD - Australian Dollar",
            "CAD - Canadian Dollar",
            "CNY - Chinese Yuan"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_business_profile);
        
        toolbar();
        enableDisplayHomeAsUp();
        setTitle(getString(R.string.business_profile));

        wallet = getIntent().getParcelableExtra("wallet");
        if (wallet == null)
        {
            finish();
            return;
        }

        initViews();
        setupDropdowns();
        loadExistingProfile();
        setupSaveButton();
    }

    private void initViews()
    {
        imgBusinessLogo = findViewById(R.id.imgBusinessLogo);
        inputBusinessName = findViewById(R.id.inputBusinessName);
        inputBusinessType = findViewById(R.id.inputBusinessType);
        inputOwnerName = findViewById(R.id.inputOwnerName);
        inputPhone = findViewById(R.id.inputPhone);
        inputEmail = findViewById(R.id.inputEmail);
        inputAddress = findViewById(R.id.inputAddress);
        inputCity = findViewById(R.id.inputCity);
        inputPincode = findViewById(R.id.inputPincode);
        inputState = findViewById(R.id.inputState);
        inputCountry = findViewById(R.id.inputCountry);
        inputGst = findViewById(R.id.inputGst);
        inputDefaultCurrency = findViewById(R.id.inputDefaultCurrency);
        btnSaveProfile = findViewById(R.id.btnSaveProfile);

        // Logo click to change
        findViewById(R.id.btnChangeLogo).setOnClickListener(v -> {
            // TODO: Implement image picker
            Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupDropdowns()
    {
        // Business type dropdown
        ArrayAdapter<String> businessTypeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                BUSINESS_TYPES
        );
        inputBusinessType.setAdapter(businessTypeAdapter);

        // Currency dropdown
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                CURRENCIES
        );
        inputDefaultCurrency.setAdapter(currencyAdapter);
    }

    private void loadExistingProfile()
    {
        try (Realm realm = realmManager.getRealmInstance(wallet))
        {
            RealmBusinessProfile profile = realm.where(RealmBusinessProfile.class)
                    .equalTo("walletAddress", wallet.address)
                    .findFirst();

            if (profile != null)
            {
                existingProfile = realm.copyFromRealm(profile);
                populateFields(existingProfile);
            }
        }
        catch (Exception e)
        {
            Timber.e(e, "Error loading business profile");
        }
    }

    private void populateFields(RealmBusinessProfile profile)
    {
        if (profile.getBusinessName() != null)
            inputBusinessName.setText(profile.getBusinessName());
        if (profile.getBusinessType() != null)
            inputBusinessType.setText(profile.getBusinessType(), false);
        if (profile.getOwnerName() != null)
            inputOwnerName.setText(profile.getOwnerName());
        if (profile.getPhoneNumber() != null)
            inputPhone.setText(profile.getPhoneNumber());
        if (profile.getEmail() != null)
            inputEmail.setText(profile.getEmail());
        if (profile.getAddress() != null)
            inputAddress.setText(profile.getAddress());
        if (profile.getCity() != null)
            inputCity.setText(profile.getCity());
        if (profile.getPincode() != null)
            inputPincode.setText(profile.getPincode());
        if (profile.getState() != null)
            inputState.setText(profile.getState());
        if (profile.getCountry() != null)
            inputCountry.setText(profile.getCountry());
        if (profile.getGstNumber() != null)
            inputGst.setText(profile.getGstNumber());
        if (profile.getDefaultCurrency() != null)
        {
            // Find matching currency string
            for (String currency : CURRENCIES)
            {
                if (currency.startsWith(profile.getDefaultCurrency()))
                {
                    inputDefaultCurrency.setText(currency, false);
                    break;
                }
            }
        }
    }

    private void setupSaveButton()
    {
        btnSaveProfile.setOnClickListener(v -> saveProfile());
    }

    private void saveProfile()
    {
        String businessName = getText(inputBusinessName);
        
        if (TextUtils.isEmpty(businessName))
        {
            inputBusinessName.setError(getString(R.string.business_name_required));
            inputBusinessName.requestFocus();
            return;
        }

        try (Realm realm = realmManager.getRealmInstance(wallet))
        {
            realm.executeTransaction(r -> {
                RealmBusinessProfile profile = r.where(RealmBusinessProfile.class)
                        .equalTo("walletAddress", wallet.address)
                        .findFirst();

                if (profile == null)
                {
                    profile = r.createObject(RealmBusinessProfile.class, wallet.address);
                    profile.setCreatedAt(System.currentTimeMillis());
                }

                profile.setBusinessName(businessName);
                profile.setBusinessType(getText(inputBusinessType));
                profile.setOwnerName(getText(inputOwnerName));
                profile.setPhoneNumber(getText(inputPhone));
                profile.setEmail(getText(inputEmail));
                profile.setAddress(getText(inputAddress));
                profile.setCity(getText(inputCity));
                profile.setPincode(getText(inputPincode));
                profile.setState(getText(inputState));
                profile.setCountry(getText(inputCountry));
                profile.setGstNumber(getText(inputGst));
                
                // Extract currency code from selection
                String currencySelection = getText(inputDefaultCurrency);
                if (!TextUtils.isEmpty(currencySelection) && currencySelection.contains(" - "))
                {
                    profile.setDefaultCurrency(currencySelection.split(" - ")[0]);
                }
                
                profile.setUpdatedAt(System.currentTimeMillis());
            });

            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        }
        catch (Exception e)
        {
            Timber.e(e, "Error saving business profile");
            Toast.makeText(this, R.string.error_saving_profile, Toast.LENGTH_SHORT).show();
        }
    }

    private String getText(TextInputEditText input)
    {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    private String getText(AutoCompleteTextView input)
    {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }
}
