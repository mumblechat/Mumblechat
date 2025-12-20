package com.ramapay.app.viewmodel;

import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ramapay.app.entity.ContractType;
import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.entity.tokens.TokenInfo;
import com.ramapay.app.interact.FetchTransactionsInteract;
import com.ramapay.app.repository.EthereumNetworkRepositoryType;
import com.ramapay.app.service.TokensService;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

/**
 * ViewModel for Token Listing Request with auto-detection of network and token details
 */
@HiltViewModel
public class TokenListingViewModel extends BaseViewModel
{
    // Formspree endpoint for token listing requests
    private static final String FORMSPREE_ENDPOINT = "https://formspree.io/f/xykgzdqa";
    private static final MediaType FORM_URLENCODED = MediaType.parse("application/x-www-form-urlencoded");
    
    private final MutableLiveData<TokenInfo> detectedToken = new MutableLiveData<>();
    private final MutableLiveData<NetworkInfo> detectedNetwork = new MutableLiveData<>();
    private final MutableLiveData<Integer> scanProgress = new MutableLiveData<>();
    private final MutableLiveData<Boolean> scanComplete = new MutableLiveData<>();
    private final MutableLiveData<Boolean> noContractFound = new MutableLiveData<>();
    private final MutableLiveData<Boolean> submitSuccess = new MutableLiveData<>();
    private final MutableLiveData<String> submitError = new MutableLiveData<>();

    private final EthereumNetworkRepositoryType ethereumNetworkRepository;
    private final FetchTransactionsInteract fetchTransactionsInteract;
    private final TokensService tokensService;
    private final OkHttpClient httpClient;

    private final List<Disposable> scanThreads = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int networkCount;
    private int scannedCount;
    private boolean tokenFound;
    private TokenInfo foundTokenInfo;
    private NetworkInfo foundNetwork;

    public LiveData<TokenInfo> detectedToken() { return detectedToken; }
    public LiveData<NetworkInfo> detectedNetwork() { return detectedNetwork; }
    public LiveData<Integer> scanProgress() { return scanProgress; }
    public LiveData<Boolean> scanComplete() { return scanComplete; }
    public LiveData<Boolean> noContractFound() { return noContractFound; }
    public LiveData<Boolean> submitSuccess() { return submitSuccess; }
    public LiveData<String> submitError() { return submitError; }

    @Inject
    TokenListingViewModel(
            EthereumNetworkRepositoryType ethereumNetworkRepository,
            FetchTransactionsInteract fetchTransactionsInteract,
            TokensService tokensService,
            OkHttpClient httpClient)
    {
        this.ethereumNetworkRepository = ethereumNetworkRepository;
        this.fetchTransactionsInteract = fetchTransactionsInteract;
        this.tokensService = tokensService;
        this.httpClient = httpClient;
    }

    /**
     * Get list of all available networks
     */
    public NetworkInfo[] getAvailableNetworks()
    {
        return ethereumNetworkRepository.getAvailableNetworkList();
    }

    /**
     * Get network info by chain ID
     */
    public NetworkInfo getNetworkByChainId(long chainId)
    {
        return ethereumNetworkRepository.getNetworkByChain(chainId);
    }

    /**
     * Scan all networks to find the token contract
     */
    public void scanAllNetworks(String contractAddress)
    {
        stopScan();
        tokenFound = false;
        foundTokenInfo = null;
        foundNetwork = null;
        scannedCount = 0;

        NetworkInfo[] networks = ethereumNetworkRepository.getAvailableNetworkList();
        networkCount = networks.length;
        scanProgress.postValue(0);

        for (NetworkInfo network : networks)
        {
            TokenInfo tokenInfo = new TokenInfo(contractAddress, "", "", 0, true, network.chainId);
            Disposable d = fetchTransactionsInteract.queryInterfaceSpec(tokenInfo)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            type -> onNetworkResult(tokenInfo, type, network),
                            error -> onNetworkError(network)
                    );
            scanThreads.add(d);
        }

