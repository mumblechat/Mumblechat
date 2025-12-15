package com.ramapay.app.ui;

import static com.ramapay.app.C.Key.WALLET;
import static com.ramapay.app.entity.BackupState.ENTER_BACKUP_STATE_HD;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.ramapay.app.C;
import com.ramapay.app.R;
import com.ramapay.app.entity.BackupState;
import com.ramapay.app.entity.CreateWalletCallbackInterface;
import com.ramapay.app.ui.BackupKeyActivity;
import com.ramapay.app.entity.CustomViewSettings;
import com.ramapay.app.entity.ErrorEnvelope;
import com.ramapay.app.entity.Operation;
import com.ramapay.app.entity.SyncCallback;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.repository.EthereumNetworkRepository;
import com.ramapay.app.repository.PreferenceRepositoryType;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.ui.widget.adapter.WalletsSummaryAdapter;
import com.ramapay.app.viewmodel.WalletsViewModel;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.AddWalletView;
import com.ramapay.app.widget.SignTransactionDialog;
import com.ramapay.app.widget.SystemView;
import com.ramapay.hardware.HardwareCallback;
import com.ramapay.hardware.HardwareDevice;
import com.ramapay.hardware.SignatureFromKey;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;

import com.ramapay.app.util.Utils;
import org.web3j.crypto.Keys;

import java.security.SignatureException;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

