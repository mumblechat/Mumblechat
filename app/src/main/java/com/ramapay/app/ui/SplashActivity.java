package com.ramapay.app.ui;

import static com.ramapay.app.C.IMPORT_REQUEST_CODE;
import static com.ramapay.app.C.Key.WALLET;
import static com.ramapay.app.entity.BackupState.ENTER_BACKUP_STATE_HD;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

import com.ramapay.app.R;
import com.ramapay.app.analytics.Analytics;
import com.ramapay.app.entity.AnalyticsProperties;
import com.ramapay.app.entity.BackupOperationType;
import com.ramapay.app.entity.CreateWalletCallbackInterface;
import com.ramapay.app.entity.CustomViewSettings;
import com.ramapay.app.entity.Operation;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.analytics.FirstWalletAction;
import com.ramapay.app.router.HomeRouter;
import com.ramapay.app.router.ImportWalletRouter;
import com.ramapay.app.service.AppSecurityManager;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.util.RootUtil;

import javax.inject.Inject;
import com.ramapay.app.util.Utils;
import com.ramapay.app.viewmodel.SplashViewModel;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.SignTransactionDialog;
import com.bumptech.glide.Glide;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SplashActivity extends BaseActivity implements CreateWalletCallbackInterface, Runnable
{
    @Inject
    AppSecurityManager appSecurityManager;
    
    private SplashViewModel viewModel;
    private String errorMessage;
    private String pendingWalletAddress;
    private KeyService.AuthenticationLevel pendingAuthLevel;
    private View loadingLayout;
    private com.ramapay.app.widget.PercentageProgressView percentageProgress;
    private boolean pendingHomeNavigation = false;
    private boolean isNavigatingToHome = false; // Flag to prevent double navigation
    private boolean walletCreationInProgress = false; // Flag to track wallet creation flow
    private boolean pendingSecuritySetup = false; // Flag to track security setup before backup
    private boolean pendingImportNavigation = false; // Flag to proceed to home after security for imports
    private boolean pendingImportAfterSecurity = false; // Flag to open ImportWalletActivity after security setup
    
    // Network status views
    private ImageView iconNetworkStatus;
    private TextView textNetworkStatus;
    private boolean isNetworkAvailable = false;
    private ConnectivityManager connectivityManager;
    
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            runOnUiThread(() -> updateNetworkStatus(true));
        }

        @Override
        public void onLost(@NonNull Network network) {
            runOnUiThread(() -> updateNetworkStatus(false));
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                  capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            runOnUiThread(() -> updateNetworkStatus(hasInternet));
        }
    };
    
    private final Runnable displayError = new Runnable()
    {
        @Override
        public void run()
        {
            AWalletAlertDialog aDialog = new AWalletAlertDialog(getThisActivity());
            aDialog.setTitle(R.string.key_error);
            aDialog.setIcon(AWalletAlertDialog.ERROR);
            aDialog.setMessage(errorMessage);
            aDialog.setButtonText(R.string.dialog_ok);
            aDialog.setButtonListener(v -> aDialog.dismiss());
            aDialog.show();
        }
    };
    private Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> appLockHandler = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            int resultCode = result.getResultCode();
            if (resultCode == RESULT_OK || resultCode == AppLockActivity.RESULT_AUTHENTICATED) {
                // Authentication successful - proceed to home
                proceedToHome();
            } else {
                // User cancelled authentication - close app
                finishAffinity();
            }
        }
    );
    
    private final ActivityResultLauncher<Intent> securitySetupHandler = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            // Security setup completed or skipped
            pendingSecuritySetup = false;
            
            if (pendingImportAfterSecurity)
            {
                // Security done, now open ImportWalletActivity
                pendingImportAfterSecurity = false;
                new ImportWalletRouter().openForResult(this, IMPORT_REQUEST_CODE, true);
            }
            else if (pendingImportNavigation)
            {
                // This was from an import completion, proceed to home
                pendingImportNavigation = false;
                isNavigatingToHome = true;
                viewModel.fetchWallets();
            }
            else if (pendingWalletAddress != null && pendingAuthLevel != null)
            {
                // This was from wallet creation, proceed to backup flow
                launchBackupFlow();
            }
        }
    );

    private final ActivityResultLauncher<Intent> handleBackupWallet = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && pendingWalletAddress != null)
            {
                // Mark that we're navigating to home to prevent double navigation
                isNavigatingToHome = true;
                walletCreationInProgress = false;
                
                // Show loading indicator while wallet is being stored
                showLoading(true);
                // Backup successful, now store the wallet and proceed to home
                viewModel.StoreHDKey(pendingWalletAddress, pendingAuthLevel);
                pendingWalletAddress = null;
                pendingAuthLevel = null;
            }
            else
            {
                walletCreationInProgress = false;
                // Backup was cancelled, show friendly confirmation dialog
                showBackupCancelledDialog();
            }
        }
    );

    private void showLoading(boolean show)
    {
        if (loadingLayout != null)
        {
            loadingLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && percentageProgress != null)
            {
                // Simulate progress over 3 seconds for wallet creation
                percentageProgress.startSimulation(3000);
            }
            else if (!show && percentageProgress != null)
            {
                percentageProgress.hide();
            }
        }
    }

    @Override
    protected void attachBaseContext(Context base)
    {
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        
        // Set window background for futuristic theme
        getWindow().setBackgroundDrawableResource(R.drawable.futuristic_background);
        
        setContentView(R.layout.activity_splash);

        // Load splash screen image
        ImageView splashImage = findViewById(R.id.splash_image);
        Glide.with(this)
            .load(R.raw.ramapay_splash)
            .into(splashImage);

        // Start logo and ring animations
        startLogoAnimations();

        // Apply gradient to Ramestta Network text (Purple to Dark Gold)
        TextView ramesttaText = findViewById(R.id.text_ramestta_network);
        if (ramesttaText != null)
        {
            ramesttaText.post(() -> {
                float width = ramesttaText.getPaint().measureText(ramesttaText.getText().toString());
                android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(
                    0, 0, width, 0,
                    new int[]{
                        android.graphics.Color.parseColor("#D4AF37"), // Gold
                        android.graphics.Color.parseColor("#FFD700"), // Bright Gold
                        android.graphics.Color.parseColor("#D4AF37")  // Gold
                    },
                    new float[]{0.0f, 0.5f, 1.0f},
                    android.graphics.Shader.TileMode.CLAMP
                );
                ramesttaText.getPaint().setShader(gradient);
                ramesttaText.invalidate();
            });
        }

        // Initialize loading layout and percentage progress
        loadingLayout = findViewById(R.id.layout_loading);
        percentageProgress = findViewById(R.id.percentage_progress);
        
        // Initialize network status views
        iconNetworkStatus = findViewById(R.id.icon_network_status);
        textNetworkStatus = findViewById(R.id.text_network_status);
        
        // Setup network connectivity monitoring
        setupNetworkMonitoring();

        //detect previous launch
        viewModel = new ViewModelProvider(this)
            .get(SplashViewModel.class);
        viewModel.cleanAuxData(getApplicationContext());
        viewModel.wallets().observe(this, this::onWallets);
        viewModel.createWallet().observe(this, this::onWalletCreate);
        viewModel.fetchWallets();

        checkRoot();
    }

    protected Activity getThisActivity()
    {
        return this;
    }

    //wallet created, now check if we need to import
    private void onWalletCreate(Wallet wallet)
    {
        Wallet[] wallets = new Wallet[1];
        wallets[0] = wallet;
        onWallets(wallets);
    }

    private void onWallets(Wallet[] wallets)
    {
        // Prevent double navigation
        if (isNavigatingToHome)
        {
            // Already navigating, just proceed directly
            if (wallets.length > 0)
            {
                viewModel.doWalletStartupActions(wallets[0]);
                proceedToHomeImmediately();
            }
            return;
        }
        
        //event chain should look like this:
        //1. check if wallets are empty:
        //      - yes, get either create a new account or take user to wallet page if SHOW_NEW_ACCOUNT_PROMPT is set
        //              then come back to this check.
        //      - no. proceed to check if we are importing a link
        //2. repeat after step 1 is complete. Are we importing a ticket?
        //      - yes - proceed with import
        //      - no - proceed to home activity
        if (wallets.length == 0)
        {
            viewModel.setDefaultBrowser();
            findViewById(R.id.layout_new_wallet).setVisibility(View.VISIBLE);
            findViewById(R.id.button_create).setOnClickListener(v -> {
                // Check network connectivity first
                if (!isNetworkAvailable)
                {
                    Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_LONG).show();
                    return;
                }
                // Mark wallet creation in progress
                walletCreationInProgress = true;
                AnalyticsProperties props = new AnalyticsProperties();
                props.put(FirstWalletAction.KEY, FirstWalletAction.CREATE_WALLET.getValue());
                viewModel.track(Analytics.Action.FIRST_WALLET_ACTION, props);
                viewModel.createNewWallet(this, this);
            });
            findViewById(R.id.button_watch).setOnClickListener(v -> {
                walletCreationInProgress = true;
                new ImportWalletRouter().openWatchCreate(this, IMPORT_REQUEST_CODE);
            });
            findViewById(R.id.button_import).setOnClickListener(v -> {
                // Check network connectivity first
                if (!isNetworkAvailable)
                {
                    Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_LONG).show();
                    return;
                }
                walletCreationInProgress = true;
                
                // Check if security is already set up or skipped - show security FIRST like create wallet
                if (appSecurityManager != null && 
                    !appSecurityManager.isSecurityEnabled() && 
                    !appSecurityManager.isSecuritySetupSkipped())
                {
                    // Show security setup FIRST before import
                    pendingSecuritySetup = true;
                    pendingImportAfterSecurity = true;
                    Intent securityIntent = SetupSecurityActivity.createIntent(this, false, true);
                    securitySetupHandler.launch(securityIntent);
                }
                else
                {
                    // Security already set up or skipped, proceed to import
                    new ImportWalletRouter().openForResult(this, IMPORT_REQUEST_CODE, true);
                }
            });
        }
        else
        {
            viewModel.doWalletStartupActions(wallets[0]);
            handler.postDelayed(this, CustomViewSettings.startupDelay());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode >= SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS && requestCode <= SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS + 10)
        {
            Operation taskCode = Operation.values()[requestCode - SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS];
            if (resultCode == RESULT_OK)
            {
                viewModel.completeAuthentication(taskCode);
            }
            else
            {
                viewModel.failedAuthentication(taskCode);
            }
        }
        else if (requestCode == IMPORT_REQUEST_CODE)
        {
            walletCreationInProgress = false;
            if (resultCode == RESULT_OK && data != null)
            {
                // Mark the imported wallet as new so HomeActivity shows network selection
                Wallet importedWallet = data.getParcelableExtra(WALLET);
                if (importedWallet != null)
                {
                    viewModel.markWalletAsNew(importedWallet.address);
                }
                
                // Security was already set up BEFORE import, proceed directly to home
                isNavigatingToHome = true;
                viewModel.fetchWallets();
            }
            else
            {
                // Import cancelled, stay on splash and reset state
                isNavigatingToHome = false;
            }
        }
    }

    @Override
    public void HDKeyCreated(String address, Context ctx, KeyService.AuthenticationLevel level)
    {
        // Store wallet details temporarily
        pendingWalletAddress = address;
        pendingAuthLevel = level;
        
        // Check if security is already set up or skipped
        if (appSecurityManager != null && 
            !appSecurityManager.isSecurityEnabled() && 
            !appSecurityManager.isSecuritySetupSkipped())
        {
            // Show security setup FIRST before showing seed phrase
            pendingSecuritySetup = true;
            Intent securityIntent = SetupSecurityActivity.createIntent(this, false, true);
            securitySetupHandler.launch(securityIntent);
        }
        else
        {
            // Security already set up or skipped, proceed to backup flow
            launchBackupFlow();
        }
    }
    
    /**
     * Launch the backup flow to show seed phrase and verify
     */
    private void launchBackupFlow()
    {
        if (pendingWalletAddress == null || pendingAuthLevel == null)
        {
            return;
        }
        
        // Create temporary wallet for backup
        Wallet tempWallet = new Wallet(pendingWalletAddress);
        tempWallet.type = com.ramapay.app.entity.WalletType.HDKEY;
        tempWallet.authLevel = pendingAuthLevel;
        
        // Launch backup activity for seed phrase verification
        Intent intent = new Intent(this, BackupKeyActivity.class);
        intent.putExtra(WALLET, tempWallet);
        intent.putExtra("STATE", ENTER_BACKUP_STATE_HD);
        intent.setFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        handleBackupWallet.launch(intent);
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        handler = null;
        
        // Unregister network callback
        if (connectivityManager != null)
        {
            try
            {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
            catch (Exception e)
            {
                // Callback may not be registered
            }
        }
    }

    @Override
    public void keyFailure(String message)
    {
        errorMessage = message;
        if (handler != null) handler.post(displayError);
    }

    @Override
    public void cancelAuthentication()
    {

    }

    @Override
    public void fetchMnemonic(String mnemonic)
    {

    }

    @Override
    public void run()
    {
        // Check if authentication is required before going to HomeActivity
        if (appSecurityManager != null && appSecurityManager.requiresAuthentication())
        {
            pendingHomeNavigation = true;
            Intent lockIntent = AppLockActivity.createIntent(this);
            appLockHandler.launch(lockIntent);
        }
        else
        {
            proceedToHome();
        }
    }
    
    private void proceedToHome()
    {
        if (isFinishing()) return; // Don't navigate if activity is finishing
        new HomeRouter().open(this, true);
        finish();
    }
    
    /**
     * Navigate to Home immediately without delay (used after wallet creation/import)
     */
    private void proceedToHomeImmediately()
    {
        if (isFinishing()) return; // Don't navigate if activity is finishing
        showLoading(false);
        new HomeRouter().open(this, true, true); // Pass true for newWalletCreated
        finish();
    }

    private void checkRoot()
    {
        if (RootUtil.isDeviceRooted())
        {
            AWalletAlertDialog dialog = new AWalletAlertDialog(this);
            dialog.setTitle(R.string.root_title);
            dialog.setMessage(R.string.root_body);
            dialog.setButtonText(R.string.ok);
            dialog.setIcon(AWalletAlertDialog.ERROR);
            dialog.setButtonListener(v -> dialog.dismiss());
            dialog.show();
        }
    }
    
    private void showBackupCancelledDialog()
    {
        AWalletAlertDialog dialog = new AWalletAlertDialog(this);
        dialog.setTitle(R.string.backup_cancelled_title);
        dialog.setMessage(R.string.backup_cancelled_message);
        dialog.setIcon(AWalletAlertDialog.WARNING);
        dialog.setCanceledOnTouchOutside(false);
        
        // Try Again button - restart wallet creation
        dialog.setButtonText(R.string.try_again);
        dialog.setButtonListener(v -> {
            dialog.dismiss();
            // Start wallet creation again
            viewModel.createNewWallet(getThisActivity(), this);
        });
        
        // Cancel button - go back to welcome screen
        dialog.setSecondaryButtonText(R.string.cancel_creation);
        dialog.setSecondaryButtonListener(v -> {
            dialog.dismiss();
            pendingWalletAddress = null;
            pendingAuthLevel = null;
            // Refresh to show welcome screen
            viewModel.fetchWallets();
        });
        
        dialog.show();
    }
    
    /**
     * Setup network connectivity monitoring to track online/offline status
     */
    private void setupNetworkMonitoring()
    {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null)
        {
            // Check initial network status
            isNetworkAvailable = Utils.isNetworkAvailable(this);
            updateNetworkStatus(isNetworkAvailable);
            
            // Register for network changes
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
        }
    }
    
    /**
     * Update the network status indicator UI
     * @param isOnline true if connected to internet, false otherwise
     */
    private void updateNetworkStatus(boolean isOnline)
    {
        isNetworkAvailable = isOnline;
        
        if (iconNetworkStatus != null)
        {
            iconNetworkStatus.setImageResource(isOnline ? 
                R.drawable.ic_online_indicator : R.drawable.ic_offline_indicator);
        }
        
        if (textNetworkStatus != null)
        {
            textNetworkStatus.setText(isOnline ? R.string.online : R.string.offline);
            textNetworkStatus.setTextColor(isOnline ? 
                android.graphics.Color.parseColor("#4CAF50") : 
                android.graphics.Color.parseColor("#F44336"));
        }
    }
    
    /**
     * Start all logo animations for futuristic effect
     */
    private void startLogoAnimations()
    {
        // Rotating inner ring (gold gradient)
        ImageView ringInner = findViewById(R.id.ring_inner);
        if (ringInner != null)
        {
            Animation rotateRing = AnimationUtils.loadAnimation(this, R.anim.rotate_ring);
            ringInner.startAnimation(rotateRing);
        }
        
        // Rotating outer ring (purple) - reverse direction
        ImageView ringOuter = findViewById(R.id.ring_outer);
        if (ringOuter != null)
        {
            Animation rotateRingReverse = AnimationUtils.loadAnimation(this, R.anim.rotate_ring_reverse);
            ringOuter.startAnimation(rotateRingReverse);
        }
        
        // Pulsing glow effect
        ImageView glowOuter = findViewById(R.id.glow_outer);
        if (glowOuter != null)
        {
            Animation pulseGlow = AnimationUtils.loadAnimation(this, R.anim.pulse_glow);
            glowOuter.startAnimation(pulseGlow);
        }
        
        // Floating logo animation
        ImageView splashImage = findViewById(R.id.splash_image);
        if (splashImage != null)
        {
            Animation logoFloat = AnimationUtils.loadAnimation(this, R.anim.logo_float);
            splashImage.startAnimation(logoFloat);
        }
        
        // Fade in text animations with delays
        TextView welcomeText = findViewById(R.id.text_welcome);
        TextView subtitleText = findViewById(R.id.text_subtitle);
        
        if (welcomeText != null)
        {
            Animation fadeInUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up);
            fadeInUp.setStartOffset(300);
            welcomeText.startAnimation(fadeInUp);
        }
        
        if (subtitleText != null)
        {
            Animation fadeInUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up);
            fadeInUp.setStartOffset(500);
            subtitleText.startAnimation(fadeInUp);
        }
    }
}
