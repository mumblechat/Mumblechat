package com.ramapay.app.viewmodel;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ramapay.app.entity.Wallet;
import com.ramapay.app.interact.GenericWalletInteract;
import com.ramapay.app.repository.PreferenceRepositoryType;
import com.ramapay.app.service.RamaPayNotificationService;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

@HiltViewModel
public class NotificationSettingsViewModel extends BaseViewModel
{
    private final GenericWalletInteract genericWalletInteract;
    private final RamaPayNotificationService ramaPayNotificationService;
    private final PreferenceRepositoryType preferenceRepository;

    private final MutableLiveData<Wallet> wallet = new MutableLiveData<>();
    @Nullable
    private Disposable findWalletDisposable;
    @Nullable
    private Disposable disposable;

    @Inject
    NotificationSettingsViewModel(
        GenericWalletInteract genericWalletInteract,
        RamaPayNotificationService ramaPayNotificationService,
        PreferenceRepositoryType preferenceRepository)
    {
        this.genericWalletInteract = genericWalletInteract;
        this.ramaPayNotificationService = ramaPayNotificationService;
        this.preferenceRepository = preferenceRepository;

        prepare();
    }

    public LiveData<Wallet> wallet()
    {
        return wallet;
    }

    private void prepare()
    {
        findWalletDisposable = genericWalletInteract
            .find()
            .subscribe(wallet::setValue, this::onError);
    }

    public void subscribe(long chainId)
    {
        disposable = ramaPayNotificationService.subscribe(chainId)
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .subscribe(result -> Timber.d("subscribe result => %s", result), Timber::e);
    }

    public void unsubscribe(long chainId)
    {
        disposable = ramaPayNotificationService.unsubscribe(chainId)
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .subscribe(result -> Timber.d("unsubscribe result => %s", result), Timber::e);
    }

    // TODO: [Notifications] Delete when unsubscribe is implemented
    public void unsubscribeToTopic(long chainId)
    {
        ramaPayNotificationService.unsubscribeToTopic(chainId);
    }

    public boolean isTransactionNotificationsEnabled(String address)
    {
        return preferenceRepository.isTransactionNotificationsEnabled(address);
    }

    public void setTransactionNotificationsEnabled(String address, boolean enabled)
    {
        preferenceRepository.setTransactionNotificationEnabled(address, enabled);
    }

    @Override
    protected void onCleared()
    {
        super.onCleared();
        if (disposable != null)
        {
            disposable.dispose();
        }
        if (findWalletDisposable != null)
        {
            findWalletDisposable.dispose();
        }
    }
}
