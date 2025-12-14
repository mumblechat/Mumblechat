package com.alphawallet.app.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.alphawallet.app.R;
import com.alphawallet.app.analytics.Analytics;
import com.alphawallet.app.entity.ActivityMeta;
import com.alphawallet.app.entity.ContractLocator;
import com.alphawallet.app.entity.TransactionMeta;
import com.alphawallet.app.entity.Wallet;
import com.alphawallet.app.interact.ActivityDataInteract;
import com.alphawallet.app.repository.entity.RealmTransaction;
import com.alphawallet.app.repository.entity.RealmTransfer;
import com.alphawallet.app.ui.widget.adapter.ActivityAdapter;
import com.alphawallet.app.ui.widget.entity.TokenTransferData;
import com.alphawallet.app.util.LocaleUtils;
import com.alphawallet.app.viewmodel.ActivityViewModel;
import com.alphawallet.app.widget.EmptyTransactionsView;
import com.alphawallet.app.widget.SystemView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dagger.hilt.android.AndroidEntryPoint;
import io.realm.Realm;
import io.realm.RealmResults;

/**
 * Created by JB on 26/06/2020.
 */
@AndroidEntryPoint
public class ActivityFragment extends BaseFragment implements View.OnClickListener, ActivityDataInteract
{
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityViewModel viewModel;
    private SystemView systemView;
    private ActivityAdapter adapter;
    private RecyclerView listView;
    private SwipeRefreshLayout refreshLayout;
    private RealmResults<RealmTransaction> realmUpdates;
    private boolean checkTimer;
    private Realm realm;
    private long lastUpdateTime = 0;
    private boolean isVisible = false;
    private boolean isNetworkAvailable = true;
    private ConnectivityManager connectivityManager;
    private View networkStatusBanner;
    private TextView networkStatusText;
    
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> updateNetworkStatus(true));
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> updateNetworkStatus(false));
            }
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                  capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> updateNetworkStatus(hasInternet));
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        LocaleUtils.setActiveLocale(requireContext());
        View view = inflater.inflate(R.layout.fragment_transactions, container, false);
        toolbar(view);
        setToolbarTitle(R.string.activity_label);
        initViewModel();
        initViews(view);
        setupNetworkMonitoring();
        return view;
    }
    
    private void setupNetworkMonitoring() {
        try {
            connectivityManager = (ConnectivityManager) requireContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                NetworkRequest networkRequest = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
                
                // Check initial network state
                Network activeNetwork = connectivityManager.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
                    isNetworkAvailable = capabilities != null && 
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                } else {
                    isNetworkAvailable = false;
                }
                updateNetworkStatus(isNetworkAvailable);
            }
        } catch (Exception e) {
            // Network monitoring not available
        }
    }
    
    private void updateNetworkStatus(boolean hasInternet) {
        isNetworkAvailable = hasInternet;
        if (networkStatusBanner != null && networkStatusText != null) {
            if (!hasInternet) {
                networkStatusBanner.setVisibility(View.VISIBLE);
                networkStatusText.setText(R.string.no_internet_connection);
            } else {
                networkStatusBanner.setVisibility(View.GONE);
            }
        }
        
        // If we just got internet back, refresh the list
        if (hasInternet && adapter != null && adapter.isEmpty()) {
            refreshTransactionList();
        }
    }

    private void initViewModel()
    {
        if (viewModel == null)
        {
            viewModel = new ViewModelProvider(this)
                    .get(ActivityViewModel.class);
            viewModel.defaultWallet().observe(getViewLifecycleOwner(), this::onDefaultWallet);
            viewModel.activityItems().observe(getViewLifecycleOwner(), this::onItemsLoaded);
        }
    }

    private void onItemsLoaded(ActivityMeta[] activityItems)
    {
        // Stop the refresh indicator
        if (refreshLayout != null && refreshLayout.isRefreshing())
        {
            refreshLayout.setRefreshing(false);
        }
        
        try (Realm realm = viewModel.getRealmInstance())
        {
            adapter.updateActivityItems(buildTransactionList(realm, activityItems).toArray(new ActivityMeta[0]));
            showEmptyTx();

            for (ActivityMeta am : activityItems)
            {
                if (am instanceof TransactionMeta && am.getTimeStampSeconds() > lastUpdateTime)
                    lastUpdateTime = am.getTimeStampSeconds() - 60;
            }
        }

        if (isVisible) startTxListener();
    }

    private void startTxListener()
    {
        if (viewModel.defaultWallet().getValue() == null) return;
        if (realm == null || realm.isClosed()) realm = viewModel.getRealmInstance();
        if (realmUpdates != null) realmUpdates.removeAllChangeListeners();
        if (viewModel == null || viewModel.defaultWallet().getValue() == null || TextUtils.isEmpty(viewModel.defaultWallet().getValue().address))
            return;

        realmUpdates = realm.where(RealmTransaction.class).greaterThan("timeStamp", lastUpdateTime).findAllAsync();
        realmUpdates.addChangeListener(realmTransactions -> {
            List<TransactionMeta> metas = new ArrayList<>();
            //make list
            if (realmTransactions.size() == 0) return;
            for (RealmTransaction item : realmTransactions)
            {
                if (viewModel.getTokensService().getNetworkFilters().contains(item.getChainId()))
                {
                    TransactionMeta newMeta = new TransactionMeta(item.getHash(), item.getTimeStamp(), item.getTo(), item.getChainId(), item.getBlockNumber());
                    metas.add(newMeta);
                    lastUpdateTime = newMeta.getTimeStampSeconds() + 1;
                }
            }

            if (metas.size() > 0)
            {
                TransactionMeta[] metaArray = metas.toArray(new TransactionMeta[0]);
                adapter.updateActivityItems(buildTransactionList(realm, metaArray).toArray(new ActivityMeta[0]));
                systemView.hide();
            }
        });
    }

    private List<ActivityMeta> buildTransactionList(Realm realm, ActivityMeta[] activityItems)
    {
        //selectively filter the items with the following rules:
        // - allow through all normal transactions with no token transfer consequences
        // - for any transaction with token transfers; if there's only one token transfer, only show the transfer
        // - for any transaction with more than one token transfer, show the transaction and show the child transfer consequences
        List<ActivityMeta> filteredList = new ArrayList<>();
        Set<String> seenTransfers = new HashSet<>(); // Track seen transfers to avoid duplicates

        for (ActivityMeta am : activityItems)
        {
            if (am instanceof TransactionMeta)
            {
                List<TokenTransferData> tokenTransfers = getTokenTransfersForHash(realm, (TransactionMeta) am);
                if (tokenTransfers.size() != 1)
                {
                    filteredList.add(am);
                } //only 1 token transfer ? No need to show the underlying transaction
                
                // Add transfers but skip duplicates
                for (TokenTransferData ttd : tokenTransfers)
                {
                    // Use hash + tokenAddress + eventName + transferDetail for unique identification
                    String transferKey = ttd.hash + "_" + ttd.tokenAddress + "_" + ttd.eventName + "_" + ttd.transferDetail;
                    if (!seenTransfers.contains(transferKey))
                    {
                        seenTransfers.add(transferKey);
                        filteredList.add(ttd);
                    }
                }
            }
        }

        return filteredList;
    }

    private List<TokenTransferData> getTokenTransfersForHash(Realm realm, TransactionMeta tm)
    {
        List<TokenTransferData> transferData = new ArrayList<>();
        Set<String> seenTransferKeys = new HashSet<>(); // Deduplicate at database level
        
        //summon realm items
        //get matching entries for this transaction
        RealmResults<RealmTransfer> transfers = realm.where(RealmTransfer.class)
                .equalTo("hash", RealmTransfer.databaseKey(tm.chainId, tm.hash))
                .findAll();

        if (transfers != null && transfers.size() > 0)
        {
            //list of transfers, descending in time to give ordered list
            long nextTransferTime = transfers.size() == 1 ? tm.getTimeStamp() : tm.getTimeStamp() - 1; // if there's only 1 transfer, keep the transaction timestamp
            for (RealmTransfer rt : transfers)
            {
                // Create unique key for this transfer
                String transferKey = rt.getTokenAddress() + "_" + rt.getEventName() + "_" + rt.getTransferDetail();
                if (!seenTransferKeys.contains(transferKey))
                {
                    seenTransferKeys.add(transferKey);
                    TokenTransferData ttd = new TokenTransferData(rt.getHash(), tm.chainId,
                            rt.getTokenAddress(), rt.getEventName(), rt.getTransferDetail(), nextTransferTime);
                    transferData.add(ttd);
                    nextTransferTime--;
                }
            }
        }

        return transferData;
    }

    private void initViews(View view)
    {
        adapter = new ActivityAdapter(viewModel.getTokensService(), viewModel.provideTransactionsInteract(),
                viewModel.getAssetDefinitionService(), this);
        refreshLayout = view.findViewById(R.id.refresh_layout);
        systemView = view.findViewById(R.id.system_view);
        listView = view.findViewById(R.id.list);
        listView.setLayoutManager(new LinearLayoutManager(requireContext()));
        listView.setAdapter(adapter);
        listView.addRecyclerListener(holder -> adapter.onRViewRecycled(holder));

        systemView.attachRecyclerView(listView);
        systemView.attachSwipeRefreshLayout(refreshLayout);
        
        // Initialize network status banner
        networkStatusBanner = view.findViewById(R.id.network_status_banner);
        networkStatusText = view.findViewById(R.id.network_status_text);

        // Show loading on first load
        refreshLayout.setRefreshing(true);
        refreshLayout.setOnRefreshListener(this::refreshTransactionList);
    }

    private void onDefaultWallet(Wallet wallet)
    {
        adapter.setDefaultWallet(wallet);
    }

    private void showEmptyTx()
    {
        if (adapter.isEmpty())
        {
            EmptyTransactionsView emptyView = new EmptyTransactionsView(requireContext(), this);
            systemView.showEmpty(emptyView);
        }
        else
        {
            systemView.hide();
        }
    }

    private void refreshTransactionList()
    {
        // Check network connectivity first
        if (!isNetworkAvailable) {
            if (refreshLayout != null) {
                refreshLayout.setRefreshing(false);
            }
            Toast.makeText(requireContext(), R.string.no_internet_connection, Toast.LENGTH_SHORT).show();
            updateNetworkStatus(false);
            return;
        }
        
        //clear tx list and reload
        adapter.clear();
        // Force fetch latest transactions from API first, then prepare
        viewModel.forceRefreshFromApi();
    }

    @Override
    public void resetTokens()
    {
        if (adapter != null)
        {
            //wallet changed, reset
            adapter.clear();
            viewModel.prepare();
        }
        else
        {
            requireActivity().recreate();
        }
    }

    @Override
    public void addedToken(List<ContractLocator> tokenContracts)
    {
        if (adapter != null) adapter.updateItems(tokenContracts);
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        if (realmUpdates != null) realmUpdates.removeAllChangeListeners();
        if (realm != null && !realm.isClosed()) realm.close();
        if (viewModel != null) viewModel.onDestroy();
        if (adapter != null && listView != null) adapter.onDestroy(listView);
        
        // Unregister network callback
        try {
            if (connectivityManager != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) {
            // Ignore - callback may not have been registered
        }
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
            viewModel.track(Analytics.Navigation.ACTIVITY);
            viewModel.prepare();
        }

        checkTimer = true;
    }

    @Override
    public void fetchMoreData(long latestDate)
    {
        if (checkTimer)
        {
            viewModel.fetchMoreTransactions(latestDate);
            checkTimer = false;
            handler.postDelayed(() -> {
                checkTimer = true;
            }, 5 * DateUtils.SECOND_IN_MILLIS); //restrict checking for previous transactions every 5 seconds
        }
    }

    @Override
    public void onClick(View v)
    {

    }

    @Override
    public void comeIntoFocus()
    {
        isVisible = true;
        //start listener
        startTxListener(); //adjust for timestamp delay
    }

    @Override
    public void leaveFocus()
    {
        isVisible = false;
        //stop listener
        if (realmUpdates != null) realmUpdates.removeAllChangeListeners();
        if (realm != null && !realm.isClosed()) realm.close();
    }

    @Override
    public void resetTransactions()
    {
        //called when we just refreshed the database
        refreshTransactionList();
    }

    @Override
    public void scrollToTop()
    {
        if (listView != null) listView.smoothScrollToPosition(0);
    }
}