@AndroidEntryPoint
public class WalletsActivity extends BaseActivity implements
        View.OnClickListener,
        AddWalletView.OnNewWalletClickListener,
        AddWalletView.OnImportWalletClickListener,
        AddWalletView.OnWatchWalletClickListener,
        AddWalletView.OnCloseActionListener,
        AddWalletView.OnHardwareCardActionListener,
        AddWalletView.OnAddAccountClickListener,
        CreateWalletCallbackInterface,
        HardwareCallback,
        SyncCallback
{
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long balanceChain = EthereumNetworkRepository.getOverrideToken().chainId;
    private WalletsViewModel viewModel;
    private RecyclerView list;
    private SystemView systemView;
    private Dialog dialog;
    private AWalletAlertDialog aDialog;
    private WalletsSummaryAdapter adapter;
    private ActivityResultLauncher<Intent> editWalletDetails;
    private AWalletAlertDialog cardReadDialog;
    private String dialogError;
    private final Runnable displayWalletError = new Runnable()
    {
        @Override
        public void run()
        {
            aDialog = new AWalletAlertDialog(getThisActivity());
            aDialog.setTitle(R.string.title_dialog_error);
            aDialog.setIcon(AWalletAlertDialog.ERROR);
            aDialog.setMessage(TextUtils.isEmpty(dialogError)
                    ? getString(R.string.error_create_wallet)
                    : dialogError);
            aDialog.setButtonText(R.string.dialog_ok);
            aDialog.setButtonListener(v -> aDialog.dismiss());
            aDialog.show();
        }
    };

    private final HardwareDevice hardwareCard = new HardwareDevice(this);
    private boolean isDerivingAccount = false;
    private String parentWalletAddress = null;
    private int derivedAccountIndex = 0;
    private boolean isCreatingNewHDWallet = false;
    private String pendingWalletAddress = null;
    private KeyService.AuthenticationLevel pendingAuthLevel = null;
    private ActivityResultLauncher<Intent> handleBackupWallet;
    
    // Account discovery state
    private Wallet pendingDiscoveryWallet = null;
    private android.app.AlertDialog discoveryProgressDialog = null;

    @Inject
    PreferenceRepositoryType preferenceRepository;

    private Wallet lastActiveWallet;
    private boolean reloadRequired;
    private Disposable disposable;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallets);
        toolbar();
        setTitle(getString(R.string.title_wallets_summary));
        initResultLaunchers();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        initViewModel();
        hardwareCard.activateReader(this);
        hardwareCard.setSigningData(org.web3j.crypto.Hash.sha3(WalletsViewModel.TEST_STRING.getBytes()));
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        hideDialog();
        viewModel.onPause(); //no need to update balances if view isn't showing
    }

    private void scrollToDefaultWallet()
    {
        int position = adapter.getDefaultWalletIndex();
        if (position != -1)
        {
            list.getLayoutManager().scrollToPosition(position);
        }
    }

    private void initViewModel()
    {
        if (viewModel == null)
        {
            systemView = findViewById(R.id.system_view);
            viewModel = new ViewModelProvider(this)
                    .get(WalletsViewModel.class);
            viewModel.error().observe(this, this::onError);
            viewModel.progress().observe(this, systemView::showProgress);
            viewModel.wallets().observe(this, this::onFetchWallets);
            viewModel.setupWallet().observe(this, this::setupWallet); //initial wallet setup at activity startup
            viewModel.newWalletCreated().observe(this, this::onNewWalletCreated); //new wallet was created
            viewModel.changeDefaultWallet().observe(this, this::walletChanged);
            viewModel.createWalletError().observe(this, this::onCreateWalletError);
            viewModel.noWalletsError().observe(this, this::noWallets);
            viewModel.baseTokens().observe(this, this::updateBaseTokens);
            viewModel.discoveryAuthComplete().observe(this, this::onDiscoveryAuthComplete);
        }

        disposable = viewModel.getWalletInteract().find()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onActiveWalletFetched);

        initViews();
        viewModel.onPrepare(balanceChain, this);
    }

    private void onActiveWalletFetched(Wallet activeWallet)
    {
        if (lastActiveWallet != null)
        {
            reloadRequired = !lastActiveWallet.equals(activeWallet);
        }

        lastActiveWallet = activeWallet;
    }

    private void updateBaseTokens(Map<String, Token[]> walletTokens)
    {
        adapter.setTokens(walletTokens);
    }

    protected Activity getThisActivity()
    {
        return this;
    }

    private void noWallets(Boolean aBoolean)
    {
        Intent intent = new Intent(this, SplashActivity.class);
        startActivity(intent);
        preFinish();
    }

    private void initViews()
    {
        SwipeRefreshLayout refreshLayout = findViewById(R.id.refresh_layout);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));

        adapter = new WalletsSummaryAdapter(this, this::onSetWalletDefault, viewModel.getWalletInteract());
        list.setAdapter(adapter);

        systemView.attachRecyclerView(list);
        systemView.attachSwipeRefreshLayout(refreshLayout);
        refreshLayout.setOnRefreshListener(this::onSwipeRefresh);
    }

    private void onSwipeRefresh()
    {
        viewModel.swipeRefreshWallets(); //check all records
    }

    private void onCreateWalletError(ErrorEnvelope errorEnvelope)
    {
        dialogError = errorEnvelope.message;
        if (handler != null) handler.post(displayWalletError);
    }

    @Override
    public void syncUpdate(String wallet, Pair<Double, Double> value)
    {
        runOnUiThread(() -> adapter.updateWalletState(wallet, value));
    }

    @Override
    public void syncCompleted(String wallet, Pair<Double, Double> value)
    {
        runOnUiThread(() -> adapter.completeWalletSync(wallet, value));
    }

    @Override
    public void syncStarted(String wallet, Pair<Double, Double> value)
    {
        runOnUiThread(() -> adapter.setUnsyncedWalletValue(wallet, value));
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        if (reloadRequired)
        {
            walletChanged(lastActiveWallet);
        }

        if (disposable != null && !disposable.isDisposed())
            disposable.dispose();

        if (adapter != null) adapter.onDestroy();
        if (viewModel != null) viewModel.onDestroy();
    }

    private void backPressed()
    {
        preFinish();
        // User can't start work without wallet.
        if (adapter.getItemCount() == 0)
        {
            System.exit(0);
        }
    }

    @Override
    public void handleBackPressed()
    {
        backPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        if (CustomViewSettings.canChangeWallets()) getMenuInflater().inflate(R.menu.menu_add, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == R.id.action_add)
        {
            onAddWallet();
        }
        else if (item.getItemId() == android.R.id.home)
        {
            backPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        initViewModel();

        if (requestCode >= SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS && requestCode <= SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS + 10)
        {
            Operation taskCode = Operation.values()[requestCode - SignTransactionDialog.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS];
            if (resultCode == RESULT_OK)
            {
                viewModel.completeAuthentication(taskCode);
                // Discovery will be started via LiveData observer in onDiscoveryAuthComplete
            }
            else
            {
                viewModel.failedAuthentication(taskCode);
                
                // If discovery authentication failed, clean up
                if (taskCode == Operation.DISCOVER_ACCOUNTS)
                {
                    pendingDiscoveryWallet = null;
                    Toast.makeText(this, R.string.authentication_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
        else if (requestCode == C.IMPORT_REQUEST_CODE)
        {
            showToolbar();
            if (resultCode == RESULT_OK)
            {
                Snackbar.make(systemView, getString(R.string.toast_message_wallet_imported), Snackbar.LENGTH_SHORT)
                        .show();

                Wallet importedWallet = data.getParcelableExtra(C.Key.WALLET);
                if (importedWallet != null)
                {
                    //switch to this wallet
                    viewModel.setNewWallet(importedWallet);
                    
                    // If it's an HD wallet (seed phrase import), offer to discover existing accounts
                    if (importedWallet.type == WalletType.HDKEY)
                    {
                        showDiscoverAccountsDialog(importedWallet);
                    }
                }
            }
        }
    }
    
    private void showDiscoverAccountsDialog(Wallet masterWallet)
    {
        // Show dialog asking if user wants to discover existing accounts
        aDialog = new AWalletAlertDialog(this);
        aDialog.setTitle(R.string.discover_accounts_title);
        aDialog.setMessage(getString(R.string.discover_accounts_message));
        aDialog.setIcon(AWalletAlertDialog.NONE);
        aDialog.setButtonText(R.string.discover_accounts);
        aDialog.setButtonListener(v -> {
            aDialog.dismiss();
            startAccountDiscovery(masterWallet);
        });
        aDialog.setSecondaryButtonText(R.string.skip_discovery);
        aDialog.setSecondaryButtonListener(v -> aDialog.dismiss());
        aDialog.show();
    }
    
    private void startAccountDiscovery(Wallet masterWallet)
    {
        // Store for potential authentication retry
        pendingDiscoveryWallet = masterWallet;
        
        // Request authentication FIRST before showing progress dialog
        // The discovery will start after authentication succeeds
        viewModel.requestAuthentication(this, masterWallet, Operation.DISCOVER_ACCOUNTS);
    }
    
    /**
     * Called when discovery authentication completes via LiveData (handles both biometric and device credential)
     */
    private void onDiscoveryAuthComplete(Wallet wallet)
    {
        if (wallet != null)
        {
            performAccountDiscovery(wallet);
        }
    }
    
    /**
     * Actually start the discovery process after authentication succeeds
     */
    private void performAccountDiscovery(Wallet masterWallet)
    {
        // Create progress dialog
        discoveryProgressDialog = createProgressDialog();
        discoveryProgressDialog.show();
        
        // Keep track of found accounts for real-time update
        final java.util.List<Integer> foundIndices = new java.util.ArrayList<>();
        
        // Start discovering accounts
        viewModel.discoverDerivedAccounts(masterWallet, new WalletsViewModel.AccountDiscoveryCallback() {
            @Override
            public void onProgress(int current, int total, String currentAddress, int foundCount, String networkName) {
                runOnUiThread(() -> {
                    if (discoveryProgressDialog != null && discoveryProgressDialog.isShowing()) {
                        updateProgressDialog(discoveryProgressDialog, current, total, currentAddress, foundCount, networkName);
                    }
                });
            }
            
            @Override
            public void onAccountsDiscovered(java.util.List<Integer> activeIndices) {
                runOnUiThread(() -> {
                    if (discoveryProgressDialog != null) {
                        discoveryProgressDialog.dismiss();
                        discoveryProgressDialog = null;
                    }
                    pendingDiscoveryWallet = null;
                    
                    // Filter out indices that are already imported
                    Wallet[] existingWallets = viewModel.wallets().getValue();
                    java.util.List<Integer> newIndices = new java.util.ArrayList<>();
                    
                    if (existingWallets != null) {
                        for (int index : activeIndices) {
                            // Skip index 0 (master wallet)
                            if (index == 0) continue;
                            
                            boolean alreadyExists = false;
                            for (Wallet w : existingWallets) {
                                if (w.type == WalletType.HDKEY && 
                                    w.parentAddress != null && 
                                    w.parentAddress.equalsIgnoreCase(masterWallet.address) &&
                                    w.hdKeyIndex == index) {
                                    alreadyExists = true;
                                    break;
                                }
                            }
                            if (!alreadyExists) {
                                newIndices.add(index);
                            }
                        }
                    }
                    
                    if (newIndices.isEmpty()) {
                        // Check if there were any active indices found at all
                        if (activeIndices != null && !activeIndices.isEmpty()) {
                            aDialog = new AWalletAlertDialog(WalletsActivity.this);
                            aDialog.setTitle(R.string.discover_all_accounts);
                            aDialog.setMessage(getString(R.string.all_accounts_already_imported, activeIndices.size()));
                            aDialog.setIcon(AWalletAlertDialog.SUCCESS);
                            aDialog.setButtonText(R.string.dialog_ok);
                            aDialog.setButtonListener(v -> aDialog.dismiss());
                            aDialog.show();
                        } else {
                            aDialog = new AWalletAlertDialog(WalletsActivity.this);
                            aDialog.setTitle(R.string.discover_all_accounts);
                            aDialog.setMessage(getString(R.string.no_accounts_found) + "\n\n" + getString(R.string.discovery_explanation));
                            aDialog.setIcon(AWalletAlertDialog.NONE);
                            aDialog.setButtonText(R.string.dialog_ok);
                            aDialog.setButtonListener(v -> aDialog.dismiss());
                            aDialog.show();
                        }
                    } else {
                        showDiscoveredAccountsDialog(masterWallet, newIndices);
                    }
                });
            }
            
            @Override
            public void onDiscoveryFailed(String error) {
                runOnUiThread(() -> {
                    if (discoveryProgressDialog != null) {
                        discoveryProgressDialog.dismiss();
                        discoveryProgressDialog = null;
                    }
                    pendingDiscoveryWallet = null;
                    Toast.makeText(WalletsActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
            
            @Override
            public void onAuthenticationRequired() {
                // This shouldn't be called since we authenticate before starting
                runOnUiThread(() -> {
                    if (discoveryProgressDialog != null) {
                        discoveryProgressDialog.dismiss();
                        discoveryProgressDialog = null;
                    }
                    Toast.makeText(WalletsActivity.this, R.string.authentication_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private android.app.AlertDialog createProgressDialog()
    {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_account_discovery_progress, null);
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        
        android.app.AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        return dialog;
    }
    
    private void updateProgressDialog(android.app.AlertDialog dialog, int current, int total, String address, int foundCount, String networkName)
    {
        if (dialog != null && dialog.isShowing()) {
            View dialogView = dialog.findViewById(android.R.id.content);
            if (dialogView != null) {
                ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar);
                TextView progressText = dialogView.findViewById(R.id.progress_text);
                TextView percentageText = dialogView.findViewById(R.id.percentage_text);
                TextView currentAddress = dialogView.findViewById(R.id.current_address);
                TextView foundCountText = dialogView.findViewById(R.id.found_count);
                TextView networkText = dialogView.findViewById(R.id.network_text);
                
                int percentage = (int) ((current / (float) total) * 100);
                
                if (progressBar != null) {
                    progressBar.setProgress(percentage);
                }
                
                if (progressText != null) {
                    progressText.setText(getString(R.string.scanning_account, current, total));
                }
                
                if (percentageText != null) {
                    percentageText.setText(percentage + "%");
                }
                
                if (currentAddress != null && address != null) {
                    currentAddress.setText(address.substring(0, 10) + "..." + address.substring(address.length() - 8));
                }
                
                if (networkText != null && networkName != null && !networkName.isEmpty()) {
                    networkText.setVisibility(View.VISIBLE);
                    networkText.setText(getString(R.string.checking_on_network, networkName));
                }
                
                if (foundCountText != null && foundCount > 0) {
                    foundCountText.setVisibility(View.VISIBLE);
                    foundCountText.setText(getString(R.string.accounts_found_so_far, foundCount));
                }
            }
        }
    }
    
    private void showDiscoveredAccountsDialog(Wallet masterWallet, java.util.List<Integer> activeIndices)
    {
        aDialog = new AWalletAlertDialog(this);
        aDialog.setTitle(getString(R.string.accounts_found, activeIndices.size()));
        
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.discovered_accounts_message)).append("\n\n");
        for (int index : activeIndices) {
            // Display as Account 2, Account 3, etc. (since Account 1 is the Master Wallet at index 0)
            sb.append("• Account ").append(index + 1).append(" (Derivation path index ").append(index).append(")\n");
        }
        aDialog.setMessage(sb.toString());
        aDialog.setIcon(AWalletAlertDialog.NONE);
        aDialog.setButtonText(getString(R.string.add_all_accounts, activeIndices.size()));
        aDialog.setButtonListener(v -> {
            aDialog.dismiss();
            addAllDiscoveredAccounts(masterWallet, activeIndices);
        });
        aDialog.setSecondaryButtonText(R.string.add_manually);
        aDialog.setSecondaryButtonListener(v -> aDialog.dismiss());
        aDialog.show();
    }
    
    private void addAllDiscoveredAccounts(Wallet masterWallet, java.util.List<Integer> activeIndices)
    {
        // Show progress
        systemView.showProgress(true);
        
        if (activeIndices.isEmpty()) {
            systemView.showProgress(false);
            return;
        }
        
        // Add accounts one by one (activeIndices already filtered to exclude existing ones)
        addDiscoveredAccountsSequentially(masterWallet, activeIndices, 0);
    }
    
    private void addDiscoveredAccountsSequentially(Wallet masterWallet, java.util.List<Integer> indices, int currentPosition)
    {
        if (currentPosition >= indices.size()) {
            // All accounts added
            runOnUiThread(() -> {
                systemView.showProgress(false);
                Toast.makeText(this, getString(R.string.all_accounts_added, indices.size()), Toast.LENGTH_SHORT).show();
                viewModel.fetchWallets();
            });
            return;
        }
        
        int index = indices.get(currentPosition);
        isDerivingAccount = true;
        parentWalletAddress = masterWallet.address;
        derivedAccountIndex = index;
        
        viewModel.addHDAccountFromMasterAtIndex(this, masterWallet, index, new CreateWalletCallbackInterface() {
            @Override
            public void HDKeyCreated(String address, Context ctx, KeyService.AuthenticationLevel level) {
                // Store the account
                viewModel.storeDerivedHDAccount(address, level, parentWalletAddress, derivedAccountIndex);
                // Continue with next account
                addDiscoveredAccountsSequentially(masterWallet, indices, currentPosition + 1);
            }
            
            @Override
            public void keyFailure(String message) {
                // Continue with next account even if this one fails
                addDiscoveredAccountsSequentially(masterWallet, indices, currentPosition + 1);
            }
            
            @Override
            public void cancelAuthentication() {
                runOnUiThread(() -> {
                    systemView.showProgress(false);
                });
            }
            
            @Override
            public void fetchMnemonic(String mnemonic) {}
        });
    }

    @Override
    public void onClick(View view)
    {
        if (view.getId() == R.id.try_again)
        {
            viewModel.fetchWallets();
        }
    }

    @Override
    public void onNewWallet(View view)
    {
        hideDialog();
        
        // Check network connectivity first
        if (!Utils.isNetworkAvailable(this))
        {
            Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_LONG).show();
            return;
        }
        
        Toast.makeText(this, "Creating new wallet...", Toast.LENGTH_SHORT).show();
        systemView.showProgress(true);
        viewModel.newWallet(this, this);
    }

    @Override
    public void onWatchWallet(View view)
    {
        hideDialog();
        viewModel.watchWallet(this);
    }

    @Override
    public void onImportWallet(View view)
    {
        hideDialog();
        
        // Check network connectivity first
        if (!Utils.isNetworkAvailable(this))
        {
            Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_LONG).show();
            return;
        }
        
        viewModel.importWallet(this);
    }

    @Override
    public void onClose(View view)
    {
        hideDialog();
    }

    @Override
    public void onAddAccount(View view)
    {
        try {
            hideDialog();
            
            // Check network connectivity first
            if (!Utils.isNetworkAvailable(this))
            {
                Toast.makeText(this, R.string.no_internet_connection, Toast.LENGTH_LONG).show();
                return;
            }
            
            Wallet[] currentWallets = viewModel.wallets().getValue();
            if (currentWallets == null || currentWallets.length == 0)
            {
                Toast.makeText(this, R.string.no_hd_wallet_found, Toast.LENGTH_LONG).show();
                return;
            }
            
            List<Wallet> masterWallets = viewModel.findAllMasterHDWallets(currentWallets);
            
            if (masterWallets == null || masterWallets.isEmpty())
            {
                Toast.makeText(this, R.string.no_hd_wallet_found, Toast.LENGTH_LONG).show();
                return;
            }
            
            // If only one master wallet, show options dialog
            if (masterWallets.size() == 1)
            {
                showSingleMasterWalletOptionsDialog(masterWallets.get(0));
            }
            else
            {
                // Multiple master wallets - show selection dialog
                showMasterWalletSelectionDialog(masterWallets);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showSingleMasterWalletOptionsDialog(Wallet masterWallet)
    {
        try {
            if (masterWallet == null) {
                Toast.makeText(this, "Error: Invalid wallet", Toast.LENGTH_SHORT).show();
                return;
            }
            
            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View sheetView = getLayoutInflater().inflate(R.layout.dialog_single_master_wallet_options, null);
            bottomSheet.setContentView(sheetView);
        
        // Expand the bottom sheet
        bottomSheet.setOnShowListener(dialog -> {
            BottomSheetDialog d = (BottomSheetDialog) dialog;
            View bottomSheetInternal = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheetInternal != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheetInternal)
                    .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        
        // Close button
        ImageView closeButton = sheetView.findViewById(R.id.close_action);
        closeButton.setOnClickListener(v -> bottomSheet.dismiss());
        
        // Master wallet info
        TextView walletName = sheetView.findViewById(R.id.wallet_name);
        TextView walletAddress = sheetView.findViewById(R.id.wallet_address);
        walletName.setText(masterWallet.name != null && !masterWallet.name.isEmpty() ? 
            masterWallet.name : getString(R.string.master_wallet));
        walletAddress.setText(Keys.toChecksumAddress(masterWallet.address).substring(0, 10) + "..." + 
            masterWallet.address.substring(masterWallet.address.length() - 4));
        
        // Get existing derived accounts count
        Wallet[] allWallets = viewModel.wallets().getValue();
        int derivedCount = 0;
        if (allWallets != null) {
            for (Wallet w : allWallets) {
                if (w.type == WalletType.HDKEY && 
                    w.parentAddress != null && 
                    w.parentAddress.equalsIgnoreCase(masterWallet.address)) {
                    derivedCount++;
                }
            }
        }
        
        // Show existing accounts count
        TextView existingAccountsText = sheetView.findViewById(R.id.existing_accounts_info);
        int nextIndex = viewModel.getNextHDKeyIndexForMaster(allWallets, masterWallet.address);
        existingAccountsText.setText(getString(R.string.derived_accounts_count, derivedCount, nextIndex));
        
        // Option 1: Add next account
        View addNextAccount = sheetView.findViewById(R.id.add_next_account);
        addNextAccount.setOnClickListener(v -> {
            bottomSheet.dismiss();
            deriveAccountFromMaster(masterWallet);
        });
        
        // Option 2: Discover all accounts with activity
        View discoverAccounts = sheetView.findViewById(R.id.discover_accounts);
        discoverAccounts.setOnClickListener(v -> {
            bottomSheet.dismiss();
            startAccountDiscovery(masterWallet);
        });
        
        // Option 3: Recover missing account
        View recoverMissing = sheetView.findViewById(R.id.recover_missing_account);
        recoverMissing.setOnClickListener(v -> {
            bottomSheet.dismiss();
            List<Wallet> singleMasterList = new java.util.ArrayList<>();
            singleMasterList.add(masterWallet);
            showRecoverMissingAccountDialog(singleMasterList);
        });
        
        bottomSheet.show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error showing dialog: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showMasterWalletSelectionDialog(List<Wallet> masterWallets)
    {
        try {
            if (masterWallets == null || masterWallets.isEmpty()) {
                Toast.makeText(this, "Error: No master wallets found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View sheetView = getLayoutInflater().inflate(R.layout.dialog_select_master_wallet, null);
            bottomSheet.setContentView(sheetView);
        
        // Expand the bottom sheet to show all content
        bottomSheet.setOnShowListener(dialog -> {
            BottomSheetDialog d = (BottomSheetDialog) dialog;
            View bottomSheetInternal = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheetInternal != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheetInternal)
                    .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        
        // Set up close button
        ImageView closeButton = sheetView.findViewById(R.id.close_action);
        closeButton.setOnClickListener(v -> bottomSheet.dismiss());
        
        // Set up RecyclerView with wallet list
        RecyclerView walletList = sheetView.findViewById(R.id.wallet_list);
        walletList.setLayoutManager(new LinearLayoutManager(this));
        
        // Create adapter for master wallets
        MasterWalletSelectAdapter walletAdapter = new MasterWalletSelectAdapter(
            masterWallets, 
            viewModel.wallets().getValue(),
            wallet -> {
                bottomSheet.dismiss();
                deriveAccountFromMaster(wallet);
            }
        );
        walletList.setAdapter(walletAdapter);
        
        // Set up add bulk wallets option
        View addBulkWallets = sheetView.findViewById(R.id.add_bulk_wallets);
        if (addBulkWallets != null) {
            addBulkWallets.setOnClickListener(v -> {
                bottomSheet.dismiss();
                showBulkAddWalletDialog(masterWallets);
            });
        }
        
        // Set up recover missing account option
        View recoverMissingContainer = sheetView.findViewById(R.id.recover_missing_container);
        View recoverMissingAccount = sheetView.findViewById(R.id.recover_missing_account);
        
        // Only show recover option if there are HD wallets
        if (!masterWallets.isEmpty())
        {
            recoverMissingContainer.setVisibility(View.VISIBLE);
            recoverMissingAccount.setOnClickListener(v -> {
                bottomSheet.dismiss();
                showRecoverMissingAccountDialog(masterWallets);
            });
        }
        else
        {
            recoverMissingContainer.setVisibility(View.GONE);
        }
        
        bottomSheet.setCancelable(true);
        bottomSheet.setCanceledOnTouchOutside(true);
        bottomSheet.show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error showing wallet selection: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showBulkAddWalletDialog(List<Wallet> masterWallets)
    {
        try {
            if (masterWallets == null || masterWallets.isEmpty()) {
                Toast.makeText(this, "No master wallets found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // If only one master wallet, go directly to count input
            if (masterWallets.size() == 1) {
                showBulkAddCountDialog(masterWallets.get(0));
                return;
            }
            
            // Multiple master wallets - show selection first
            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View sheetView = getLayoutInflater().inflate(R.layout.dialog_select_wallet_discover, null);
            bottomSheet.setContentView(sheetView);
            
            // Update title
            TextView title = sheetView.findViewById(R.id.title);
            if (title != null) {
                title.setText(R.string.add_bulk_wallets);
            }
            
            ImageView backButton = sheetView.findViewById(R.id.back_action);
            backButton.setOnClickListener(v -> {
                bottomSheet.dismiss();
                showMasterWalletSelectionDialog(masterWallets);
            });
            
            RecyclerView walletList = sheetView.findViewById(R.id.wallet_list);
            walletList.setLayoutManager(new LinearLayoutManager(this));
            
            MasterWalletSelectAdapter adapter = new MasterWalletSelectAdapter(
                masterWallets,
                viewModel.wallets().getValue(),
                wallet -> {
                    bottomSheet.dismiss();
                    showBulkAddCountDialog(wallet);
                }
            );
            walletList.setAdapter(adapter);
            
            bottomSheet.show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showBulkAddCountDialog(Wallet masterWallet)
    {
        // Get the next available index for this master wallet
        Wallet[] allWallets = viewModel.wallets().getValue();
        int nextIndex = viewModel.getNextHDKeyIndexForMaster(allWallets, masterWallet.address);
        
        // Create dialog with input field
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_bulk_add_wallets, null);
        builder.setView(dialogView);
        
        TextView messageText = dialogView.findViewById(R.id.message_text);
        android.widget.EditText countInput = dialogView.findViewById(R.id.count_input);
        TextView walletNameText = dialogView.findViewById(R.id.wallet_name);
        
        // Set wallet name
        String walletName = masterWallet.name != null && !masterWallet.name.isEmpty() 
            ? masterWallet.name 
            : getString(R.string.master_wallet) + " 1";
        walletNameText.setText(walletName);
        
        // Set message with next index info
        messageText.setText(getString(R.string.bulk_add_message, nextIndex));
        
        // Set default value
        countInput.setText("5");
        countInput.setSelection(countInput.getText().length());
        
        android.app.AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        // Setup buttons
        android.widget.Button addButton = dialogView.findViewById(R.id.add_button);
        android.widget.Button cancelButton = dialogView.findViewById(R.id.cancel_button);
        
        // Update button text based on input
        addButton.setText(getString(R.string.add_wallets, 5));
        countInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    int count = Integer.parseInt(s.toString());
                    if (count > 0 && count <= 50) {
                        addButton.setText(getString(R.string.add_wallets, count));
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        });
        
        addButton.setOnClickListener(v -> {
            String countStr = countInput.getText().toString().trim();
            if (countStr.isEmpty()) {
                Toast.makeText(this, "Please enter a number", Toast.LENGTH_SHORT).show();
                return;
            }
            
            int count;
            try {
                count = Integer.parseInt(countStr);
                if (count < 1 || count > 50) {
                    Toast.makeText(this, "Please enter a number between 1 and 50", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show();
                return;
            }
            
            dialog.dismiss();
            startBulkAddWallets(masterWallet, nextIndex, count);
        });
        
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
    }
    
    private void startBulkAddWallets(Wallet masterWallet, int startIndex, int count)
    {
        // Show progress
        aDialog = new AWalletAlertDialog(this);
        aDialog.setTitle(R.string.add_bulk_wallets);
        aDialog.setMessage(getString(R.string.adding_wallets_progress, 1, count));
        aDialog.setProgressMode();
        aDialog.setCancelable(false);
        aDialog.show();
        
        // Create list of indices to add
        java.util.List<Integer> indicesToAdd = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            indicesToAdd.add(startIndex + i);
        }
        
        // Start adding wallets sequentially
        addBulkWalletsSequentially(masterWallet, indicesToAdd, 0, count, 0);
    }
    
    private void addBulkWalletsSequentially(Wallet masterWallet, java.util.List<Integer> indices, int currentPosition, int totalCount, int successCount)
    {
        if (currentPosition >= indices.size()) {
            // All wallets added
            runOnUiThread(() -> {
                if (aDialog != null) {
                    aDialog.dismiss();
                }
                Toast.makeText(this, getString(R.string.wallets_added_success, successCount), Toast.LENGTH_SHORT).show();
                viewModel.fetchWallets();
            });
            return;
        }
        
        int index = indices.get(currentPosition);
        final int position = currentPosition;
        
        // Update progress dialog with detailed status
        runOnUiThread(() -> {
            if (aDialog != null) {
                aDialog.setMessage("Adding wallet index " + index + "... (" + (position + 1) + "/" + totalCount + ")");
            }
        });
        
        android.util.Log.d("WalletsActivity", "Adding wallet index " + index + "...");
        
        // Set up for derived account
        isDerivingAccount = true;
        parentWalletAddress = masterWallet.address;
        derivedAccountIndex = index;
        
        // Derive the account - use callback to continue to next
        viewModel.addHDAccountFromMasterAtIndex(this, masterWallet, index, new CreateWalletCallbackInterface() {
            @Override
            public void HDKeyCreated(String address, android.content.Context ctx, KeyService.AuthenticationLevel level) {
                android.util.Log.d("WalletsActivity", "Wallet index " + index + " derived: " + address + ", storing...");
                
                // Update progress to show storing
                runOnUiThread(() -> {
                    if (aDialog != null) {
                        aDialog.setMessage("Saving wallet index " + index + "... (" + (position + 1) + "/" + totalCount + ")");
                    }
                });
                
                // Store the derived account with callback to wait for completion
                viewModel.storeDerivedHDAccountWithCallback(address, level, masterWallet.address, index, 
                    new WalletsViewModel.WalletStoreCallback() {
                        @Override
                        public void onWalletStored(Wallet wallet) {
                            android.util.Log.d("WalletsActivity", "Wallet index " + index + " saved successfully: " + wallet.address);
                            
                            runOnUiThread(() -> {
                                Toast.makeText(WalletsActivity.this, 
                                    "Wallet " + (position + 1) + " saved successfully", Toast.LENGTH_SHORT).show();
                            });
                            
                            // Continue to next wallet after successful save
                            addBulkWalletsSequentially(masterWallet, indices, currentPosition + 1, totalCount, successCount + 1);
                        }
                        
                        @Override
                        public void onStoreFailed(String error) {
                            android.util.Log.e("WalletsActivity", "Failed to save wallet index " + index + ": " + error);
                            
                            runOnUiThread(() -> {
                                Toast.makeText(WalletsActivity.this, 
                                    "Failed to save wallet " + (position + 1) + ": " + error, Toast.LENGTH_SHORT).show();
                            });
                            
                            // Continue to next wallet even if this one fails
                            addBulkWalletsSequentially(masterWallet, indices, currentPosition + 1, totalCount, successCount);
                        }
                    });
            }
            
            @Override
            public void keyFailure(String message) {
                android.util.Log.e("WalletsActivity", "Failed to derive wallet index " + index + ": " + message);
                runOnUiThread(() -> {
                    if (aDialog != null) {
                        aDialog.dismiss();
                    }
                    Toast.makeText(WalletsActivity.this, "Error adding wallet at index " + index + ": " + message, Toast.LENGTH_SHORT).show();
                    // Still refresh to show any wallets that were added
                    viewModel.fetchWallets();
                });
            }
            
            @Override
            public void cancelAuthentication() {
                android.util.Log.d("WalletsActivity", "Authentication cancelled at wallet index " + index);
                runOnUiThread(() -> {
                    if (aDialog != null) {
                        aDialog.dismiss();
                    }
                    if (successCount > 0) {
                        Toast.makeText(WalletsActivity.this, 
                            getString(R.string.wallets_added_success, successCount), Toast.LENGTH_SHORT).show();
                    }
                    viewModel.fetchWallets();
                });
            }
            
            @Override
            public void fetchMnemonic(String mnemonic) {}
            
            @Override
            public void keyUpgraded(KeyService.UpgradeKeyResult result) {}
        });
    }
    
    private void showMultiWalletDiscoveryDialog(List<Wallet> masterWallets)
    {
        try {
            if (masterWallets == null || masterWallets.isEmpty()) {
                Toast.makeText(this, "Error: No master wallets found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // If only one wallet, start discovery directly
            if (masterWallets.size() == 1) {
                startAccountDiscovery(masterWallets.get(0));
                return;
            }
            
            // Multiple wallets - show selection dialog
            aDialog = new AWalletAlertDialog(this);
            aDialog.setTitle(R.string.discover_all_accounts);
            
            // Build message with wallet list
            StringBuilder message = new StringBuilder();
            message.append(getString(R.string.select_wallet_to_discover)).append("\\n\\n");
            for (int i = 0; i < masterWallets.size(); i++) {
                Wallet wallet = masterWallets.get(i);
                String name = wallet.name != null && !wallet.name.isEmpty() 
                    ? wallet.name 
                    : getString(R.string.wallet_default_name) + " " + (i + 1);
                message.append((i + 1)).append(". ").append(name).append("\\n");
            }
            message.append("\\n").append(getString(R.string.tap_wallet_to_discover));
            
            aDialog.setMessage(message.toString());
            aDialog.setIcon(AWalletAlertDialog.NONE);
            
            // Show list of wallets as buttons
            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View sheetView = getLayoutInflater().inflate(R.layout.dialog_select_wallet_discover, null);
            bottomSheet.setContentView(sheetView);
            
            ImageView backButton = sheetView.findViewById(R.id.back_action);
            backButton.setOnClickListener(v -> {
                bottomSheet.dismiss();
                showMasterWalletSelectionDialog(masterWallets);
            });
            
            RecyclerView walletList = sheetView.findViewById(R.id.wallet_list);
            walletList.setLayoutManager(new LinearLayoutManager(this));
            
            MasterWalletSelectAdapter adapter = new MasterWalletSelectAdapter(
                masterWallets,
                viewModel.wallets().getValue(),
                wallet -> {
                    bottomSheet.dismiss();
                    startAccountDiscovery(wallet);
                }
            );
            walletList.setAdapter(adapter);
            
            bottomSheet.show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error showing discovery dialog: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showRecoverMissingAccountDialog(List<Wallet> masterWallets)
    {
        try {
            if (masterWallets == null || masterWallets.isEmpty()) {
                Toast.makeText(this, "Error: No master wallets found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View sheetView = getLayoutInflater().inflate(R.layout.dialog_recover_missing_account, null);
            bottomSheet.setContentView(sheetView);
        
        // Set up back button
        ImageView backButton = sheetView.findViewById(R.id.back_action);
        backButton.setOnClickListener(v -> {
            bottomSheet.dismiss();
            showMasterWalletSelectionDialog(masterWallets);
        });
        
        // Set up master wallet spinner
        android.widget.Spinner masterWalletSpinner = sheetView.findViewById(R.id.master_wallet_spinner);
        String[] walletNames = new String[masterWallets.size()];
        for (int i = 0; i < masterWallets.size(); i++)
        {
            Wallet wallet = masterWallets.get(i);
            String name = wallet.name != null && !wallet.name.isEmpty() 
                ? wallet.name 
                : getString(R.string.wallet_default_name) + " " + (i + 1);
            String addressPreview = wallet.address.substring(0, 6) + "..." + wallet.address.substring(wallet.address.length() - 4);
            walletNames[i] = name + " (" + addressPreview + ")";
        }
        android.widget.ArrayAdapter<String> spinnerAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, walletNames);
        masterWalletSpinner.setAdapter(spinnerAdapter);
        
        // Index input and info
        android.widget.EditText indexInput = sheetView.findViewById(R.id.index_input);
        TextView existingAccountsInfo = sheetView.findViewById(R.id.existing_accounts_info);
        
        // Update existing accounts info when master wallet changes
        masterWalletSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Wallet selectedMaster = masterWallets.get(position);
                Wallet[] allWallets = viewModel.wallets().getValue();
                
                // Get existing indices for this master
                java.util.List<Integer> existingIndices = new java.util.ArrayList<>();
                if (allWallets != null) {
                    for (Wallet w : allWallets) {
                        if (w.type == WalletType.HDKEY) {
                            if (w.address.equalsIgnoreCase(selectedMaster.address)) {
                                existingIndices.add(w.hdKeyIndex);
                            } else if (w.parentAddress != null && w.parentAddress.equalsIgnoreCase(selectedMaster.address)) {
                                existingIndices.add(w.hdKeyIndex);
                            }
                        }
                    }
                }
                
                // Calculate missing indices (0-9 range)
                java.util.List<Integer> missingIndices = new java.util.ArrayList<>();
                for (int i = 0; i <= 9; i++) {
                    if (!existingIndices.contains(i)) {
                        missingIndices.add(i);
                    }
                }
                
                if (!missingIndices.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < missingIndices.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(missingIndices.get(i));
                    }
                    existingAccountsInfo.setText(getString(R.string.missing_accounts_hint, sb.toString()));
                    existingAccountsInfo.setTextColor(getResources().getColor(R.color.brand, getTheme()));
                    existingAccountsInfo.setVisibility(View.VISIBLE);
                    
                    // Set click listener to auto-fill first missing index
                    final int firstMissing = missingIndices.get(0);
                    existingAccountsInfo.setOnClickListener(v2 -> {
                        indexInput.setText(String.valueOf(firstMissing));
                    });
                } else {
                    existingAccountsInfo.setText(R.string.no_missing_accounts);
                    existingAccountsInfo.setTextColor(getResources().getColor(android.R.color.darker_gray, getTheme()));
                    existingAccountsInfo.setVisibility(View.VISIBLE);
                    existingAccountsInfo.setOnClickListener(null);
                }
            }
            
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        
        // Recover button
        View recoverButton = sheetView.findViewById(R.id.recover_button);
        recoverButton.setOnClickListener(v -> {
            String indexStr = indexInput.getText().toString().trim();
            if (indexStr.isEmpty()) {
                Toast.makeText(this, R.string.invalid_index, Toast.LENGTH_SHORT).show();
                return;
            }
            
            int selectedIndex;
            try {
                selectedIndex = Integer.parseInt(indexStr);
                if (selectedIndex < 0) {
                    Toast.makeText(this, R.string.invalid_index, Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, R.string.invalid_index, Toast.LENGTH_SHORT).show();
                return;
            }
            
            Wallet selectedMaster = masterWallets.get(masterWalletSpinner.getSelectedItemPosition());
            Wallet[] allWallets = viewModel.wallets().getValue();
            
            // Check if index already exists
            if (allWallets != null) {
                for (Wallet w : allWallets) {
                    if (w.type == WalletType.HDKEY) {
                        boolean belongsToMaster = w.address.equalsIgnoreCase(selectedMaster.address) ||
                            (w.parentAddress != null && w.parentAddress.equalsIgnoreCase(selectedMaster.address));
                        if (belongsToMaster && w.hdKeyIndex == selectedIndex) {
                            Toast.makeText(this, R.string.index_already_exists, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                }
            }
            
            bottomSheet.dismiss();
            deriveAccountFromMasterAtIndex(selectedMaster, selectedIndex);
        });
        
        bottomSheet.setCancelable(true);
        bottomSheet.setCanceledOnTouchOutside(true);
        bottomSheet.show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error showing recover dialog: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void deriveAccountFromMasterAtIndex(Wallet masterWallet, int index)
    {
        // Show loading indicator
        systemView.showProgress(true);
        
        // Set up for derived account
        isDerivingAccount = true;
        parentWalletAddress = masterWallet.address;
        derivedAccountIndex = index;
        
        // Derive account at specific index
        viewModel.addHDAccountFromMasterAtIndex(this, masterWallet, index, this);
    }
    
    /**
     * Adapter for displaying master wallets in the selection dialog
     */
    private class MasterWalletSelectAdapter extends RecyclerView.Adapter<MasterWalletSelectAdapter.ViewHolder>
    {
        private final List<Wallet> masterWallets;
        private final Wallet[] allWallets;
        private final OnWalletSelectedListener listener;
        
        interface OnWalletSelectedListener {
            void onWalletSelected(Wallet wallet);
        }
        
        MasterWalletSelectAdapter(List<Wallet> masterWallets, Wallet[] allWallets, OnWalletSelectedListener listener)
        {
            this.masterWallets = masterWallets;
            this.allWallets = allWallets;
            this.listener = listener;
        }
        
        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType)
        {
            View view = getLayoutInflater().inflate(R.layout.item_select_master_wallet, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position)
        {
            Wallet wallet = masterWallets.get(position);
            
            // Set wallet name - prioritize actual names, then use Master Wallet numbering
            String walletName;
            if (wallet.name != null && !wallet.name.isEmpty() && 
                !wallet.name.equals(wallet.getHDAccountLabel())) {
                // User has set a custom name
                walletName = wallet.name;
            } else {
                // Use "Master Wallet 1", "Master Wallet 2", etc.
                walletName = getString(R.string.master_wallet) + " " + (position + 1);
            }
            holder.walletName.setText(walletName);
            
            // Set wallet address preview
            String addressPreview = wallet.address.substring(0, 8) + "..." + wallet.address.substring(wallet.address.length() - 6);
            holder.walletAddress.setText(addressPreview);
            
            // Set derived accounts count
            int derivedCount = viewModel.countHDAccountsForMaster(allWallets, wallet.address) - 1; // -1 to exclude master
            if (derivedCount > 0)
            {
                holder.derivedCount.setVisibility(View.VISIBLE);
                int nextIndex = viewModel.getNextHDKeyIndexForMaster(allWallets, wallet.address);
                holder.derivedCount.setText(getString(R.string.derived_accounts_count, derivedCount, nextIndex));
            }
            else
            {
                holder.derivedCount.setVisibility(View.GONE);
            }
            
            // Set wallet icon
            holder.walletIcon.bind(wallet);
            
            // Set click listener
            holder.itemView.setOnClickListener(v -> listener.onWalletSelected(wallet));
        }
        
        @Override
        public int getItemCount()
        {
            return masterWallets.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder
        {
            com.ramapay.app.widget.UserAvatar walletIcon;
            TextView walletName;
            TextView walletAddress;
            TextView derivedCount;
            
            ViewHolder(View itemView)
            {
                super(itemView);
                walletIcon = itemView.findViewById(R.id.wallet_icon);
                walletName = itemView.findViewById(R.id.wallet_name);
                walletAddress = itemView.findViewById(R.id.wallet_address);
                derivedCount = itemView.findViewById(R.id.derived_count);
            }
        }
    }

    private void deriveAccountFromMaster(Wallet masterWallet)
    {
        // Show loading indicator
        systemView.showProgress(true);
        
        // Set up for derived account
        Wallet[] currentWallets = viewModel.wallets().getValue();
        isDerivingAccount = true;
        parentWalletAddress = masterWallet.address;
        derivedAccountIndex = viewModel.getNextHDKeyIndexForMaster(currentWallets, masterWallet.address);
        
        // Add a new account derived from the specified master HD wallet
        viewModel.addHDAccountFromMaster(this, masterWallet, this);
    }

    private void onAddWallet()
    {
        AddWalletView addWalletView = new AddWalletView(this);
        addWalletView.setOnNewWalletClickListener(this);
        addWalletView.setOnImportWalletClickListener(this);
        addWalletView.setOnWatchWalletClickListener(this);
        addWalletView.setOnHardwareCardClickListener(this);
        addWalletView.setOnAddAccountClickListener(this);
        addWalletView.setHardwareActive(hardwareCard.isStub());
        
        // Show "Add Account" option if user has an HD wallet
        Wallet[] currentWallets = viewModel.wallets().getValue();
        addWalletView.setHasHDWallet(viewModel.hasHDWallet(currentWallets));
        
        dialog = new BottomSheetDialog(this);
        dialog.setContentView(addWalletView);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /**
     * Called once at Activity startup
     * @param wallet
     */
    private void setupWallet(Wallet wallet)
    {
        if (adapter != null)
        {
            adapter.setDefaultWallet(wallet);
            scrollToDefaultWallet();
        }
    }

    /**
     * Called after new wallet has been stored, take user to WalletActionsActivity to finish setup
     * @param wallet
     */
    private void onNewWalletCreated(Wallet wallet)
    {
        // TODO: [Notifications] Uncomment when backend service is implemented
        // viewModel.subscribeToNotifications();
        updateCurrentWallet(wallet);
        hideToolbar();
        callNewWalletPage(wallet);
    }

    /**
     * User selected new wallet, change to that wallet and jump to wallet page
     * @param wallet
     */
    private void walletChanged(Wallet wallet)
    {
        // TODO: [Notifications] Uncomment when backend service is implemented
        // viewModel.subscribeToNotifications();
        updateCurrentWallet(wallet);
        viewModel.showHome(this);
    }

    private void updateCurrentWallet(Wallet wallet)
    {
        viewModel.logIn(wallet.address);

        if (adapter == null)
        {
            recreate();
            return;
        }

        adapter.setDefaultWallet(wallet);
        scrollToDefaultWallet();

        viewModel.stopUpdates();
    }

    private void onFetchWallets(Wallet[] wallets)
    {
        enableDisplayHomeAsUp();
        if (adapter != null)
        {
            adapter.setWallets(wallets);
            scrollToDefaultWallet();
        }

        invalidateOptionsMenu();
    }

    private void preFinish()
    {
        hardwareCard.deactivateReader();
        finish();
    }

    private void callNewWalletPage(Wallet wallet)
    {
        Intent intent = new Intent(this, WalletActionsActivity.class);
        intent.putExtra("wallet", wallet);
        intent.putExtra("currency", viewModel.getNetwork().symbol);
        intent.putExtra("isNewWallet", true);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        editWalletDetails.launch(intent);
    }

    private void initResultLaunchers()
    {
        editWalletDetails = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    viewModel.showHome(this);
                });

        handleBackupWallet = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK)
                    {
                        // Backup completed successfully, now store the wallet
                        if (pendingWalletAddress != null && pendingAuthLevel != null)
                        {
                            viewModel.storeHDWallet(pendingWalletAddress, pendingAuthLevel);
                        }
                    }
                    else
                    {
                        // Backup was cancelled or failed - don't save the wallet
                        Toast.makeText(this, R.string.backup_cancelled, Toast.LENGTH_SHORT).show();
                    }
                    pendingWalletAddress = null;
                    pendingAuthLevel = null;
                    isCreatingNewHDWallet = false;
                });
    }

    private void onError(ErrorEnvelope errorEnvelope)
    {
        systemView.showError(errorEnvelope.message, this);
    }

    private void onSetWalletDefault(Wallet wallet)
    {
        reloadRequired = false;
        viewModel.changeDefaultWallet(wallet);
    }

    private void hideDialog()
    {
        if (dialog != null && dialog.isShowing())
        {
            dialog.dismiss();
            dialog = null;
        }

        if (aDialog != null && aDialog.isShowing())
        {
            aDialog.dismiss();
            aDialog = null;
        }
    }

    @Override
    public void HDKeyCreated(String address, Context ctx, KeyService.AuthenticationLevel level)
    {
        systemView.showProgress(false);
        if (address == null) 
        {
            isDerivingAccount = false;
            isCreatingNewHDWallet = false;
            onCreateWalletError(new ErrorEnvelope(""));
        }
        else if (isDerivingAccount)
        {
            // This is a derived account from an existing HD wallet
            viewModel.storeDerivedHDAccount(address, level, parentWalletAddress, derivedAccountIndex);
            isDerivingAccount = false;
            parentWalletAddress = null;
            derivedAccountIndex = 0;
            Toast.makeText(this, R.string.account_added_success, Toast.LENGTH_SHORT).show();
        }
        else
        {
            // This is a new HD wallet - launch backup flow
            pendingWalletAddress = address;
            pendingAuthLevel = level;
            isCreatingNewHDWallet = true;

            // Create temporary wallet for backup
            Wallet tempWallet = new Wallet(address);
            tempWallet.type = WalletType.HDKEY;
            
            Intent intent = new Intent(this, BackupKeyActivity.class);
            intent.putExtra(WALLET, tempWallet);
            intent.putExtra("STATE", ENTER_BACKUP_STATE_HD);
            handleBackupWallet.launch(intent);
        }
    }

    @Override
    public void keyFailure(String message)
    {
        systemView.showProgress(false);
        isDerivingAccount = false;
        onCreateWalletError(new ErrorEnvelope(message));
    }

    @Override
    public void cancelAuthentication()
    {
        onCreateWalletError(new ErrorEnvelope(getString(R.string.authentication_cancelled)));
    }

    @Override
    public void detectCard(View view)
    {
        //TODO: Hardware: Show waiting for card scan. Inform user to keep the card still and in place
        Toast.makeText(this, hardwareCard.getPlaceCardMessage(this), Toast.LENGTH_SHORT).show();
        hideDialog();
    }

    @Override
    public void fetchMnemonic(String mnemonic)
    {

    }

    // Callbacks from HardwareDevice

    @Override
    public void hardwareCardError(String errorMessage)
    {
        cardReadDialog.dismiss();
        //TODO: Hardware Improve error reporting UI (Popup?)
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void signedMessageFromHardware(SignatureFromKey returnSig)
    {
        cardReadDialog.dismiss();
        try
        {
            viewModel.storeHardwareWallet(returnSig);
        }
        catch (SignatureException ex)
        {
            //TODO: Hardware: Display this in a popup
            Toast.makeText(this, "Import Card: " + ex.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCardReadStart()
    {
        //TODO: Hardware; display popup graphic - this popup doesn't show
        runOnUiThread(() -> {
            if (cardReadDialog != null && cardReadDialog.isShowing()) cardReadDialog.dismiss();
            cardReadDialog = new AWalletAlertDialog(this);
            cardReadDialog.setTitle(hardwareCard.getPlaceCardMessage(this));
            cardReadDialog.setIcon(AWalletAlertDialog.NONE);
            cardReadDialog.setProgressMode();
            cardReadDialog.setCancelable(false);
            cardReadDialog.show();
        });
    }
}
