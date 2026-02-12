package com.ramapay.app.ui;

import static com.ramapay.app.C.Key.WALLET;

import static com.ramapay.app.service.KeystoreAccountService.KEYSTORE_FOLDER;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.ramapay.app.R;
import com.ramapay.app.entity.BackupOperationType;
import com.ramapay.app.entity.BackupState;
import com.ramapay.app.entity.CreateWalletCallbackInterface;
import com.ramapay.app.entity.Operation;
import com.ramapay.app.entity.SignAuthenticationCallback;
import com.ramapay.app.entity.StandardFunctionInterface;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.hardware.SignatureFromKey;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.service.KeystoreAccountService;
import com.ramapay.app.ui.QRScanning.DisplayUtils;
import com.ramapay.app.viewmodel.BackupKeyViewModel;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.FunctionButtonBar;
import com.ramapay.app.widget.LayoutCallbackListener;
import com.ramapay.app.widget.PasswordInputView;
import com.ramapay.app.widget.SignTransactionDialog;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.material.card.MaterialCardView;

import androidx.core.content.ContextCompat;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.utils.Numeric;

import java.io.File;

import wallet.core.jni.CoinType;
import wallet.core.jni.HDWallet;
import wallet.core.jni.PrivateKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dagger.hilt.android.AndroidEntryPoint;
import timber.log.Timber;

