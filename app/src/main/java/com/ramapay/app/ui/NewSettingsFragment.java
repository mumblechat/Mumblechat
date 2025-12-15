package com.ramapay.app.ui;


import static android.app.Activity.RESULT_OK;
import static com.ramapay.app.C.CHANGED_LOCALE;
import static com.ramapay.app.C.CHANGE_CURRENCY;
import static com.ramapay.app.C.EXTRA_CURRENCY;
import static com.ramapay.app.C.EXTRA_LOCALE;
import static com.ramapay.app.C.EXTRA_STATE;
import static com.ramapay.app.C.Key.WALLET;
import static com.ramapay.app.C.RESET_WALLET;
import static com.ramapay.app.C.SETTINGS_INSTANTIATED;
import static com.ramapay.app.entity.BackupOperationType.BACKUP_HD_KEY;
import static com.ramapay.app.entity.BackupOperationType.BACKUP_KEYSTORE_KEY;
import static com.ramapay.app.ui.HomeActivity.RESET_TOKEN_SERVICE;
import static com.ramapay.token.tools.TokenDefinition.TOKENSCRIPT_CURRENT_SCHEMA;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.ramapay.app.BuildConfig;
import com.ramapay.app.C;
import com.ramapay.app.R;
import com.ramapay.app.analytics.Analytics;
import com.ramapay.app.entity.BackupOperationType;
import com.ramapay.app.entity.CustomViewSettings;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.interact.GenericWalletInteract;
import com.ramapay.app.util.LocaleUtils;
import com.ramapay.app.util.UpdateUtils;
import com.ramapay.app.util.Utils;
import com.ramapay.app.viewmodel.NewSettingsViewModel;
import com.ramapay.app.widget.NotificationView;
import com.ramapay.app.widget.SettingsItemView;
import com.ramapay.app.service.AppSecurityManager;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import javax.inject.Inject;

@AndroidEntryPoint
public class NewSettingsFragment extends BaseFragment
{
    @Inject
    AppSecurityManager appSecurityManager;
    
    private NewSettingsViewModel viewModel;
    private LinearLayout walletSettingsLayout;
    private LinearLayout systemSettingsLayout;
    private LinearLayout supportSettingsLayout;
    private SettingsItemView myAddressSetting;
    private SettingsItemView changeWalletSetting;
    private SettingsItemView backUpWalletSetting;
    private SettingsItemView appSecuritySetting;
    private SettingsItemView notificationsSetting;
    private SettingsItemView changeLanguage;
    private SettingsItemView changeCurrency;
    private SettingsItemView biometricsSetting;
    private SettingsItemView selectNetworksSetting;
    private SettingsItemView advancedSetting;
    private SettingsItemView darkModeSetting;
    private SettingsItemView supportSetting;
    private SettingsItemView aboutRamaPaySetting;
    private SettingsItemView tokenListingRequestSetting;
    private SettingsItemView walletConnectSetting;
    private SettingsItemView posSetting;
    private SettingsItemView showSeedPhrase;
    private SettingsItemView showPrivateKey;
    private SettingsItemView nameThisWallet;
    private LinearLayout layoutBackup;
    private Button backupButton;
    private TextView backupTitle;
    private TextView backupDetail;
    private ImageView closeBtn;
    private NotificationView notificationView;
    private MaterialCardView updateLayout;
    private String pendingUpdate;
    private Wallet wallet;
    private ActivityResultLauncher<Intent> handleBackupClick;
    private ActivityResultLauncher<Intent> networkSettingsHandler;
    private ActivityResultLauncher<Intent> advancedSettingsHandler;
    private ActivityResultLauncher<Intent> updateLocale;
    private ActivityResultLauncher<Intent> updateCurrency;
    private ActivityResultLauncher<Intent> supportSettingsHandler;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        LocaleUtils.setActiveLocale(getContext());

        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        toolbar(view);

        setToolbarTitle(R.string.toolbar_header_settings);

        initViewModel();

        initializeSettings(view);

        addSettingsToLayout();

        setInitialSettingsData(view);

        initBackupWarningViews(view);