        // Timeout after 60 seconds
        handler.postDelayed(this::stopScan, 60 * DateUtils.SECOND_IN_MILLIS);
    }

    /**
     * Scan a specific network for the token contract
     */
    public void scanNetwork(String contractAddress, long chainId)
    {
        stopScan();
        tokenFound = false;
        foundTokenInfo = null;
        foundNetwork = null;
        scannedCount = 0;
        networkCount = 1;
        scanProgress.postValue(0);

        NetworkInfo network = ethereumNetworkRepository.getNetworkByChain(chainId);
        if (network == null)
        {
            noContractFound.postValue(true);
            return;
        }

        TokenInfo tokenInfo = new TokenInfo(contractAddress, "", "", 0, true, chainId);
        Disposable d = fetchTransactionsInteract.queryInterfaceSpec(tokenInfo)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        type -> onNetworkResult(tokenInfo, type, network),
                        error -> onNetworkError(network)
                );
        scanThreads.add(d);

        // Timeout after 30 seconds for single network
        handler.postDelayed(this::stopScan, 30 * DateUtils.SECOND_IN_MILLIS);
    }

    private void onNetworkResult(TokenInfo info, ContractType type, NetworkInfo network)
    {
        scannedCount++;
        updateProgress();

        if (type != ContractType.OTHER && !tokenFound)
        {
            tokenFound = true;
            foundNetwork = network;

            // Fetch full token details
            tokensService.update(info.address, info.chainId, type)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            tokenInfo -> {
                                foundTokenInfo = tokenInfo;
                                detectedToken.postValue(tokenInfo);
                                detectedNetwork.postValue(network);
                                stopScan();
                            },
                            error -> {
                                // Even if details fetch fails, we found the network
                                foundTokenInfo = info;
                                detectedToken.postValue(info);
                                detectedNetwork.postValue(network);
                                stopScan();
                            }
                    );
        }

        checkScanComplete();
    }

    private void onNetworkError(NetworkInfo network)
    {
        scannedCount++;
        updateProgress();
        checkScanComplete();
    }

    private void updateProgress()
    {
        int progress = (int) ((scannedCount * 100.0f) / networkCount);
        scanProgress.postValue(progress);
    }

    private void checkScanComplete()
    {
        if (scannedCount >= networkCount)
        {
            scanComplete.postValue(true);
            if (!tokenFound)
            {
                noContractFound.postValue(true);
            }
        }
    }

    public void stopScan()
    {
        handler.removeCallbacksAndMessages(null);
        for (Disposable d : scanThreads)
        {
            if (d != null && !d.isDisposed())
            {
                d.dispose();
            }
        }
        scanThreads.clear();
        scanProgress.postValue(100);
    }

    /**
     * Submit token listing request via Formspree
     */
    public void submitTokenListingRequest(
            String contractAddress,
            long chainId,
            String tokenName,
            String tokenSymbol,
            int decimals,
            String iconUrl,
            String website,
            String contactEmail,
            String notes)
    {
        new Thread(() -> {
            try
            {
                NetworkInfo network = ethereumNetworkRepository.getNetworkByChain(chainId);
                String networkName = network != null ? network.name : String.valueOf(chainId);

                // Build form message for Formspree
                StringBuilder message = new StringBuilder();
                message.append("=== TOKEN LISTING REQUEST ===\n\n");
                message.append("Contract Address: ").append(contractAddress).append("\n");
                message.append("Network: ").append(networkName).append(" (Chain ID: ").append(chainId).append(")\n");
                message.append("Token Name: ").append(tokenName).append("\n");
                message.append("Token Symbol: ").append(tokenSymbol).append("\n");
                message.append("Decimals: ").append(decimals).append("\n");
                message.append("Icon URL: ").append(iconUrl).append("\n");
                message.append("Website: ").append(website).append("\n");
                message.append("Contact Email: ").append(contactEmail).append("\n");
                if (notes != null && !notes.isEmpty())
                {
                    message.append("Notes: ").append(notes).append("\n");
                }
                message.append("\nSubmitted from RamaPay Android App");

                // Build form data for Formspree
                String formData = "email=" + urlEncode(contactEmail) 
                        + "&_subject=" + urlEncode("Token Listing Request: " + tokenSymbol + " on " + networkName)
                        + "&message=" + urlEncode(message.toString());

                RequestBody body = RequestBody.create(formData, FORM_URLENCODED);
                Request request = new Request.Builder()
                        .url(FORMSPREE_ENDPOINT)
                        .post(body)
                        .addHeader("Accept", "application/json")
                        .build();

                Response response = httpClient.newCall(request).execute();

                if (response.isSuccessful())
                {
                    submitSuccess.postValue(true);
                    Timber.d("Token listing request sent successfully for: %s", tokenSymbol);
                }
                else
                {
                    String errorMsg = response.body() != null ? response.body().string() : "Unknown error";
                    Timber.e("Token listing submission failed: %s", errorMsg);
                    submitError.postValue("Submission failed: " + response.code());
                }
                response.close();
            }
            catch (Exception e)
            {
                Timber.e(e, "Failed to submit token listing request");
                submitError.postValue("Failed to submit: " + e.getMessage());
            }
        }).start();
    }

    private String urlEncode(String input)
    {
        if (input == null) return "";
        try
        {
            return java.net.URLEncoder.encode(input, "UTF-8");
        }
        catch (Exception e)
        {
            return input;
        }
    }

    private String escapeJson(String input)
    {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    protected void onCleared()
    {
        super.onCleared();
        stopScan();
    }
}
