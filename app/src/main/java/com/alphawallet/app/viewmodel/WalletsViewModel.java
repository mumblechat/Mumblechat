package com.alphawallet.app.viewmodel;

import static com.alphawallet.app.entity.tokenscript.TokenscriptFunction.ZERO_ADDRESS;
import static com.alphawallet.ethereum.EthereumNetworkBase.MAINNET_ID;

import android.app.Activity;
import android.content.Context;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.alphawallet.app.C;
import com.alphawallet.app.entity.CreateWalletCallbackInterface;
import com.alphawallet.app.entity.CryptoFunctions;
import com.alphawallet.app.entity.ErrorEnvelope;
import com.alphawallet.app.entity.NetworkInfo;
import com.alphawallet.app.entity.Operation;
import com.alphawallet.app.entity.ServiceSyncCallback;
import com.alphawallet.app.entity.SyncCallback;
import com.alphawallet.app.entity.Wallet;
import com.alphawallet.app.entity.WalletType;
import com.alphawallet.app.entity.tokendata.TokenUpdateType;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.interact.FetchWalletsInteract;
import com.alphawallet.app.interact.FindDefaultNetworkInteract;
import com.alphawallet.app.interact.GenericWalletInteract;
import com.alphawallet.app.interact.ImportWalletInteract;
import com.alphawallet.app.interact.SetDefaultWalletInteract;
import com.alphawallet.app.repository.EthereumNetworkBase;
import com.alphawallet.app.repository.EthereumNetworkRepositoryType;
import com.alphawallet.app.repository.PreferenceRepositoryType;
import com.alphawallet.app.repository.TokenRepository;
import com.alphawallet.app.repository.TokenRepositoryType;
import com.alphawallet.app.router.HomeRouter;
import com.alphawallet.app.router.ImportWalletRouter;
import com.alphawallet.app.service.AlphaWalletNotificationService;
import com.alphawallet.app.service.AssetDefinitionService;
import com.alphawallet.app.service.KeyService;
import com.alphawallet.app.service.TickerService;
import com.alphawallet.app.service.TokensService;
import com.alphawallet.app.util.ens.AWEnsResolver;
import com.alphawallet.hardware.SignatureFromKey;

import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SignatureException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

@HiltViewModel
public class WalletsViewModel extends BaseViewModel implements ServiceSyncCallback
{
    private static final int BALANCE_CHECK_INTERVAL_SECONDS = 30;
    private final SetDefaultWalletInteract setDefaultWalletInteract;
    private final FetchWalletsInteract fetchWalletsInteract;
    private final GenericWalletInteract genericWalletInteract;
    private final ImportWalletInteract importWalletInteract;
    private final FindDefaultNetworkInteract findDefaultNetworkInteract;
    private final KeyService keyService;
    private final ImportWalletRouter importWalletRouter;
    private final HomeRouter homeRouter;
    private final TokensService tokensService;
    private final AWEnsResolver ensResolver;
    private final AssetDefinitionService assetService;
    private final PreferenceRepositoryType preferenceRepository;
    private final EthereumNetworkRepositoryType ethereumNetworkRepository;
    private final AlphaWalletNotificationService alphaWalletNotificationService;
    private final TokenRepositoryType tokenRepository;
    private final TickerService tickerService;
    private final MutableLiveData<Wallet[]> wallets = new MutableLiveData<>();
    private final MutableLiveData<Wallet> setupWallet = new MutableLiveData<>();
    private final MutableLiveData<Pair<Wallet, Boolean>> defaultWallet = new MutableLiveData<>();
    private final MutableLiveData<Wallet> changeDefaultWallet = new MutableLiveData<>();
    private final MutableLiveData<Wallet> newWalletCreated = new MutableLiveData<>();
    private final MutableLiveData<ErrorEnvelope> createWalletError = new MutableLiveData<>();
    private final MutableLiveData<Boolean> noWalletsError = new MutableLiveData<>();
    private final MutableLiveData<Map<String, Token[]>> baseTokens = new MutableLiveData<>();
    private final MutableLiveData<String> getPublicKey = new MutableLiveData<>();
    private final MutableLiveData<Wallet> discoveryAuthComplete = new MutableLiveData<>();
    private Wallet pendingDiscoveryWallet;
    private volatile String currentScanningNetwork = "";
    private NetworkInfo currentNetwork;
    private final Map<String, Wallet> walletBalances = new HashMap<>();
    private final Map<String, TokensService> walletServices = new ConcurrentHashMap<>();
    private final Map<String, Wallet> walletUpdate = new ConcurrentHashMap<>();
    private final Map<String, Disposable> currentWalletUpdates = new ConcurrentHashMap<>();
    private SyncCallback syncCallback;