        initNotificationView(view);

        initResultLaunchers();

        getParentFragmentManager().setFragmentResult(SETTINGS_INSTANTIATED, new Bundle());

        return view;
    }

    private void initResultLaunchers()
    {
        handleBackupClick = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    String keyBackup = "";
                    boolean noLockScreen = false;
                    Intent data = result.getData();
                    if (data != null) keyBackup = data.getStringExtra("Key");
                    if (data != null) noLockScreen = data.getBooleanExtra("nolock", false);

                    Bundle b = new Bundle();
                    b.putBoolean(C.HANDLE_BACKUP, result.getResultCode() == RESULT_OK);
                    b.putString("Key", keyBackup);
                    b.putBoolean("nolock", noLockScreen);
                    getParentFragmentManager().setFragmentResult(C.HANDLE_BACKUP, b);
                });

        networkSettingsHandler = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    //send instruction to restart tokenService
                    getParentFragmentManager().setFragmentResult(RESET_TOKEN_SERVICE, new Bundle());
                });

        advancedSettingsHandler = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    Intent data = result.getData();
                    if (data == null) return;
                    if (data.getBooleanExtra(RESET_WALLET, false))
                    {
                        getParentFragmentManager().setFragmentResult(RESET_WALLET, new Bundle());
                    }
                    else if (data.getBooleanExtra(CHANGE_CURRENCY, false))
                    {
                        getParentFragmentManager().setFragmentResult(CHANGE_CURRENCY, new Bundle());
                    }
                    else if (data.getBooleanExtra(CHANGED_LOCALE, false))
                    {
                        getParentFragmentManager().setFragmentResult(CHANGED_LOCALE, new Bundle());
                    }
                });
        updateLocale = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    updateLocale(result.getData());
                });
        updateCurrency = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    updateCurrency(result.getData());
                });
        supportSettingsHandler = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    Intent data = result.getData();
                    if (data != null && result.getResultCode() == RESULT_OK)
                    {
                        String url = data.getStringExtra(C.DAPP_URL_LOAD);
                        if (url != null)
                        {
                            // Open URL in wallet browser
                            Intent intent = new Intent(getActivity(), HomeActivity.class);
                            intent.putExtra(C.DAPP_URL_LOAD, url);
                            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(intent);
                        }
                    }
                });
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this)
                .get(NewSettingsViewModel.class);
        viewModel.defaultWallet().observe(getViewLifecycleOwner(), this::onDefaultWallet);
        viewModel.backUpMessage().observe(getViewLifecycleOwner(), this::backupWarning);
    }

    private void initNotificationView(View view)
    {
        notificationView = view.findViewById(R.id.notification);
        if (android.os.Build.VERSION.SDK_INT <= Build.VERSION_CODES.M)
        {
            notificationView.setTitle(getContext().getString(R.string.title_version_support_warning));
            notificationView.setMessage(getContext().getString(R.string.message_version_support_warning));
            notificationView.setPrimaryButtonText(getContext().getString(R.string.hide_notification));
            notificationView.setPrimaryButtonListener(() ->
            {
                notificationView.setVisibility(View.GONE);
                viewModel.setMarshMallowWarning(true);
            });
        }
        else
        {
            notificationView.setVisibility(View.GONE);
        }
    }

    @Override
    public void signalPlayStoreUpdate(int updateVersion)
    {
        //add wallet update signal to adapter
        pendingUpdate = String.valueOf(updateVersion);
        checkPendingUpdate(getView(), true, v ->
        {
            UpdateUtils.pushUpdateDialog(getActivity());
            updateLayout.setVisibility(View.GONE);
            pendingUpdate = "";
            if (getActivity() != null)
            {
                ((HomeActivity) getActivity()).removeSettingsBadgeKey(C.KEY_UPDATE_AVAILABLE);
            }
        });
    }

    @Override
    public void signalExternalUpdate(String updateVersion)
    {
        pendingUpdate = updateVersion;
        checkPendingUpdate(getView(), false, v ->
        {
            pendingUpdate = "";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(C.EXTERNAL_APP_DOWNLOAD_LINK));
            if (getActivity() != null)
            {
                ((HomeActivity) getActivity()).removeSettingsBadgeKey(C.KEY_UPDATE_AVAILABLE);
                getActivity().startActivity(intent);
            }
        });
    }

    private void initBackupWarningViews(View view)
    {
        layoutBackup = view.findViewById(R.id.layout_item_warning);
        backupTitle = view.findViewById(R.id.text_title);
        backupDetail = view.findViewById(R.id.text_detail);
        backupButton = view.findViewById(R.id.button_backup);
        closeBtn = view.findViewById(R.id.btn_close);
        layoutBackup.setVisibility(View.GONE);
    }

    private void initializeSettings(View view)
    {
        walletSettingsLayout = view.findViewById(R.id.layout_settings_wallet);
        systemSettingsLayout = view.findViewById(R.id.layout_settings_system);
        supportSettingsLayout = view.findViewById(R.id.layout_settings_support);
        updateLayout = view.findViewById(R.id.layout_update);

        myAddressSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_wallet_address)
                        .withTitle(R.string.title_show_wallet_address)
                        .withListener(this::onShowWalletAddressSettingClicked)
                        .build();

        changeWalletSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_change_wallet)
                        .withTitle(R.string.title_change_add_wallet)
                        .withListener(this::onChangeWalletSettingClicked)
                        .build();

        backUpWalletSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_backup)
                        .withTitle(R.string.title_back_up_wallet)
                        .withListener(this::onBackUpWalletSettingClicked)
                        .build();

        showSeedPhrase = new SettingsItemView.Builder(getContext())
                .withIcon(R.drawable.ic_settings_show_seed)
                .withTitle(R.string.show_seed_phrase)
                .withListener(this::onShowSeedPhrase) //onShow
                .build();

        showPrivateKey = new SettingsItemView.Builder(getContext())
                .withIcon(R.drawable.ic_settings_private_key)
                .withTitle(R.string.show_private_key)
                .withListener(this::onShowPrivateKey)
                .build();

        nameThisWallet = new SettingsItemView.Builder(getContext())
                .withIcon(R.drawable.ic_settings_name_this_wallet)
                .withTitle(R.string.name_this_wallet)
                .withListener(this::onNameThisWallet)
                .build();

        walletConnectSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_wallet_connect)
                        .withTitle(R.string.title_wallet_connect)
                        .withListener(this::onWalletConnectSettingClicked)
                        .build();

        posSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_qr_rounded_white)
                        .withTitle(R.string.pos_title)
                        .withSubtitle(R.string.pos_enter_amount)
                        .withListener(this::onPosSettingClicked)
                        .build();

        notificationsSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_notifications)
                        .withTitle(R.string.title_notifications)
                        .withListener(this::onNotificationsSettingClicked)
                        .build();

        changeLanguage = new SettingsItemView.Builder(getContext())
                .withIcon(R.drawable.ic_settings_language)
                .withTitle(R.string.title_change_language)
                .withListener(this::onChangeLanguageClicked)
                .build();

        changeCurrency = new SettingsItemView.Builder(getContext())
                .withIcon(R.drawable.ic_currency)
                .withTitle(R.string.settings_locale_currency)
                .withListener(this::onChangeCurrencyClicked)
                .build();

