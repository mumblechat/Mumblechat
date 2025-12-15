package com.ramapay.app.di;

import com.ramapay.app.interact.ChangeTokenEnableInteract;
import com.ramapay.app.interact.CreateTransactionInteract;
import com.ramapay.app.interact.DeleteWalletInteract;
import com.ramapay.app.interact.ExportWalletInteract;
import com.ramapay.app.interact.FetchTokensInteract;
import com.ramapay.app.interact.FetchTransactionsInteract;
import com.ramapay.app.interact.FetchWalletsInteract;
import com.ramapay.app.interact.FindDefaultNetworkInteract;
import com.ramapay.app.interact.GenericWalletInteract;
import com.ramapay.app.interact.ImportWalletInteract;
import com.ramapay.app.interact.MemPoolInteract;
import com.ramapay.app.interact.SetDefaultWalletInteract;
import com.ramapay.app.interact.SignatureGenerateInteract;
import com.ramapay.app.repository.CurrencyRepository;
import com.ramapay.app.repository.CurrencyRepositoryType;
import com.ramapay.app.repository.EthereumNetworkRepositoryType;
import com.ramapay.app.repository.LocaleRepository;
import com.ramapay.app.repository.LocaleRepositoryType;
import com.ramapay.app.repository.PreferenceRepositoryType;
import com.ramapay.app.repository.TokenRepositoryType;
import com.ramapay.app.repository.TransactionRepositoryType;
import com.ramapay.app.repository.WalletRepositoryType;
import com.ramapay.app.router.CoinbasePayRouter;
import com.ramapay.app.router.ExternalBrowserRouter;
import com.ramapay.app.router.HomeRouter;
import com.ramapay.app.router.ImportTokenRouter;
import com.ramapay.app.router.ImportWalletRouter;
import com.ramapay.app.router.ManageWalletsRouter;
import com.ramapay.app.router.MyAddressRouter;
import com.ramapay.app.router.RedeemSignatureDisplayRouter;
import com.ramapay.app.router.SellDetailRouter;
import com.ramapay.app.router.TokenDetailRouter;
import com.ramapay.app.router.TransferTicketDetailRouter;
import com.ramapay.app.service.AnalyticsServiceType;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.components.ViewModelComponent;

@Module
@InstallIn(ViewModelComponent.class)
/** Module for providing dependencies to viewModels.
 * All bindings of modules from BuildersModule is shifted here as they were injected in activity for ViewModelFactory but not needed in Hilt
 * */
public class ViewModelModule {

    @Provides
    FetchWalletsInteract provideFetchWalletInteract(WalletRepositoryType walletRepository) {
        return new FetchWalletsInteract(walletRepository);
    }

    @Provides
    SetDefaultWalletInteract provideSetDefaultAccountInteract(WalletRepositoryType accountRepository) {
        return new SetDefaultWalletInteract(accountRepository);
    }

    @Provides
    ImportWalletRouter provideImportAccountRouter() {
        return new ImportWalletRouter();
    }

    @Provides
    HomeRouter provideHomeRouter() {
        return new HomeRouter();
    }

    @Provides
    FindDefaultNetworkInteract provideFindDefaultNetworkInteract(
            EthereumNetworkRepositoryType networkRepository) {
        return new FindDefaultNetworkInteract(networkRepository);
    }

    @Provides
    ImportWalletInteract provideImportWalletInteract(
            WalletRepositoryType walletRepository) {
        return new ImportWalletInteract(walletRepository);
    }

    @Provides
    ExternalBrowserRouter externalBrowserRouter() {
        return new ExternalBrowserRouter();
    }

    @Provides
    FetchTransactionsInteract provideFetchTransactionsInteract(TransactionRepositoryType transactionRepository,
                                                               TokenRepositoryType tokenRepositoryType) {
        return new FetchTransactionsInteract(transactionRepository, tokenRepositoryType);
    }

    @Provides
    CreateTransactionInteract provideCreateTransactionInteract(TransactionRepositoryType transactionRepository,
                                                               AnalyticsServiceType analyticsService) {
        return new CreateTransactionInteract(transactionRepository, analyticsService);
    }

    @Provides
    MyAddressRouter provideMyAddressRouter() {
        return new MyAddressRouter();
    }

    @Provides
    CoinbasePayRouter provideCoinbasePayRouter() {
        return new CoinbasePayRouter();
    }

    @Provides
    FetchTokensInteract provideFetchTokensInteract(TokenRepositoryType tokenRepository) {
        return new FetchTokensInteract(tokenRepository);
    }

    @Provides
    SignatureGenerateInteract provideSignatureGenerateInteract(WalletRepositoryType walletRepository) {
        return new SignatureGenerateInteract(walletRepository);
    }

    @Provides
    MemPoolInteract provideMemPoolInteract(TokenRepositoryType tokenRepository) {
        return new MemPoolInteract(tokenRepository);
    }

    @Provides
    TransferTicketDetailRouter provideTransferTicketRouter() {
        return new TransferTicketDetailRouter();
    }

    @Provides
    LocaleRepositoryType provideLocaleRepository(PreferenceRepositoryType preferenceRepository) {
        return new LocaleRepository(preferenceRepository);
    }

    @Provides
    CurrencyRepositoryType provideCurrencyRepository(PreferenceRepositoryType preferenceRepository) {
        return new CurrencyRepository(preferenceRepository);
    }

    @Provides
    TokenDetailRouter provideErc20DetailRouterRouter() {
        return new TokenDetailRouter();
    }

    @Provides
    GenericWalletInteract provideGenericWalletInteract(WalletRepositoryType walletRepository) {
        return new GenericWalletInteract(walletRepository);
    }

    @Provides
    ChangeTokenEnableInteract provideChangeTokenEnableInteract(TokenRepositoryType tokenRepository) {
        return new ChangeTokenEnableInteract(tokenRepository);
    }

    @Provides
    ManageWalletsRouter provideManageWalletsRouter() {
        return new ManageWalletsRouter();
    }

    @Provides
    SellDetailRouter provideSellDetailRouter() {
        return new SellDetailRouter();
    }

    @Provides
    DeleteWalletInteract provideDeleteAccountInteract(
            WalletRepositoryType accountRepository) {
        return new DeleteWalletInteract(accountRepository);
    }

    @Provides
    ExportWalletInteract provideExportWalletInteract(
            WalletRepositoryType walletRepository) {
        return new ExportWalletInteract(walletRepository);
    }

    @Provides
    ImportTokenRouter provideImportTokenRouter() {
        return new ImportTokenRouter();
    }

    @Provides
    RedeemSignatureDisplayRouter provideRedeemSignatureDisplayRouter() {
        return new RedeemSignatureDisplayRouter();
    }
}
