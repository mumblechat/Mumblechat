package com.ramapay.app.viewmodel;

import android.text.TextUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ramapay.app.entity.NetworkInfo;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.entity.tokendata.TokenTicker;
import com.ramapay.app.interact.GenericWalletInteract;
import com.ramapay.app.repository.CurrencyRepositoryType;
import com.ramapay.app.repository.EthereumNetworkRepositoryType;
import com.ramapay.app.repository.PreferenceRepositoryType;
import com.ramapay.app.repository.TokenRepository;
import com.ramapay.app.repository.entity.RealmPosInvoice;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import com.ramapay.app.service.RealmManager;
import com.ramapay.app.service.TickerService;
import com.ramapay.app.service.TokensService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;
import timber.log.Timber;

/**
 * ViewModel for Point of Sale functionality
 * Handles invoice creation, fiat-to-crypto conversion, and payment monitoring
 */
@HiltViewModel
public class PosViewModel extends BaseViewModel
{
    private final GenericWalletInteract genericWalletInteract;
    private final TokensService tokensService;
    private final TickerService tickerService;
    private final EthereumNetworkRepositoryType ethereumNetworkRepository;
    private final CurrencyRepositoryType currencyRepository;
    private final PreferenceRepositoryType preferenceRepository;
    private final RealmManager realmManager;

    private final MutableLiveData<Wallet> defaultWallet = new MutableLiveData<>();
    private final MutableLiveData<List<Token>> tokens = new MutableLiveData<>();
    private final MutableLiveData<BigDecimal> cryptoAmount = new MutableLiveData<>();
    private final MutableLiveData<Double> exchangeRate = new MutableLiveData<>();
    private final MutableLiveData<RealmPosInvoice> currentInvoice = new MutableLiveData<>();
    private final MutableLiveData<RealmPosInvoice> paymentReceived = new MutableLiveData<>();
    private final MutableLiveData<List<RealmPosInvoice>> invoiceHistory = new MutableLiveData<>();
    private final MutableLiveData<Long> remainingTime = new MutableLiveData<>();
    private final MutableLiveData<Boolean> paymentTimeout = new MutableLiveData<>();

    private Token selectedToken;
    private String selectedFiatCurrency = "INR";
    private Disposable paymentMonitorDisposable;
    private Disposable timerDisposable;
    private BigDecimal expectedCryptoAmount;
    private BigDecimal initialBalance; // Balance when monitoring started
    private BigDecimal lastKnownBalance; // Last checked balance for detecting incremental changes
    private long monitoringStartBlock; // Block number when monitoring started
    private long paymentStartTime;
    
    private static final String RAMASCAN_API_BASE = "https://latest-backendapi.ramascan.com/api/v2";
    
    // Payment monitoring constants
    private static final long PAYMENT_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    private static final long CHECK_INTERVAL_SECONDS = 10; // Check every 10 seconds

    // Supported fiat currencies
    public static final String[] SUPPORTED_CURRENCIES = {"INR", "USD", "EUR", "GBP", "AED", "SGD"};

    @Inject
    public PosViewModel(
            GenericWalletInteract genericWalletInteract,
            TokensService tokensService,
            TickerService tickerService,
            EthereumNetworkRepositoryType ethereumNetworkRepository,
            CurrencyRepositoryType currencyRepository,
            PreferenceRepositoryType preferenceRepository,
            RealmManager realmManager)
    {
        this.genericWalletInteract = genericWalletInteract;
        this.tokensService = tokensService;
        this.tickerService = tickerService;
        this.ethereumNetworkRepository = ethereumNetworkRepository;
        this.currencyRepository = currencyRepository;
        this.preferenceRepository = preferenceRepository;
        this.realmManager = realmManager;

        // Set default currency from preferences
        this.selectedFiatCurrency = currencyRepository.getDefaultCurrency();
    }

