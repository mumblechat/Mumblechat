package com.ramapay.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ramapay.app.R;
import com.ramapay.app.entity.BackupOperationType;
import com.ramapay.app.entity.BackupState;
import com.ramapay.app.entity.CreateWalletCallbackInterface;
import com.ramapay.app.entity.Operation;
import com.ramapay.app.entity.SignAuthenticationCallback;
import com.ramapay.app.entity.StandardFunctionInterface;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.ui.QRScanning.DisplayUtils;
import com.ramapay.app.util.Utils;
import com.ramapay.app.viewmodel.BackupKeyViewModel;
import com.ramapay.app.viewmodel.ScammerWarningViewModel;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.FunctionButtonBar;
import com.ramapay.app.widget.LayoutCallbackListener;
import com.ramapay.app.widget.PasswordInputView;
import com.ramapay.app.widget.SignTransactionDialog;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayout;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.ViewModelProvider;

import static com.ramapay.app.C.Key.WALLET;
import static com.ramapay.app.entity.BackupState.ENTER_BACKUP_STATE_HD;
import static com.ramapay.app.entity.BackupState.ENTER_JSON_BACKUP;
import static com.ramapay.app.entity.BackupState.SHOW_SEED_PHRASE_SINGLE;
import static com.ramapay.app.entity.BackupState.UPGRADE_KEY_SECURITY;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ScammerWarningActivity extends BaseActivity
{
    private FunctionButtonBar functionButtonBar;
    private Wallet wallet;
    private ScammerWarningViewModel viewModel;
    private AWalletAlertDialog alertDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        lockOrientation();
        toolbar();
        
        viewModel = new ViewModelProvider(this).get(ScammerWarningViewModel.class);
        wallet = getIntent().getParcelableExtra(WALLET);
        setShowSeedPhraseSplash();
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

    private void setShowSeedPhraseSplash()
    {
        setContentView(R.layout.activity_show_seed);
        initViews();
        functionButtonBar.setPrimaryButtonText(R.string.show_seed_phrase);
        functionButtonBar.setPrimaryButtonClickListener(v -> {
            openBackupKeyActivity();
        });
    }

    private void openBackupKeyActivity()
    {
        // Check if the key exists before attempting to show the seed phrase
        if (wallet != null && wallet.type == WalletType.HDKEY && !viewModel.hasKey(wallet.address))
        {
            showKeyNotFoundError();
            return;
        }
        
        Intent intent = new Intent(this, BackupKeyActivity.class);
        intent.putExtra(WALLET, wallet);
        intent.putExtra("STATE", SHOW_SEED_PHRASE_SINGLE);
        startActivity(intent);
    }
    
    private void showKeyNotFoundError()
    {
        alertDialog = new AWalletAlertDialog(this);
        alertDialog.setIcon(AWalletAlertDialog.ERROR);
        alertDialog.setTitle(R.string.key_error);
        alertDialog.setMessage(getString(R.string.wallet_key_not_found));
        alertDialog.setButtonText(R.string.dialog_ok);
        alertDialog.setSecondaryButtonText(R.string.action_my_wallets);
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialog.setButtonListener(v -> {
            alertDialog.dismiss();
            finish();
        });
        alertDialog.setSecondaryButtonListener(v -> {
            alertDialog.dismiss();
            Intent intent = new Intent(this, WalletActionsActivity.class);
            intent.putExtra(WALLET, wallet);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
        alertDialog.show();
    }

    private void initViews()
    {
        functionButtonBar = findViewById(R.id.layoutButtons);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        toolbar();
        setTitle(getString(R.string.empty));
    }
}
