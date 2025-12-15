package com.ramapay.app.di;

import static com.ramapay.app.service.KeystoreAccountService.KEYSTORE_FOLDER;

import android.content.Context;

import com.ramapay.app.repository.CoinbasePayRepository;
import com.ramapay.app.repository.CoinbasePayRepositoryType;
import com.ramapay.app.repository.EthereumNetworkRepository;
import com.ramapay.app.repository.EthereumNetworkRepositoryType;
import com.ramapay.app.repository.OnRampRepository;
import com.ramapay.app.repository.OnRampRepositoryType;
import com.ramapay.app.repository.PreferenceRepositoryType;
import com.ramapay.app.repository.SharedPreferenceRepository;
import com.ramapay.app.repository.SwapRepository;
import com.ramapay.app.repository.SwapRepositoryType;
import com.ramapay.app.repository.TokenLocalSource;
import com.ramapay.app.repository.TokenRepository;
import com.ramapay.app.repository.TokenRepositoryType;
import com.ramapay.app.repository.TokensMappingRepository;
import com.ramapay.app.repository.TokensMappingRepositoryType;
import com.ramapay.app.repository.TokensRealmSource;
import com.ramapay.app.repository.TransactionLocalSource;
import com.ramapay.app.repository.TransactionRepository;
import com.ramapay.app.repository.TransactionRepositoryType;
import com.ramapay.app.repository.TransactionsRealmCache;
import com.ramapay.app.repository.WalletDataRealmSource;
import com.ramapay.app.repository.WalletRepository;
import com.ramapay.app.repository.WalletRepositoryType;
import com.ramapay.app.service.AccountKeystoreService;
import com.ramapay.app.service.RamaPayNotificationService;
import com.ramapay.app.service.RamaPayService;
import com.ramapay.app.service.AnalyticsService;
import com.ramapay.app.service.AnalyticsServiceType;
import com.ramapay.app.service.AssetDefinitionService;
import com.ramapay.app.service.GasService;
import com.ramapay.app.service.IPFSService;
import com.ramapay.app.service.IPFSServiceType;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.service.KeystoreAccountService;
import com.ramapay.app.service.NotificationService;
import com.ramapay.app.service.OkLinkService;
import com.ramapay.app.service.OpenSeaService;
import com.ramapay.app.service.RealmManager;
import com.ramapay.app.service.SwapService;
import com.ramapay.app.service.TickerService;
import com.ramapay.app.service.TokensService;
import com.ramapay.app.service.TransactionsNetworkClient;
import com.ramapay.app.service.TransactionsNetworkClientType;
import com.ramapay.app.service.TransactionsService;
import com.ramapay.app.service.TransactionNotificationService;
import com.google.gson.Gson;

import java.io.File;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

@Module
@InstallIn(SingletonComponent.class)
public class RepositoriesModule
{
    @Singleton
    @Provides
    PreferenceRepositoryType providePreferenceRepository(@ApplicationContext Context context)
    {
        return new SharedPreferenceRepository(context);
    }

    @Singleton
    @Provides
    AccountKeystoreService provideAccountKeyStoreService(@ApplicationContext Context context, KeyService keyService)
    {
        File file = new File(context.getFilesDir(), KEYSTORE_FOLDER);
        return new KeystoreAccountService(file, context.getFilesDir(), keyService);
    }

    @Singleton
    @Provides
    TickerService provideTickerService(OkHttpClient httpClient, PreferenceRepositoryType sharedPrefs, TokenLocalSource localSource)
    {
        return new TickerService(httpClient, sharedPrefs, localSource);
    }

    @Singleton
    @Provides
    EthereumNetworkRepositoryType provideEthereumNetworkRepository(
        PreferenceRepositoryType preferenceRepository,
        @ApplicationContext Context context
    )
    {
        return new EthereumNetworkRepository(preferenceRepository, context);
    }

    @Singleton
    @Provides
    WalletRepositoryType provideWalletRepository(
        PreferenceRepositoryType preferenceRepositoryType,
        AccountKeystoreService accountKeystoreService,
        EthereumNetworkRepositoryType networkRepository,
        WalletDataRealmSource walletDataRealmSource,
        KeyService keyService)
    {
        return new WalletRepository(
            preferenceRepositoryType, accountKeystoreService, networkRepository, walletDataRealmSource, keyService);
    }

    @Singleton
    @Provides
    TransactionRepositoryType provideTransactionRepository(
        EthereumNetworkRepositoryType networkRepository,
        AccountKeystoreService accountKeystoreService,
        TransactionLocalSource inDiskCache,
        TransactionsService transactionsService)
    {
        return new TransactionRepository(
            networkRepository,
            accountKeystoreService,
            inDiskCache,
            transactionsService);
    }

    @Singleton
    @Provides
    OnRampRepositoryType provideOnRampRepository(@ApplicationContext Context context)
    {
        return new OnRampRepository(context);
    }

    @Singleton
    @Provides
    SwapRepositoryType provideSwapRepository(@ApplicationContext Context context)
    {
        return new SwapRepository(context);
    }

