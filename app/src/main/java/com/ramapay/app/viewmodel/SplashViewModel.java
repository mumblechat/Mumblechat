package com.ramapay.app.viewmodel;

import static com.ramapay.app.entity.tokenscript.TokenscriptFunction.ZERO_ADDRESS;
import static com.ramapay.ethereum.EthereumNetworkBase.RAMESTTA_MAINNET_ID;

import android.app.Activity;
import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ramapay.app.entity.CreateWalletCallbackInterface;
import com.ramapay.app.entity.Operation;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.interact.FetchWalletsInteract;
import com.ramapay.app.repository.PreferenceRepositoryType;
import com.ramapay.app.service.AnalyticsServiceType;
import com.ramapay.app.service.KeyService;

import java.io.File;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

@HiltViewModel
public class SplashViewModel extends BaseViewModel
{
    private static final String LEGACY_CERTIFICATE_DB = "CERTIFICATE_CACHE-db.realm";
    private static final String LEGACY_AUX_DB_PREFIX = "AuxData-";
    private final FetchWalletsInteract fetchWalletsInteract;
    private final PreferenceRepositoryType preferenceRepository;
    private final KeyService keyService;
    private final MutableLiveData<Wallet[]> wallets = new MutableLiveData<>();
    private final MutableLiveData<Wallet> createWallet = new MutableLiveData<>();

    @Inject
    SplashViewModel(
        FetchWalletsInteract fetchWalletsInteract,
        PreferenceRepositoryType preferenceRepository,
        KeyService keyService,
        AnalyticsServiceType analyticsService)
    {
        this.fetchWalletsInteract = fetchWalletsInteract;
        this.preferenceRepository = preferenceRepository;
        this.keyService = keyService;
        setAnalyticsService(analyticsService);
        // increase launch count
//        this.preferenceRepository.incrementLaunchCount();
    }

    public void fetchWallets()
    {
        fetchWalletsInteract
            .fetch()
            .subscribe(wallets::postValue, this::onError)
            .isDisposed();
    }

    //on wallet error ensure execution still continues and splash screen terminates
    @Override
    protected void onError(Throwable throwable)
    {
        wallets.postValue(new Wallet[0]);
    }

    public LiveData<Wallet[]> wallets()
    {
        return wallets;
    }

    public LiveData<Wallet> createWallet()
    {
        return createWallet;
    }

    public void createNewWallet(Activity ctx, CreateWalletCallbackInterface createCallback)
    {
        Completable.fromAction(() -> keyService.createNewHDKey(ctx, createCallback)) //create wallet on a computation thread to give UI a chance to complete all tasks
            .subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe()
            .isDisposed();
    }

    public void StoreHDKey(String address, KeyService.AuthenticationLevel authLevel)
    {
        if (!address.equals(ZERO_ADDRESS))
        {
            Wallet wallet = new Wallet(address);
            wallet.type = WalletType.HDKEY;
            wallet.authLevel = authLevel;
            wallet.lastBackupTime = System.currentTimeMillis(); // Set backup time since user just completed backup
            fetchWalletsInteract.storeWallet(wallet)
                .map(w -> {
                    preferenceRepository.setCurrentWalletAddress(w.address);
                    // Explicitly update backup time to ensure it's persisted
                    fetchWalletsInteract.updateBackupTime(w.address);
                    return w;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(newWallet -> wallets.postValue(new Wallet[]{newWallet}), this::onError).isDisposed();
        }
        else
        {
            wallets.postValue(new Wallet[0]);
        }

        preferenceRepository.setNewWallet(address, true);
    }
    
    /**
     * Mark a wallet as new (for triggering security setup after import)
     */
    public void markWalletAsNew(String address)
    {
        preferenceRepository.setNewWallet(address, true);
    }

    public void completeAuthentication(Operation taskCode)
    {
        keyService.completeAuthentication(taskCode);
    }

    public void failedAuthentication(Operation taskCode)
    {
        keyService.failedAuthentication(taskCode);
    }

    public void cleanAuxData(Context ctx)
    {
        try
        {
            File[] files = ctx.getFilesDir().listFiles();
            for (File file : files)
            {
                String fileName = file.getName();
                if (fileName.startsWith(LEGACY_AUX_DB_PREFIX) || fileName.equals(LEGACY_CERTIFICATE_DB))
                {
                    deleteRecursive(file);
                }
            }
        }
        catch (Exception e)
        {
            //
        }
    }

    private void deleteRecursive(File fp)
    {
        if (fp.isDirectory())
        {
            File[] contents = fp.listFiles();
            if (contents != null)
            {
                for (File child : contents)
                    deleteRecursive(child);
            }
        }

        fp.delete();
    }

    public void setDefaultBrowser()
    {
        preferenceRepository.setActiveBrowserNetwork(RAMESTTA_MAINNET_ID);
    }

    public long getInstallTime()
    {
        return preferenceRepository.getInstallTime();
    }

    public void setInstallTime(long time)
    {
        preferenceRepository.setInstallTime(time);
    }

    public void doWalletStartupActions(Wallet wallet)
    {
        preferenceRepository.logIn(wallet.address);
        preferenceRepository.setCurrentWalletAddress(wallet.address);
        preferenceRepository.setWatchOnly(wallet.watchOnly());
    }
}