    public void prepare()
    {
        disposable = genericWalletInteract.find()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onDefaultWallet, this::onError);
    }

    private void onDefaultWallet(Wallet wallet)
    {
        defaultWallet.postValue(wallet);
        loadTokens(wallet);
    }

    private void loadTokens(Wallet wallet)
    {
        // Get tokens that can be used for payment (native + ERC20)
        List<Token> payableTokens = new ArrayList<>();
        
        // Add native RAMA token from enabled networks
        for (NetworkInfo network : ethereumNetworkRepository.getAvailableNetworkList())
        {
            Token baseToken = tokensService.getToken(network.chainId, wallet.address);
            if (baseToken != null)
            {
                payableTokens.add(baseToken);
            }
        }
        
        // Add ERC20 tokens (TODO: filter by enabled tokens)
        tokens.postValue(payableTokens);
        
        // Set first token as default
        if (!payableTokens.isEmpty())
        {
            selectedToken = payableTokens.get(0);
            fetchExchangeRate();
        }
    }

    public void setSelectedToken(Token token)
    {
        this.selectedToken = token;
        fetchExchangeRate();
    }

    public void setSelectedCurrency(String currency)
    {
        this.selectedFiatCurrency = currency;
        fetchExchangeRate();
    }

    public String getSelectedCurrency()
    {
        return selectedFiatCurrency;
    }

    private void fetchExchangeRate()
    {
        if (selectedToken == null) return;

        // Try to get token price from TokensService 
        TokenTicker ticker = tokensService.getTokenTicker(selectedToken);
        
        if (ticker != null && ticker.price != null)
        {
            try
            {
                double tokenPriceInUserCurrency = Double.parseDouble(ticker.price);
                // ticker.price is already in user's default currency, need to convert if different
                String defaultCurrency = currencyRepository.getDefaultCurrency();
                if (defaultCurrency.equals(selectedFiatCurrency))
                {
                    exchangeRate.postValue(tokenPriceInUserCurrency);
                }
                else
                {
                    // Use approximate cross-rate conversion
                    double rate = convertCurrency(tokenPriceInUserCurrency, defaultCurrency, selectedFiatCurrency);
                    exchangeRate.postValue(rate);
                }
            }
            catch (NumberFormatException e)
            {
                exchangeRate.postValue(getDefaultExchangeRate());
            }
        }
        else
        {
            // Fallback: Use a default rate (this should be fetched from an API)
            double defaultRate = getDefaultExchangeRate();
            exchangeRate.postValue(defaultRate);
        }
    }

    private double convertCurrency(double amount, String fromCurrency, String toCurrency)
    {
        // Approximate USD base rates for currency conversion
        double fromUsd = getUsdRate(fromCurrency);
        double toUsd = getUsdRate(toCurrency);
        return amount * (toUsd / fromUsd);
    }

    private double getUsdRate(String currency)
    {
        switch (currency)
        {
            case "USD": return 1.0;
            case "INR": return 83.0;
            case "EUR": return 0.92;
            case "GBP": return 0.79;
            case "AED": return 3.67;
            case "SGD": return 1.35;
            case "JPY": return 150.0;
            case "CNY": return 7.2;
            case "AUD": return 1.5;
            case "CAD": return 1.36;
            default: return 1.0;
        }
    }

    private double getDefaultExchangeRate()
    {
        // Default approximate rates for RAMA (should be fetched from API in production)
        switch (selectedFiatCurrency)
        {
            case "INR":
                return 50.0; // 1 RAMA ≈ ₹50
            case "USD":
                return 0.60; // 1 RAMA ≈ $0.60
            case "EUR":
                return 0.55;
            case "GBP":
                return 0.47;
            case "AED":
                return 2.20;
            case "SGD":
                return 0.80;
            default:
                return 1.0;
        }
    }

    public BigDecimal convertFiatToCrypto(BigDecimal fiatAmount)
    {
        Double rate = exchangeRate.getValue();
        if (rate == null || rate <= 0 || fiatAmount == null || fiatAmount.compareTo(BigDecimal.ZERO) <= 0)
        {
            return BigDecimal.ZERO;
        }

        // cryptoAmount = fiatAmount / rate
        return fiatAmount.divide(BigDecimal.valueOf(rate), 8, RoundingMode.HALF_UP);
    }

    public void calculateCryptoAmount(String fiatAmountStr)
    {
        try
        {
            if (TextUtils.isEmpty(fiatAmountStr))
            {
                cryptoAmount.postValue(BigDecimal.ZERO);
                return;
            }

            BigDecimal fiatAmount = new BigDecimal(fiatAmountStr);
            BigDecimal crypto = convertFiatToCrypto(fiatAmount);
            cryptoAmount.postValue(crypto);
        }
        catch (NumberFormatException e)
        {
            cryptoAmount.postValue(BigDecimal.ZERO);
        }
    }

    public RealmPosInvoice createInvoice(BigDecimal fiatAmount, BigDecimal cryptoAmountValue, 
            String category, String remark)
    {
        if (selectedToken == null || defaultWallet.getValue() == null) return null;

        String walletAddress = defaultWallet.getValue().address;
        long timestamp = System.currentTimeMillis();
        String nonce = generateNonce();

        // Create invoice ID: first 8 chars of address + timestamp + nonce
        String invoiceId = walletAddress.substring(2, 10).toUpperCase(Locale.ROOT) 
                + "-" + timestamp 
                + "-" + nonce;

        // Create invoice hash (first 16 chars of keccak256 for TX data field)
        String invoiceHash = generateInvoiceHash(invoiceId, cryptoAmountValue);

        RealmPosInvoice invoice = new RealmPosInvoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setInvoiceHash(invoiceHash);
        invoice.setMerchantAddress(walletAddress);
        
        // Crypto details
        BigInteger weiAmount = cryptoAmountValue.multiply(BigDecimal.TEN.pow(selectedToken.tokenInfo.decimals))
                .toBigInteger();
        invoice.setCryptoAmount(weiAmount.toString());
        invoice.setTokenDecimals(selectedToken.tokenInfo.decimals);
        invoice.setTokenAddress(selectedToken.isEthereum() ? "" : selectedToken.getAddress());
        invoice.setTokenSymbol(selectedToken.getSymbol());
        invoice.setChainId(selectedToken.tokenInfo.chainId);
        
        // Fiat details
        invoice.setFiatAmount(fiatAmount.toPlainString());
        invoice.setFiatCurrency(selectedFiatCurrency);
        Double rate = exchangeRate.getValue();
        invoice.setExchangeRate(rate != null ? rate : 0);
        
        // Category and remark
        invoice.setCategory(category);
        invoice.setNote(remark);
        
        // Status
        invoice.setStatus(RealmPosInvoice.STATUS_PENDING);
        invoice.setCreatedAt(timestamp);
        invoice.setExpiresAt(timestamp + (30 * 60 * 1000)); // 30 minutes expiry
        
        // Save to Realm
        saveInvoice(invoice);
        currentInvoice.postValue(invoice);
        
        // Store expected amount for payment monitoring
        expectedCryptoAmount = cryptoAmountValue;

        return invoice;
    }

    /**
     * Start monitoring for incoming payment
     * Checks every 10 seconds for 5 minutes
     */
    public void startPaymentMonitoring(RealmPosInvoice invoice)
    {
        stopPaymentMonitoring();
        
        // Set expected amount from invoice if not already set
        if (expectedCryptoAmount == null || expectedCryptoAmount.compareTo(BigDecimal.ZERO) == 0)
        {
            int decimals = invoice.getTokenDecimals();
            expectedCryptoAmount = new BigDecimal(invoice.getCryptoAmount())
                    .divide(BigDecimal.TEN.pow(decimals), decimals, RoundingMode.DOWN);
        }
        
        paymentStartTime = System.currentTimeMillis();
        paymentTimeout.postValue(false);
        remainingTime.postValue(PAYMENT_TIMEOUT_MS);
        
        Timber.d("Starting payment monitoring for invoice: %s", invoice.getInvoiceId());
        
        // Fetch starting block number and initial balance (for Ramascan API filtering)
        io.reactivex.Single.fromCallable(() -> {
            // Get current block number
            try {
                long chainId = selectedToken.tokenInfo.chainId;
                Web3j web3j = TokenRepository.getWeb3jService(chainId);
                monitoringStartBlock = web3j.ethBlockNumber().send().getBlockNumber().longValue();
                Timber.d("Monitoring start block: %d", monitoringStartBlock);
            } catch (Exception e) {
                Timber.e(e, "Error fetching block number");
                monitoringStartBlock = 0;
            }
            return fetchCurrentBalance();
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(balance -> {
                    initialBalance = balance;
                    lastKnownBalance = balance; // Initialize lastKnownBalance for incremental tracking
                    Timber.d("Initial balance: %s, Start block: %d", 
                            initialBalance != null ? initialBalance.toPlainString() : "null",
                            monitoringStartBlock);
                }, error -> {
                    Timber.e(error, "Error fetching initial balance");
                    initialBalance = BigDecimal.ZERO;
                    lastKnownBalance = BigDecimal.ZERO;
                });
        
        // Timer that updates every second for countdown display
        timerDisposable = Observable.interval(1, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    long elapsed = System.currentTimeMillis() - paymentStartTime;
                    long remaining = PAYMENT_TIMEOUT_MS - elapsed;
                    
                    if (remaining <= 0)
                    {
                        // Timeout reached
                        remainingTime.postValue(0L);
                        paymentTimeout.postValue(true);
                        stopPaymentMonitoring();
                        Timber.d("Payment timeout for invoice: %s", invoice.getInvoiceId());
                    }
                    else
                    {
                        remainingTime.postValue(remaining);
                    }
                }, Timber::e);
        
        // Check for payment every 10 seconds (on IO thread)
        paymentMonitorDisposable = Observable.interval(0, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io()) // Stay on IO thread for RPC call
                .flatMapSingle(tick -> io.reactivex.Single.fromCallable(() -> {
                    checkForPaymentAsync(invoice);
                    return tick;
                }))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> {}, Timber::e);
    }

    /**
     * Check if payment has been received using Ramascan API coin-balance-history
     * This gives us exact transaction deltas instead of comparing balances
     * Called on IO thread
     */
    private void checkForPaymentAsync(RealmPosInvoice invoice)
    {
        if (selectedToken == null || defaultWallet.getValue() == null) return;
        
        Timber.d("Checking for payment via Ramascan API... (tick)");
        
        String walletAddress = defaultWallet.getValue().address;
        
        // Convert expected amount to wei for comparison
        BigInteger expectedWei = expectedCryptoAmount
                .multiply(BigDecimal.TEN.pow(18))
                .toBigInteger();
        
        // 1% tolerance
        BigInteger toleranceWei = expectedWei.divide(BigInteger.valueOf(100));
        BigInteger minAcceptableWei = expectedWei.subtract(toleranceWei);
        BigInteger maxAcceptableWei = expectedWei.add(toleranceWei);
        
        Timber.d("Expected: %s wei, Min: %s, Max: %s", 
                expectedWei.toString(), minAcceptableWei.toString(), maxAcceptableWei.toString());
        
        try
        {
            // Call Ramascan API for coin balance history
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            
            String url = RAMASCAN_API_BASE + "/addresses/" + walletAddress + "/coin-balance-history";
            Request request = new Request.Builder()
                    .url(url)
                    .header("accept", "application/json")
                    .get()
                    .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null)
            {
                String responseBody = response.body().string();
                JSONObject json = new JSONObject(responseBody);
                JSONArray items = json.optJSONArray("items");
                
                if (items != null && items.length() > 0)
                {
                    // Check recent transactions (after monitoring started)
                    for (int i = 0; i < items.length(); i++)
                    {
                        JSONObject item = items.getJSONObject(i);
                        long blockNumber = item.optLong("block_number", 0);
                        String deltaStr = item.optString("delta", "0");
                        String txHash = item.optString("transaction_hash", "");
                        
                        // Only check transactions after monitoring started
                        if (blockNumber <= monitoringStartBlock)
                        {
                            Timber.d("Skipping old transaction at block %d (started at %d)", 
                                    blockNumber, monitoringStartBlock);
                            continue;
                        }
                        
                        // Parse delta (can be negative for outgoing)
                        BigInteger delta = new BigInteger(deltaStr);
                        
                        // Only check incoming transactions (positive delta)
                        if (delta.compareTo(BigInteger.ZERO) > 0)
                        {
                            Timber.d("Found incoming tx: block=%d, delta=%s wei, hash=%s", 
                                    blockNumber, delta.toString(), txHash);
                            
                            // Check if this delta matches expected amount within ±1%
                            if (delta.compareTo(minAcceptableWei) >= 0 && delta.compareTo(maxAcceptableWei) <= 0)
                            {
                                // Payment received with correct amount!
                                BigDecimal receivedAmount = new BigDecimal(delta)
                                        .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);
                                
                                Timber.d("Payment received! Amount: %s RAMA (expected: %s), TX: %s", 
                                        receivedAmount.toPlainString(), 
                                        expectedCryptoAmount.toPlainString(),
                                        txHash);
                                
                                // Store tx hash in invoice
                                invoice.setTxHash(txHash);
                                
                                onPaymentDetected(invoice, receivedAmount);
                                return;
                            }
                            else
                            {
                                Timber.d("Different amount: %s wei (expected: %s ±1%%) - ignoring", 
                                        delta.toString(), expectedWei.toString());
                            }
                        }
                    }
                }
                
                Timber.d("No matching payment found yet");
            }
            else
            {
                Timber.w("Ramascan API error: %d", response.code());
                // Fallback to RPC balance check if API fails
                checkForPaymentViaRpc(invoice);
            }
        }
        catch (Exception e)
        {
            Timber.e(e, "Error checking Ramascan API");
            // Fallback to RPC balance check
            checkForPaymentViaRpc(invoice);
        }
    }
    
    /**
     * Fallback: Check payment via RPC balance comparison
     */
    private void checkForPaymentViaRpc(RealmPosInvoice invoice)
    {
        if (lastKnownBalance == null) return;
        
        BigDecimal currentBalance = fetchCurrentBalance();
        
        if (currentBalance != null && currentBalance.compareTo(BigDecimal.ZERO) >= 0)
        {
            BigDecimal incrementalChange = currentBalance.subtract(lastKnownBalance);
            
            if (incrementalChange.compareTo(BigDecimal.ZERO) > 0)
            {
                BigDecimal tolerancePercent = new BigDecimal("0.01");
                BigDecimal tolerance = expectedCryptoAmount.multiply(tolerancePercent);
                BigDecimal minAcceptable = expectedCryptoAmount.subtract(tolerance);
                BigDecimal maxAcceptable = expectedCryptoAmount.add(tolerance);
                
                if (incrementalChange.compareTo(minAcceptable) >= 0 && incrementalChange.compareTo(maxAcceptable) <= 0)
                {
                    Timber.d("Payment received via RPC! Amount: %s", incrementalChange.toPlainString());
                    onPaymentDetected(invoice, incrementalChange);
                }
                else
                {
                    lastKnownBalance = currentBalance;
                }
            }
        }
    }

    /**
     * Fetch current balance from blockchain via RPC
     */
    private BigDecimal fetchCurrentBalance()
    {
        if (selectedToken == null || defaultWallet.getValue() == null) return null;
        
        try
        {
            long chainId = selectedToken.tokenInfo.chainId;
            String walletAddress = defaultWallet.getValue().address;
            Web3j web3j = TokenRepository.getWeb3jService(chainId);
            
            if (selectedToken.isEthereum()) // Native token (RAMA)
            {
                BigInteger balanceWei = web3j.ethGetBalance(walletAddress, DefaultBlockParameterName.LATEST)
                        .send()
                        .getBalance();
                return new BigDecimal(balanceWei)
                        .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);
            }
            else // ERC20 token
            {
                // For ERC20, call balanceOf on the token contract
                String tokenAddress = selectedToken.getAddress();
                org.web3j.abi.datatypes.Function function = new org.web3j.abi.datatypes.Function(
                        "balanceOf",
                        java.util.Collections.singletonList(new org.web3j.abi.datatypes.Address(walletAddress)),
                        java.util.Collections.singletonList(new org.web3j.abi.TypeReference<org.web3j.abi.datatypes.generated.Uint256>() {})
                );
                
                String encodedFunction = org.web3j.abi.FunctionEncoder.encode(function);
                org.web3j.protocol.core.methods.response.EthCall response = web3j.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                                walletAddress, tokenAddress, encodedFunction),
                        DefaultBlockParameterName.LATEST
                ).send();
                
                String result = response.getValue();
                if (result != null && !result.equals("0x"))
                {
                    BigInteger balance = new BigInteger(result.substring(2), 16);
                    return new BigDecimal(balance)
                            .divide(BigDecimal.TEN.pow(selectedToken.tokenInfo.decimals), 18, RoundingMode.DOWN);
                }
            }
        }
        catch (Exception e)
        {
            Timber.e(e, "Error fetching balance via RPC");
        }
        
        return null;
    }

    /**
     * Called when payment is detected
     */
    private void onPaymentDetected(RealmPosInvoice invoice, BigDecimal receivedAmount)
    {
        stopPaymentMonitoring();
        
        // Update invoice status
        markInvoicePaid(invoice.getInvoiceId(), "", ""); // TX hash would come from transaction monitoring
        
        // Notify UI
        invoice.setStatus(RealmPosInvoice.STATUS_PAID);
        invoice.setPaidAt(System.currentTimeMillis());
        paymentReceived.postValue(invoice);
    }

    /**
     * Stop payment monitoring
     */
    public void stopPaymentMonitoring()
    {
        if (timerDisposable != null && !timerDisposable.isDisposed())
        {
            timerDisposable.dispose();
            timerDisposable = null;
        }
        if (paymentMonitorDisposable != null && !paymentMonitorDisposable.isDisposed())
        {
            paymentMonitorDisposable.dispose();
            paymentMonitorDisposable = null;
        }
    }

    private void saveInvoice(RealmPosInvoice invoice)
    {
        try (Realm realm = realmManager.getRealmInstance(defaultWallet.getValue()))
        {
            realm.executeTransactionAsync(r -> {
                r.insertOrUpdate(invoice);
            });
        }
        catch (Exception e)
        {
            Timber.e(e, "Failed to save invoice");
        }
    }

    public void markInvoicePaid(String invoiceId, String txHash, String payerAddress)
    {
        try (Realm realm = realmManager.getRealmInstance(defaultWallet.getValue()))
        {
            realm.executeTransactionAsync(r -> {
                RealmPosInvoice invoice = r.where(RealmPosInvoice.class)
                        .equalTo("invoiceId", invoiceId)
                        .findFirst();
                if (invoice != null)
                {
                    invoice.setStatus(RealmPosInvoice.STATUS_PAID);
                    invoice.setPaidAt(System.currentTimeMillis());
                    invoice.setTxHash(txHash);
                    invoice.setPayerAddress(payerAddress);
                }
            });
        }
    }

    public void cancelInvoice(String invoiceId)
    {
        try (Realm realm = realmManager.getRealmInstance(defaultWallet.getValue()))
        {
            realm.executeTransactionAsync(r -> {
                RealmPosInvoice invoice = r.where(RealmPosInvoice.class)
                        .equalTo("invoiceId", invoiceId)
                        .findFirst();
                if (invoice != null)
                {
                    invoice.setStatus(RealmPosInvoice.STATUS_CANCELLED);
                }
            });
        }
    }

    public void loadInvoiceHistory()
    {
        if (defaultWallet.getValue() == null) return;

        try (Realm realm = realmManager.getRealmInstance(defaultWallet.getValue()))
        {
            RealmResults<RealmPosInvoice> results = realm.where(RealmPosInvoice.class)
                    .equalTo("merchantAddress", defaultWallet.getValue().address)
                    .sort("createdAt", Sort.DESCENDING)
                    .findAll();

            List<RealmPosInvoice> historyList = realm.copyFromRealm(results);
            invoiceHistory.postValue(historyList);
        }
        catch (Exception e)
        {
            Timber.e(e, "Failed to load invoice history");
        }
    }

    public String generatePaymentUri(RealmPosInvoice invoice)
    {
        if (invoice == null) return "";

        // Generate EIP-681 compatible payment URI
        // Format: ethereum:address@chainId?value=amount&data=invoiceHash
        StringBuilder uri = new StringBuilder();
        uri.append("ethereum:");
        uri.append(invoice.getMerchantAddress());
        uri.append("@");
        uri.append(invoice.getChainId());

        if (TextUtils.isEmpty(invoice.getTokenAddress()))
        {
            // Native token payment
            uri.append("?value=");
            uri.append(invoice.getCryptoAmount());
        }
        else
        {
            // ERC20 token payment
            uri.append("/transfer?address=");
            uri.append(invoice.getMerchantAddress());
            uri.append("&uint256=");
            uri.append(invoice.getCryptoAmount());
        }

        // Add invoice hash as data field for matching
        uri.append("&data=0x");
        uri.append(invoice.getInvoiceHash());

        return uri.toString();
    }

    private String generateNonce()
    {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
        {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private String generateInvoiceHash(String invoiceId, BigDecimal amount)
    {
        // Simple hash for invoice matching (in production, use proper keccak256)
        String data = invoiceId + "|" + amount.toPlainString();
        int hash = data.hashCode();
        return String.format("%08X", hash).toUpperCase(Locale.ROOT);
    }

    public String getCurrencySymbol(String currencyCode)
    {
        switch (currencyCode)
        {
            case "INR":
                return "₹";
            case "USD":
                return "$";
            case "EUR":
                return "€";
            case "GBP":
                return "£";
            case "AED":
                return "د.إ";
            case "SGD":
                return "S$";
            default:
                return currencyCode + " ";
        }
    }

    // Getters for LiveData
    public LiveData<Wallet> defaultWallet() { return defaultWallet; }
    public LiveData<List<Token>> tokens() { return tokens; }
    public LiveData<BigDecimal> cryptoAmount() { return cryptoAmount; }
    public LiveData<Double> exchangeRate() { return exchangeRate; }
    public LiveData<RealmPosInvoice> currentInvoice() { return currentInvoice; }
    public LiveData<RealmPosInvoice> paymentReceived() { return paymentReceived; }
    public LiveData<List<RealmPosInvoice>> invoiceHistory() { return invoiceHistory; }
    public LiveData<Long> remainingTime() { return remainingTime; }
    public LiveData<Boolean> paymentTimeout() { return paymentTimeout; }

    public Token getSelectedToken() { return selectedToken; }
    public TokensService getTokensService() { return tokensService; }
    public NetworkInfo getNetworkInfo(long chainId) { return ethereumNetworkRepository.getNetworkByChain(chainId); }

    @Override
    protected void onCleared()
    {
        super.onCleared();
        stopPaymentMonitoring();
    }
}
