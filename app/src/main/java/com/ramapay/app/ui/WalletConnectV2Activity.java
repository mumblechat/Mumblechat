package com.ramapay.app.ui;

import static java.util.stream.Collectors.toList;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.ramapay.app.R;
import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.entity.StandardFunctionInterface;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.entity.walletconnect.NamespaceParser;
import com.ramapay.app.entity.walletconnect.WalletConnectV2SessionItem;
import com.ramapay.app.ui.widget.adapter.ChainAdapter;
import com.ramapay.app.ui.widget.adapter.EventAdapter;
import com.ramapay.app.ui.widget.adapter.MethodAdapter;
import com.ramapay.app.ui.widget.adapter.WalletAdapter;
import com.ramapay.app.util.LayoutHelper;
import com.ramapay.app.viewmodel.NetworkToggleViewModel;
import com.ramapay.app.viewmodel.WalletConnectV2ViewModel;
import com.ramapay.app.walletconnect.AWWalletConnectClient;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.FunctionButtonBar;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WalletConnectV2Activity extends BaseActivity implements StandardFunctionInterface, AWWalletConnectClient.WalletConnectV2Callback
{
    @Inject
    AWWalletConnectClient awWalletConnectClient;
    private WalletConnectV2ViewModel viewModel;
    private NetworkToggleViewModel networkToggleViewModel;
    private ImageView icon;
    private TextView peerName;
    private TextView peerUrl;
    private ProgressBar progressBar;
    private LinearLayout infoLayout;
    private TextView networksLabel;
    private ListView walletList;
    private ListView chainList;
    private ListView methodList;
    private ListView eventsList;
    private FunctionButtonBar functionBar;
    private WalletAdapter walletAdapter;
    private WalletConnectV2SessionItem session;
    private boolean launchedFromExternalApp = false;
    private String callingPackage = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_wallet_connect_v2);
        toolbar();
        setTitle(getString(R.string.title_wallet_connect));
        initViews();
        
        // Check if we were launched from an external app (like Chrome)
        callingPackage = getCallingPackage();
        if (callingPackage == null && getReferrer() != null)
        {
            callingPackage = getReferrer().getHost();
        }
        launchedFromExternalApp = callingPackage != null && !callingPackage.equals(getPackageName());
        
        // Also check if we're a new task (launched via deep link from external app)
        if (!launchedFromExternalApp)
        {
            launchedFromExternalApp = (getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0;
        }

        String url = retrieveUrl();
        if (!TextUtils.isEmpty(url))
        {
            progressBar.setVisibility(View.VISIBLE);
            awWalletConnectClient.pair(url, (msg) -> {
                if (TextUtils.isEmpty(msg))
                {
                    return;
                }
                runOnUiThread(() -> {
                    Toast.makeText(WalletConnectV2Activity.this, msg, Toast.LENGTH_SHORT).show();
                    finish();
                });
            });
            return;
        }

        this.session = retrieveSession(getIntent());
        initViewModel();
    }

    private void initViews()
    {
        progressBar = findViewById(R.id.progress);
        infoLayout = findViewById(R.id.layout_info);
        icon = findViewById(R.id.icon);
        peerName = findViewById(R.id.peer_name);
        peerUrl = findViewById(R.id.peer_url);
        networksLabel = findViewById(R.id.label_networks);
        walletList = findViewById(R.id.wallet_list);
        chainList = findViewById(R.id.chain_list);
        methodList = findViewById(R.id.method_list);
        eventsList = findViewById(R.id.event_list);
        functionBar = findViewById(R.id.layoutButtons);

        progressBar.setVisibility(View.VISIBLE);
        infoLayout.setVisibility(View.GONE);
        functionBar.setupFunctions(this, Arrays.asList(R.string.dialog_approve, R.string.dialog_reject));
        functionBar.setVisibility(View.GONE);
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        this.session = retrieveSession(intent);
        initViewModel();
    }

    private String retrieveUrl()
    {
        return getIntent().getStringExtra("url");
    }

    private WalletConnectV2SessionItem retrieveSession(Intent intent)
    {
        return intent.getParcelableExtra("session");
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this)
                .get(WalletConnectV2ViewModel.class);
        networkToggleViewModel = new ViewModelProvider(this)
                .get(NetworkToggleViewModel.class);
        viewModel.defaultWallet().observe(this, this::onDefaultWallet);
        viewModel.wallets().observe(this, this::onWallets);
    }

    private void onWallets(Wallet[] wallets)
    {
        viewModel.fetchDefaultWallet();
    }

    private void onDefaultWallet(Wallet wallet)
    {
        if (wallet.type == WalletType.WATCH)
        {
            AWalletAlertDialog errorDialog = new AWalletAlertDialog(this);
            errorDialog.setTitle(R.string.title_dialog_error);
            errorDialog.setMessage(getString(R.string.error_message_watch_only_wallet));
            errorDialog.setButton(R.string.dialog_ok, v -> {
                errorDialog.dismiss();
                finish();
            });
            errorDialog.show();
        }
        else
        {
            displaySessionStatus(session, wallet);
            progressBar.setVisibility(View.GONE);
            functionBar.setVisibility(View.VISIBLE);
            infoLayout.setVisibility(View.VISIBLE);
        }
    }

    private void displaySessionStatus(WalletConnectV2SessionItem session, Wallet wallet)
    {
        if (session == null)
        {
            return;
        }
        
        if (session.icon == null)
        {
            icon.setImageResource(R.drawable.grey_circle);
        }
        else
        {
            Glide.with(this)
                    .load(session.icon)
                    .circleCrop()
                    .into(icon);
        }

        if (!TextUtils.isEmpty(session.name))
        {
            peerName.setText(session.name);
        }

        peerUrl.setText(session.url);
        peerUrl.setTextColor(ContextCompat.getColor(this, R.color.brand));
        peerUrl.setOnClickListener(v -> {
            String url = peerUrl.getText().toString();
            if (url.startsWith("http"))
            {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
            }
        });

        if (session.settled)
        {
            walletAdapter = new WalletAdapter(this, findWallets(session.wallets));
            networksLabel.setText(R.string.network);
        }
        else
        {
            walletAdapter = new WalletAdapter(this, new Wallet[]{wallet}, viewModel.defaultWallet().getValue());
        }

        walletList.setAdapter(walletAdapter);

        if (session.chains.size() > 1)
        {
            networksLabel.setText(R.string.network);
        }
        else
        {
            networksLabel.setText(R.string.subtitle_network);
        }

        chainList.setAdapter(new ChainAdapter(this, session.chains));
        methodList.setAdapter(new MethodAdapter(this, session.methods));
        eventsList.setAdapter(new EventAdapter(this, session.events));

        resizeList();

        if (session.settled)
        {
            setTitle(getString(R.string.title_session_details));

            functionBar.setupFunctions(new StandardFunctionInterface()
            {
                @Override
                public void handleClick(String action, int actionId)
                {
                    endSessionDialog();
                }
            }, Collections.singletonList(R.string.action_end_session));
        }
        else
        {
            setTitle(getString(R.string.title_session_proposal));

            functionBar.setupFunctions(new StandardFunctionInterface()
            {
                @Override
                public void handleClick(String action, int actionId)
                {
                    if (actionId == R.string.dialog_approve)
                    {
                        approve(AWWalletConnectClient.sessionProposal);
                    }
                    else
                    {
                        reject(AWWalletConnectClient.sessionProposal);
                    }
                }
            }, Arrays.asList(R.string.dialog_approve, R.string.dialog_reject));
        }

    }

    private void resizeList()
    {
        LayoutHelper.resizeList(chainList);
        LayoutHelper.resizeList(methodList);
        LayoutHelper.resizeList(eventsList);
    }

    private void endSessionDialog()
    {
        runOnUiThread(() ->
        {
            AWalletAlertDialog dialog = new AWalletAlertDialog(this, AWalletAlertDialog.ERROR);
            dialog.setTitle(R.string.dialog_title_disconnect_session);
            dialog.setButton(R.string.action_close, v -> {
                dialog.dismiss();
                killSession(session.sessionId);
            });
            dialog.setSecondaryButton(R.string.action_cancel, v -> dialog.dismiss());
            dialog.setCancelable(false);
            dialog.show();
        });
    }

    private void killSession(String sessionId)
    {
        awWalletConnectClient.disconnect(sessionId, this);
    }

    private void reject(com.walletconnect.web3.wallet.client.Wallet.Model.SessionProposal sessionProposal)
    {
        awWalletConnectClient.reject(sessionProposal, this);
    }

    private void approve(com.walletconnect.web3.wallet.client.Wallet.Model.SessionProposal sessionProposal)
    {
        List<Long> disabledNetworks = disabledNetworks(sessionProposal.getRequiredNamespaces());
        if (disabledNetworks.isEmpty())
        {
            awWalletConnectClient.approve(sessionProposal, getSelectedAccounts(), this);
        }
        else
        {
            showDialog(disabledNetworks);
        }
    }

    private void showDialog(List<Long> disabledNetworks)
    {
        AWalletAlertDialog dialog = new AWalletAlertDialog(this);
        dialog.setMessage(String.format(getString(R.string.network_must_be_enabled), joinNames(disabledNetworks)));
        dialog.setButton(R.string.select_active_networks, view -> {
            Intent intent = new Intent(this, NetworkToggleActivity.class);
            startActivity(intent);
            dialog.dismiss();
        });
        dialog.setSecondaryButton(R.string.action_cancel, (view) -> dialog.dismiss());
        dialog.show();
    }

    @NonNull
    private String joinNames(List<Long> disabledNetworks)
    {
        return disabledNetworks.stream()
                .map((chainId) -> {
                    NetworkInfo network = networkToggleViewModel.getNetworkByChain(chainId);
                    if (network != null)
                    {
                        return network.name;
                    }
                    return String.valueOf(chainId);
                })
                .collect(Collectors.joining(", "));
    }

    private List<Long> disabledNetworks(Map<String, com.walletconnect.web3.wallet.client.Wallet.Model.Namespace.Proposal> requiredNamespaces)
    {
        NamespaceParser namespaceParser = new NamespaceParser();
        namespaceParser.parseProposal(requiredNamespaces);
        List<Long> enabledChainIds = networkToggleViewModel.getActiveNetworks();
        List<Long> result = new ArrayList<>();
        List<Long> chains = namespaceParser.getChains().stream().map((s) -> Long.parseLong(s.split(":")[1])).collect(toList());
        for (Long chainId : chains)
        {
            if (!enabledChainIds.contains(chainId))
            {
                result.add(chainId);
            }
        }
        return result;
    }

    private List<Wallet> findWallets(List<String> addresses)
    {
        List<Wallet> result = new ArrayList<>();
        if (viewModel.wallets().getValue() == null)
        {
            return result;
        }

        Map<String, Wallet> map = toMap(Objects.requireNonNull(viewModel.wallets().getValue()));
        for (String address : addresses)
        {
            Wallet wallet = map.get(address);
            if (wallet == null)
            {
                wallet = new Wallet(address);
            }
            result.add(wallet);
        }
        return result;
    }

    private Map<String, Wallet> toMap(Wallet[] wallets)
    {
        HashMap<String, Wallet> map = new HashMap<>();
        for (Wallet wallet : wallets)
        {
            map.put(wallet.address, wallet);
        }
        return map;
    }

    private List<String> getSelectedAccounts()
    {
        return walletAdapter.getSelectedWallets().stream()
                .map((wallet) -> wallet.address).collect(toList());
    }

    @Override
    public void onSessionProposalApproved()
    {
        runOnUiThread(() -> {
            Toast.makeText(this, R.string.wc_session_connected, Toast.LENGTH_SHORT).show();
            // Return to the calling app (browser)
            returnToCallingApp();
        });
    }

    @Override
    public void onSessionProposalRejected()
    {
        runOnUiThread(() -> {
            Toast.makeText(this, R.string.wc_session_rejected, Toast.LENGTH_SHORT).show();
            returnToCallingApp();
        });
    }

    @Override
    public void onSessionDisconnected()
    {
        runOnUiThread(() -> {
            awWalletConnectClient.updateNotification(null);
            Toast.makeText(this, R.string.wc_session_disconnected, Toast.LENGTH_SHORT).show();
        });
        finish();
    }
    
    private void returnToCallingApp()
    {
        String redirectUrl = session != null ? session.url : null;
        
        // If launched from external app (Chrome, etc.), use moveTaskToBack to return
        // This reveals the previous app without launching a new activity
        if (launchedFromExternalApp)
        {
            // Move RamaPay to background, which reveals the calling app (Chrome)
            moveTaskToBack(true);
            // Give time for the task switch, then finish this activity
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 500);
            return;
        }
        
        // If we're at the root of our task (launched via deep link as new task)
        if (isTaskRoot())
        {
            // Try to open the DApp URL in browser to return user to the DApp
            if (!TextUtils.isEmpty(redirectUrl) && redirectUrl.startsWith("http"))
            {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(redirectUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                try
                {
                    startActivity(browserIntent);
                    finish();
                    return;
                }
                catch (Exception e)
                {
                    // Fallback: just move to back
                }
            }
            
            // Fallback: move to background
            moveTaskToBack(true);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 500);
            return;
        }
        
        // Default: just finish (this will show the previous activity in our app)
        finish();
    }
}