    public static final String TEST_STRING = "EncodedUUID to determine Public Key" + UUID.randomUUID().toString();

    @Nullable
    private Disposable balanceTimerDisposable;

    @Nullable
    private Disposable walletBalanceUpdate;

    @Nullable
    private Disposable ensCheck;

    @Nullable
    private Disposable ensWrappingCheck;

    @Inject
    WalletsViewModel(
        AlphaWalletNotificationService alphaWalletNotificationService,
        SetDefaultWalletInteract setDefaultWalletInteract,
        FetchWalletsInteract fetchWalletsInteract,
        GenericWalletInteract genericWalletInteract,
        ImportWalletInteract importWalletInteract,
        ImportWalletRouter importWalletRouter,
        HomeRouter homeRouter,
        FindDefaultNetworkInteract findDefaultNetworkInteract,
        KeyService keyService,
        EthereumNetworkRepositoryType ethereumNetworkRepository,
        TokenRepositoryType tokenRepository,
        TickerService tickerService,
        AssetDefinitionService assetService,
        PreferenceRepositoryType preferenceRepository,
        @ApplicationContext Context context)
    {
        this.alphaWalletNotificationService = alphaWalletNotificationService;
        this.setDefaultWalletInteract = setDefaultWalletInteract;
        this.fetchWalletsInteract = fetchWalletsInteract;
        this.genericWalletInteract = genericWalletInteract;
        this.importWalletInteract = importWalletInteract;
        this.importWalletRouter = importWalletRouter;
        this.homeRouter = homeRouter;
        this.findDefaultNetworkInteract = findDefaultNetworkInteract;
        this.keyService = keyService;
        this.ethereumNetworkRepository = ethereumNetworkRepository;
        this.tokenRepository = tokenRepository;
        this.tickerService = tickerService;
        this.assetService = assetService;
        this.preferenceRepository = preferenceRepository;
        this.tokensService = new TokensService(ethereumNetworkRepository, tokenRepository, tickerService, null, null, null);

        ensResolver = new AWEnsResolver(TokenRepository.getWeb3jService(MAINNET_ID), context);
        syncCallback = null;
    }

    public LiveData<Wallet[]> wallets()
    {
        return wallets;
    }

    public LiveData<Wallet> setupWallet()
    {
        return setupWallet;
    }

    public LiveData<Wallet> newWalletCreated()
    {
        return newWalletCreated;
    }

    public LiveData<ErrorEnvelope> createWalletError()
    {
        return createWalletError;
    }

    public LiveData<Boolean> noWalletsError()
    {
        return noWalletsError;
    }

    public LiveData<Map<String, Token[]>> baseTokens()
    {
        return baseTokens;
    }

    public LiveData<Wallet> changeDefaultWallet()
    {
        return changeDefaultWallet;
    }

    public LiveData<String> getPublicKey()
    {
        return getPublicKey;
    }

    public LiveData<Wallet> discoveryAuthComplete()
    {
        return discoveryAuthComplete;
    }

    public void setDefaultWallet(Wallet wallet)
    {
        preferenceRepository.setNewWallet(wallet.address, false);
        disposable = setDefaultWalletInteract
                .set(wallet)
                .subscribe(() -> onDefaultWallet(wallet), this::onError);
    }

    /**
     * Change to an existing wallet after user selection
     *
     * @param wallet
     */
    public void changeDefaultWallet(Wallet wallet)
    {
        preferenceRepository.setWatchOnly(wallet.watchOnly());
        preferenceRepository.setNewWallet(wallet.address, false);
        disposable = setDefaultWalletInteract
                .set(wallet)
                .subscribe(() -> changeDefaultWallet.postValue(wallet), this::onError);
    }

    public void subscribeToNotifications()
    {
        disposable = alphaWalletNotificationService.subscribe(MAINNET_ID)
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .subscribe(result -> Timber.d("subscribe result => %s", result), Timber::e);
    }

    public void onPrepare(long chainId, SyncCallback cb)
    {
        syncCallback = cb;
        currentNetwork = findDefaultNetworkInteract.getNetworkInfo(chainId);

        startWalletUpdate();
    }

    private void startWalletUpdate()
    {
        walletBalances.clear();
        progress.postValue(true);

        disposable = genericWalletInteract
                .find()
                .subscribe(this::onDefaultWallet,
                        error -> noWalletsError.postValue(true));
    }

