package com.alphawallet.app.ui;

import static org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.alphawallet.app.R;
import com.alphawallet.app.entity.NetworkInfo;
import com.alphawallet.app.entity.SignAuthenticationCallback;
import com.alphawallet.app.entity.TransactionReturn;
import com.alphawallet.app.entity.Wallet;
import com.alphawallet.app.entity.WalletType;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.repository.TokenRepository;
import com.alphawallet.app.service.GasService;
import com.alphawallet.app.ui.widget.entity.ActionSheetCallback;
import com.alphawallet.app.viewmodel.DappBrowserViewModel;
import com.alphawallet.app.web3.OnEthCallListener;
import com.alphawallet.app.web3.OnSignMessageListener;
import com.alphawallet.app.web3.OnSignPersonalMessageListener;
import com.alphawallet.app.web3.OnSignTransactionListener;
import com.alphawallet.app.web3.OnSignTypedMessageListener;
import com.alphawallet.app.web3.OnWalletActionListener;
import com.alphawallet.app.web3.OnWalletAddEthereumChainObjectListener;
import com.alphawallet.app.web3.Web3View;
import com.alphawallet.app.web3.entity.Address;
import com.alphawallet.app.web3.entity.WalletAddEthereumChainObject;
import com.alphawallet.app.web3.entity.Web3Call;
import com.alphawallet.app.web3.entity.Web3Transaction;
import com.alphawallet.app.widget.AWalletAlertDialog;
import com.alphawallet.app.widget.ActionSheet;
import com.alphawallet.app.widget.ActionSheetDialog;
import com.alphawallet.app.widget.ActionSheetSignDialog;
import com.alphawallet.hardware.SignatureFromKey;
import com.alphawallet.token.entity.EthereumMessage;
import com.alphawallet.token.entity.EthereumTypedMessage;
import com.alphawallet.token.entity.SignMessageType;
import com.alphawallet.token.entity.Signable;

import org.jetbrains.annotations.NotNull;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