//        biometricsSetting =
//                new SettingsItemView.Builder(getContext())
//                        .withType(SettingsItemView.Type.TOGGLE)
//                        .withIcon(R.drawable.ic_settings_biometrics)
//                        .withTitle(R.string.title_biometrics)
//                        .withListener(this::onBiometricsSettingClicked)
//                        .build();

        selectNetworksSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_networks)
                        .withTitle(R.string.select_active_networks)
                        .withListener(this::onSelectNetworksSettingClicked)
                        .build();

        advancedSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_advanced)
                        .withTitle(R.string.title_advanced)
                        .withListener(this::onAdvancedSettingClicked)
                        .build();

        darkModeSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_darkmode)
                        .withTitle(R.string.title_dark_mode)
                        .withListener(this::onDarkModeSettingClicked)
                        .build();

        appSecuritySetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_key_security)
                        .withTitle(R.string.app_security)
                        .withListener(this::onAppSecuritySettingClicked)
                        .build();

        supportSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_support)
                        .withTitle(R.string.title_support)
                        .withListener(this::onSupportSettingClicked)
                        .build();

        tokenListingRequestSetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_add_circle)
                        .withTitle(R.string.title_token_listing_request)
                        .withListener(this::onTokenListingRequestClicked)
                        .build();

        aboutRamaPaySetting =
                new SettingsItemView.Builder(getContext())
                        .withIcon(R.drawable.ic_settings_about)
                        .withTitle(R.string.title_about_ramapay)
                        .withListener(this::onAboutRamaPayClicked)
                        .build();
    }

    private void addSettingsToLayout()
    {
        int walletIndex = 0;
        int systemIndex = 0;
        int supportIndex = 0;

        walletSettingsLayout.addView(myAddressSetting, walletIndex++);

        if (CustomViewSettings.canChangeWallets())
            walletSettingsLayout.addView(changeWalletSetting, walletIndex++);

        walletSettingsLayout.addView(backUpWalletSetting, walletIndex++);

        walletSettingsLayout.addView(showSeedPhrase, walletIndex++);
        showSeedPhrase.setVisibility(View.GONE);

        walletSettingsLayout.addView(showPrivateKey, walletIndex++);
        showPrivateKey.setVisibility(View.GONE);

        walletSettingsLayout.addView(nameThisWallet, walletIndex++);

        walletSettingsLayout.addView(walletConnectSetting, walletIndex++);

        walletSettingsLayout.addView(posSetting, walletIndex++);

        if (CustomViewSettings.getLockedChains().size() == 0)
            systemSettingsLayout.addView(selectNetworksSetting, systemIndex++);

        if (biometricsSetting != null)
            systemSettingsLayout.addView(biometricsSetting, systemIndex++);

        systemSettingsLayout.addView(notificationsSetting, systemIndex++);

        systemSettingsLayout.addView(changeLanguage, systemIndex++);

        systemSettingsLayout.addView(changeCurrency, systemIndex++);

        systemSettingsLayout.addView(darkModeSetting, systemIndex++);

        systemSettingsLayout.addView(appSecuritySetting, systemIndex++);

        systemSettingsLayout.addView(advancedSetting, systemIndex++);

        supportSettingsLayout.addView(supportSetting, supportIndex++);
        supportSettingsLayout.addView(tokenListingRequestSetting, supportIndex++);
        supportSettingsLayout.addView(aboutRamaPaySetting, supportIndex++);
    }

    private void setInitialSettingsData(View view)
    {
        TextView appVersionText = view.findViewById(R.id.text_version);
        appVersionText.setText(String.format(Locale.getDefault(), "%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        TextView tokenScriptVersionText = view.findViewById(R.id.text_tokenscript_compatibility);
        tokenScriptVersionText.setText(TOKENSCRIPT_CURRENT_SCHEMA);
    }

    private void openShowSeedPhrase(Wallet wallet)
    {
        if (wallet.type != WalletType.HDKEY) return;

        Intent intent = new Intent(getContext(), ScammerWarningActivity.class);
        intent.putExtra(WALLET, wallet);
        startActivity(intent);
    }

    private void openBackupActivity(Wallet wallet)
    {
        Intent intent = new Intent(getContext(), BackupFlowActivity.class);
        intent.putExtra(WALLET, wallet);

        switch (wallet.type)
        {
            case HDKEY:
                intent.putExtra("TYPE", BACKUP_HD_KEY);
                break;
            case KEYSTORE_LEGACY:
            case KEYSTORE:
                intent.putExtra("TYPE", BACKUP_KEYSTORE_KEY);
                break;
        }

        //override if this is an upgrade
        switch (wallet.authLevel)
        {
            case NOT_SET:
            case STRONGBOX_NO_AUTHENTICATION:
            case TEE_NO_AUTHENTICATION:
                if (wallet.lastBackupTime > 0)
                {
                    intent.putExtra("TYPE", BackupOperationType.UPGRADE_KEY);
                }
                break;
            default:
                break;
        }

        intent.setFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        handleBackupClick.launch(intent);
    }

    private void onDefaultWallet(Wallet wallet)
    {
        this.wallet = wallet;
        if (wallet.address != null)
        {
            String walletAddressDisplay = wallet.ENSname.isEmpty() ? wallet.address
                    : wallet.ENSname + " | " + Utils.formatAddress(wallet.address);

            changeWalletSetting.setSubtitle(walletAddressDisplay);
        }

        switch (wallet.authLevel)
        {
            case NOT_SET:
            case STRONGBOX_NO_AUTHENTICATION:
            case TEE_NO_AUTHENTICATION:
                if (wallet.lastBackupTime > 0)
                {
                    backUpWalletSetting.setTitle(getString(R.string.action_upgrade_key));
                    backUpWalletSetting.setSubtitle(getString(R.string.not_locked));
                }
                else
                {
                    backUpWalletSetting.setTitle(getString(R.string.back_up_this_wallet));
                    backUpWalletSetting.setSubtitle(getString(R.string.back_up_now));
                }
                break;
            case TEE_AUTHENTICATION:
            case STRONGBOX_AUTHENTICATION:
                backUpWalletSetting.setTitle(getString(R.string.back_up_this_wallet));
                backUpWalletSetting.setSubtitle(getString(R.string.key_secure));
                break;
        }

        switch (wallet.type)
        {
            case NOT_DEFINED:
                break;
            case KEYSTORE:
                break;
            case HDKEY:
                // For derived HD accounts, only show private key (no seed phrase)
                if (wallet.isDerivedHDAccount())
                {
                    showSeedPhrase.setVisibility(View.GONE);
                    showPrivateKey.setVisibility(View.VISIBLE);
                    // Derived accounts don't need separate backup - they're backed up via master
                    backUpWalletSetting.setVisibility(View.GONE);
                }
                else
                {
                    // Master HD wallet - show both seed phrase and private key
                    showSeedPhrase.setVisibility(View.VISIBLE);
                    showPrivateKey.setVisibility(View.VISIBLE);
                }
                break;
            case WATCH:
                backUpWalletSetting.setVisibility(View.GONE);
                break;
            case TEXT_MARKER:
                break;
            case KEYSTORE_LEGACY:
                break;
        }

        viewModel.setLocale(getContext());

        changeLanguage.setSubtitle(LocaleUtils.getDisplayLanguage(viewModel.getActiveLocale(), viewModel.getActiveLocale()));

        changeCurrency.setSubtitle(viewModel.getDefaultCurrency());
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (viewModel == null)
        {
            requireActivity().recreate();
        }
        else
        {
            viewModel.track(Analytics.Navigation.SETTINGS);
            viewModel.prepare();
        }
        
        // Update app security setting subtitle
        updateAppSecurityStatus();
    }
    
    private void updateAppSecurityStatus()
    {
        if (appSecuritySetting != null && appSecurityManager != null)
        {
            if (appSecurityManager.isSecurityEnabled())
            {
                String status = appSecurityManager.isBiometricEnabled() 
                        ? getString(R.string.key_secure) + " + " + getString(R.string.biometric_auth)
                        : getString(R.string.key_secure);
                appSecuritySetting.setSubtitle(status);
            }
            else
            {
                appSecuritySetting.setSubtitle(getString(R.string.not_locked));
            }
        }
    }

    @Override
    public void backupSeedSuccess(boolean hasNoLock)
    {
        if (viewModel != null) viewModel.TestWalletBackup();
        if (layoutBackup != null) layoutBackup.setVisibility(View.GONE);
        if (hasNoLock)
        {
            backUpWalletSetting.setSubtitle(getString(R.string.not_locked));
        }
    }

    private void backupWarning(String s)
    {
        Wallet defaultWallet = viewModel.defaultWallet().getValue();
        // Skip backup warning for derived HD accounts - they're backed up via master
        if (defaultWallet != null && defaultWallet.isDerivedHDAccount())
        {
            if (layoutBackup != null)
            {
                layoutBackup.setVisibility(View.GONE);
            }
            return;
        }
        
        if (s.equals(defaultWallet.address))
        {
            addBackupNotice(GenericWalletInteract.BackupLevel.WALLET_HAS_HIGH_VALUE);
        }
        else
        {
            if (layoutBackup != null)
            {
                layoutBackup.setVisibility(View.GONE);
            }
            //remove the number prompt
            if (getActivity() != null)
                ((HomeActivity) getActivity()).removeSettingsBadgeKey(C.KEY_NEEDS_BACKUP);
            onDefaultWallet(viewModel.defaultWallet().getValue());
        }
    }

    void addBackupNotice(GenericWalletInteract.BackupLevel walletValue)
    {
        // Don't show backup notice for derived HD accounts - they're backed up via master wallet
        if (wallet != null && wallet.isDerivedHDAccount())
        {
            layoutBackup.setVisibility(View.GONE);
            return;
        }
        
        layoutBackup.setVisibility(View.VISIBLE);
        if (wallet != null)
        {
            backupButton.setText(getString(R.string.back_up_now));
            backupButton.setOnClickListener(v -> openBackupActivity(wallet));
            backupTitle.setText(getString(R.string.title_back_up_your_wallet));
            backupDetail.setText(getString(R.string.backup_wallet_detail));
            closeBtn.setOnClickListener(v -> {
                backedUp(wallet.address);
                viewModel.setIsDismissed(wallet.address, true);
            });

            if (getActivity() != null)
            {
                ((HomeActivity) getActivity()).addSettingsBadgeKey(C.KEY_NEEDS_BACKUP);
            }
        }
    }

    private void backedUp(String walletAddress)
    {
        layoutBackup.setVisibility(View.GONE);
        if (getActivity() != null)
            ((HomeActivity) getActivity()).postponeWalletBackupWarning(walletAddress);
    }

    private void onShowWalletAddressSettingClicked()
    {
        viewModel.showMyAddress(getContext());
    }

    private void onChangeWalletSettingClicked()
    {
        viewModel.showManageWallets(getContext(), false);
    }

    private void onBackUpWalletSettingClicked()
    {
        Wallet wallet = viewModel.defaultWallet().getValue();
        if (wallet != null)
        {
            openBackupActivity(wallet);
        }
    }

    private void onShowSeedPhrase()
    {
        Wallet wallet = viewModel.defaultWallet().getValue();
        if (wallet != null)
        {
            openShowSeedPhrase(wallet);
        }
    }

    private void onShowPrivateKey()
    {
        Wallet wallet = viewModel.defaultWallet().getValue();
        if (wallet != null && wallet.type == WalletType.HDKEY)
        {
            Intent intent = new Intent(getContext(), BackupKeyActivity.class);
            intent.putExtra(C.Key.WALLET, wallet);
            intent.putExtra("TYPE", BackupOperationType.EXPORT_PRIVATE_KEY);
            startActivity(intent);
        }
    }

    private void onNameThisWallet()
    {
        Intent intent = new Intent(getActivity(), NameThisWalletActivity.class);
        requireActivity().startActivity(intent);
    }

    private void onNotificationsSettingClicked()
    {
        Intent intent = new Intent(getActivity(), NotificationSettingsActivity.class);
        requireActivity().startActivity(intent);
    }

    private void onBiometricsSettingClicked()
    {
        // TODO: Implementation
    }

    private void onSelectNetworksSettingClicked()
    {
        Intent intent = new Intent(getActivity(), NetworkToggleActivity.class);
        intent.putExtra(C.EXTRA_SINGLE_ITEM, false);
        networkSettingsHandler.launch(intent);
    }

    private void onAdvancedSettingClicked()
    {
        Intent intent = new Intent(getActivity(), AdvancedSettingsActivity.class);
        advancedSettingsHandler.launch(intent);
    }

    private void onDarkModeSettingClicked()
    {
        Intent intent = new Intent(getActivity(), SelectThemeActivity.class);
        startActivity(intent);
    }

    private void onAppSecuritySettingClicked()
    {
        // If security is already enabled, go to security settings
        // Otherwise, go to setup security
        if (appSecurityManager.isSecurityEnabled()) {
            Intent intent = SecuritySettingsActivity.createIntent(requireContext());
            startActivity(intent);
        } else {
            Intent intent = SetupSecurityActivity.createIntent(requireContext(), true, false);
            startActivity(intent);
        }
    }

    private void onSupportSettingClicked()
    {
        Intent intent = new Intent(getActivity(), SupportSettingsActivity.class);
        supportSettingsHandler.launch(intent);
    }

    private void onAboutRamaPayClicked()
    {
        Intent intent = new Intent(getActivity(), AboutRamaPayActivity.class);
        startActivity(intent);
    }

    private void onTokenListingRequestClicked()
    {
        Intent intent = new Intent(getActivity(), TokenListingRequestActivity.class);
        startActivity(intent);
    }

    private void onWalletConnectSettingClicked()
    {
        Intent intent = new Intent(getActivity(), WalletConnectSessionActivity.class);
        startActivity(intent);
    }

    private void onPosSettingClicked()
    {
        Intent intent = new Intent(getActivity(), PosActivity.class);
        intent.putExtra(C.Key.WALLET, viewModel.defaultWallet().getValue());
        startActivity(intent);
    }

    private void checkPendingUpdate(View view, boolean isFromPlayStore, View.OnClickListener listener)
    {
        if (updateLayout == null || view == null) return;

        if (!TextUtils.isEmpty(pendingUpdate))
        {
            updateLayout.setVisibility(View.VISIBLE);
            updateLayout.setOnClickListener(listener);
            TextView current = view.findViewById(R.id.text_detail_current);
            TextView available = view.findViewById(R.id.text_detail_available);
            if (isFromPlayStore)
            {
                current.setText(getString(R.string.installed_version, String.valueOf(BuildConfig.VERSION_CODE)));
            }
            else
            {
                current.setText(getString(R.string.installed_version, BuildConfig.VERSION_NAME));
            }
            available.setText(getString(R.string.available_version, String.valueOf(pendingUpdate)));

            if (getActivity() != null)
            {
                ((HomeActivity) getActivity()).addSettingsBadgeKey(C.KEY_UPDATE_AVAILABLE);
            }
        }
        else
        {
            updateLayout.setVisibility(View.GONE);
        }
    }

    private void onChangeLanguageClicked()
    {
        Intent intent = new Intent(getActivity(), SelectLocaleActivity.class);
        String selectedLocale = viewModel.getActiveLocale();
        intent.putExtra(EXTRA_LOCALE, selectedLocale);
        intent.putParcelableArrayListExtra(EXTRA_STATE, viewModel.getLocaleList(getContext()));
        updateLocale.launch(intent);
    }

    private void onChangeCurrencyClicked()
    {
        Intent intent = new Intent(getActivity(), SelectCurrencyActivity.class);
        String currentLocale = viewModel.getDefaultCurrency();
        intent.putExtra(EXTRA_CURRENCY, currentLocale);
        intent.putParcelableArrayListExtra(EXTRA_STATE, viewModel.getCurrencyList());
        updateCurrency.launch(intent);
    }

    public void updateLocale(Intent data)
    {
        if (data != null)
        {
            String newLocale = data.getStringExtra(C.EXTRA_LOCALE);
            String oldLocale = viewModel.getActiveLocale();
            if (!TextUtils.isEmpty(newLocale) && !newLocale.equals(oldLocale))
            {
                viewModel.updateLocale(newLocale, getContext());
                getActivity().recreate();
            }
        }
    }

    public void updateCurrency(Intent data)
    {
        if (data != null)
        {
            String currencyCode = data.getStringExtra(C.EXTRA_CURRENCY);
            if (!viewModel.getDefaultCurrency().equals(currencyCode))
            {
                viewModel.updateCurrency(currencyCode)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(res -> getActivity().recreate())
                        .isDisposed();
            }
        }
    }
}