    @Singleton
    @Provides
    CoinbasePayRepositoryType provideCoinbasePayRepository()
    {
        return new CoinbasePayRepository();
    }

    @Singleton
    @Provides
    TransactionLocalSource provideTransactionInDiskCache(RealmManager realmManager)
    {
        return new TransactionsRealmCache(realmManager);
    }

    @Singleton
    @Provides
    TransactionsNetworkClientType provideBlockExplorerClient(
        OkHttpClient httpClient,
        Gson gson,
        RealmManager realmManager)
    {
        return new TransactionsNetworkClient(httpClient, gson, realmManager);
    }

    @Singleton
    @Provides
    TokenRepositoryType provideTokenRepository(
        EthereumNetworkRepositoryType ethereumNetworkRepository,
        TokenLocalSource tokenLocalSource,
        @ApplicationContext Context context,
        TickerService tickerService)
    {
        return new TokenRepository(
            ethereumNetworkRepository,
            tokenLocalSource,
            context,
            tickerService);
    }

    @Singleton
    @Provides
    TokenLocalSource provideRealmTokenSource(RealmManager realmManager, EthereumNetworkRepositoryType ethereumNetworkRepository, TokensMappingRepositoryType tokensMappingRepository)
    {
        return new TokensRealmSource(realmManager, ethereumNetworkRepository, tokensMappingRepository);
    }

    @Singleton
    @Provides
    WalletDataRealmSource provideRealmWalletDataSource(RealmManager realmManager)
    {
        return new WalletDataRealmSource(realmManager);
    }

    @Singleton
    @Provides
    TokensService provideTokensServices(EthereumNetworkRepositoryType ethereumNetworkRepository,
                                        TokenRepositoryType tokenRepository,
                                        TickerService tickerService,
                                        OpenSeaService openseaService,
                                        AnalyticsServiceType analyticsService,
                                        OkHttpClient client)
    {
        return new TokensService(ethereumNetworkRepository, tokenRepository, tickerService, openseaService, analyticsService, client);
    }

    @Singleton
    @Provides
    IPFSServiceType provideIPFSService(OkHttpClient client)
    {
        return new IPFSService(client);
    }

    @Singleton
    @Provides
    TransactionsService provideTransactionsServices(TokensService tokensService,
                                                    EthereumNetworkRepositoryType ethereumNetworkRepositoryType,
                                                    TransactionsNetworkClientType transactionsNetworkClientType,
                                                    TransactionLocalSource transactionLocalSource,
                                                    TransactionNotificationService transactionNotificationService)
    {
        return new TransactionsService(tokensService, ethereumNetworkRepositoryType, transactionsNetworkClientType, transactionLocalSource, transactionNotificationService);
    }

    @Singleton
    @Provides
    GasService provideGasService(EthereumNetworkRepositoryType ethereumNetworkRepository, OkHttpClient client, RealmManager realmManager)
    {
        return new GasService(ethereumNetworkRepository, client, realmManager);
    }

    @Singleton
    @Provides
    OpenSeaService provideOpenseaService()
    {
        return new OpenSeaService();
    }

    @Singleton
    @Provides
    SwapService provideSwapService()
    {
        return new SwapService();
    }

    @Singleton
    @Provides
    RamaPayService provideFeemasterService(OkHttpClient okHttpClient, Gson gson)
    {
        return new RamaPayService(okHttpClient, gson);
    }

    @Singleton
    @Provides
    NotificationService provideNotificationService(@ApplicationContext Context ctx)
    {
        return new NotificationService(ctx);
    }

    @Singleton
    @Provides
    AssetDefinitionService providingAssetDefinitionServices(IPFSServiceType ipfsService, @ApplicationContext Context ctx, NotificationService notificationService, RealmManager realmManager,
                                                            TokensService tokensService, TokenLocalSource tls,
                                                            RamaPayService alphaService)
    {
        return new AssetDefinitionService(ipfsService, ctx, notificationService, realmManager, tokensService, tls, alphaService);
    }

    @Singleton
    @Provides
    KeyService provideKeyService(@ApplicationContext Context ctx, AnalyticsServiceType analyticsService)
    {
        return new KeyService(ctx, analyticsService);
    }

    @Singleton
    @Provides
    AnalyticsServiceType provideAnalyticsService(@ApplicationContext Context ctx, PreferenceRepositoryType preferenceRepository)
    {
        return new AnalyticsService(ctx, preferenceRepository);
    }

    @Singleton
    @Provides
    TokensMappingRepositoryType provideTokensMappingRepository(@ApplicationContext Context ctx)
    {
        return new TokensMappingRepository(ctx);
    }

    @Singleton
    @Provides
    TransactionNotificationService provideTransactionNotificationService(@ApplicationContext Context ctx,
                                                                         PreferenceRepositoryType preferenceRepositoryType)
    {
        return new TransactionNotificationService(ctx, preferenceRepositoryType);
    }

    @Singleton
    @Provides
    RamaPayNotificationService provideRamaPayNotificationService(WalletRepositoryType walletRepository)
    {
        return new RamaPayNotificationService(walletRepository);
    }
}