@AndroidEntryPoint
public class ChatFragment extends BaseFragment implements
        OnSignTransactionListener, OnSignPersonalMessageListener,
        OnSignTypedMessageListener, OnSignMessageListener, OnEthCallListener,
        OnWalletAddEthereumChainObjectListener, OnWalletActionListener,
        ActionSheetCallback
{
    private Web3View web3;
    private DappBrowserViewModel viewModel;
    private Wallet wallet;
    private NetworkInfo activeNetwork;
    private ActionSheet confirmationDialog;
    private AWalletAlertDialog resultDialog;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private boolean isWebViewSetup = false;
    private static final String MUMBLECHAT_URL = "https://mumblechat.com/conversations";

    private final ActivityResultLauncher<Intent> gasSettingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (confirmationDialog != null && confirmationDialog.isShowing())
                {
                    confirmationDialog.setCurrentGasIndex(result);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_chat, container, false);
        initViews(view);
        initViewModel();
        return view;
    }

    private void initViews(View view)
    {
        progressBar = view.findViewById(R.id.progressBar);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh);
        web3 = view.findViewById(R.id.web3view);

        if (swipeRefreshLayout != null)
        {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                if (web3 != null)
                {
                    web3.reload();
                }
            });
        }
    }

    private void initViewModel()
    {
        viewModel = new ViewModelProvider(this).get(DappBrowserViewModel.class);
        viewModel.activeNetwork().observe(getViewLifecycleOwner(), this::onNetworkChanged);
        viewModel.defaultWallet().observe(getViewLifecycleOwner(), this::onDefaultWallet);
        viewModel.transactionFinalised().observe(getViewLifecycleOwner(), this::txWritten);
        viewModel.transactionError().observe(getViewLifecycleOwner(), this::txError);
        activeNetwork = viewModel.getActiveNetwork();
        viewModel.findWallet();
    }

    private void txWritten(TransactionReturn txData)
    {
        if (confirmationDialog != null && confirmationDialog.isShowing())
        {
            confirmationDialog.transactionWritten(txData.hash);
        }
        if (web3 != null)
        {
            web3.onSignTransactionSuccessful(txData);
        }
    }

    private void txError(TransactionReturn rtn)
    {
        if (confirmationDialog != null && confirmationDialog.isShowing())
        {
            confirmationDialog.dismiss();
        }
        if (web3 != null)
        {
            web3.onSignCancel(rtn.tx != null ? rtn.tx.leafPosition : 0);
        }

        if (getContext() == null) return;

        if (resultDialog != null && resultDialog.isShowing()) resultDialog.dismiss();
        resultDialog = new AWalletAlertDialog(requireContext());
        resultDialog.setIcon(AWalletAlertDialog.ERROR);
        resultDialog.setTitle(R.string.error_transaction_failed);
        if (rtn.throwable != null)
        {
            resultDialog.setMessage(rtn.throwable.getMessage());
        }
        resultDialog.setButtonText(R.string.button_ok);
        resultDialog.setButtonListener(v -> resultDialog.dismiss());
        resultDialog.show();
    }

    private void onDefaultWallet(Wallet w)
    {
        wallet = w;
        if (activeNetwork != null)
        {
            setupWeb3();
        }
    }

    private void onNetworkChanged(NetworkInfo networkInfo)
    {
        activeNetwork = networkInfo;
        if (wallet != null)
        {
            setupWeb3();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb3()
    {
        if (web3 == null || wallet == null || activeNetwork == null || isWebViewSetup)
        {
            return;
        }

        isWebViewSetup = true;

        web3.setChainId(activeNetwork.chainId, false);
        web3.setWalletAddress(new Address(wallet.address));

        WebSettings webSettings = web3.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setUserAgentString(webSettings.getUserAgentString() + " AlphaWallet(Platform=Android)");

        web3.setWebChromeClient(new WebChromeClient()
        {
            @Override
            public void onProgressChanged(WebView view, int newProgress)
            {
                if (progressBar != null)
                {
                    progressBar.setProgress(newProgress);
                    if (newProgress == 100)
                    {
                        progressBar.setVisibility(View.GONE);
                        if (swipeRefreshLayout != null)
                        {
                            swipeRefreshLayout.setRefreshing(false);
                        }
                    }
                    else
                    {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage msg)
            {
                Timber.d("MumbleChat: %s", msg.message());
                return super.onConsoleMessage(msg);
            }
        });

        web3.setWebViewClient(new WebViewClient()
        {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request)
            {
                return false;
            }
        });

        // Set up wallet connection listeners
        web3.setOnSignMessageListener(this);
        web3.setOnSignPersonalMessageListener(this);
        web3.setOnSignTransactionListener(this);
        web3.setOnSignTypedMessageListener(this);
        web3.setOnEthCallListener(this);
        web3.setOnWalletAddEthereumChainObjectListener(this);
        web3.setOnWalletActionListener(this);

        // Load MumbleChat
        web3.loadUrl(MUMBLECHAT_URL);
    }

    // ========== OnSignMessageListener ==========
    @Override
    public void onSignMessage(final EthereumMessage message)
    {
        handleSignMessage(message);
    }

    // ========== OnSignPersonalMessageListener ==========
    @Override
    public void onSignPersonalMessage(final EthereumMessage message)
    {
        handleSignMessage(message);
    }

    // ========== OnSignTypedMessageListener ==========
    @Override
    public void onSignTypedMessage(@NotNull EthereumTypedMessage message)
    {
        if (message.getPrehash() == null || message.getMessageType() == SignMessageType.SIGN_ERROR)
        {
            web3.onSignCancel(message.getCallbackId());
        }
        else
        {
            handleSignMessage(message);
        }
    }

    private void handleSignMessage(Signable message)
    {
        if (message.getMessageType() == SignMessageType.SIGN_TYPED_DATA_V3 && message.getChainId() != activeNetwork.chainId)
        {
            showErrorDialog(R.string.error_transaction_failed, getString(R.string.title_dialog_error));
            web3.onSignCancel(message.getCallbackId());
        }
        else if (confirmationDialog == null || !confirmationDialog.isShowing())
        {
            ActionSheetSignDialog signDialog = new ActionSheetSignDialog(requireActivity(), this, message);
            confirmationDialog = signDialog;
            signDialog.show();
        }
    }

    // ========== OnSignTransactionListener ==========
    @Override
    public void onSignTransaction(Web3Transaction transaction, String url)
    {
        try
        {
            if ((confirmationDialog == null || !confirmationDialog.isShowing()) &&
                    (transaction.recipient.equals(Address.EMPTY) && transaction.payload != null)
                    || (!transaction.recipient.equals(Address.EMPTY) && (transaction.payload != null || transaction.value != null)))
            {
                Token token = viewModel.getTokenService().getTokenOrBase(activeNetwork.chainId, transaction.recipient.toString());
                ActionSheetDialog txDialog = new ActionSheetDialog(requireActivity(), transaction, token,
                        "", transaction.recipient.toString(), viewModel.getTokenService(), this);
                confirmationDialog = txDialog;
                txDialog.setURL(url);
                txDialog.setCanceledOnTouchOutside(false);
                txDialog.show();
                txDialog.fullExpand();

                viewModel.calculateGasEstimate(wallet, transaction, activeNetwork.chainId)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(estimate -> txDialog.setGasEstimate(estimate),
                                Throwable::printStackTrace)
                        .isDisposed();
                return;
            }
        }
        catch (Exception e)
        {
            Timber.e(e, "Error signing transaction");
        }
        web3.onSignCancel(transaction.leafPosition);
    }

    // ========== OnEthCallListener ==========
    @Override
    public void onEthCall(Web3Call call)
    {
        Single.fromCallable(() -> {
                    Web3j web3j = TokenRepository.getWeb3jService(activeNetwork.chainId);
                    org.web3j.protocol.core.methods.request.Transaction transaction
                            = createFunctionCallTransaction(wallet.address, null, null, call.gasLimit, call.to.toString(), call.value, call.payload);
                    return web3j.ethCall(transaction, call.blockParam).send();
                }).map(EthCall::getValue)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> web3.onCallFunctionSuccessful(call.leafPosition, result),
                        error -> web3.onCallFunctionError(call.leafPosition, error.getMessage()))
                .isDisposed();
    }

    // ========== OnWalletAddEthereumChainObjectListener ==========
    @Override
    public void onWalletAddEthereumChainObject(long callbackId, WalletAddEthereumChainObject chainObj)
    {
        long chainId = chainObj.getChainId();
        final NetworkInfo info = viewModel.getNetworkInfo(chainId);

        if (info == null)
        {
            // Unknown network - notify failure
            web3.onWalletActionSuccessful(callbackId, "null");
        }
        else if (activeNetwork != null && activeNetwork.chainId == info.chainId)
        {
            // Already on this network
            web3.onWalletActionSuccessful(callbackId, "null");
        }
        else
        {
            // Switch to the requested network
            loadNewNetwork(info.chainId);
            web3.onWalletActionSuccessful(callbackId, "null");
        }
    }

    // ========== OnWalletActionListener ==========
    @Override
    public void onRequestAccounts(long callbackId)
    {
        if (wallet != null && wallet.address != null)
        {
            Timber.d("onRequestAccounts: returning address %s", wallet.address);
            web3.onWalletActionSuccessful(callbackId, "[\"" + wallet.address + "\"]");
        }
        else
        {
            Timber.e("onRequestAccounts: wallet is null!");
            web3.onWalletActionSuccessful(callbackId, "[]");
        }
    }

    @Override
    public void onWalletSwitchEthereumChain(long callbackId, WalletAddEthereumChainObject chainObj)
    {
        long chainId = chainObj.getChainId();
        final NetworkInfo info = viewModel.getNetworkInfo(chainId);

        if (info == null)
        {
            showErrorDialog(R.string.error_transaction_failed, getString(R.string.title_dialog_error));
            web3.onWalletActionSuccessful(callbackId, "null");
        }
        else
        {
            loadNewNetwork(info.chainId);
            web3.onWalletActionSuccessful(callbackId, "null");
        }
    }

    private void loadNewNetwork(long newNetworkId)
    {
        if (activeNetwork == null || activeNetwork.chainId != newNetworkId)
        {
            viewModel.setNetwork(newNetworkId);
            activeNetwork = viewModel.getNetworkInfo(newNetworkId);
            if (web3 != null && wallet != null && activeNetwork != null)
            {
                web3.setChainId(activeNetwork.chainId, false);
            }
        }
    }

    // ========== ActionSheetCallback ==========
    @Override
    public void getAuthorisation(SignAuthenticationCallback callback)
    {
        viewModel.getAuthorisation(wallet, requireActivity(), callback);
    }

    @Override
    public void sendTransaction(Web3Transaction tx)
    {
        viewModel.requestSignature(tx, wallet, activeNetwork.chainId);
    }

    @Override
    public void completeSendTransaction(Web3Transaction tx, SignatureFromKey signature)
    {
        viewModel.sendTransaction(wallet, activeNetwork.chainId, tx, signature);
    }

    @Override
    public void dismissed(String txHash, long callbackId, boolean actionCompleted)
    {
        // actionsheet dismissed - if action not completed then user cancelled
        if (!actionCompleted)
        {
            // actionsheet dismissed before completing signing
            web3.onSignCancel(callbackId);
        }
    }

    @Override
    public void notifyConfirm(String mode)
    {
        // Called when user confirms transaction
    }

    @Override
    public ActivityResultLauncher<Intent> gasSelectLauncher()
    {
        return gasSettingsLauncher;
    }

    @Override
    public WalletType getWalletType()
    {
        return wallet != null ? wallet.type : WalletType.NOT_DEFINED;
    }

    @Override
    public GasService getGasService()
    {
        return viewModel.getGasService();
    }

    @Override
    public void signingComplete(SignatureFromKey signature, Signable message)
    {
        String signHex = Numeric.toHexString(signature.signature);
        Timber.d("Signing complete for message: %s", message.getMessage());
        if (confirmationDialog != null)
        {
            confirmationDialog.success();
        }
        web3.onSignMessageSuccessful(message, signHex);
    }

    @Override
    public void signingFailed(Throwable error, Signable message)
    {
        web3.onSignCancel(message.getCallbackId());
        if (confirmationDialog != null)
        {
            confirmationDialog.dismiss();
        }
    }

    private void showErrorDialog(int titleRes, String message)
    {
        if (getContext() == null) return;

        if (resultDialog != null && resultDialog.isShowing())
        {
            resultDialog.dismiss();
        }
        resultDialog = new AWalletAlertDialog(requireContext());
        resultDialog.setIcon(AWalletAlertDialog.ERROR);
        resultDialog.setTitle(titleRes);
        resultDialog.setMessage(message);
        resultDialog.setButtonText(R.string.button_ok);
        resultDialog.setButtonListener(v -> resultDialog.dismiss());
        resultDialog.show();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (web3 != null)
        {
            web3.onResume();
        }
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (web3 != null)
        {
            web3.onPause();
        }
    }

    @Override
    public void onDestroy()
    {
        if (web3 != null)
        {
            web3.destroy();
        }
        super.onDestroy();
    }
}