@AndroidEntryPoint
public class BackupKeyActivity extends BaseActivity implements
        View.OnClickListener,
        CreateWalletCallbackInterface,
        TextWatcher,
        SignAuthenticationCallback,
        Runnable,
        LayoutCallbackListener,
        StandardFunctionInterface
{

    BackupKeyViewModel viewModel;

    private BackupState state;
    private Wallet wallet;
    private TextView title;
    private TextView detail;
    private FlexboxLayout layoutWordHolder;
    private PasswordInputView inputView;
    private ImageView backupImage;
    private TextView verifyTextBox;
    private MaterialCardView verifyTextContainer;
    private String[] mnemonicArray;
    private LinearLayout successOverlay;
    private final Handler handler = new Handler();
    private AWalletAlertDialog alertDialog;
    private String keystorePassword;
    private FunctionButtonBar functionButtonBar;

    private boolean hasNoLock = false;
    private boolean isExplicitUpgrade = false; // True when user explicitly clicks upgrade from settings

    private int screenWidth;
    
    // For random word verification (only 4 words)
    private int[] randomWordPositions = new int[4]; // Stores positions like 3, 5, 9, 12
    private List<String> selectedWords = new ArrayList<>(); // Stores user's selected words in order
    private TextView[] allWordBoxes = new TextView[12]; // All 12 word boxes in the grid
    private TextView[] selectableWordViews = new TextView[4]; // The 4 selectable word views at bottom
    private int currentFillIndex = 0; // Tracks which position to fill next (0-3)
    private TextView tapHintText; // Hint for tap to remove

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        // Screenshots are allowed for seed phrase backup so users can save their recovery words
        // Copy button is also available for convenience

        alertDialog = null;
        lockOrientation();

        toolbar();
        initViewModel();

        screenWidth = DisplayUtils.getScreenResolution(this).x;

        wallet = getIntent().getParcelableExtra(WALLET);

        if (Objects.requireNonNull(getIntent().getExtras()).containsKey("STATE"))
        {
            initBackupState();
        }
        else
        {
            initBackupType();
        }
    }

    private void initBackupState()
    {
        state = (BackupState) getIntent().getSerializableExtra("STATE");

        assert state != null;
        switch (state)
        {
            case SHOW_SEED_PHRASE_SINGLE:
                showSeedPhrase();
                break;
            case ENTER_BACKUP_STATE_HD:
                WriteDownSeedPhrase();
                DisplaySeed();
                break;
            case WRITE_DOWN_SEED_PHRASE:
            case SHOW_SEED_PHRASE:
                VerifySeedPhrase();
                break;
            case VERIFY_SEED_PHRASE:
                TestSeedPhrase();
                break;
            case SEED_PHRASE_INVALID:
                ResetInputBox();
                VerifySeedPhrase();
                break;
            case ENTER_JSON_BACKUP:
                JSONBackup();
                break;
            case UPGRADE_KEY_SECURITY:
                //first open authentication
                isExplicitUpgrade = true;
                setupUpgradeKey(false);
                break;
        }
    }

    private void showSeedPhrase()
    {
        // First check if the key exists in the keystore
        if (!viewModel.hasKey(wallet.address))
        {
            DisplayKeyFailureDialog(getString(R.string.key_not_found_reimport));
            return;
        }
        
        setupTestSeed();
        ((TextView) findViewById(R.id.text_title)).setText(R.string.your_seed_phrase);
        DisplaySeed();
        functionButtonBar.setPrimaryButtonText(R.string.hide_seed_text);
        functionButtonBar.setPrimaryButtonClickListener(this);
    }

    private void initBackupType()
    {
        BackupOperationType type = (BackupOperationType) getIntent().getSerializableExtra("TYPE");
        if (type == null) type = BackupOperationType.UNDEFINED;

        switch (type)
        {
            case UNDEFINED:
                state = BackupState.UNDEFINED;
                DisplayKeyFailureDialog("Unknown Key operation");
                break;
            case BACKUP_HD_KEY:
                state = BackupState.ENTER_BACKUP_STATE_HD;
                WriteDownSeedPhrase();
                DisplaySeed();
                break;
            case BACKUP_KEYSTORE_KEY:
                state = BackupState.ENTER_JSON_BACKUP;
                JSONBackup();
                break;
            case SHOW_SEED_PHRASE:
                state = BackupState.SHOW_SEED_PHRASE;
                setupTestSeed();
                DisplaySeed();
                break;
            case EXPORT_PRIVATE_KEY:
                state = BackupState.SHOW_PRIVATE_KEY;
                setupShowPrivateKey();
                break;
            case UPGRADE_KEY:
                setupUpgradeKey(false);
                break;
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void lockOrientation()
    {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
        {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        else
        {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void setupUpgradeKey(boolean showSuccess)
    {
        setContentView(R.layout.activity_backup);
        initViews();

        successOverlay = findViewById(R.id.layout_success_overlay);
        if (successOverlay != null && showSuccess)
        {
            successOverlay.setVisibility(View.VISIBLE);
            handler.postDelayed(this, 1000);
        }

        state = BackupState.UPGRADE_KEY_SECURITY;
        if (wallet.type == WalletType.KEYSTORE)
        {
            title.setText(R.string.lock_keystore_upgrade);
        }
        else
        {
            title.setText(R.string.lock_key_upgrade);
        }
        backupImage.setImageResource(R.drawable.biometrics);
        detail.setVisibility(View.VISIBLE);
        detail.setText(R.string.upgrade_key_security_detail);

        int res;
        if (wallet.type == WalletType.HDKEY)
        {
            res = R.string.lock_seed_phrase;
        }
        else
        {
            res = R.string.action_upgrade_key;
        }

        functionButtonBar.setPrimaryButtonText(res);
        functionButtonBar.setPrimaryButtonClickListener(this);
    }

    @Override
    public void keyUpgraded(final KeyService.UpgradeKeyResult upgrade)
    {
        handler.post(() ->
        {
            switch (upgrade.result)
            {
                case REQUESTING_SECURITY: //Deprecated
                    //Do nothing, callback will return to 'CreatedKey()'. If it fails the returned key is empty. //Update - this should never happen - remove
                    break;
                case NO_SCREENLOCK:
                    hasNoLock = true;
                    if (isExplicitUpgrade)
                    {
                        // User explicitly requested upgrade - show error and instructions
                        showEnableScreenLockDialog();
                    }
                    else
                    {
                        // Automatic upgrade after wallet creation - skip upgrade and continue
                        finishBackupSuccess(false);
                    }
                    break;
                case ALREADY_LOCKED:
                    finishBackupSuccess(false); // already upgraded to top level
                    break;
                case ERROR:
                    hasNoLock = true;
                    if (isExplicitUpgrade)
                    {
                        DisplayKeyFailureDialog(getString(R.string.unable_to_upgrade_key, upgrade.message));
                    }
                    else
                    {
                        // During wallet creation, skip upgrade on error
                        finishBackupSuccess(false);
                    }
                    break;
                case SUCCESSFULLY_UPGRADED:
                    createdKey(wallet.address);
                    break;
            }
        });
    }
    
    private void showEnableScreenLockDialog()
    {
        AWalletAlertDialog dialog = new AWalletAlertDialog(this);
        dialog.setTitle(R.string.setup_required);
        dialog.setIcon(AWalletAlertDialog.WARNING);
        dialog.setMessage(getString(R.string.enable_screenlock));
        dialog.setButtonText(R.string.action_open_settings);
        dialog.setSecondaryButtonText(R.string.action_close);
        dialog.setButtonListener(v -> {
            // Open device security settings
            dialog.dismiss();
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                // Fallback to general settings
                Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                startActivity(intent);
            }
        });
        dialog.setSecondaryButtonListener(v -> {
            dialog.dismiss();
            finish();
        });
        dialog.setCancelable(true);
        dialog.show();
    }

    private void upgradeKeySecurity()
    {
        // Check if the key exists in secure storage before attempting to upgrade
        if (wallet != null && (wallet.type == WalletType.HDKEY || wallet.type == WalletType.KEYSTORE) 
            && !viewModel.hasKey(wallet.address))
        {
            DisplayKeyFailureDialog(getString(R.string.wallet_key_not_found));
            return;
        }
        
        switch (wallet.type)
        {
            case KEYSTORE:
            case KEYSTORE_LEGACY:
            case HDKEY:
                viewModel.upgradeKeySecurity(wallet, this, this);
                break;

            default:
                break;
        }
    }

    @Override
    public void createdKey(String address)
    {
        //key upgraded
        //store wallet upgrade
        if (wallet.address.equalsIgnoreCase(address))
        {
            switch (wallet.type)
            {
                case KEYSTORE_LEGACY:
                case KEYSTORE:
                case HDKEY:
                    viewModel.upgradeWallet(address);
                    finishBackupSuccess(true);
                    break;
                default:
                    cancelAuthentication();
                    break;
            }
        }
    }

    private void setupShowPrivateKey()
    {
        setContentView(R.layout.activity_show_private_key);
        initViews();
        
        functionButtonBar.setPrimaryButtonText(R.string.copy_private_key);
        functionButtonBar.setPrimaryButtonClickListener(v -> copyPrivateKeyToClipboard());
        functionButtonBar.setPrimaryButtonEnabled(false);
        
        // Request authentication to get the mnemonic
        viewModel.getAuthentication(wallet, this, this);
    }
    
    private void displayPrivateKey(String secretData)
    {
        try
        {
            String privateKeyHex;
            
            // For KEYSTORE wallets (imported via private key), secretData is the keystore password
            // We need to decrypt the keystore to get the actual private key
            if (wallet.type == WalletType.KEYSTORE || wallet.type == WalletType.KEYSTORE_LEGACY)
            {
                // Use the same method as KeystoreAccountService for consistency
                File keyFolder = new File(getFilesDir(), KEYSTORE_FOLDER);
                Credentials credentials = KeystoreAccountService.getCredentials(keyFolder, wallet.address, secretData);
                
                if (credentials != null)
                {
                    // Get the private key from the credentials
                    privateKeyHex = "0x" + Numeric.toHexStringNoPrefixZeroPadded(
                        credentials.getEcKeyPair().getPrivateKey(), 64);
                }
                else
                {
                    throw new Exception("Could not load wallet credentials. Please check your keystore.");
                }
            }
            else
            {
                // For HD wallets, derive the private key from the mnemonic
                HDWallet hdWallet = new HDWallet(secretData, "");
                PrivateKey pk;
                
                // For derived HD accounts, use the correct account index
                if (wallet.isDerivedHDAccount())
                {
                    // Use wallet's hdKeyIndex for the derivation path
                    String derivationPath = "m/44'/60'/0'/0/" + wallet.hdKeyIndex;
                    pk = hdWallet.getKey(CoinType.ETHEREUM, derivationPath);
                }
                else
                {
                    // For master wallet (index 0), use default derivation
                    pk = hdWallet.getKeyForCoin(CoinType.ETHEREUM);
                }
                
                privateKeyHex = "0x" + Numeric.toHexStringNoPrefix(pk.data());
            }
            
            TextView privateKeyText = findViewById(R.id.text_private_key);
            TextView walletAddressText = findViewById(R.id.text_wallet_address);
            ImageView copyButton = findViewById(R.id.btn_copy_private_key);
            
            if (privateKeyText != null)
            {
                privateKeyText.setText(privateKeyHex);
            }
            
            if (walletAddressText != null)
            {
                walletAddressText.setText(wallet.address);
            }
            
            if (copyButton != null)
            {
                copyButton.setOnClickListener(v -> copyPrivateKeyToClipboard());
            }
            
            functionButtonBar.setPrimaryButtonEnabled(true);
        }
        catch (Exception e)
        {
            DisplayKeyFailureDialog("Failed to derive private key: " + e.getMessage());
        }
    }
    
    private void copyPrivateKeyToClipboard()
    {
        TextView privateKeyText = findViewById(R.id.text_private_key);
        if (privateKeyText != null && privateKeyText.getText() != null)
        {
            String privateKey = privateKeyText.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Private Key", privateKey);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.private_key_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupTestSeed()
    {
        setContentView(R.layout.activity_backup_write_seed);
        initViews();
    }

    @Override
    public void run()
    {
        if (successOverlay == null) return;
        if (successOverlay.getAlpha() > 0)
        {
            successOverlay.animate().alpha(0.0f).setDuration(500);
            handler.postDelayed(this, 750);
        }
        else
        {
            successOverlay.setVisibility(View.GONE);
            successOverlay.setAlpha(1.0f);
        }
    }

    @Override
    public void onPause()
    {
        super.onPause();

        viewModel.resetSignDialog();
        //hide seed phrase and any visible words
        if (layoutWordHolder != null) layoutWordHolder.removeAllViews();

        switch (state)
        {
            case WRITE_DOWN_SEED_PHRASE:
            case SHOW_SEED_PHRASE:
                //note, the OS calls onPause if user chooses to authenticate using PIN or password (takes them to the auth screen).
                WriteDownSeedPhrase();
                break;

            case SEED_PHRASE_INVALID:
            case VERIFY_SEED_PHRASE:
                // Don't reset state or re-fetch mnemonic when minimizing during verification
                // The mnemonicArray is already stored in memory, just clear the UI for security
                // On resume, we'll restore the verification UI with the same mnemonic
                break;

            case SET_JSON_PASSWORD:
                JSONBackup();
                break;

            case SHOW_PRIVATE_KEY:
                // Clear private key from screen for security when pausing
                TextView privateKeyText = findViewById(R.id.text_private_key);
                if (privateKeyText != null) privateKeyText.setText("");
                break;

            case ENTER_JSON_BACKUP:
            case ENTER_BACKUP_STATE_HD:
            case UPGRADE_KEY_SECURITY:
            case FINISH:
                break;
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        
        // Restore seed phrase display when user returns to the activity
        // The mnemonicArray still contains the seed words, just need to re-display them
        if (mnemonicArray != null && mnemonicArray.length > 0)
        {
            switch (state)
            {
                case WRITE_DOWN_SEED_PHRASE:
                case SHOW_SEED_PHRASE:
                case SHOW_SEED_PHRASE_SINGLE:
                    // Restore the seed phrase words to the screen
                    if (layoutWordHolder != null)
                    {
                        layoutWordHolder.setVisibility(View.VISIBLE);
                        layoutWordHolder.removeAllViews();
                        addSeedWordsToScreen();
                    }
                    break;
                    
                case VERIFY_SEED_PHRASE:
                case SEED_PHRASE_INVALID:
                    // For verification state, we need to restore the verification UI
                    // but the mnemonic was already fetched, so just setup the verification again
                    if (layoutWordHolder != null)
                    {
                        layoutWordHolder.setVisibility(View.VISIBLE);
                        layoutWordHolder.removeAllViews();
                        setupSelectableWords();
                    }
                    break;
                    
                case SHOW_PRIVATE_KEY:
                    // Private key needs to be re-derived for security
                    // This requires re-authentication, so redirect to seed fetch
                    viewModel.getAuthentication(wallet, this, this);
                    break;
                    
                default:
                    // Other states don't need special handling
                    break;
            }
        }
    }

    private void initViews()
    {
        title = findViewById(R.id.text_title);
        detail = findViewById(R.id.text_detail);
        layoutWordHolder = findViewById(R.id.layout_word_holder);
        verifyTextBox = findViewById(R.id.text_verify);
        verifyTextContainer = findViewById(R.id.container);
        backupImage = findViewById(R.id.backup_seed_image);
        functionButtonBar = findViewById(R.id.layoutButtons);
        inputView = findViewById(R.id.input_password);
        if (inputView != null)
        {
            inputView.getEditText().addTextChangedListener(this);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        toolbar();
        setTitle(getString(R.string.empty));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home)
        {
            backPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void handleBackPressed()
    {
        backPressed();
    }

    private void backPressed()
    {
        switch (state)
        {
            case VERIFY_SEED_PHRASE:
            case SEED_PHRASE_INVALID:
                //if we're currently verifying seed or we made a mistake copying the seed down then allow user to restart
                state = BackupState.WRITE_DOWN_SEED_PHRASE;
                WriteDownSeedPhrase();
                DisplaySeed();
                break;
            case WRITE_DOWN_SEED_PHRASE:
                state = BackupState.ENTER_BACKUP_STATE_HD;
                keyFailure("");
                break;
            case SET_JSON_PASSWORD:
                state = BackupState.ENTER_JSON_BACKUP;
                keyFailure("");
                break;
            case FINISH:
            case SHOW_SEED_PHRASE_SINGLE:
                state = BackupState.FINISH;
                finish();
                break;
            default:
                keyFailure("");
                break;
        }
    }

    @Override
    public void onClick(View view)
    {
        // Passing an empty String as this class handles clicks based on state
        handleClick("", 0);
    }

    private void ResetInputBox()
    {
        // Clear only the 4 selectable word boxes and reset their state
        for (int position : randomWordPositions)
        {
            if (allWordBoxes[position] != null)
            {
                allWordBoxes[position].setText((position + 1) + "#");
                allWordBoxes[position].setTag(null);
                allWordBoxes[position].setBackground(ContextCompat.getDrawable(this, R.drawable.background_seed_word_box_active));
            }
        }
        currentFillIndex = 0;
        if (randomWordPositions.length > 0)
        {
            updateWordBoxHighlight();
        }
        
        // Reset selectable words visibility
        for (int i = 0; i < selectableWordViews.length; i++)
        {
            if (selectableWordViews[i] != null)
            {
                selectableWordViews[i].setVisibility(View.VISIBLE);
            }
        }
    }

    private void JSONBackup()
    {
        setContentView(R.layout.activity_set_json_password);
        initViews();
        setTitle(getString(R.string.set_keystore_password));
        inputView.setInstruction(R.string.password_6_characters_or_more);
        state = BackupState.SET_JSON_PASSWORD;
        inputView.getEditText().addTextChangedListener(this);
        functionButtonBar.setPrimaryButtonText(R.string.save_keystore);
        functionButtonBar.setPrimaryButtonClickListener(this);
        functionButtonBar.setPrimaryButtonEnabled(false);
        inputView.setLayoutListener(this, this);
    }

    private void TestSeedPhrase()
    {
        // Verify only the 4 selected words match their positions
        if (selectedWords.size() != 4)
        {
            seedIncorrect();
            return;
        }
        
        // Check each selected word matches the expected word at that position
        for (int i = 0; i < 4; i++)
        {
            int position = randomWordPositions[i];
            String expectedWord = mnemonicArray[position];
            String selectedWord = selectedWords.get(i);
            
            if (!expectedWord.equals(selectedWord))
            {
                seedIncorrect();
                return;
            }
        }

        layoutWordHolder.setVisibility(View.GONE);

        //terminate and display tick
        backupKeySuccess(BackupOperationType.BACKUP_HD_KEY);
    }

    private void seedIncorrect()
    {
        // Removed for now:
        // The color switch is not shown anyway because ResetInputBox() is called immediately after
        // verifyTextContainer.setStrokeColor(ContextCompat.getColor(this, R.color.negative));
        // verifyTextBox.setTextColor(getColor(R.color.text_secondary));
        // TextView invalid = findViewById(R.id.text_invalid);
        // invalid.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.invalid_phrase, Toast.LENGTH_LONG).show();
        selectedWords.clear();
        ResetInputBox();
        VerifySeedPhrase();
    }

    private void backupKeySuccess(BackupOperationType type)
    {
        //first record backup time success, in case user aborts operation during key locking
        viewModel.backupSuccess(wallet);

        //now ask if user wants to upgrade the key security (if required)
        switch (wallet.authLevel)
        {
            case STRONGBOX_NO_AUTHENTICATION:
            case TEE_NO_AUTHENTICATION:
                //improve key security - but only if device has screen lock
                // For first-time wallet creation, check if device is secured first
                if (viewModel.deviceIsSecured())
                {
                    // During first-time wallet creation, skip upgrade to avoid white screen
                    // The key is already stored securely without authentication
                    // User can upgrade later from settings if they want
                    if (!isExplicitUpgrade)
                    {
                        // Skip upgrade during wallet creation for smoother experience
                        finishBackupSuccess(false);
                    }
                    else
                    {
                        // From settings, show the upgrade screen
                        setupUpgradeKey(true);
                    }
                }
                else
                {
                    // Device not secured, skip upgrade and finish
                    finishBackupSuccess(false);
                }
                break;
            default:
                finishBackupSuccess(true);
                break;
        }
    }

    private void finishBackupSuccess(boolean upgradeKey)
    {
        state = BackupState.SEED_PHRASE_VALIDATED;

        Intent intent = new Intent();
        switch (wallet.type)
        {
            case KEYSTORE_LEGACY:
            case KEYSTORE:
                intent.putExtra("TYPE", BackupOperationType.BACKUP_KEYSTORE_KEY);
                break;
            case HDKEY:
                intent.putExtra("TYPE", BackupOperationType.BACKUP_HD_KEY);
                break;
            default:
                cancelAuthentication();
                break;
        }

        intent.putExtra("Key", wallet.address);
        intent.putExtra("Upgrade", upgradeKey);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void VerifySeedPhrase()
    {
        setContentView(R.layout.activity_verify_seed_phrase);
        initViews();
        
        // Initialize all 12 word boxes from the grid
        allWordBoxes[0] = findViewById(R.id.word_box_1);
        allWordBoxes[1] = findViewById(R.id.word_box_2);
        allWordBoxes[2] = findViewById(R.id.word_box_3);
        allWordBoxes[3] = findViewById(R.id.word_box_4);
        allWordBoxes[4] = findViewById(R.id.word_box_5);
        allWordBoxes[5] = findViewById(R.id.word_box_6);
        allWordBoxes[6] = findViewById(R.id.word_box_7);
        allWordBoxes[7] = findViewById(R.id.word_box_8);
        allWordBoxes[8] = findViewById(R.id.word_box_9);
        allWordBoxes[9] = findViewById(R.id.word_box_10);
        allWordBoxes[10] = findViewById(R.id.word_box_11);
        allWordBoxes[11] = findViewById(R.id.word_box_12);
        
        tapHintText = findViewById(R.id.text_tap_hint);
        
        functionButtonBar.setPrimaryButtonText(R.string.action_continue);
        functionButtonBar.setPrimaryButtonClickListener(v -> TestSeedPhrase());
        functionButtonBar.setPrimaryButtonEnabled(false);
        state = BackupState.VERIFY_SEED_PHRASE;
        title.setText(R.string.verify_seed_phrase);
        TextView invalid = findViewById(R.id.text_invalid);
        if (invalid != null) invalid.setVisibility(View.INVISIBLE);
        layoutWordHolder.setVisibility(View.VISIBLE);
        layoutWordHolder.removeAllViews();
        
        // Generate 4 random positions for verification
        if (mnemonicArray != null && mnemonicArray.length > 0)
        {
            generateRandomWordPositions();
            selectedWords.clear();
            currentFillIndex = 0;
            
            // Setup all 12 word boxes
            setupWordBoxesGrid();
            
            // Update the instruction text
            TextView detail = findViewById(R.id.text_detail);
            if (detail != null)
            {
                detail.setText(R.string.complete_seed_phrase);
                detail.setVisibility(View.VISIBLE);
            }

            // Show only the 4 words that need to be selected (shuffled)
            setupSelectableWords();
        }
    }
    
    private void setupWordBoxesGrid()
    {
        for (int i = 0; i < 12; i++)
        {
            final int position = i;
            TextView box = allWordBoxes[i];
            if (box == null) continue;
            
            boolean isRandomPosition = isPositionRandom(i);
            
            if (isRandomPosition)
            {
                // This is one of the 4 positions user needs to fill
                box.setText((i + 1) + "# ......");
                box.setBackgroundResource(R.drawable.background_seed_word_box_active);
                box.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                box.setTag(null); // No word filled yet
                
                // Set click listener to remove word if filled
                box.setOnClickListener(v -> onWordBoxClick(position));
            }
            else
            {
                // This position shows masked word
                box.setText((i + 1) + "# ****");
                box.setBackgroundResource(R.drawable.background_seed_word_box_disabled);
                box.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                box.setOnClickListener(null);
                box.setClickable(false);
            }
        }
        updateWordBoxHighlight();
    }
    
    private boolean isPositionRandom(int position)
    {
        for (int randomPos : randomWordPositions)
        {
            if (randomPos == position) return true;
        }
        return false;
    }
    
    private void updateWordBoxHighlight()
    {
        // Highlight the current box that needs to be filled
        for (int i = 0; i < 4; i++)
        {
            int pos = randomWordPositions[i];
            TextView box = allWordBoxes[pos];
            if (box == null) continue;
            
            String tag = (String) box.getTag();
            boolean isFilled = tag != null && !tag.isEmpty();
            
            if (isFilled)
            {
                // Box is filled - show the word with filled style
                box.setBackgroundResource(R.drawable.background_seed_word_box_filled);
                box.setTextColor(getResources().getColor(R.color.brand, getTheme()));
            }
            else if (i == currentFillIndex)
            {
                // This is the next box to fill - highlight it
                box.setBackgroundResource(R.drawable.background_seed_word_box_active);
                box.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                box.setText((pos + 1) + "# ......");
            }
            else
            {
                // Empty but not current
                box.setBackgroundResource(R.drawable.background_seed_word_box);
                box.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                box.setText((pos + 1) + "# ......");
            }
        }
        
        // Show/hide tap hint based on whether any words are filled
        if (tapHintText != null)
        {
            tapHintText.setVisibility(currentFillIndex > 0 ? View.VISIBLE : View.GONE);
        }
    }
    
    private void onWordBoxClick(int position)
    {
        // Find which random position index this is
        int randomIndex = -1;
        for (int i = 0; i < 4; i++)
        {
            if (randomWordPositions[i] == position)
            {
                randomIndex = i;
                break;
            }
        }
        
        if (randomIndex == -1) return;
        
        TextView box = allWordBoxes[position];
        if (box == null) return;
        
        String filledWord = (String) box.getTag();
        if (filledWord == null || filledWord.isEmpty()) return; // Nothing to remove
        
        // Remove this word and all words after it
        for (int i = randomIndex; i < 4; i++)
        {
            int pos = randomWordPositions[i];
            TextView b = allWordBoxes[pos];
            if (b != null)
            {
                String word = (String) b.getTag();
                if (word != null && !word.isEmpty())
                {
                    // Return word to selectable list
                    returnWordToSelection(word);
                }
                b.setTag(null);
                b.setText((pos + 1) + "# ......");
            }
        }
        
        // Update selected words list
        while (selectedWords.size() > randomIndex)
        {
            selectedWords.remove(selectedWords.size() - 1);
        }
        
        currentFillIndex = randomIndex;
        updateWordBoxHighlight();
        functionButtonBar.setPrimaryButtonEnabled(false);
        
        // Hide invalid message
        TextView invalid = findViewById(R.id.text_invalid);
        if (invalid != null) invalid.setVisibility(View.INVISIBLE);
    }
    
    private void returnWordToSelection(String word)
    {
        // Find the word view and make it visible/enabled again
        for (TextView tv : selectableWordViews)
        {
            if (tv != null && word.equals(tv.getText().toString()))
            {
                tv.setSelected(false);
                tv.setAlpha(1.0f);
                tv.setClickable(true);
                tv.setOnClickListener(view -> onSelectableWordClick(tv));
                break;
            }
        }
    }
    
    private void setupSelectableWords()
    {
        layoutWordHolder.removeAllViews();
        
        // Create a shuffled list of just the 4 words
        List<String> wordsToShow = new ArrayList<>();
        for (int pos : randomWordPositions)
        {
            wordsToShow.add(mnemonicArray[pos]);
        }
        
        // Shuffle the words
        java.util.Collections.shuffle(wordsToShow);
        
        // Create TextViews for each word
        for (int i = 0; i < wordsToShow.size(); i++)
        {
            String word = wordsToShow.get(i);
            TextView tv = generateSeedWordTextView(word);
            tv.setOnClickListener(view -> onSelectableWordClick(tv));
            layoutWordHolder.addView(tv);
            selectableWordViews[i] = tv;
        }
    }
    
    private void onSelectableWordClick(TextView tv)
    {
        if (currentFillIndex >= 4) return; // All positions filled
        
        String selectedWord = tv.getText().toString();
        
        // Fill the current position
        int positionToFill = randomWordPositions[currentFillIndex];
        TextView box = allWordBoxes[positionToFill];
        if (box != null)
        {
            box.setText((positionToFill + 1) + "# " + selectedWord);
            box.setTag(selectedWord);
        }
        
        selectedWords.add(selectedWord);
        
        // Disable/dim the selected word
        tv.setSelected(true);
        tv.setAlpha(0.4f);
        tv.setClickable(false);
        tv.setOnClickListener(null);
        
        currentFillIndex++;
        updateWordBoxHighlight();
        
        // Enable continue button when all 4 words are selected
        if (currentFillIndex == 4)
        {
            functionButtonBar.setPrimaryButtonEnabled(true);
        }
    }
    
    private void generateRandomWordPositions()
    {
        // Generate 4 unique random positions (0-11 for 12 words)
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < mnemonicArray.length; i++)
        {
            positions.add(i);
        }
        
        // Shuffle and pick first 4
        for (int i = 0; i < 4; i++)
        {
            int randomIndex = (int) (Math.random() * positions.size());
            randomWordPositions[i] = positions.get(randomIndex);
            positions.remove(randomIndex);
        }
        
        // Sort the positions so they appear in order (easier for user)
        java.util.Arrays.sort(randomWordPositions);
    }

    private void WriteDownSeedPhrase()
    {
        setContentView(R.layout.activity_backup_write_seed);
        initViews();
        state = BackupState.WRITE_DOWN_SEED_PHRASE;
        title.setText(R.string.write_down_seed_phrase);
        functionButtonBar.setPrimaryButtonText(R.string.wrote_down_seed_phrase);
        functionButtonBar.setPrimaryButtonClickListener(this);
        
        // Setup copy seed phrase button
        View copySeedButton = findViewById(R.id.button_copy_seed);
        if (copySeedButton != null)
        {
            copySeedButton.setOnClickListener(v -> copySeedPhraseToClipboard());
        }
    }
    
    private void copySeedPhraseToClipboard()
    {
        if (mnemonicArray != null && mnemonicArray.length > 0)
        {
            StringBuilder seedPhrase = new StringBuilder();
            for (int i = 0; i < mnemonicArray.length; i++)
            {
                if (i > 0) seedPhrase.append(" ");
                seedPhrase.append(mnemonicArray[i]);
            }
            
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Seed Phrase", seedPhrase.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.seed_phrase_copied_auto_clear, Toast.LENGTH_LONG).show();
            
            // Auto-clear clipboard after 60 seconds for security
            // Use clearPrimaryClip on Android 10+ to avoid triggering 'Copied' indicator
            handler.postDelayed(() -> {
                try {
                    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            cb.clearPrimaryClip();
                        } else {
                            cb.setPrimaryClip(ClipData.newPlainText("", ""));
                        }
                    }
                } catch (Exception e) {
                    Timber.e(e, "Error clearing clipboard");
                }
            }, 60000); // 60 seconds
        }
    }

    private void DisplaySeed()
    {
        // Check if the key exists in secure storage before attempting to display
        if (wallet != null && wallet.type == WalletType.HDKEY && !viewModel.hasKey(wallet.address))
        {
            DisplayKeyFailureDialog(getString(R.string.wallet_key_not_found));
            return;
        }
        
        if (layoutWordHolder != null)
        {
            layoutWordHolder.setVisibility(View.VISIBLE);
            layoutWordHolder.removeAllViews();
        }

        viewModel.getAuthentication(wallet, this, this);
    }

    private TextView generateSeedWordTextView(String word)
    {
        int margin = (int) getResources().getDimension(R.dimen.mini_4);
        FlexboxLayout.LayoutParams params =
                new FlexboxLayout.LayoutParams(FlexboxLayout.LayoutParams.WRAP_CONTENT, FlexboxLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(margin, margin, margin, margin);

        TextView seedWord = new TextView(this, null, R.attr.seedWordStyle);
        seedWord.setText(word);
        seedWord.setLayoutParams(params);

        return seedWord;
    }

    @Override
    public void HDKeyCreated(String address, Context ctx, KeyService.AuthenticationLevel level)
    {
        //empty, doesn't get called
    }

    @Override
    public void keyFailure(String message)
    {
        if (message != null && message.length() > 0)
        {
            DisplayKeyFailureDialog(message);
        }
        else
        {
            Intent intent = new Intent();
            setResult(RESULT_CANCELED, intent);
            intent.putExtra("Key", wallet.address);
            finish();
        }
    }

    private void DisplayKeyFailureDialog(String message)
    {
        hideDialog();

        alertDialog = new AWalletAlertDialog(this);
        alertDialog.setIcon(AWalletAlertDialog.ERROR);
        alertDialog.setTitle(R.string.key_error);
        
        // Add helpful context to the error message
        String enhancedMessage = message;
        if (message != null && (message.contains("not found") || message.contains("Re-import")))
        {
            enhancedMessage = message + "\n\n" + getString(R.string.suggest_reimport_wallet);
        }
        
        alertDialog.setMessage(enhancedMessage);
        alertDialog.setButtonText(R.string.dialog_ok);
        alertDialog.setSecondaryButtonText(R.string.action_my_wallets);
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialog.setButtonListener(v ->
        {
            cancelAuthentication();
            alertDialog.dismiss();
        });
        alertDialog.setSecondaryButtonListener(v ->
        {
            cancelAuthentication();
            alertDialog.dismiss();
            // Navigate to wallet management screen
            finish();
        });
        alertDialog.setOnCancelListener(v ->
        {
            cancelAuthentication();
        });
        alertDialog.show();
    }

    @Override
    public void fetchMnemonic(String mnemonic)
    {
        handler.post(() ->
        {
            switch (state)
            {
                case WRITE_DOWN_SEED_PHRASE:
                    WriteDownSeedPhrase();
                    mnemonicArray = mnemonic.split(" ");
                    addSeedWordsToScreen();
                    break;
                case ENTER_JSON_BACKUP:
                case SET_JSON_PASSWORD:
                    viewModel.exportWallet(wallet, mnemonic, keystorePassword);
                    break;
                case SHOW_SEED_PHRASE:
                    setupTestSeed(); //drop through
                case SHOW_SEED_PHRASE_SINGLE:
                    mnemonicArray = mnemonic.split(" ");
                    addSeedWordsToScreen();
                    break;
                case VERIFY_SEED_PHRASE:
                    // Mnemonic already set, display verification screen
                    mnemonicArray = mnemonic.split(" ");
                    VerifySeedPhrase();
                    break;
                case SHOW_PRIVATE_KEY:
                    displayPrivateKey(mnemonic);
                    break;
                case SEED_PHRASE_INVALID:
                case UNDEFINED:
                case ENTER_BACKUP_STATE_HD:
                case UPGRADE_KEY_SECURITY:
                    DisplayKeyFailureDialog("Error in key restore: " + state.ordinal());
                    break;
            }
        });
    }

    private void addSeedWordsToScreen()
    {
        if (mnemonicArray == null) return;
        layoutWordHolder.setFlexDirection(FlexDirection.ROW);

        int position = 1;
        for (String word : mnemonicArray)
        {
            String numberedWord = position + ". " + word;
            layoutWordHolder.addView(generateSeedWordTextView(numberedWord));
            position++;
        }
    }

    @Override
    public void gotAuthorisation(boolean gotAuth)
    {
        if (gotAuth)
        {
            //use this to get seed backup
            switch (state)
            {
                case UNDEFINED:
                    break;
                case ENTER_BACKUP_STATE_HD:
                    break;
                case WRITE_DOWN_SEED_PHRASE:
                    //proceed and get the mnemonic
                    viewModel.getSeedPhrase(wallet, this, this);
                    break;
                case VERIFY_SEED_PHRASE:
                    break;
                case SEED_PHRASE_INVALID:
                    break;
                case ENTER_JSON_BACKUP:
                case SET_JSON_PASSWORD:
                    viewModel.getPasswordForKeystore(wallet, this, this);
                    break;
                case SHOW_SEED_PHRASE_SINGLE:
                case SHOW_SEED_PHRASE:
                    viewModel.getSeedPhrase(wallet, this, this);
                    break;
                case SHOW_PRIVATE_KEY:
                    viewModel.getSeedPhrase(wallet, this, this);
                    break;
                case UPGRADE_KEY_SECURITY:
                    upgradeKeySecurity();
                    break;
            }
        }
        else
        {
            DisplayKeyFailureDialog(getString(R.string.authentication_error));
        }
    }

    @Override
    public void cancelAuthentication()
    {
        Intent intent = new Intent();
        setResult(RESULT_CANCELED, intent);
        intent.putExtra("Key", wallet.address);
        if (hasNoLock) intent.putExtra("nolock", true);
        finish();
    }

    @Override
    public void gotSignature(SignatureFromKey signature)
    {
        //No code here since we don't need to backup hardware key
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this)
                .get(BackupKeyViewModel.class);
        viewModel.exportedStore().observe(this, this::onExportKeystore);
    }

    ActivityResultLauncher<Intent> handleBackupWallet = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result ->
            {
                if (result.getResultCode() == RESULT_OK)
                {
                    backupKeySuccess(BackupOperationType.BACKUP_KEYSTORE_KEY);
                }
                else
                {
                    AskUserSuccess();
                }
            });

    private void onExportKeystore(String keystore)
    {
        Intent sharingIntent = new Intent(Intent.ACTION_SEND);
        sharingIntent.setType("text/plain");
        sharingIntent.putExtra(Intent.EXTRA_SUBJECT, "Keystore");
        sharingIntent.putExtra(Intent.EXTRA_TEXT, keystore);
        handleBackupWallet.launch(Intent.createChooser(sharingIntent, "Share via"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        Operation taskCode = null;

        //Interpret the return code; if it's within the range of values possible to return from PIN confirmation then separate out
        //the task code from the return value. We have to do it this way because there's no way to send a bundle across the PIN dialog
        //and out through the PIN dialog's return back to here
        if (requestCode >= SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS && requestCode <= SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS + 10)
        {
            taskCode = Operation.values()[requestCode - SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS];
            requestCode = SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS;
        }

        switch (requestCode)
        {
            case SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS:
                if (resultCode == RESULT_OK)
                {
                    viewModel.completeAuthentication(taskCode);
                }
                else
                {
                    viewModel.failedAuthentication(taskCode);
                }
                break;
        }
    }

    private void AskUserSuccess()
    {
        hideDialog();
        alertDialog = new AWalletAlertDialog(this);
        alertDialog.setIcon(AWalletAlertDialog.SUCCESS);
        alertDialog.setTitle(R.string.do_manage_make_backup);
        alertDialog.setButtonText(R.string.yes_continue);
        alertDialog.setButtonListener(v ->
        {
            hideDialog();
            backupKeySuccess(BackupOperationType.BACKUP_KEYSTORE_KEY);
        });
        alertDialog.setSecondaryButtonText(R.string.no_repeat);
        alertDialog.setSecondaryButtonListener(v ->
        {
            hideDialog();
            cancelAuthentication();
        });
        alertDialog.show();
    }

    private void hideDialog()
    {
        if (alertDialog != null && alertDialog.isShowing())
        {
            alertDialog.dismiss();
            alertDialog = null;
        }
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2)
    {

    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2)
    {

    }

    @Override
    public void afterTextChanged(Editable editable)
    {
        switch (state)
        {
            case ENTER_BACKUP_STATE_HD:
                break;
            case WRITE_DOWN_SEED_PHRASE:
                break;
            case VERIFY_SEED_PHRASE:
                break;
            case SEED_PHRASE_INVALID:
                break;
            case ENTER_JSON_BACKUP:
                break;
            case SET_JSON_PASSWORD:
                String txt = inputView.getText().toString();
                if (txt.length() >= 6)
                {
                    inputView.setError("");
                    functionButtonBar.setPrimaryButtonEnabled(true);
                }
                break;
            case SHOW_SEED_PHRASE:
                break;
        }
    }

    @Override
    public void onLayoutShrunk()
    {

    }

    @Override
    public void onLayoutExpand()
    {

    }

    @Override
    public void onInputDoneClick(View view)
    {
        inputView = findViewById(R.id.input_password);
        keystorePassword = inputView.getText().toString();
        if (keystorePassword.length() > 5)
        {
            //get authentication
            viewModel.getAuthentication(wallet, this, this);
        }
        else
        {
            inputView.setError(R.string.password_6_characters_or_more);
        }
    }

    @Override
    public void handleClick(String action, int id)
    {
        switch (state)
        {
            case ENTER_BACKUP_STATE_HD:
                WriteDownSeedPhrase();
                DisplaySeed();
                break;
            case WRITE_DOWN_SEED_PHRASE:
            case SHOW_SEED_PHRASE:
                VerifySeedPhrase();
                break;
            case VERIFY_SEED_PHRASE:
                TestSeedPhrase();
                break;
            case SEED_PHRASE_INVALID:
                ResetInputBox();
                VerifySeedPhrase();
                break;
            case SHOW_SEED_PHRASE_SINGLE:
                state = BackupState.FINISH;
                finish();
                break;
            case ENTER_JSON_BACKUP:
                JSONBackup();
                break;
            case SET_JSON_PASSWORD:
                inputView = findViewById(R.id.input_password);
                onInputDoneClick(inputView);
                break;
            case UPGRADE_KEY_SECURITY:
                //first open authentication
                viewModel.getAuthentication(wallet, this, this);
                break;
        }
    }

    // Note: Screenshots are intentionally ALLOWED for seed phrase backup
    // This helps users save their recovery phrase securely
    // The copy button provides an additional way to save the seed phrase
    private void secureWindow()
    {
        // Previously blocked screenshots, now allowing for better UX
        // Users can screenshot their seed phrase to save it
        // getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    }
}
