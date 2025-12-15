package com.ramapay.app.viewmodel;

import static com.ramapay.ethereum.EthereumNetworkBase.MAINNET_ID;

import android.content.Context;
import android.text.TextUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ramapay.app.C;
import com.ramapay.app.entity.ErrorEnvelope;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.interact.DeleteWalletInteract;
import com.ramapay.app.interact.ExportWalletInteract;
import com.ramapay.app.interact.FetchWalletsInteract;
import com.ramapay.app.repository.TokenRepository;
import com.ramapay.app.router.HomeRouter;
import com.ramapay.app.service.RamaPayNotificationService;
import com.ramapay.app.util.Utils;
import com.ramapay.app.util.ens.AWEnsResolver;
import com.ramapay.ethereum.EthereumNetworkBase;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

@HiltViewModel
public class WalletActionsViewModel extends BaseViewModel
{
    private final static String TAG = WalletActionsViewModel.class.getSimpleName();

    private final HomeRouter homeRouter;
    private final DeleteWalletInteract deleteWalletInteract;
    private final ExportWalletInteract exportWalletInteract;
    private final FetchWalletsInteract fetchWalletsInteract;
    private final RamaPayNotificationService ramaPayNotificationService;
    private final MutableLiveData<Integer> saved = new MutableLiveData<>();
    private final MutableLiveData<Integer> walletCount = new MutableLiveData<>();
    private final MutableLiveData<Boolean> deleted = new MutableLiveData<>();
    private final MutableLiveData<String> ensName = new MutableLiveData<>();
    private final MutableLiveData<ErrorEnvelope> exportWalletError = new MutableLiveData<>();
    private final MutableLiveData<ErrorEnvelope> deleteWalletError = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isTaskRunning = new MutableLiveData<>();
    private Disposable notificationDisposable;

    @Inject
    WalletActionsViewModel(
        HomeRouter homeRouter,
        DeleteWalletInteract deleteWalletInteract,
        ExportWalletInteract exportWalletInteract,
        FetchWalletsInteract fetchWalletsInteract,
        RamaPayNotificationService ramaPayNotificationService
    )
    {
        this.deleteWalletInteract = deleteWalletInteract;
        this.exportWalletInteract = exportWalletInteract;
        this.fetchWalletsInteract = fetchWalletsInteract;
        this.ramaPayNotificationService = ramaPayNotificationService;
        this.homeRouter = homeRouter;
    }

    public LiveData<ErrorEnvelope> exportWalletError()
    {
        return exportWalletError;
    }

    public LiveData<ErrorEnvelope> deleteWalletError()
    {
        return deleteWalletError;
    }

    public LiveData<Boolean> deleted()
    {
        return deleted;
    }

    public LiveData<Integer> saved()
    {
        return saved;
    }
    public LiveData<Integer> walletCount()
    {
        return walletCount;
    }
    public LiveData<String> ensName()
    {
        return ensName;
    }

    public LiveData<Boolean> isTaskRunning()
    {
        return isTaskRunning;
    }

    private void prepareForDeletion()
    {
        // TODO: [Notifications] Reactivate this when unsubscribe is implemented
//        notificationDisposable =
//            ramaPayNotificationService.unsubscribe(EthereumNetworkBase.MAINNET_ID)
//                .observeOn(Schedulers.io())
//                .subscribeOn(Schedulers.io())
//                .subscribe(result -> Timber.d("unsubscribe result => " + result), Timber::e);

        // For now, unsubscribe to firebase topic
        ramaPayNotificationService.unsubscribeToTopic(EthereumNetworkBase.MAINNET_ID);
    }

    public void fetchWalletCount()
    {
        disposable = fetchWalletsInteract
                .fetch()
                .map(wallets -> wallets.length)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(walletCount::postValue, this::onError);
    }

    public void deleteWallet(Wallet wallet)
    {
        isTaskRunning.postValue(true);
        prepareForDeletion();
        disposable = deleteWalletInteract
            .delete(wallet)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(this::onDelete, this::onDeleteWalletError);
    }

    private void onDeleteWalletError(Throwable throwable)
    {
        isTaskRunning.postValue(false);
        deleteWalletError.postValue(
            new ErrorEnvelope(C.ErrorCode.UNKNOWN, TextUtils.isEmpty(throwable.getLocalizedMessage())
                ? throwable.getMessage() : throwable.getLocalizedMessage()));
    }

    private void onDelete(Wallet[] wallets)
    {
        isTaskRunning.postValue(false);
        deleted.postValue(true);
    }

    @Override
    protected void onError(Throwable throwable)
    {
        isTaskRunning.postValue(false);
        super.onError(throwable);
    }

    public void showHome(Context context)
    {
        homeRouter.open(context, true);
    }

    public void updateWallet(Wallet wallet)
    {
        fetchWalletsInteract.updateWalletData(wallet, () -> {
            Timber.d("Stored %s", wallet.address);
            saved.postValue(1);
        });
    }

    public void scanForENS(Wallet wallet, Context ctx)
    {
        //check for ENS name
        new AWEnsResolver(TokenRepository.getWeb3jService(MAINNET_ID), ctx)
                .reverseResolveEns(wallet.address)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(ensName::postValue, this::onENSError).isDisposed();
    }

    private void onENSError(Throwable throwable)
    {
        //No Action
    }
}