    private void onDefaultWallet(Wallet wallet)
    {
        setupWallet.postValue(wallet);
        disposable = fetchWalletsInteract
                .fetch()
                .subscribe(this::onWallets, this::onError);
    }

    private void onWallets(Wallet[] items)
    {
        progress.postValue(false);

        for (Wallet w : items)
        {
            w.balanceSymbol = currentNetwork.symbol;
            Wallet mapW = walletBalances.get(w.address.toLowerCase());
            if (mapW != null)
            {
                w.balance = mapW.balance;
            }
        }
        wallets.postValue(items);

        for (Wallet w : items)
        {
            if (w.type == WalletType.WATCH) continue;
            syncFromDBOnly(w, true);
        }

        disposable = fetchWalletsInteract.fetch().subscribe(this::startBalanceUpdateTimer);
    }

    private Disposable startWalletSyncProcess(Wallet w)
    {
        walletUpdate.remove(w.address);
        return startWalletSync(w)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::sendUnsyncedValue, e -> {});
    }

    private void startFullWalletSync(Wallet[] items)
    {
        walletUpdate.clear();
        for (Wallet w : items)
        {
            if (w.type != WalletType.WATCH)
            {
                walletUpdate.put(w.address, w);
                syncCallback.syncStarted(w.address, null);
            }
        }

        int counter = 0;

        //set all wallets as syncing
        for (Wallet w : items)
        {
            if (w.type == WalletType.WATCH) continue;
            if (counter++ == 4) break;
            currentWalletUpdates.put(w.address, startWalletSyncProcess(w));
        }
    }

    private void syncFromDBOnly(Wallet wallet, boolean complete)
    {
        tokenRepository.getTotalValue(wallet.address.toLowerCase(), EthereumNetworkBase.getAllMainNetworks())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(value ->
                {
                    if (complete)
                    {
                        syncCallback.syncCompleted(wallet.address.toLowerCase(), value);
                    }
                    else
                    {
                        syncCallback.syncStarted(wallet.address.toLowerCase(), value);
                    }
                }).isDisposed();
    }

    private void sendUnsyncedValue(Wallet wallet)
    {
        TokensService service = walletServices.get(wallet.address.toLowerCase());
        if (service != null)
        {
            service.getFiatValuePair()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(value ->
                            syncCallback.syncStarted(service.getCurrentAddress().toLowerCase(), value)).isDisposed();
        }
    }

    private Single<Wallet> startWalletSync(Wallet wallet)
    {
        return Single.fromCallable(() -> {
            TokensService svs = new TokensService(ethereumNetworkRepository, tokenRepository, tickerService, null, null, null);
            svs.setCurrentAddress(wallet.address.toLowerCase());
            svs.startUpdateCycle();
            svs.setCompletionCallback(this, 2);
            walletServices.put(wallet.address.toLowerCase(), svs);
            return wallet;
        });
    }

    @Override
    public void syncComplete(final TokensService service, int syncCount)
    {
        if (syncCount == 2)
        {
            service.setCompletionCallback(this, 1);
        }
        else
        {
            service.stopUpdateCycle();
            walletServices.remove(service.getCurrentAddress().toLowerCase());
            currentWalletUpdates.remove(service.getCurrentAddress().toLowerCase());
            updateNextWallet();
        }

        if (syncCallback == null) return;

        //get value:
        service.getFiatValuePair()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(value -> {
                    if (syncCount == 1)
                    {
                        syncCallback.syncCompleted(service.getCurrentAddress().toLowerCase(), value);
                    }
                    else
                    {
                        syncCallback.syncUpdate(service.getCurrentAddress().toLowerCase(), value);
                    }
                }).isDisposed();
    }

    private void updateNextWallet()
    {
        String nextWalletToCheck = walletUpdate.keySet().iterator().hasNext() ? walletUpdate.keySet().iterator().next() : null;

        if (nextWalletToCheck != null)
        {
            Wallet w = walletUpdate.get(nextWalletToCheck);
            if (w != null)
            {
                currentWalletUpdates.put(nextWalletToCheck, startWalletSyncProcess(w));
            }
        }
    }

    public void swipeRefreshWallets()
    {
        //check for updates
        //check names first
        ensWrappingCheck = fetchWalletsInteract.fetch().toObservable()
                .flatMap(Observable::fromArray)
                .forEach(wallet -> ensCheck = ensResolver.reverseResolveEns(wallet.address)
                        .onErrorReturnItem(wallet.ENSname != null ? wallet.ENSname : "")
                        .map(ensName -> {
                            wallet.ENSname = ensName;
                            return wallet;
                        })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(w -> fetchWalletsInteract.updateWalletData(w, () -> {}), this::onError));

        //now load the current wallets from database
        disposable = fetchWalletsInteract
                .fetch()
                .subscribe(this::startFullWalletSync, this::onError);
    }

    public void fetchWallets()
    {
        progress.postValue(true);
        startWalletUpdate();
    }

    public void newWallet(Activity ctx, CreateWalletCallbackInterface createCallback)
    {
        Completable.fromAction(() -> keyService.createNewHDKey(ctx, createCallback)) //create wallet on a computation thread to give UI a chance to complete all tasks
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe()
                .isDisposed();
    }

    /**
     * After wallet has been stored, set the default wallet, return to WalletsActivity and run onNewWallet
     * which resets token operations and goes to main wallet page
     *
     * @param wallet
     */
    public void setNewWallet(Wallet wallet)
    {
        preferenceRepository.setNewWallet(wallet.address, true);
        disposable = setDefaultWalletInteract
                .set(wallet)
                .subscribe(() -> newWalletCreated.postValue(wallet), this::onError);
    }

    private void startBalanceUpdateTimer(final Wallet[] wallets)
    {
        if (balanceTimerDisposable != null && !balanceTimerDisposable.isDisposed()) balanceTimerDisposable.dispose();
        balanceTimerDisposable = Observable.interval(1, BALANCE_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS) //initial delay 1 second to allow view to stabilise
                .doOnNext(l -> getWalletsBalance(wallets)).subscribe();
    }

    /**
     * Sequentially updates the wallet balances on the current network and stores to database if necessary
     *
     * @param wallets - array of wallets
     */
    private void getWalletsBalance(Wallet[] wallets)
    {
        //loop through wallets and update balance
        disposable = Observable.fromArray(wallets)
                .forEach(wallet -> walletBalanceUpdate = tokensService.getChainBalance(wallet.address.toLowerCase(), currentNetwork.chainId)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(newBalance -> genericWalletInteract.updateBalanceIfRequired(wallet, newBalance), e -> {}));
        progress.postValue(false);
    }

    private void updateAllWallets(Wallet[] wallets, TokenUpdateType updateType)
    {
        disposable = Single.fromCallable(() -> {
                    //fetch all wallets in one go
                    Map<String, Token[]> walletTokenMap = new HashMap<>();
                    for (Wallet wallet : wallets)
                    {
                        Token[] walletTokens = tokensService.syncChainBalances(wallet.address.toLowerCase(), updateType).blockingGet();
                        if (walletTokens.length > 0)
                        {
                            walletTokenMap.put(walletTokens[0].getWallet(), walletTokens);
                        }
                    }
                    return walletTokenMap;
                }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(baseTokens::postValue, e -> {});
    }

    @Override
    public void onCleared()
    {
        super.onCleared();
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
        if (balanceTimerDisposable != null && !balanceTimerDisposable.isDisposed()) balanceTimerDisposable.dispose();
        if (walletBalanceUpdate != null && !walletBalanceUpdate.isDisposed()) walletBalanceUpdate.dispose();
        if (ensCheck != null && !ensCheck.isDisposed()) ensCheck.dispose();
        if (ensWrappingCheck != null && !ensWrappingCheck.isDisposed()) ensWrappingCheck.dispose();
    }

    private void onCreateWalletError(Throwable throwable)
    {
        progress.postValue(false);
        createWalletError.postValue(new ErrorEnvelope(C.ErrorCode.UNKNOWN, throwable.getMessage()));
    }

    public void importWallet(Activity activity)
    {
        importWalletRouter.openForResult(activity, C.IMPORT_REQUEST_CODE, false);
    }

    public void showHome(Context context)
    {
        homeRouter.open(context, true);
    }

    public NetworkInfo getNetwork()
    {
        return currentNetwork;
    }

    public void watchWallet(Activity activity)
    {
        importWalletRouter.openWatchCreate(activity, C.IMPORT_REQUEST_CODE);
    }
    
    public void requestAuthentication(Activity activity, Wallet wallet, Operation operation)
    {
        if (operation == Operation.DISCOVER_ACCOUNTS)
        {
            pendingDiscoveryWallet = wallet;
            // Use callback version to handle biometric auth success path
            keyService.requestAuthenticationWithCallback(activity, wallet, operation, () -> {
                // This runs when biometric auth succeeds
                if (pendingDiscoveryWallet != null)
                {
                    discoveryAuthComplete.postValue(pendingDiscoveryWallet);
                    pendingDiscoveryWallet = null;
                }
            });
        }
        else
        {
            keyService.requestAuthentication(activity, wallet, operation);
        }
    }

    public void completeAuthentication(Operation taskCode)
    {
        keyService.completeAuthentication(taskCode);
        if (taskCode == Operation.DISCOVER_ACCOUNTS && pendingDiscoveryWallet != null)
        {
            discoveryAuthComplete.postValue(pendingDiscoveryWallet);
            pendingDiscoveryWallet = null;
        }
    }

    public void failedAuthentication(Operation taskCode)
    {
        keyService.failedAuthentication(taskCode);
    }

    public void onPause()
    {
        if (balanceTimerDisposable != null && !balanceTimerDisposable.isDisposed()) balanceTimerDisposable.dispose();
        balanceTimerDisposable = null;
    }

    public GenericWalletInteract getWalletInteract()
    {
        return genericWalletInteract;
    }

    public void stopUpdates()
    {
        assetService.stopEventListener();
    }

    public void onDestroy()
    {
        for (TokensService svs : walletServices.values())
        {
            svs.stopUpdateCycle();
        }

        for (Disposable d : currentWalletUpdates.values())
        {
            if (!d.isDisposed()) d.dispose();
        }

        walletServices.clear();
        currentWalletUpdates.clear();
    }

    public void storeHDWallet(String address, KeyService.AuthenticationLevel authLevel)
    {
        if (!address.equals(ZERO_ADDRESS))
        {
            Wallet wallet = new Wallet(address);
            wallet.type = WalletType.HDKEY;
            wallet.authLevel = authLevel;
            wallet.lastBackupTime = System.currentTimeMillis(); // Set backup time since user completed backup
            fetchWalletsInteract.storeWallet(wallet)
                    .map(w -> {
                        // Explicitly update backup time to ensure it's persisted
                        fetchWalletsInteract.updateBackupTime(w.address);
                        return w;
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(account -> setNewWallet(wallet), this::onCreateWalletError).isDisposed();
        }
    }

    public void storeWallet(Wallet wallet, WalletType type)
    {

    }

    public void storeHardwareWallet(SignatureFromKey returnSig) throws SignatureException
    {
        Sign.SignatureData sigData = CryptoFunctions.sigFromByteArray(returnSig.signature);
        BigInteger recoveredKey = Sign.signedMessageToKey(TEST_STRING.getBytes(), sigData);
        String address = Numeric.prependHexPrefix(Keys.getAddress(recoveredKey));

        disposable = fetchWalletsInteract
                .fetch()
                .subscribe(wallets -> importOrSetActive(address, wallets), this::onError);
    }

    private void importOrSetActive(String addressHex, Wallet[] wallets)
    {
        Wallet existingWallet = findWallet(wallets, addressHex);
        if (existingWallet != null)
        {
            changeDefaultWallet(existingWallet);
        }
        else
        {
            storeHardwareWallet(addressHex);
        }
    }

    private Wallet findWallet(Wallet[] wallets, String address)
    {
        for (Wallet wallet : wallets)
        {
            if (wallet.address.equalsIgnoreCase(address))
            {
                return wallet;
            }
        }

        return null;
    }

    private void storeHardwareWallet(String address)
    {
        disposable = importWalletInteract.storeHardwareWallet(address)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(wallet -> setNewWallet(wallet), this::onCreateWalletError);
    }

    public void logIn(String address)
    {
        preferenceRepository.logIn(address);
    }

    /**
     * Find the primary HD wallet (the one with the seed phrase stored)
     * This is the first HD wallet that was created
     */
    public Wallet findPrimaryHDWallet(Wallet[] walletArray)
    {
        if (walletArray == null) return null;
        
        for (Wallet wallet : walletArray)
        {
            // Primary HD wallet has type HDKEY and no parent address
            if (wallet.type == WalletType.HDKEY && 
                (wallet.parentAddress == null || wallet.parentAddress.isEmpty()))
            {
                return wallet;
            }
        }
        return null;
    }

    /**
     * Count the number of HD accounts derived from the primary wallet
     */
    public int countHDAccounts(Wallet[] walletArray)
    {
        if (walletArray == null) return 0;
        
        int count = 0;
        for (Wallet wallet : walletArray)
        {
            if (wallet.type == WalletType.HDKEY)
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Count the number of HD accounts derived from a specific master wallet
     * @param walletArray All wallets
     * @param masterAddress The master wallet address to count accounts for
     * @return The count of derived accounts (including the master itself which is at index 0)
     */
    public int countHDAccountsForMaster(Wallet[] walletArray, String masterAddress)
    {
        if (walletArray == null || masterAddress == null) return 0;
        
        int count = 0;
        for (Wallet wallet : walletArray)
        {
            if (wallet.type == WalletType.HDKEY)
            {
                // Count the master wallet itself
                if (wallet.address.equalsIgnoreCase(masterAddress))
                {
                    count++;
                }
                // Count derived accounts that belong to this master
                else if (wallet.parentAddress != null && wallet.parentAddress.equalsIgnoreCase(masterAddress))
                {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Get the next available hdKeyIndex for deriving a new account from a master wallet.
     * This finds the maximum existing hdKeyIndex and returns max + 1.
     * @param walletArray All wallets
     * @param masterAddress The master wallet address
     * @return The next available hdKeyIndex for derivation
     */
    public int getNextHDKeyIndexForMaster(Wallet[] walletArray, String masterAddress)
    {
        if (walletArray == null || masterAddress == null) return 0;
        
        int maxIndex = -1;
        for (Wallet wallet : walletArray)
        {
            if (wallet.type == WalletType.HDKEY)
            {
                // Check the master wallet itself (index 0)
                if (wallet.address.equalsIgnoreCase(masterAddress))
                {
                    if (wallet.hdKeyIndex > maxIndex)
                    {
                        maxIndex = wallet.hdKeyIndex;
                    }
                }
                // Check derived accounts that belong to this master
                else if (wallet.parentAddress != null && wallet.parentAddress.equalsIgnoreCase(masterAddress))
                {
                    if (wallet.hdKeyIndex > maxIndex)
                    {
                        maxIndex = wallet.hdKeyIndex;
                    }
                }
            }
        }
        return maxIndex + 1;
    }

    /**
     * Find all master HD wallets (those with no parent address)
     * @param walletArray All wallets
     * @return List of master HD wallets
     */
    public java.util.List<Wallet> findAllMasterHDWallets(Wallet[] walletArray)
    {
        java.util.List<Wallet> masterWallets = new java.util.ArrayList<>();
        if (walletArray == null) return masterWallets;
        
        for (Wallet wallet : walletArray)
        {
            if (wallet.type == WalletType.HDKEY && 
                (wallet.parentAddress == null || wallet.parentAddress.isEmpty()))
            {
                masterWallets.add(wallet);
            }
        }
        return masterWallets;
    }

    /**
     * Find the master wallet for a given wallet (either itself if it's a master, or its parent)
     * @param wallet The wallet to find the master for
     * @param walletArray All wallets
     * @return The master wallet, or null if not found
     */
    public Wallet findMasterWalletFor(Wallet wallet, Wallet[] walletArray)
    {
        if (wallet == null || walletArray == null) return null;
        
        // If wallet is not HD, return null
        if (wallet.type != WalletType.HDKEY) return null;
        
        // If wallet has no parent, it IS the master
        if (wallet.parentAddress == null || wallet.parentAddress.isEmpty())
        {
            return wallet;
        }
        
        // Find the parent wallet
        for (Wallet w : walletArray)
        {
            if (w.address.equalsIgnoreCase(wallet.parentAddress))
            {
                return w;
            }
        }
        return null;
    }

    /**
     * Check if there's an HD wallet available for deriving new accounts
     */
    public boolean hasHDWallet(Wallet[] walletArray)
    {
        return findPrimaryHDWallet(walletArray) != null;
    }

    /**
     * Add a new account derived from the HD wallet (uses primary wallet)
     * @param activity The calling activity
     * @param callback Callback for the result
     */
    public void addHDAccount(Activity activity, CreateWalletCallbackInterface callback)
    {
        Wallet[] currentWallets = wallets.getValue();
        Wallet primaryHDWallet = findPrimaryHDWallet(currentWallets);
        
        if (primaryHDWallet == null)
        {
            callback.keyFailure("No HD wallet found");
            return;
        }
        
        addHDAccountFromMaster(activity, primaryHDWallet, callback);
    }

    /**
     * Add a new account derived from a specific master HD wallet
     * @param activity The calling activity
     * @param masterWallet The master HD wallet to derive from
     * @param callback Callback for the result
     */
    public void addHDAccountFromMaster(Activity activity, Wallet masterWallet, CreateWalletCallbackInterface callback)
    {
        Wallet[] currentWallets = wallets.getValue();
        
        if (masterWallet == null)
        {
            callback.keyFailure("No master wallet specified");
            return;
        }
        
        // Get the next available hdKeyIndex (max existing index + 1)
        int nextIndex = getNextHDKeyIndexForMaster(currentWallets, masterWallet.address);
        keyService.deriveNewHDAccount(masterWallet, nextIndex, activity, callback);
    }

    /**
     * Add a new account derived from a specific master HD wallet at a specific index
     * Used for recovering missing accounts
     * @param activity The calling activity
     * @param masterWallet The master HD wallet to derive from
     * @param index The specific index to derive
     * @param callback Callback for the result
     */
    public void addHDAccountFromMasterAtIndex(Activity activity, Wallet masterWallet, int index, CreateWalletCallbackInterface callback)
    {
        if (masterWallet == null)
        {
            callback.keyFailure("No master wallet specified");
            return;
        }
        
        keyService.deriveNewHDAccount(masterWallet, index, activity, callback);
    }

    /**
     * Store a derived HD account
     * @param address The derived account address
     * @param authLevel The authentication level
     * @param parentAddress The parent HD wallet address
     * @param hdKeyIndex The derivation index
     */
    public void storeDerivedHDAccount(String address, KeyService.AuthenticationLevel authLevel, 
                                       String parentAddress, int hdKeyIndex)
    {
        if (!address.equals(ZERO_ADDRESS))
        {
            Wallet wallet = new Wallet(address);
            wallet.type = WalletType.HDKEY;
            wallet.authLevel = authLevel;
            wallet.parentAddress = parentAddress;
            wallet.hdKeyIndex = hdKeyIndex;
            fetchWalletsInteract.storeWallet(wallet)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(account -> setNewWallet(wallet), this::onCreateWalletError).isDisposed();
        }
    }

    /**
     * Callback interface for wallet store operations
     */
    public interface WalletStoreCallback {
        void onWalletStored(Wallet wallet);
        void onStoreFailed(String error);
    }

    /**
     * Store a derived HD account with callback for sequential operations
     * Waits for store to complete before calling callback
     * @param address The derived account address
     * @param authLevel The authentication level
     * @param parentAddress The parent HD wallet address
     * @param hdKeyIndex The derivation index
     * @param callback Callback when store completes or fails
     */
    public void storeDerivedHDAccountWithCallback(String address, KeyService.AuthenticationLevel authLevel, 
                                                   String parentAddress, int hdKeyIndex, WalletStoreCallback callback)
    {
        if (address == null || address.equals(ZERO_ADDRESS))
        {
            if (callback != null) {
                callback.onStoreFailed("Invalid wallet address");
            }
            return;
        }
        
        Wallet wallet = new Wallet(address);
        wallet.type = WalletType.HDKEY;
        wallet.authLevel = authLevel;
        wallet.parentAddress = parentAddress;
        wallet.hdKeyIndex = hdKeyIndex;
        
        android.util.Log.d("WalletsViewModel", "Storing wallet at index " + hdKeyIndex + ": " + address);
        
        fetchWalletsInteract.storeWallet(wallet)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    account -> {
                        android.util.Log.d("WalletsViewModel", "Successfully stored wallet at index " + hdKeyIndex + ": " + address);
                        if (callback != null) {
                            callback.onWalletStored(wallet);
                        }
                    }, 
                    error -> {
                        android.util.Log.e("WalletsViewModel", "Failed to store wallet at index " + hdKeyIndex + ": " + error.getMessage());
                        if (callback != null) {
                            callback.onStoreFailed(error.getMessage());
                        }
                    }
                ).isDisposed();
    }

    /**
     * Interface for account discovery callbacks
     */
    public interface AccountDiscoveryCallback {
        void onAccountsDiscovered(java.util.List<Integer> activeIndices);
        void onDiscoveryFailed(String error);
        void onProgress(int current, int total, String currentAddress, int foundCount, String networkName);
        void onAuthenticationRequired();
    }

    /**
     * Discover derived accounts that have activity (transactions) on the blockchain
     * Scans up to 20 consecutive empty accounts before stopping (BIP44 gap limit)
     * @param masterWallet The master HD wallet
     * @param callback Callback for discovery results
     */
    public void discoverDerivedAccounts(Wallet masterWallet, AccountDiscoveryCallback callback)
    {
        final int GAP_LIMIT = 20; // Standard BIP44 gap limit
        final int MAX_SCAN = 20; // Maximum accounts to scan
        
        disposable = io.reactivex.Observable.create((io.reactivex.ObservableEmitter<java.util.List<Integer>> emitter) -> {
            java.util.List<Integer> activeIndices = new java.util.ArrayList<>();
            int consecutiveEmpty = 0;
            
            try {
                for (int index = 0; index < MAX_SCAN && consecutiveEmpty < GAP_LIMIT && !emitter.isDisposed(); index++) {
                    try {
                        // Derive address at this index using authenticated method
                        String address = keyService.getAddressAtIndexAuthenticated(masterWallet, index);
                        
                        // Update progress on main thread
                        final int currentIndex = index;
                        final String currentAddr = address;
                        final int currentFoundCount = activeIndices.size();
                        final String scanningNetwork = currentScanningNetwork;
                        AndroidSchedulers.mainThread().scheduleDirect(() -> 
                            callback.onProgress(currentIndex + 1, MAX_SCAN, currentAddr, currentFoundCount, scanningNetwork));
                        
                        android.util.Log.d("WalletsViewModel", "Checking address at index " + index + ": " + address);
                        
                        // Check if this address has any activity on ETH, BNB, RAMA chains
                        NetworkResult result = checkAddressHasActivity(address);
                        if (result.hasActivity) {
                            activeIndices.add(index);
                            consecutiveEmpty = 0;
                            android.util.Log.d("WalletsViewModel", "Found active account at index " + index + ": " + address + " on " + result.networkName);
                        } else {
                            consecutiveEmpty++;
                        }
                    } catch (UserNotAuthenticatedException e) {
                        // Authentication required - rethrow to outer catch
                        throw e;
                    } catch (Exception e) {
                        android.util.Log.w("WalletsViewModel", "Error checking index " + index + ": " + e.getMessage());
                        // Don't increment consecutiveEmpty for transient errors
                        // This allows retrying on network issues
                    }
                }
            } catch (UserNotAuthenticatedException e) {
                // Authentication required - propagate to UI
                android.util.Log.d("WalletsViewModel", "Authentication required for discovery");
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
                return;
            } catch (Exception e) {
                android.util.Log.e("WalletsViewModel", "Discovery error: " + e.getMessage(), e);
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
                return;
            }
            
            if (!emitter.isDisposed()) {
                emitter.onNext(activeIndices);
                emitter.onComplete();
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(
            activeIndices -> callback.onAccountsDiscovered(activeIndices),
            error -> {
                if (error instanceof UserNotAuthenticatedException) {
                    callback.onAuthenticationRequired();
                } else {
                    callback.onDiscoveryFailed(error.getMessage());
                }
            }
        );
    }

    /**
     * Check if an address has any activity (balance or transactions)
     * Checks ETH, BNB, and RAMA chains
     * @param address The address to check
     * @return NetworkResult with activity status and network name
     */
    private NetworkResult checkAddressHasActivity(String address)
    {
        // List of RPC endpoints to check with network names
        String[][] networks = {
            {"https://mainnet.infura.io/v3/da3717f25f824cc1baa32d812386d93f", "Ethereum"},
            {"https://bsc-dataseed.binance.org", "BNB Chain"},
            {"https://blockchain.ramestta.com", "RAMA Network"}
        };
        
        for (String[] network : networks) {
            String endpoint = network[0];
            String networkName = network[1];
            try {
                android.util.Log.d("WalletsViewModel", "Checking address " + address + " on " + networkName);
                currentScanningNetwork = networkName;
                org.web3j.protocol.Web3j web3j = org.web3j.protocol.Web3j.build(
                    new org.web3j.protocol.http.HttpService(endpoint));
                
                // Check transaction count first (faster than balance check)
                org.web3j.protocol.core.methods.response.EthGetTransactionCount txCount = 
                    web3j.ethGetTransactionCount(address, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send();
                
                if (txCount.getTransactionCount().compareTo(java.math.BigInteger.ZERO) > 0) {
                    android.util.Log.d("WalletsViewModel", "Found activity on " + networkName + ": tx count = " + txCount.getTransactionCount());
                    web3j.shutdown();
                    return new NetworkResult(true, networkName);
                }
                
                // Check balance
                org.web3j.protocol.core.methods.response.EthGetBalance balance = 
                    web3j.ethGetBalance(address, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send();
                
                web3j.shutdown();
                
                if (balance.getBalance().compareTo(java.math.BigInteger.ZERO) > 0) {
                    android.util.Log.d("WalletsViewModel", "Found activity on " + networkName + ": balance = " + balance.getBalance());
                    return new NetworkResult(true, networkName);
                }
            } catch (Exception e) {
                android.util.Log.w("WalletsViewModel", "Failed to check " + networkName + ": " + e.getMessage());
                // Continue to next network
                continue;
            }
        }
        
        return new NetworkResult(false, null);
    }
    
    /**
     * Result class for network checking
     */
    public static class NetworkResult {
        public final boolean hasActivity;
        public final String networkName;
        
        public NetworkResult(boolean hasActivity, String networkName) {
            this.hasActivity = hasActivity;
            this.networkName = networkName;
        }
    }
}
