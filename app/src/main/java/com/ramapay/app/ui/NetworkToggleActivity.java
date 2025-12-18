package com.ramapay.app.ui;

import static com.ramapay.app.ui.AddCustomRPCNetworkActivity.CHAIN_ID;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.ramapay.app.R;
import com.ramapay.app.analytics.Analytics;
import com.ramapay.app.router.HomeRouter;
import com.ramapay.app.ui.widget.adapter.MultiSelectNetworkAdapter;
import com.ramapay.app.ui.widget.entity.NetworkItem;
import com.ramapay.app.viewmodel.NetworkToggleViewModel;
import com.ramapay.app.widget.PercentageProgressView;
import com.ramapay.ethereum.NetworkInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class NetworkToggleActivity extends NetworkBaseActivity
{
    public static final String FROM_NEW_WALLET = "from_new_wallet";
    
    private NetworkToggleViewModel viewModel;
    private MultiSelectNetworkAdapter mainNetAdapter;
    private MultiSelectNetworkAdapter testNetAdapter;
    private FrameLayout loadingLayout;
    private PercentageProgressView percentageProgress;
    private boolean isFromNewWallet = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this)
                .get(NetworkToggleViewModel.class);
        initTestNetDialog(this);
        setupFilterList();
        setupFinishButton();
        setupLoadingOverlay();
        
        // Check if this is from wallet creation (new wallet flow)
        isFromNewWallet = getIntent().getBooleanExtra(FROM_NEW_WALLET, false);
    }
    
    private void setupLoadingOverlay()
    {
        loadingLayout = findViewById(R.id.layout_loading);
        percentageProgress = findViewById(R.id.percentage_progress);
    }

    private void setupFinishButton()
    {
        findViewById(R.id.button_finish).setOnClickListener(v -> {
            if (isFromNewWallet && loadingLayout != null && percentageProgress != null)
            {
                // Show loading overlay with progress for new wallet flow
                showLoadingWithProgress();
            }
            else
            {
                handleSetNetworks();
            }
        });
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        setupFilterList();
        viewModel.track(Analytics.Navigation.SELECT_NETWORKS);
    }

    private void setupFilterList()
    {
        List<NetworkItem> mainNetList = viewModel.getNetworkList(true);
        List<NetworkItem> testNetList = viewModel.getNetworkList(false);

        MultiSelectNetworkAdapter.Callback callback = new MultiSelectNetworkAdapter.Callback()
        {

            private void showPopup(View view, long chainId)
            {
                LayoutInflater inflater = LayoutInflater.from(NetworkToggleActivity.this);
                View popupView = inflater.inflate(R.layout.popup_view_delete_network, null);

                int width = LinearLayout.LayoutParams.WRAP_CONTENT;
                int height = LinearLayout.LayoutParams.WRAP_CONTENT;
                final PopupWindow popupWindow = new PopupWindow(popupView, width, height, true);
                popupView.findViewById(R.id.popup_view).setOnClickListener(v -> {
                    // view network
                    Intent intent = new Intent(NetworkToggleActivity.this, AddCustomRPCNetworkActivity.class);
                    intent.putExtra(CHAIN_ID, chainId);
                    startActivity(intent);
                    popupWindow.dismiss();
                });

                NetworkInfo network = viewModel.getNetworkByChain(chainId);
                if (network.isCustom)
                {
                    popupView.findViewById(R.id.popup_delete).setOnClickListener(v -> {
                        // delete network
                        viewModel.removeCustomNetwork(chainId);
                        popupWindow.dismiss();
                        setupFilterList();
                    });
                }
                else
                {
                    popupView.findViewById(R.id.popup_delete).setVisibility(View.GONE);
                }

                popupView.measure(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                popupWindow.setHeight(popupView.getMeasuredHeight());

                popupWindow.setElevation(5);

                popupWindow.showAsDropDown(view);
            }

            @Override
            public void onEditSelected(long chainId, View parent)
            {
                showPopup(parent, chainId);
            }

            @Override
            public void onCheckChanged(long chainId, int count)
            {
                updateTitle();
            }
        };

        mainNetAdapter = new MultiSelectNetworkAdapter(mainNetList, callback);
        mainnetRecyclerView.setAdapter(mainNetAdapter);

        testNetAdapter = new MultiSelectNetworkAdapter(testNetList, callback);
        testnetRecyclerView.setAdapter(testNetAdapter);

        updateTitle();
    }

    @Override
    protected void updateTitle()
    {
        if (mainNetAdapter == null || testNetAdapter == null)
        {
            return;
        }

        int count = mainNetAdapter.getSelectedItemCount();
        if (testnetSwitch.isChecked())
        {
            count += testNetAdapter.getSelectedItemCount();
        }
        setTitle(getString(R.string.title_enabled_networks, String.valueOf(count)));
    }

    @Override
    protected void handleSetNetworks()
    {
        viewModel.setTestnetEnabled(testnetSwitch.isChecked());

        List<Long> filterList = new ArrayList<>(Arrays.asList(mainNetAdapter.getSelectedItems()));
        if (testnetSwitch.isChecked())
        {
            filterList.addAll(Arrays.asList(testNetAdapter.getSelectedItems()));
        }
        boolean hasClicked = mainNetAdapter.hasSelectedItems() || testNetAdapter.hasSelectedItems();
        boolean shouldBlankUserSelection = filterList.size() == 0; //This is only set when we want to automatically discover all tokens. If user sets all networks blank it auto-fills them

        viewModel.setFilterNetworks(filterList, hasClicked, shouldBlankUserSelection);
        setResult(RESULT_OK, new Intent());
        finish();
    }
    
    private void showLoadingWithProgress()
    {
        // Show loading overlay
        loadingLayout.setVisibility(View.VISIBLE);
        
        // Disable finish button to prevent double-tap
        findViewById(R.id.button_finish).setEnabled(false);
        
        // Save network settings first
        saveNetworkSettings();
        
        // Start progress simulation (1.2 seconds for smooth experience)
        percentageProgress.startSimulation(1200, () -> {
            // When progress completes, navigate directly to Home
            runOnUiThread(() -> {
                // Navigate directly to HomeActivity (skip returning to SplashActivity)
                new HomeRouter().open(NetworkToggleActivity.this, true, true);
                finish();
            });
        });
    }
    
    private void saveNetworkSettings()
    {
        viewModel.setTestnetEnabled(testnetSwitch.isChecked());

        List<Long> filterList = new ArrayList<>(Arrays.asList(mainNetAdapter.getSelectedItems()));
        if (testnetSwitch.isChecked())
        {
            filterList.addAll(Arrays.asList(testNetAdapter.getSelectedItems()));
        }
        boolean hasClicked = mainNetAdapter.hasSelectedItems() || testNetAdapter.hasSelectedItems();
        boolean shouldBlankUserSelection = filterList.size() == 0;

        viewModel.setFilterNetworks(filterList, hasClicked, shouldBlankUserSelection);
    }
    
    @Override
    public void onBackPressed()
    {
        // Prevent back press during loading
        if (loadingLayout != null && loadingLayout.getVisibility() == View.VISIBLE)
        {
            return;
        }
        super.onBackPressed();
    }
}
