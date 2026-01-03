package com.ramapay.app.ui;

import static com.ramapay.ethereum.EthereumNetworkBase.RAMESTTA_MAINNET_ID;
import static org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.ramapay.app.R;
import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.entity.SignAuthenticationCallback;
import com.ramapay.app.entity.TransactionReturn;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.repository.TokenRepository;
import com.ramapay.app.service.GasService;
import com.ramapay.app.ui.widget.entity.ActionSheetCallback;
import com.ramapay.app.viewmodel.DappBrowserViewModel;
import com.ramapay.app.web3.OnEthCallListener;
import com.ramapay.app.web3.OnSignMessageListener;
import com.ramapay.app.web3.OnSignPersonalMessageListener;
import com.ramapay.app.web3.OnSignTransactionListener;
import com.ramapay.app.web3.OnSignTypedMessageListener;
import com.ramapay.app.web3.OnWalletActionListener;
import com.ramapay.app.web3.OnWalletAddEthereumChainObjectListener;
import com.ramapay.app.web3.Web3View;
import com.ramapay.app.web3.entity.Address;
import com.ramapay.app.web3.entity.WalletAddEthereumChainObject;
import com.ramapay.app.web3.entity.Web3Call;
import com.ramapay.app.web3.entity.Web3Transaction;
import com.ramapay.app.widget.AWalletAlertDialog;
import com.ramapay.app.widget.ActionSheet;
import com.ramapay.app.widget.ActionSheetDialog;
import com.ramapay.app.widget.ActionSheetSignDialog;
import com.ramapay.hardware.SignatureFromKey;
import com.ramapay.token.entity.EthereumMessage;
import com.ramapay.token.entity.EthereumTypedMessage;
import com.ramapay.token.entity.SignMessageType;
import com.ramapay.token.entity.Signable;

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
    
    // Camera permission handling
    public static final int REQUEST_CAMERA_ACCESS = 112;
    private PermissionRequest permissionRequest;
    
    // Save WebView state to prevent reloading on tab switch
    private Bundle webViewState;
    private boolean hasLoadedOnce = false;
    
    // JavaScript bridge for native features (file attachments, crypto payments)
    private com.ramapay.app.chat.ui.ChatBridge chatBridge;
    
    // Keys for persistent storage
    private static final String PREF_NAME = "chat_prefs";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_HAS_LOADED = "has_loaded";

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
        
        // Fix: Only enable pull-to-refresh when WebView is scrolled to the top
        // This prevents accidental page refresh when scrolling down within the page
        if (web3 != null)
        {
            web3.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (swipeRefreshLayout != null)
                {
                    // Only enable swipe refresh when at the top of the page
                    swipeRefreshLayout.setEnabled(scrollY == 0);
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
        
        // Force Ramestta network for MumbleChat to ensure consistent RPC (blockchain.ramestta.com)
        NetworkInfo ramesttaNetwork = viewModel.getNetworkInfo(RAMESTTA_MAINNET_ID);
        if (ramesttaNetwork != null)
        {
            activeNetwork = ramesttaNetwork;
            Timber.d("ChatFragment: Forcing Ramestta network for MumbleChat");
        }
        else
        {
            activeNetwork = viewModel.getActiveNetwork();
        }
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
        // MumbleChat always uses Ramestta network, ignore other network changes
        // This ensures consistent RPC (blockchain.ramestta.com) for XMTP signing
        if (activeNetwork != null && activeNetwork.chainId == RAMESTTA_MAINNET_ID)
        {
            Timber.d("ChatFragment: Ignoring network change, keeping Ramestta for MumbleChat");
            return;
        }
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
        webSettings.setUserAgentString(webSettings.getUserAgentString() + " RamaPay(Platform=Android)");

        // Initialize JavaScript bridge for native features (file attachments, crypto payments)
        chatBridge = new com.ramapay.app.chat.ui.ChatBridge(this, web3, wallet, viewModel);
        web3.addJavascriptInterface(chatBridge, "RamaPayBridge");
        Timber.d("ChatBridge initialized and injected into WebView");

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
            
            @Override
            public void onPermissionRequest(final PermissionRequest request)
            {
                requestCameraPermission(request);
            }
        });

        web3.setWebViewClient(new WebViewClient()
        {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request)
            {
                return false;
            }
            
            @Override
            public void onPageFinished(WebView view, String url)
            {
                super.onPageFinished(view, url);
                // Inject JavaScript to fix RPC URL - blockchain2 has CORS issues, use blockchain instead
                // This intercepts fetch/XMLHttpRequest to replace blockchain2 with blockchain
                String fixRpcScript = 
                    "(function() {" +
                    "  const originalFetch = window.fetch;" +
                    "  window.fetch = function(url, options) {" +
                    "    if (typeof url === 'string' && url.includes('blockchain2.ramestta.com')) {" +
                    "      url = url.replace('blockchain2.ramestta.com', 'blockchain.ramestta.com');" +
                    "      console.log('RamaPay: Fixed RPC URL to blockchain.ramestta.com');" +
                    "    }" +
                    "    return originalFetch.call(this, url, options);" +
                    "  };" +
                    "  const originalXHROpen = XMLHttpRequest.prototype.open;" +
                    "  XMLHttpRequest.prototype.open = function(method, url) {" +
                    "    if (typeof url === 'string' && url.includes('blockchain2.ramestta.com')) {" +
                    "      url = url.replace('blockchain2.ramestta.com', 'blockchain.ramestta.com');" +
                    "      console.log('RamaPay: Fixed XHR URL to blockchain.ramestta.com');" +
                    "    }" +
                    "    return originalXHROpen.apply(this, [method, url, ...Array.from(arguments).slice(2)]);" +
                    "  };" +
                    "  console.log('RamaPay: RPC URL fix injected');" +
                    "})();";
                view.evaluateJavascript(fixRpcScript, null);
                
                // FIX: Inject CSS to make chat input text visible (change from white to dark)
                String fixInputColorCSS = 
                    "(function() {" +
                    "  const style = document.createElement('style');" +
                    "  style.innerHTML = '" +
                    "    input, textarea, [contenteditable=\"true\"] { " +
                    "      color: #1a1a1a !important; " +
                    "      -webkit-text-fill-color: #1a1a1a !important; " +
                    "    } " +
                    "    input::placeholder, textarea::placeholder { " +
                    "      color: #666 !important; " +
                    "      -webkit-text-fill-color: #666 !important; " +
                    "    } " +
                    "    /* Dark mode support */ " +
                    "    @media (prefers-color-scheme: dark) { " +
                    "      input, textarea, [contenteditable=\"true\"] { " +
                    "        color: #ffffff !important; " +
                    "        -webkit-text-fill-color: #ffffff !important; " +
                    "      } " +
                    "    }';" +
                    "  document.head.appendChild(style);" +
                    "  console.log('RamaPay: Fixed input text color');" +
                    "})();";
                view.evaluateJavascript(fixInputColorCSS, null);
                
                // Inject RamaPay Bridge API for file attachments and crypto payments
                String bridgeAPI = 
                    "(function() {" +
                    "  if (typeof window.RamaPayBridge === 'undefined') {" +
                    "    console.warn('RamaPayBridge not available');" +
                    "    return;" +
                    "  }" +
                    "  window.RamaPay = {" +
                    "    getWalletAddress: () => RamaPayBridge.getWalletAddress()," +
                    "    getBalances: () => JSON.parse(RamaPayBridge.getWalletBalances())," +
                    "    pickFile: (callback) => {" +
                    "      const callbackId = 'file_' + Date.now();" +
                    "      window.onFileSelected = (id, result) => {" +
                    "        if (id === callbackId && callback) callback(result);" +
                    "      };" +
                    "      RamaPayBridge.pickFile(callbackId);" +
                    "    }," +
                    "    pickImage: (callback) => {" +
                    "      const callbackId = 'img_' + Date.now();" +
                    "      window.onFileSelected = (id, result) => {" +
                    "        if (id === callbackId && callback) callback(result);" +
                    "      };" +
                    "      RamaPayBridge.pickImage(callbackId);" +
                    "    }," +
                    "    sendPayment: (recipient, token, amount) => {" +
                    "      RamaPayBridge.sendCryptoPayment(recipient, token, amount);" +
                    "    }," +
                    "    showReceipt: (txHash, details) => {" +
                    "      RamaPayBridge.showTransactionReceipt(txHash, JSON.stringify(details));" +
                    "    }," +
                    "    capabilities: () => JSON.parse(RamaPayBridge.getCapabilities())" +
                    "  };" +
                    "  console.log('RamaPay Bridge API initialized:', window.RamaPay.capabilities());" +
                    "  // Notify web app that native features are available" +
                    "  window.dispatchEvent(new CustomEvent('RamaPayReady', {" +
                    "    detail: window.RamaPay.capabilities()" +
                    "  }));" +
                    "})();";
                view.evaluateJavascript(bridgeAPI, null);
                
                Timber.d("Injected RPC fix, input color fix, and RamaPay Bridge API");
            }
            
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl)
            {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Timber.e("WebView error: %d - %s for URL: %s", errorCode, description, failingUrl);
                
                // If the error is for our main URL, show a friendly error page with retry option
                if (failingUrl != null && failingUrl.contains("mumblechat.com"))
                {
                    showErrorPage(view, description);
                }
            }
            
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error)
            {
                super.onReceivedError(view, request, error);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                {
                    String url = request.getUrl().toString();
                    Timber.e("WebView error: %d - %s for URL: %s", error.getErrorCode(), error.getDescription(), url);
                    
                    // Only show error page for main frame requests to our chat URL
                    if (request.isForMainFrame() && url.contains("mumblechat.com"))
                    {
                        showErrorPage(view, error.getDescription().toString());
                    }
                }
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

        // Restore WebView state if available, otherwise load from saved URL or default
        if (webViewState != null)
        {
            web3.restoreState(webViewState);
            hasLoadedOnce = true;
        }
        else if (!hasLoadedOnce)
        {
            // Try to load saved URL from persistent storage
            String savedUrl = getSavedUrl();
            if (savedUrl != null && !savedUrl.isEmpty())
            {
                web3.loadUrl(savedUrl);
            }
            else
            {
                web3.loadUrl(MUMBLECHAT_URL);
            }
            hasLoadedOnce = true;
        }
    }
    
    private String getSavedUrl()
    {
        if (getContext() == null) return null;
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_URL, null);
    }
    
    private void saveCurrentUrl()
    {
        if (getContext() == null || web3 == null) return;
        String currentUrl = web3.getUrl();
        if (currentUrl != null && currentUrl.contains("mumblechat.com"))
        {
            SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_LAST_URL, currentUrl).apply();
        }
    }
    
    private void showErrorPage(WebView view, String errorDescription)
    {
        String errorHtml = "<html><head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "body { font-family: sans-serif; background-color: #141414; color: #ffffff; " +
            "display: flex; flex-direction: column; align-items: center; justify-content: center; " +
            "height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; text-align: center; }" +
            "h2 { color: #B8860B; margin-bottom: 10px; }" +
            "p { color: #999999; margin-bottom: 20px; }" +
            ".btn { background-color: #B8860B; color: #141414; border: none; padding: 15px 40px; " +
            "border-radius: 25px; font-size: 16px; font-weight: bold; cursor: pointer; " +
            "text-decoration: none; display: inline-block; margin: 10px; }" +
            ".btn:hover { background-color: #996515; }" +
            ".error-icon { font-size: 60px; margin-bottom: 20px; }" +
            "</style></head><body>" +
            "<div class='error-icon'>⚠️</div>" +
            "<h2>Connection Error</h2>" +
            "<p>Unable to connect to MumbleChat.<br/>Please check your internet connection and try again.</p>" +
            "<p style='font-size: 12px; color: #666;'>" + errorDescription + "</p>" +
            "<a class='btn' href='" + MUMBLECHAT_URL + "'>Retry</a>" +
            "</body></html>";
        
        view.loadData(android.util.Base64.encodeToString(errorHtml.getBytes(), android.util.Base64.NO_PADDING), 
            "text/html; charset=utf-8", "base64");
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
            // Save WebView state when pausing
            webViewState = new Bundle();
            web3.saveState(webViewState);
            // Save current URL to persistent storage
            saveCurrentUrl();
        }
    }
    
    @Override
    public void onDestroyView()
    {
        // Save WebView state and URL before view is destroyed
        if (web3 != null)
        {
            webViewState = new Bundle();
            web3.saveState(webViewState);
            saveCurrentUrl();
        }
        // Reset the setup flag so WebView can be reconfigured when view is recreated
        isWebViewSetup = false;
        super.onDestroyView();
    }

    @Override
    public void onDestroy()
    {
        // Save URL before destroying
        saveCurrentUrl();
        
        // Only destroy WebView when fragment is actually being destroyed (not just view)
        if (web3 != null && getActivity() != null && getActivity().isFinishing())
        {
            web3.destroy();
            webViewState = null;
            hasLoadedOnce = false;
        }
        super.onDestroy();
    }
    
    // Camera permission handling for MumbleChat QR scanning
    private void requestCameraPermission(@NotNull PermissionRequest request)
    {
        final String[] requestedResources = request.getResources();
        permissionRequest = request;
        for (String r : requestedResources)
        {
            if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            {
                final String[] permissions = new String[]{Manifest.permission.CAMERA};
                requireActivity().requestPermissions(permissions, REQUEST_CAMERA_ACCESS);
                return;
            }
        }
        // If no video capture requested, deny the request
        request.deny();
    }
    
    @Override
    public void gotCameraAccess(@NotNull String[] permissions, int[] grantResults)
    {
        boolean cameraAccess = false;
        for (int i = 0; i < permissions.length; i++)
        {
            if (permissions[i].equals(Manifest.permission.CAMERA) && grantResults[i] != -1)
            {
                cameraAccess = true;
                if (permissionRequest != null)
                {
                    permissionRequest.grant(permissionRequest.getResources());
                }
                break;
            }
        }
        if (!cameraAccess)
        {
            if (permissionRequest != null)
            {
                permissionRequest.deny();
            }
            Toast.makeText(getContext(), "Camera permission not granted", Toast.LENGTH_SHORT).show();
        }
        permissionRequest = null;
    }
}