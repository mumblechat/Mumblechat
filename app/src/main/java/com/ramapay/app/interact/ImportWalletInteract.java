package com.ramapay.app.interact;

import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.repository.WalletRepositoryType;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.util.ens.AWEnsResolver;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class ImportWalletInteract {

    private final WalletRepositoryType walletRepository;

    public ImportWalletInteract(WalletRepositoryType walletRepository) {
        this.walletRepository = walletRepository;
    }

    public Single<Wallet> importKeystore(String keystore, String password, String newPassword) {
        return walletRepository
                        .importKeystoreToWallet(keystore, password, newPassword)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<Wallet> importPrivateKey(String privateKey, String newPassword) {
        return walletRepository
                        .importPrivateKeyToWallet(privateKey, newPassword)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<Wallet> storeHDWallet(String walletAddress, KeyService.AuthenticationLevel authLevel, AWEnsResolver ensResolver)
    {
        Wallet wallet = new Wallet(walletAddress);
        wallet.type = WalletType.HDKEY;
        wallet.authLevel = authLevel;
        wallet.lastBackupTime = System.currentTimeMillis();
        return walletRepository.storeWallet(wallet);
    }

    public Single<Wallet> storeWatchWallet(String address, AWEnsResolver ensResolver)
    {
        Wallet wallet = new Wallet(address);
        wallet.type = WalletType.WATCH;
        wallet.lastBackupTime = System.currentTimeMillis();
        return walletRepository.storeWallet(wallet);
                /* return ensResolver.resolveEnsName(wallet.address)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .map(name -> { wallet.ENSname = name; return wallet; })
                .flatMap(walletRepository::storeWallet);*/
    }

    public Single<Wallet> storeHardwareWallet(String address)
    {
        Wallet wallet = new Wallet(address);
        wallet.type = WalletType.HARDWARE;
        wallet.lastBackupTime = -1;
        return walletRepository.storeWallet(wallet);
    }

    public Single<Wallet> storeKeystoreWallet(Wallet wallet, KeyService.AuthenticationLevel level, AWEnsResolver ensResolver)
    {
        wallet.authLevel = level;
        wallet.type = WalletType.KEYSTORE;
        wallet.lastBackupTime = System.currentTimeMillis();
        return walletRepository.storeWallet(wallet);

        /*return ensResolver.resolveEnsName(wallet.address)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .map(name -> { wallet.ENSname = name; return wallet; })
                .flatMap(walletRepository::storeWallet);*/
    }
    
    /**
     * Store wallet imported from private key - uses KEYSTORE_LEGACY type
     * to differentiate from keystore JSON imports and allow private key export
     */
    public Single<Wallet> storePrivateKeyWallet(Wallet wallet, KeyService.AuthenticationLevel level, AWEnsResolver ensResolver)
    {
        wallet.authLevel = level;
        wallet.type = WalletType.KEYSTORE_LEGACY;
        wallet.lastBackupTime = System.currentTimeMillis();
        return walletRepository.storeWallet(wallet);
    }

    public boolean keyStoreExists(String address)
    {
        return walletRepository.keystoreExists(address);
    }
}
